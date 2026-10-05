package com.rangandale.appthrottler.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.rangandale.appthrottler.MainActivity
import com.rangandale.appthrottler.R
import com.rangandale.appthrottler.domain.model.Scenario
import com.rangandale.appthrottler.domain.model.ScenarioStatus
import com.rangandale.appthrottler.domain.model.ThrottleConfig
import com.rangandale.appthrottler.logging.ThrottleLogger
import com.rangandale.appthrottler.vpn.capture.PacketCapture
import com.rangandale.appthrottler.vpn.flow.FlowKey
import com.rangandale.appthrottler.vpn.flow.FlowOwners
import com.rangandale.appthrottler.vpn.flow.FlowPolicy
import com.rangandale.appthrottler.vpn.flow.FlowRegistry
import com.rangandale.appthrottler.vpn.forward.IpPacket
import com.rangandale.appthrottler.vpn.throttle.ShaperSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress

class ThrottleVpnService : VpnService() {

    private var packetProcessor: PacketProcessor? = null
    private var tunFd: ParcelFileDescriptor? = null

    /** The running tunnel's rules; network and screen changes are pushed into it. */
    @Volatile private var policy: FlowPolicy? = null
    private var shapers: ShaperSet? = null

    private val handler = Handler(Looper.getMainLooper())
    private var scenarioStep: Runnable? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            policy?.screenOn = intent.action == Intent.ACTION_SCREEN_ON
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException when started from the background
            ThrottleLogger.e(TAG, "Could not enter foreground: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Commands for a running tunnel. Without one they have nothing to act on and fall
        // through to the teardown below, which also takes the service out of the foreground.
        if (packetProcessor != null) {
            when (intent?.action) {
                ACTION_SCENARIO -> {
                    scenarioOf(intent)?.let(::runScenario)
                    return Service.START_NOT_STICKY
                }
                ACTION_CANCEL_SCENARIO -> {
                    cancelScenario(log = true)
                    return Service.START_NOT_STICKY
                }
                ACTION_RESET_CONNECTIONS -> {
                    packetProcessor?.resetConnections()
                    ThrottleLogger.i(TAG, "All connections reset")
                    return Service.START_NOT_STICKY
                }
            }
        }

        // A null intent or a foreign action (e.g. a system restart) carries no config to run with.
        val config = intent?.takeIf { it.action == ACTION_START }?.let(::configOf)
        if (config == null) {
            tearDown()
            return Service.START_NOT_STICKY
        }

        // Replace any tunnel that is already up (re-applying changed settings).
        stopTunnel()

        val newTunFd = buildTunnel(config)
        if (newTunFd == null) {
            ThrottleLogger.e(TAG, "Failed to establish VPN tunnel")
            tearDown()
            return Service.START_NOT_STICKY
        }
        tunFd = newTunFd

        ThrottleLogger.i(TAG, "VPN tunnel established — throttling ${config.selectedApps.size} app(s)")
        config.selectedApps.forEach { ThrottleLogger.i(TAG, "  → $it") }

        TrafficCounters.reset()
        FlowRegistry.reset()
        val uids = uidsOf(config.selectedApps)
        val rulesByUid = config.appRules.mapNotNull { rule -> uids[rule.packageName]?.let { it to rule } }.toMap()
        val newPolicy = FlowPolicy(rulesByUid, config.blockedDomains)
        val newShapers = ShaperSet(
            default = config.params,
            overridesByUid = rulesByUid.mapNotNull { (uid, rule) -> rule.throttle?.let { uid to it } }.toMap(),
        )
        policy = newPolicy
        shapers = newShapers
        watchDeviceState(newPolicy)
        if (!canAttributeFlows && (newPolicy.hasRules || rulesByUid.isNotEmpty())) {
            ThrottleLogger.w(TAG, "Per-app rules need Android 10 or newer — they are ignored on this device")
        }

        packetProcessor = PacketProcessor(config, newTunFd, this, newShapers, newPolicy, FlowOwners(::ownerUid), MTU)
            .also { it.start() }
        _activeConfig.value = config // reported only once the tunnel is really up

        return Service.START_NOT_STICKY
    }

    /** The user disconnected the VPN from system settings, or another VPN took over. */
    override fun onRevoke() {
        ThrottleLogger.w(TAG, "VPN revoked by the system")
        tearDown()
    }

    override fun onDestroy() {
        tearDown()
        super.onDestroy()
    }

    /** Steps through [scenario], putting every routed app on each step's conditions in turn. */
    private fun runScenario(scenario: Scenario) {
        cancelScenario(log = false)
        val steps = scenario.steps.filter { it.seconds > 0 }
        if (steps.isEmpty()) return
        ThrottleLogger.i(TAG, "Scenario \"${scenario.name}\" started (${steps.size} step(s))")

        fun enter(index: Int) {
            val target = shapers ?: return
            if (index >= steps.size) {
                target.overrideAll(null)
                scenarioStep = null
                _scenario.value = null
                ThrottleLogger.i(TAG, "Scenario \"${scenario.name}\" finished — back to the session settings")
                return
            }
            val step = steps[index]
            target.overrideAll(step.params)
            val durationMs = step.seconds * 1000L
            _scenario.value = ScenarioStatus(scenario.name, step.label, index, steps.size, System.currentTimeMillis() + durationMs)
            ThrottleLogger.i(TAG, "Scenario step ${index + 1}/${steps.size}: ${step.label} for ${step.seconds} s")
            scenarioStep = Runnable { enter(index + 1) }.also { handler.postDelayed(it, durationMs) }
        }
        enter(0)
    }

    private fun cancelScenario(log: Boolean) {
        val wasRunning = scenarioStep != null
        scenarioStep?.let(handler::removeCallbacks)
        scenarioStep = null
        shapers?.overrideAll(null)
        _scenario.value = null
        if (log && wasRunning) ThrottleLogger.i(TAG, "Scenario cancelled — back to the session settings")
    }

    private fun stopTunnel() {
        cancelScenario(log = false)
        shapers = null
        unwatchDeviceState()
        policy = null
        packetProcessor?.stop()
        packetProcessor = null
        try {
            tunFd?.close()
        } catch (e: Exception) {
            ThrottleLogger.e(TAG, "Closing TUN failed: $e")
        }
        tunFd = null
    }

    private fun tearDown() {
        val wasActive = _activeConfig.value != null
        _activeConfig.value = null
        stopTunnel()
        PacketCapture.stop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        stopSelf()
        if (wasActive) ThrottleLogger.i(TAG, "VPN stopped")
    }

    private fun buildTunnel(config: ThrottleConfig): ParcelFileDescriptor? {
        val builder = Builder()
            .addAddress(TUN_ADDRESS, 32)
            .addRoute("0.0.0.0", 0)
            .setMtu(MTU)

        // Tunnel IPv6 only where the real network has it. Without these lines Android blocks
        // IPv6 for the routed apps, which then use IPv4 — the right outcome on an IPv4-only network.
        val ipv6 = networkHasIpv6()
        if (ipv6) {
            builder.addAddress(TUN_ADDRESS_V6, 128).addRoute("::", 0)
        }
        builder
            .setSession(getString(R.string.app_name))
            .setBlocking(true)

        dnsServers(ipv6).forEach { builder.addDnsServer(it) }
        ThrottleLogger.d(TAG, if (ipv6) "Tunnelling IPv4 and IPv6" else "Tunnelling IPv4 only (the network has no IPv6)")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // VPNs are metered by default, which makes apps behave differently than on Wi-Fi.
            builder.setMetered(false)
        }

        var allowed = 0
        for (pkg in config.selectedApps) {
            try {
                builder.addAllowedApplication(pkg)
                allowed++
            } catch (e: PackageManager.NameNotFoundException) {
                ThrottleLogger.w(TAG, "Package not installed, skipping: $pkg")
            }
        }
        // With no allowed application the tunnel would capture every app on the device.
        if (allowed == 0) {
            ThrottleLogger.w(TAG, "No installed apps selected — VPN not started")
            return null
        }

        return try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "VpnService.Builder.establish() failed", e)
            null
        }
    }

    /**
     * The DNS servers of the current network (so captive or private resolvers keep working),
     * followed by public fallbacks. IPv6 resolvers are only usable when IPv6 is tunnelled.
     */
    private fun dnsServers(ipv6: Boolean): List<InetAddress> {
        val servers = LinkedHashSet<InetAddress>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.getLinkProperties(cm.activeNetwork)?.dnsServers
                    ?.filterTo(servers) { (ipv6 || it is Inet4Address) && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            } catch (e: Exception) {
                Log.w(TAG, "Could not read system DNS servers", e)
            }
        }
        FALLBACK_DNS.forEach { servers.add(InetAddress.getByName(it)) }
        return servers.toList()
    }

    private fun uidsOf(packages: List<String>): Map<String, Int> = packages.mapNotNull { pkg ->
        try {
            pkg to packageManager.getApplicationInfo(pkg, 0).uid
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }.toMap()

    /** The app that owns a flow. Only VPN apps may ask, and only on Android 10+. */
    private fun ownerUid(key: FlowKey): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return FlowOwners.UNKNOWN_UID
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.getConnectionOwnerUid(
                key.protocol,
                InetSocketAddress(IpPacket.addressOf(key.srcIp), key.srcPort),
                InetSocketAddress(IpPacket.addressOf(key.dstIp), key.dstPort),
            )
        } catch (e: Exception) {
            FlowOwners.UNKNOWN_UID
        }
    }

    /** Keeps [target] informed about the network type and screen state its rules depend on. */
    private fun watchDeviceState(target: FlowPolicy) {
        target.screenOn = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // This app is not routed through its own VPN, so its default network is the real one.
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                target.onWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                target.onMobile = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            }

            override fun onLost(network: Network) {
                target.onWifi = false
                target.onMobile = false
            }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "Could not watch the default network", e)
        }
    }

    private fun unwatchDeviceState() {
        if (policy != null) runCatching { unregisterReceiver(screenReceiver) }
        networkCallback?.let { callback ->
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
    }

    /** Whether the active network has a routable (not link-local) IPv6 address. */
    private fun networkHasIpv6(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.getLinkProperties(cm.activeNetwork)?.linkAddresses.orEmpty().any {
                val address = it.address
                address is Inet6Address && !address.isLinkLocalAddress && !address.isLoopbackAddress
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_throttle)
        .setContentTitle(getString(R.string.notification_title))
        .setContentText(getString(R.string.notification_text))
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                pendingIntentFlags(),
            ),
        )
        .addAction(
            0,
            getString(R.string.notification_stop),
            PendingIntent.getService(this, 1, stopIntent(this), pendingIntentFlags()),
        )
        .setOngoing(true)
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun pendingIntentFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
    }

    companion object {
        private const val TAG = "ThrottleVpnService"
        private const val MTU = 1500
        private const val TUN_ADDRESS = "10.0.0.2"
        private const val TUN_ADDRESS_V6 = "fd00:7468:726f:7474::2" // unique local address
        private val FALLBACK_DNS = listOf("8.8.8.8", "1.1.1.1")

        const val ACTION_START = "com.rangandale.appthrottler.vpn.action.START"
        const val ACTION_STOP = "com.rangandale.appthrottler.vpn.action.STOP"
        private const val ACTION_SCENARIO = "com.rangandale.appthrottler.vpn.action.SCENARIO"
        private const val ACTION_CANCEL_SCENARIO = "com.rangandale.appthrottler.vpn.action.CANCEL_SCENARIO"
        private const val ACTION_RESET_CONNECTIONS = "com.rangandale.appthrottler.vpn.action.RESET_CONNECTIONS"
        private const val EXTRA_SCENARIO = "extra_scenario"
        private const val EXTRA_CONFIG = "extra_config"

        /** Attributing flows to apps (per-app rules, the connections list) needs Android 10. */
        val canAttributeFlows: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        private const val CHANNEL_ID = "app_throttler_vpn"
        private const val NOTIFICATION_ID = 1001

        // Source of truth for the VPN state that the ViewModel observes: the config the tunnel
        // is currently running with, or null while stopped.
        private val _activeConfig = MutableStateFlow<ThrottleConfig?>(null)
        val activeConfig: StateFlow<ThrottleConfig?> = _activeConfig.asStateFlow()

        /** The scenario step in force, or null when the configured values apply. */
        private val _scenario = MutableStateFlow<ScenarioStatus?>(null)
        val scenario: StateFlow<ScenarioStatus?> = _scenario.asStateFlow()

        fun scenarioIntent(context: Context, scenario: Scenario): Intent =
            Intent(context, ThrottleVpnService::class.java).apply {
                action = ACTION_SCENARIO
                putExtra(EXTRA_SCENARIO, Gson().toJson(scenario))
            }

        fun cancelScenarioIntent(context: Context): Intent =
            Intent(context, ThrottleVpnService::class.java).apply { action = ACTION_CANCEL_SCENARIO }

        fun resetConnectionsIntent(context: Context): Intent =
            Intent(context, ThrottleVpnService::class.java).apply { action = ACTION_RESET_CONNECTIONS }

        private fun scenarioOf(intent: Intent): Scenario? = try {
            intent.getStringExtra(EXTRA_SCENARIO)?.let { Gson().fromJson(it, Scenario::class.java) }
        } catch (e: Exception) {
            null
        }

        fun startIntent(context: Context, config: ThrottleConfig): Intent =
            Intent(context, ThrottleVpnService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CONFIG, Gson().toJson(config))
            }

        internal fun configOf(intent: Intent): ThrottleConfig? = try {
            intent.getStringExtra(EXTRA_CONFIG)?.let { Gson().fromJson(it, ThrottleConfig::class.java) }
        } catch (e: Exception) {
            null
        }

        fun stopIntent(context: Context): Intent = Intent(context, ThrottleVpnService::class.java).apply {
            action = ACTION_STOP
        }
    }
}

# App Throttler — Android Network Condition Simulator

> **Two parts.** The [roadmap](#roadmap-firewall-and-traffic-inspection) at the end is the
> current plan for features that are not built yet. Everything before it is the original design
> plan, kept for history: the implementation has since diverged from it in places (for example
> the TCP forwarder, the statistics path and the UI components). For how the app works today see
> [README.md](README.md), [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and
> [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

## Overview

A rootless Android app that simulates poor network conditions (2G, 3G, lossy Wi-Fi, high latency) for
individually selected apps. Uses `VpnService` to create a local TUN interface, intercepts packets
from chosen apps, applies configurable throttling, then forwards them to the internet.

No root required. Targets API 21+ (Android 5.0).

---

## Goals

- Let developers/testers reproduce bad network conditions on real devices
- Per-app granularity — throttle only the app under test, not the whole device
- Configurable: download/upload speed, latency, jitter, packet loss
- Built-in presets: 2G, 3G, 4G, Slow Wi-Fi
- Save and reuse named throttle profiles (Room DB)
- Real-time traffic stats on the home screen

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Architecture | MVVM (ViewModel + StateFlow) |
| Navigation | Compose NavHost + bottom nav |
| DI | Hilt |
| Database | Room |
| VPN | Android `VpnService` API |
| Packet I/O | Java NIO (`DatagramChannel`, `SocketChannel`, `Selector`) |
| Async | Kotlin Coroutines + Flow |
| App icons | Coil |

---

## Architecture Diagram

```
┌──────────────────────────────────────────────────────────┐
│  Jetpack Compose UI                                       │
│  HomeScreen · AppListScreen · SettingsScreen · Profiles  │
└─────────────────────┬────────────────────────────────────┘
                      │ StateFlow / collectAsState
┌─────────────────────▼────────────────────────────────────┐
│  ViewModels (Hilt injected)                               │
│  VpnControlVM · AppListVM · SettingsVM · ProfilesVM       │
└──────┬───────────────────────────────────┬───────────────┘
       │                                   │
┌──────▼────────┐               ┌──────────▼───────────────┐
│ ThrottleVpn   │               │ Repositories              │
│ Service       │               │ ProfileRepository (Room)   │
│ (foreground   │               │ AppRepository (PackageMgr) │
│  Service)     │               │ StatsRepository (SharedFlow│
└──────┬────────┘               └──────────────────────────┘
       │
┌──────▼─────────────────────────────────────────────────────┐
│  PacketProcessor  (dedicated background thread)            │
│                                                            │
│  TUN read  →  PacketLossFilter  →  TokenBucket (BW limit) │
│           →  LatencyQueue (latency + jitter)               │
│           →  UdpForwarder / TcpForwarder  →  Internet      │
│                                                            │
│  Internet  →  NIO Selector  →  TUN write                   │
└────────────────────────────────────────────────────────────┘
```

---

## Project File Structure

```
app-throtler/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml          # version catalog
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        └── kotlin/com/rangandale/appthrottler/
            │
            ├── ThrottlerApplication.kt        # @HiltAndroidApp
            ├── MainActivity.kt                # @AndroidEntryPoint, hosts Compose root
            │
            ├── vpn/
            │   ├── ThrottleVpnService.kt      # VpnService subclass + foreground notification
            │   ├── PacketProcessor.kt         # Hot packet loop (one thread)
            │   ├── throttle/
            │   │   ├── ThrottleConfig.kt      # Config data class sent via Intent extras
            │   │   ├── TokenBucket.kt         # Synchronized token-bucket rate limiter
            │   │   ├── LatencyQueue.kt        # DelayQueue<DelayedPacket> for latency+jitter
            │   │   └── PacketLossFilter.kt    # Random.nextFloat() < pct/100
            │   └── forward/
            │       ├── UdpForwarder.kt        # DatagramChannel per 5-tuple, NIO Selector
            │       └── TcpForwarder.kt        # SocketChannel + minimal TCP state machine
            │
            ├── data/
            │   ├── local/
            │   │   ├── ThrottleDatabase.kt
            │   │   ├── ThrottleProfileEntity.kt
            │   │   ├── ThrottleProfileDao.kt  # Flow<List<...>>, insert, deleteById
            │   │   └── Converters.kt          # List<String> ↔ JSON (Gson)
            │   └── repository/
            │       ├── ProfileRepository.kt
            │       └── AppRepository.kt       # PackageManager query → Flow<List<AppInfo>>
            │
            ├── domain/model/
            │   ├── ThrottleProfile.kt         # + PresetProfiles object
            │   ├── AppInfo.kt
            │   └── NetworkStats.kt
            │
            └── presentation/
                ├── viewmodel/
                │   ├── VpnControlViewModel.kt
                │   ├── AppListViewModel.kt
                │   ├── SettingsViewModel.kt
                └── ui/
                    ├── navigation/
                    │   ├── NavRoutes.kt       # sealed class NavRoute
                    │   └── AppNavHost.kt
                    ├── screens/
                    │   ├── HomeScreen.kt      # VPN toggle + live stats
                    │   ├── AppListScreen.kt   # Searchable checkbox list
                    │   ├── SettingsScreen.kt  # Sliders + presets
                    │   └── ProfilesScreen.kt
                    ├── components/
                    │   ├── SliderControl.kt
                    │   ├── AppCard.kt
                    │   ├── StatChip.kt
                    │   └── PresetBottomSheet.kt
                    └── theme/
```

---

## Key Files — Detailed

### `AndroidManifest.xml` (critical entries)

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />

<service
    android:name=".vpn.ThrottleVpnService"
    android:exported="false"
    android:foregroundServiceType="specialUse"
    android:permission="android.permission.BIND_VPN_SERVICE">
    <intent-filter>
        <action android:name="android.net.VpnService" />
    </intent-filter>
</service>
```

---

### `ThrottleConfig.kt` — passed from ViewModel → VpnService via Intent

```kotlin
data class ThrottleConfig(
    val selectedApps: List<String>,     // package names
    val downloadSpeedKbps: Int,         // 0 = unlimited
    val uploadSpeedKbps: Int,
    val latencyMs: Int,
    val jitterMs: Int,
    val packetLossPercent: Float        // 0.0 – 100.0
)
```

---

### `ThrottleVpnService.kt` — VPN lifecycle

```
onStartCommand:
  1. Parse ThrottleConfig from Intent
  2. Call VpnService.Builder to create TUN fd:
       .addAddress("10.0.0.2", 32)
       .addRoute("0.0.0.0", 0)
       .addDnsServer("8.8.8.8")
       .setMtu(1500)
       .addAllowedApplication(pkg) for each selected app
       .establish()
  3. Start PacketProcessor on a dedicated thread
  4. startForeground() with persistent notification

onDestroy:
  1. Signal PacketProcessor to stop
  2. Close TUN ParcelFileDescriptor
```

---

### `PacketProcessor.kt` — hot packet loop

**Allocations:** one `ByteArray(32768)` at init. Never allocates per packet.

**Outbound flow (app → internet):**
```
tunInputStream.read(buffer)
  → check PacketLossFilter.shouldDrop(config.packetLossPercent)
  → check uploadTokenBucket.tryConsume(len)
  → enqueue into LatencyQueue with (latencyMs ± jitterMs)
  → LatencyQueue drain thread → UdpForwarder or TcpForwarder
```

**Inbound flow (internet → app):**
```
NIO Selector detects readable channel
  → check downloadTokenBucket.tryConsume(bytes)
  → tunOutputStream.write(data)
```

**Stats:** `AtomicLong totalBytesIn`, `totalBytesOut` — polled every 500ms by `StatsRepository` into a `SharedFlow<NetworkStats>`.

---

### `TokenBucket.kt` — bandwidth limiting

```kotlin
@Synchronized
fun tryConsume(bytes: Int): Boolean {
    refillTokens()                          // add elapsed * kbps * 125 tokens
    return if (tokens >= bytes) {
        tokens -= bytes; true               // allow
    } else false                            // drop / queue
}
```

---

### `LatencyQueue.kt` — latency + jitter

```kotlin
fun enqueue(data: ByteArray, baseMs: Int, jitterMs: Int) {
    val releaseAt = System.currentTimeMillis() +
        baseMs + Random.nextLong(-jitterMs.toLong(), jitterMs.toLong())
    delayQueue.offer(DelayedPacket(data, releaseAt))
}
// Drain thread: delayQueue.poll(100, MILLISECONDS) → forward
```

---

### `ThrottleProfile.kt` — domain model + presets

```kotlin
data class ThrottleProfile(
    val id: Long = 0,
    val name: String,
    val downloadSpeedKbps: Int,
    val uploadSpeedKbps: Int,
    val latencyMs: Int,
    val jitterMs: Int,
    val packetLossPercent: Float,
    val selectedAppPackages: List<String>,
    val isPreset: Boolean = false
)

object PresetProfiles {
    val TWO_G     = ThrottleProfile("2G",        40,    20,  300, 50,  2.0f, emptyList(), true)
    val THREE_G   = ThrottleProfile("3G",        384,  128,  100, 20,  1.0f, emptyList(), true)
    val FOUR_G    = ThrottleProfile("4G",      10240, 5120,   20,  5,  0.1f, emptyList(), true)
    val SLOW_WIFI = ThrottleProfile("Slow WiFi", 1024,  512,   50, 10,  0.5f, emptyList(), true)
    val all = listOf(TWO_G, THREE_G, FOUR_G, SLOW_WIFI)
}
```

---

### `VpnControlViewModel.kt` — VPN start/stop

```kotlin
data class UiState(
    val isVpnRunning: Boolean = false,
    val vpnPermissionIntent: Intent? = null,   // non-null = show permission dialog
    val currentConfig: ThrottleConfig? = null,
    val networkStats: NetworkStats = NetworkStats()
)

fun startVpn(config: ThrottleConfig) {
    val intent = VpnService.prepare(context)
    if (intent != null) {
        _uiState.update { it.copy(vpnPermissionIntent = intent) }
    } else {
        ThrottleVpnService.start(context, config)
        _uiState.update { it.copy(isVpnRunning = true, currentConfig = config) }
    }
}

fun onPermissionGranted(config: ThrottleConfig) {
    ThrottleVpnService.start(context, config)
    _uiState.update { it.copy(isVpnRunning = true, vpnPermissionIntent = null) }
}

fun stopVpn() {
    ThrottleVpnService.stop(context)
    _uiState.update { it.copy(isVpnRunning = false) }
}
```

---

## UI Screens

### Home Screen
- Large on/off toggle (VPN state)
- Active profile name
- Real-time down/up speed chips
- Total bytes transferred
- "Configure" FAB → navigates to App List

### App List Screen
- Search bar
- `LazyColumn` of installed (user-visible) apps with icon, name, package
- Checkbox per app (multi-select)
- "Next: Configure Settings" button

### Settings Screen
- Sliders:
  - Download speed (10 kbps – 50,000 kbps)
  - Upload speed (10 kbps – 50,000 kbps)
  - Latency (0 – 500 ms)
  - Jitter (0 – 100 ms)
  - Packet loss (0 – 10 %)
- "Quick Presets" button → `PresetBottomSheet`
- "Save Profile" button → name dialog → persists to Room
- "Start Throttling" button → starts VPN with current config

### Profiles Screen
- List of saved named profiles (from Room)
- Tap to load into Settings
- Swipe to delete

---

## Room Database

**Entity:** `ThrottleProfileEntity` mirrors `ThrottleProfile`; `selectedAppPackages` stored as JSON string via Gson `@TypeConverter`.

**DAO:**
```kotlin
@Query("SELECT * FROM throttle_profiles ORDER BY updatedAt DESC")
fun getAllProfiles(): Flow<List<ThrottleProfileEntity>>

@Insert(onConflict = REPLACE)
suspend fun insert(profile: ThrottleProfileEntity)

@Query("DELETE FROM throttle_profiles WHERE id = :id")
suspend fun deleteById(id: Long)
```

---

## Gradle Dependencies

```kotlin
// Compose BOM
implementation(platform("androidx.compose:compose-bom:2024.06.00"))
implementation("androidx.compose.ui:ui")
implementation("androidx.compose.material3:material3")
implementation("androidx.activity:activity-compose:1.9.0")
implementation("androidx.navigation:navigation-compose:2.7.7")

// Lifecycle
implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")

// Room
implementation("androidx.room:room-runtime:2.6.1")
implementation("androidx.room:room-ktx:2.6.1")
ksp("androidx.room:room-compiler:2.6.1")

// Hilt
implementation("com.google.dagger:hilt-android:2.51.1")
ksp("com.google.dagger:hilt-compiler:2.51.1")
implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

// Coroutines
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

// Gson (Room type converters)
implementation("com.google.code.gson:gson:2.11.0")

// Coil (app icons)
implementation("io.coil-kt:coil-compose:2.6.0")
```

---

## Per-App Packet Filtering — How It Works

### `addAllowedApplication()` — the key API

Filtering happens **before packets reach your service** — you don't inspect packets to determine which app sent them. When building the VPN tunnel with `VpnService.Builder`, you declare which apps' traffic is routed through the tunnel:

```kotlin
val builder = VpnService.Builder()
    .addAddress("10.0.0.2", 32)
    .addRoute("0.0.0.0", 0)
    .addAllowedApplication("com.android.chrome")
    .addAllowedApplication("com.example.myapp")
    .establish()
```

The Android OS kernel handles routing:
- **Allowed app** (e.g. Chrome) makes a request → kernel routes through TUN interface → `PacketProcessor` receives it
- **Any other app** (not in the list) → kernel routes directly to the real network interface → bypasses VPN entirely

### What `PacketProcessor` actually receives

Only raw IP packets (Layer 3). No "this came from Chrome" label. You don't need one — the OS already filtered them.

```
TUN fd receives:  [IPv4 header | TCP/UDP header | payload]
                   destination IP/port is all you need to forward
```

### Limitation: no per-app throttle rates within one session

Once a packet arrives at the TUN interface you cannot tell which of the allowed apps produced it. All apps in a session share the same throttle config. This is acceptable for the use case — a tester selects a set of apps and applies one profile at a time.

### What is NOT possible without root

- Reading app package names from raw IP packets
- Using `SO_MARK` or UID-based socket tagging
- `/proc/net/tcp`-style UID lookup is too slow for the hot path

`addAllowedApplication()` / `addDisallowedApplication()` is the only clean per-app mechanism available without root, and the two modes are **mutually exclusive** per VPN instance.

---

## Important Gotchas

| Gotcha | Mitigation |
|---|---|
| `VpnService.prepare()` returns non-null even after previous grant if settings changed | Always call it before starting; handle returned Intent |
| Must call `protect(socket)` on all tunnel sockets | Prevents infinite routing loop back through VPN |
| One VPN per user at a time (system limit) | Service detects existing VPN and stops it first |
| `tunInputStream.read()` blocks | Run on dedicated thread, not the main/coroutine pool |
| `addAllowedApplication` vs `addDisallowedApplication` are mutually exclusive | Use allowlist (allowed apps only); cannot mix both |
| TCP forwarding requires state machine | Track per-connection state in `ConcurrentHashMap<FiveTuple, TcpState>` |
| Foreground service required on Android 8.0+ | `startForeground()` in `onCreate`; notification must be non-dismissible |
| `QUERY_ALL_PACKAGES` needs Privacy Policy declaration in Play Store | Required to list all user-installed apps |
| Token bucket must be synchronized | Multiple threads writing to TUN can race; use `@Synchronized` or `AtomicLong` |

---

## Implementation Order

1. **Gradle project setup** — `settings.gradle.kts`, `build.gradle.kts`, `libs.versions.toml`
2. **Domain models** — `ThrottleProfile`, `AppInfo`, `NetworkStats`, `PresetProfiles`
3. **Room layer** — entity, DAO, database, converters, `ProfileRepository`
4. **App list** — `AppRepository` via `PackageManager`, `AppListViewModel`
5. **VPN core** — `ThrottleConfig`, `TokenBucket`, `PacketLossFilter`, `LatencyQueue`
6. **Packet forwarders** — `UdpForwarder`, `TcpForwarder` with NIO
7. **`PacketProcessor`** — full read/write loop wiring all throttle components
8. **`ThrottleVpnService`** — lifecycle + foreground notification
9. **`VpnControlViewModel`** — start/stop + permission flow
10. **Settings & Profiles ViewModels**
11. **Compose UI** — all screens, navigation, theme
12. **Hilt DI** — wire everything together
13. **End-to-end test** on device

---

## Verification Checklist

- [ ] `./gradlew :app:assembleDebug` compiles cleanly
- [ ] VPN permission dialog appears on first "Start"
- [ ] VPN key icon appears in Android status bar when active
- [ ] Selected app (e.g. Chrome) loads pages noticeably slower under 2G preset
- [ ] Non-selected apps load at full speed while VPN is active
- [ ] Real-time bytes/speed counters update on Home screen
- [ ] Saving a profile persists across app restart (Room DB)
- [ ] Loading a saved profile restores all slider values
- [ ] VPN stops cleanly when toggled off; key icon disappears
- [ ] Packet loss at 20% causes visible request failures in selected app

---

## Roadmap: firewall and traffic inspection

Status: **planned, nothing implemented.** Two feature sets, modelled on what NetGuard and
PCAPdroid do, built on the VPN tunnel the app already has:

- **Firewall (NetGuard-style)** — block or allow network access per app.
- **Traffic inspection (PCAPdroid-style)** — see which connections each app makes, and export
  the raw traffic as a PCAP file.

Both fit the same use case as throttling: a developer or tester controlling and observing the
network of an app under test, on an unrooted device.

### Foundation: knowing which app owns a connection

Today a packet arriving on the TUN device carries no app identity, which is why all selected
apps share one profile. Both new feature sets need that identity.

On Android 10 (API 29) and newer, a VPN app may call
`ConnectivityManager.getConnectionOwnerUid(protocol, local, remote)` to get the UID that owns a
TCP or UDP flow. The plan:

- Look the UID up **once per flow** — on the SYN in `TcpForwarder`, on the first datagram in
  `UdpForwarder` — and store it on the connection/session object. Never on the per-packet path.
- Resolve UID → package name and label through `PackageManager`, cached.
- Introduce a `FlowRegistry` (new, in `vpn/`) that both forwarders report to: flow opened,
  bytes up/down, flow closed, plus the owning UID. It is the single source for the connections
  list, per-app statistics, firewall decisions and capture filtering.

On Android 5–9 the lookup does not exist. There the new features are unavailable and the UI says
so; throttling keeps working as it does now.

Everything below depends on this step, so it is built first.

### Firewall (NetGuard-style)

| Capability | Design |
|---|---|
| Block an app completely | Per-app rule `BLOCK`. TCP: answer the SYN with a reset instead of connecting. UDP: drop the datagram. |
| Block per network type | Separate rule for Wi-Fi and for mobile data, evaluated against the transport of the current underlying network. |
| Block while the screen is off | Optional per-app flag, driven by screen on/off broadcasts. |
| Blocked-attempt log | Each refused flow is recorded with app, destination and time, and shown in the UI. |
| Block by domain (later) | Answer DNS queries for listed domains with NXDOMAIN inside `UdpForwarder`. Does not work for apps using DNS-over-HTTPS or Private DNS. |

Design points:

- **Three states per app** — *not routed* (bypasses the VPN, as today), *throttled*, *blocked*.
  A blocked app must be routed into the tunnel, so it joins the `addAllowedApplication()` list.
- **Rule storage** — new Room table `app_rules` (package, mode, Wi-Fi allowed, mobile allowed,
  block when screen off). Database version goes to 2 with a migration.
- **Rule changes while running** — rules are read from an in-memory snapshot the service
  replaces atomically; changing the *set* of routed apps still needs a tunnel rebuild, which
  the existing **Apply** flow already does.
- **Not planned** — a device-wide "block everything by default" lockdown mode. It would route
  every app through the forwarder and conflicts with the always-on VPN restriction.

### Traffic inspection (PCAPdroid-style)

| Capability | Design |
|---|---|
| Live connections list | From `FlowRegistry`: app, protocol, destination address and port, bytes up/down, start time, state (open, closed, reset, blocked). |
| Hostnames | Learned passively: map addresses to names from DNS replies seen in `UdpForwarder`, and read the SNI from TLS ClientHello / the `Host` header from plain HTTP on the first client segment. |
| Per-app statistics | Totals and current rate per app, replacing the single global counter pair on Home. |
| PCAP export | Write every packet read from and written to the TUN device to a `.pcap` file (link type `LINKTYPE_RAW`, 101), openable in Wireshark. Destination chosen by the user through the system file picker. |
| Capture filter | Optionally capture only one app's flows, using the flow's UID. |

Design points:

- **Capture tap** — one hook in `PacketProcessor` (upstream, before the loss filter) and one in
  `TunWriter` (downstream). Packets are handed to a bounded queue and written by a dedicated
  thread, so a slow disk never stalls forwarding; when the queue is full, packets are dropped
  from the capture (and counted), not from the network.
- **Limits** — capture is off by default, has a size cap, and stops automatically when the cap
  is reached or the VPN stops.
- **Memory** — the registry keeps a bounded number of closed flows (ring buffer).
- **Not planned** — TLS decryption. Capture shows encrypted payloads as they are on the wire.
- **Privacy** — captures and connection history stay on the device; nothing is uploaded.

### UI changes

- **Apps** — each app gets a mode selector (not routed / throttle / block) instead of a
  checkbox, and its Wi-Fi/mobile toggles when blocked.
- **Connections** (new tab, or replaces Logs with Logs moved under it) — live list with search
  and per-app filter; tap a connection for details; start/stop capture and export from here.
- **Home** — per-app breakdown under the totals; a count of blocked attempts.

### Build order

1. **Flow attribution and `FlowRegistry`** — UID lookup, registry, unit tests with fake flows.
2. **Connections screen and per-app statistics** — read-only, lowest risk, makes step 1 visible.
3. **Firewall rules** — `app_rules` table, block decision in both forwarders, Apps screen
   modes, blocked-attempt log.
4. **PCAP capture and export** — capture tap, writer thread, file picker, size cap.
5. **Hostnames** — DNS reply parsing, SNI and HTTP Host extraction.
6. **Domain blocking** — DNS-level rules (depends on 5).
7. **Per-app throttle profiles** (optional) — with flow ownership known, each app could get its
   own token buckets; this lifts the "one profile per session" limitation on Android 10+.

Each step is shippable on its own and is verified on a physical device before the next starts.

### Risks and open questions

- **`getConnectionOwnerUid` timing** — the lookup can fail for a flow the kernel has already
  torn down; such flows are recorded as "unknown app" and follow a configurable default
  (allow, to stay safe).
- **Battery and throughput** — blocked apps are routed through the tunnel. A refused flow costs
  almost nothing, but the more apps are routed, the more traffic passes through the forwarder.
- **Scope** — these features turn a throttling tool into a broader network toolkit. Whether the
  app keeps a single "apps under test" list, or grows a device-wide firewall, should be decided
  before step 3.
- **Play Store policy** — packet capture and `QUERY_ALL_PACKAGES` both need a declared
  justification if the app is ever published.

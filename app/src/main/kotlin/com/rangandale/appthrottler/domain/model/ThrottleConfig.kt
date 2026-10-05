package com.rangandale.appthrottler.domain.model

/** The five values that shape traffic. A speed of 0 means unlimited. */
data class ThrottleParams(
    val downloadSpeedKbps: Int = 0,
    val uploadSpeedKbps: Int = 0,
    val latencyMs: Int = 0,
    val jitterMs: Int = 0,
    val packetLossPercent: Float = 0f,
)

/**
 * Per-app behaviour for an app that is routed through the VPN. An app without a rule is simply
 * shaped with the session settings.
 */
data class AppRule(
    val packageName: String,
    /** Refuse the app's connections while the conditions below hold. */
    val block: Boolean = false,
    val blockOnWifi: Boolean = true,
    val blockOnMobile: Boolean = true,
    /** Only block while the screen is off (e.g. to test background sync being cut). */
    val onlyWhenScreenOff: Boolean = false,
    /** Shape this app with its own values instead of the session settings. */
    val throttle: ThrottleParams? = null,
    /** Name of the profile [throttle] was copied from, for display. */
    val throttleName: String? = null,
) {
    val isDefault: Boolean get() = !block && throttle == null
}

data class ThrottleConfig(
    /** Apps routed through the VPN. */
    val selectedApps: List<String> = emptyList(),
    val downloadSpeedKbps: Int = 0,
    val uploadSpeedKbps: Int = 0,
    val latencyMs: Int = 0,
    val jitterMs: Int = 0,
    val packetLossPercent: Float = 0f,
    /** Rules for routed apps that deviate from the session settings. */
    val appRules: List<AppRule> = emptyList(),
    /** Domains (and their subdomains) that routed apps may not reach. */
    val blockedDomains: List<String> = emptyList(),
) {
    val params: ThrottleParams
        get() = ThrottleParams(downloadSpeedKbps, uploadSpeedKbps, latencyMs, jitterMs, packetLossPercent)

    fun ruleFor(packageName: String): AppRule? = appRules.firstOrNull { it.packageName == packageName }
}

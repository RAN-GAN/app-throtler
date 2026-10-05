package com.rangandale.appthrottler.vpn.flow

import com.rangandale.appthrottler.domain.model.AppRule

/**
 * Decides which flows are refused. Network type and screen state are pushed in by the service
 * as they change; the packet threads only read.
 */
class FlowPolicy(
    private val rulesByUid: Map<Int, AppRule> = emptyMap(),
    blockedDomains: Collection<String> = emptyList(),
) {
    private val domains: Set<String> = blockedDomains.mapNotNull(::normalizeDomain).toSet()

    @Volatile var onWifi: Boolean = false
    @Volatile var onMobile: Boolean = false
    @Volatile var screenOn: Boolean = true

    /**
     * Domain rules work on host names, which encrypted DNS (DNS-over-TLS, "Private DNS") hides.
     * While any domain is blocked, DNS-over-TLS is refused so the system resolver falls back to
     * plain DNS, as it does in its default automatic mode.
     */
    val refusesEncryptedDns: Boolean get() = domains.isNotEmpty()

    val hasRules: Boolean get() = rulesByUid.values.any { it.block } || domains.isNotEmpty()

    /** Whether flows of [uid] are refused right now. Unknown apps are never blocked. */
    fun blocks(uid: Int): Boolean {
        val rule = rulesByUid[uid] ?: return false
        if (!rule.block) return false
        if (rule.onlyWhenScreenOff && screenOn) return false
        return when {
            onWifi -> rule.blockOnWifi
            onMobile -> rule.blockOnMobile
            // Some other transport (e.g. Ethernet): block only apps that are blocked everywhere.
            else -> rule.blockOnWifi && rule.blockOnMobile
        }
    }

    /** Whether [hostname] is a blocked domain or a subdomain of one. */
    fun blocksDomain(hostname: String): Boolean {
        if (domains.isEmpty()) return false
        var name = normalizeDomain(hostname) ?: return false
        while (true) {
            if (name in domains) return true
            val dot = name.indexOf('.')
            if (dot < 0) return false
            name = name.substring(dot + 1)
        }
    }

    companion object {
        /** Lower-cases a user-entered domain and strips wildcards, schemes, paths and dots. */
        fun normalizeDomain(input: String): String? {
            val name = input.trim().lowercase()
                .substringAfter("://")
                .substringBefore('/')
                .substringBefore(':')
                .removePrefix("*.")
                .trim('.')
            return name.takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) }
        }
    }
}

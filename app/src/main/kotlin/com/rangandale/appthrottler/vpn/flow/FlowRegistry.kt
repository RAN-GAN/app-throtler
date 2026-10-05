package com.rangandale.appthrottler.vpn.flow

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class FlowState { OPEN, CLOSED, FAILED, BLOCKED }

/** Identifies a transport flow as seen on the TUN device. */
data class FlowKey(val protocol: Int, val srcIp: Int, val srcPort: Int, val dstIp: Int, val dstPort: Int)

/** Immutable view of a flow for the UI. */
data class FlowSnapshot(
    val id: Long,
    val protocol: Int,
    val uid: Int,
    val localPort: Int,
    val remoteIp: Int,
    val remotePort: Int,
    val hostname: String?,
    val state: FlowState,
    val bytesUp: Long,
    val bytesDown: Long,
    val startMs: Long,
    val endMs: Long,
    /** How often a blocked destination was tried; 1 for everything else. */
    val attempts: Int,
)

data class AppTotals(val uid: Int, val bytesUp: Long, val bytesDown: Long)

/** Live record of one flow, updated by the packet threads. */
class FlowRecord internal constructor(
    val id: Long,
    val protocol: Int,
    val uid: Int,
    val localPort: Int,
    val remoteIp: Int,
    val remotePort: Int,
    private val appCounters: AppCounters,
) {
    private val bytesUp = AtomicLong()
    private val bytesDown = AtomicLong()
    val startMs: Long = System.currentTimeMillis()

    @Volatile var hostname: String? = null
    @Volatile var state: FlowState = FlowState.OPEN
        internal set
    @Volatile var endMs: Long = 0L
        internal set
    @Volatile var attempts: Int = 1
        internal set

    fun addUp(bytes: Int) {
        bytesUp.addAndGet(bytes.toLong())
        appCounters.up.addAndGet(bytes.toLong())
    }

    fun addDown(bytes: Int) {
        bytesDown.addAndGet(bytes.toLong())
        appCounters.down.addAndGet(bytes.toLong())
    }

    internal fun snapshot() = FlowSnapshot(
        id, protocol, uid, localPort, remoteIp, remotePort, hostname, state,
        bytesUp.get(), bytesDown.get(), startMs, endMs, attempts,
    )
}

internal class AppCounters {
    val up = AtomicLong()
    val down = AtomicLong()
}

/**
 * Process-wide record of the flows of the current VPN session: who opened them, where to, how
 * much they carried and how they ended. Feeds the connections list and per-app statistics.
 */
object FlowRegistry {
    private const val MAX_FINISHED = 500

    private data class BlockedKey(val protocol: Int, val uid: Int, val remoteIp: Int, val remotePort: Int, val hostname: String?)

    private val nextId = AtomicLong()
    private val open = ConcurrentHashMap<Long, FlowRecord>()
    private val apps = ConcurrentHashMap<Int, AppCounters>()
    private val blockedAttempts = AtomicLong()

    // Guarded by `finished`.
    private val finished = ArrayDeque<FlowRecord>()
    private val blockedIndex = HashMap<BlockedKey, FlowRecord>()

    val totalBlockedAttempts: Long get() = blockedAttempts.get()

    private fun newRecord(protocol: Int, uid: Int, localPort: Int, remoteIp: Int, remotePort: Int) = FlowRecord(
        nextId.incrementAndGet(), protocol, uid, localPort, remoteIp, remotePort,
        apps.getOrPut(uid) { AppCounters() },
    )

    fun open(protocol: Int, uid: Int, localPort: Int, remoteIp: Int, remotePort: Int): FlowRecord {
        val record = newRecord(protocol, uid, localPort, remoteIp, remotePort)
        record.hostname = HostnameCache.get(remoteIp)
        open[record.id] = record
        return record
    }

    fun close(record: FlowRecord, state: FlowState = FlowState.CLOSED) {
        if (open.remove(record.id) == null) return
        record.state = state
        record.endMs = System.currentTimeMillis()
        if (state == FlowState.BLOCKED) blockedAttempts.incrementAndGet()
        synchronized(finished) { remember(record) }
    }

    /** Records a refused flow. Repeated attempts at the same destination share one entry. */
    fun blocked(protocol: Int, uid: Int, localPort: Int, remoteIp: Int, remotePort: Int, hostname: String?) {
        blockedAttempts.incrementAndGet()
        val now = System.currentTimeMillis()
        val key = BlockedKey(protocol, uid, remoteIp, remotePort, hostname)
        synchronized(finished) {
            val existing = blockedIndex[key]
            if (existing != null) {
                existing.attempts += 1
                existing.endMs = now
                return
            }
            val record = newRecord(protocol, uid, localPort, remoteIp, remotePort)
            record.hostname = hostname ?: HostnameCache.get(remoteIp)
            record.state = FlowState.BLOCKED
            record.endMs = now
            blockedIndex[key] = record
            remember(record)
        }
    }

    private fun remember(record: FlowRecord) {
        finished.addLast(record)
        while (finished.size > MAX_FINISHED) {
            val evicted = finished.removeFirst()
            blockedIndex.values.remove(evicted)
        }
    }

    /** All known flows, most recently active first. */
    fun snapshot(): List<FlowSnapshot> {
        val all = ArrayList<FlowSnapshot>(open.size + MAX_FINISHED)
        open.values.mapTo(all) { it.snapshot() }
        synchronized(finished) { finished.mapTo(all) { it.snapshot() } }
        // Open flows first (newest on top), then finished ones by when they ended.
        all.sortWith(
            compareByDescending<FlowSnapshot> { it.state == FlowState.OPEN }
                .thenByDescending { if (it.state == FlowState.OPEN) it.startMs else it.endMs },
        )
        return all
    }

    fun appTotals(): List<AppTotals> = apps.map { (uid, counters) -> AppTotals(uid, counters.up.get(), counters.down.get()) }

    fun reset() {
        open.clear()
        apps.clear()
        blockedAttempts.set(0L)
        synchronized(finished) {
            finished.clear()
            blockedIndex.clear()
        }
        HostnameCache.clear()
    }
}

/** Names learned from DNS replies, used to label flows by the host the app asked for. */
object HostnameCache {
    private const val MAX_ENTRIES = 4096
    private val names = ConcurrentHashMap<Int, String>()

    fun get(ip: Int): String? = names[ip]

    fun put(ip: Int, name: String) {
        if (names.size >= MAX_ENTRIES) names.clear()
        names[ip] = name
    }

    fun clear() = names.clear()
}

/**
 * Remembers which app (UID) owns each flow. The lookup is a system call into the platform, so
 * it is made once per flow and cached.
 */
class FlowOwners(private val lookup: (FlowKey) -> Int) {
    private val owners = ConcurrentHashMap<FlowKey, Int>()

    /** @param refresh force a new lookup, e.g. for a TCP SYN that may reuse an old port. */
    fun resolve(key: FlowKey, refresh: Boolean = false): Int {
        if (!refresh) owners[key]?.let { return it }
        if (owners.size >= MAX_ENTRIES) owners.clear()
        val uid = lookup(key)
        owners[key] = uid
        return uid
    }

    fun forget(key: FlowKey) {
        owners.remove(key)
    }

    companion object {
        const val UNKNOWN_UID = -1
        private const val MAX_ENTRIES = 8192
    }
}

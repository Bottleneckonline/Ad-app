package online.bottleneck.adfree

import java.util.concurrent.atomic.AtomicLong

/** Live numbers shared between the VPN service and the screen. Process-wide and in memory only. */
object BlockerState {
    private const val RECENT_LIMIT = 50

    @Volatile var running = false
    @Volatile var listSize = 0
    val blocked = AtomicLong()
    val allowed = AtomicLong()
    private val recent = ArrayDeque<String>()

    fun recordBlocked(domain: String) {
        blocked.incrementAndGet()
        synchronized(recent) {
            recent.remove(domain)
            recent.addFirst(domain)
            while (recent.size > RECENT_LIMIT) recent.removeAt(recent.size - 1)
        }
    }

    fun recordAllowed() {
        allowed.incrementAndGet()
    }

    fun recentBlocked(): List<String> = synchronized(recent) { recent.toList() }
}

package online.bottleneck.adfree.core

import java.io.BufferedReader
import java.io.Reader

/**
 * A set of blocked domains with an optional allowlist.
 *
 * A domain is blocked when it, or any of its parent domains, is on the
 * blocklist and neither it nor any of its parents is on the allowlist.
 * So blocking `doubleclick.net` also blocks `ad.doubleclick.net`.
 */
class DomainBlocklist(
    blocked: Collection<String> = emptyList(),
    allowed: Collection<String> = emptyList(),
) {
    private val blocked: Set<String> = blocked.mapNotNullTo(HashSet()) { normalize(it) }
    private val allowed: Set<String> = allowed.mapNotNullTo(HashSet()) { normalize(it) }

    val size: Int get() = blocked.size

    fun isBlocked(domain: String): Boolean {
        val name = normalize(domain) ?: return false
        if (matches(name, allowed)) return false
        return matches(name, blocked)
    }

    fun withAllowed(extra: Collection<String>): DomainBlocklist =
        DomainBlocklist(blocked, allowed + extra)

    private fun matches(name: String, set: Set<String>): Boolean {
        if (set.isEmpty()) return false
        var candidate = name
        while (true) {
            if (candidate in set) return true
            val dot = candidate.indexOf('.')
            if (dot < 0 || dot == candidate.length - 1) return false
            candidate = candidate.substring(dot + 1)
        }
    }

    companion object {
        private val IGNORED = setOf(
            "localhost", "localhost.localdomain", "local", "broadcasthost",
            "ip6-localhost", "ip6-loopback", "ip6-localnet", "ip6-mcastprefix",
            "ip6-allnodes", "ip6-allrouters", "ip6-allhosts", "0.0.0.0",
        )

        private val LABEL = Regex("^[a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?$")

        /** Lowercases, trims a trailing dot, and rejects anything that is not a domain name. */
        fun normalize(domain: String): String? {
            val name = domain.trim().trimEnd('.').lowercase()
            if (name.isEmpty() || name.length > 253) return null
            val labels = name.split('.')
            if (labels.any { !LABEL.matches(it) }) return null
            return name
        }

        /**
         * Parses one line of a blocklist. Supports hosts files (`0.0.0.0 ads.example.com`),
         * plain domain lists (`ads.example.com`) and simple adblock rules (`||ads.example.com^`).
         * Returns null for comments, blank lines, and anything else.
         */
        fun parseLine(raw: String): String? {
            var line = raw.substringBefore('#').trim()
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) return null
            if (line.startsWith("||")) {
                if (!line.endsWith("^")) return null
                line = line.removePrefix("||").removeSuffix("^")
            } else {
                val parts = line.split(Regex("\\s+"))
                line = when {
                    parts.size == 1 -> parts[0]
                    parts.size >= 2 && looksLikeIp(parts[0]) -> parts[1]
                    else -> return null
                }
            }
            val name = normalize(line) ?: return null
            if (name in IGNORED || !name.contains('.')) return null
            return name
        }

        fun parseDomains(reader: Reader): List<String> =
            BufferedReader(reader).useLines { lines -> lines.mapNotNull(::parseLine).toList() }

        private fun looksLikeIp(s: String) =
            s.contains(':') || s.all { it.isDigit() || it == '.' }
    }
}

package online.bottleneck.adfree.core

/** What to do with one packet read from the tun interface. */
sealed interface FilterDecision {
    /** Not a DNS query we understand. Drop it; the app's resolver will retry or fall back. */
    data object Drop : FilterDecision

    /** The domain is blocked. Write [response] back to the tun interface. */
    class Blocked(val domain: String, val response: ByteArray) : FilterDecision

    /** The domain is allowed. Send [query]'s payload to the upstream resolver, then call [DnsFilter.wrapUpstreamReply]. */
    class Forward(val domain: String, val query: UdpPacket) : FilterDecision
}

/**
 * The platform-independent heart of the blocker. The VPN routes only DNS traffic
 * (packets to a virtual DNS server address) into the app, and this decides per query.
 */
class DnsFilter(@Volatile var blocklist: DomainBlocklist) {

    fun decide(packet: ByteArray, length: Int = packet.size): FilterDecision {
        val udp = UdpPacket.parse(packet, length) ?: return FilterDecision.Drop
        if (udp.destinationPort != 53) return FilterDecision.Drop
        val question = DnsMessage.parseQuery(udp.payload) ?: return FilterDecision.Drop
        return if (blocklist.isBlocked(question.name)) {
            val dns = DnsMessage.blockedResponse(udp.payload, 0, question)
            FilterDecision.Blocked(question.name, udp.reply(dns).toBytes())
        } else {
            FilterDecision.Forward(question.name, udp)
        }
    }

    /** Wraps the upstream resolver's answer into an IP packet addressed back to the asking app. */
    fun wrapUpstreamReply(query: UdpPacket, dnsReply: ByteArray, length: Int = dnsReply.size): ByteArray =
        query.reply(dnsReply.copyOf(length)).toBytes()
}

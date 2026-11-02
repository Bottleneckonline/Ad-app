package online.bottleneck.adfree.core

/** What to do with one packet read from the tun interface. */
sealed interface FilterDecision {
    /** Not something we handle. Drop it; the app's resolver will retry or fall back. */
    data object Drop : FilterDecision

    /** The domain is blocked. Write [response] back to the tun interface. */
    class Blocked(val domain: String, val response: ByteArray) : FilterDecision

    /** A TCP connection to a DNS address (encrypted DNS). Write [response], a reset, back. */
    class Refused(val response: ByteArray) : FilterDecision

    /** The domain is allowed. Send [query]'s payload upstream, then call [DnsFilter.upstreamReply]. */
    class Forward(val domain: String, val query: UdpPacket) : FilterDecision
}

/** What to do with a resolver's answer to a forwarded query. */
sealed interface ReplyDecision {
    val response: ByteArray

    /** Pass the answer on to the app. */
    class Pass(override val response: ByteArray) : ReplyDecision

    /** The answer pointed at a blocked domain through a CNAME; [response] blocks it instead. */
    class Blocked(val domain: String, override val response: ByteArray) : ReplyDecision
}

/**
 * The platform-independent heart of the blocker. The VPN routes only DNS traffic
 * (packets to DNS server addresses) into the app, and this decides per packet.
 */
class DnsFilter(@Volatile var blocklist: DomainBlocklist) {

    fun decide(packet: ByteArray, length: Int = packet.size): FilterDecision {
        TcpReset.forSyn(packet, length)?.let { return FilterDecision.Refused(it) }
        val udp = UdpPacket.parse(packet, length) ?: return FilterDecision.Drop
        if (udp.destinationPort != 53) return FilterDecision.Drop
        val question = DnsMessage.parseQuery(udp.payload) ?: return FilterDecision.Drop
        return if (blocklist.isBlocked(question.name)) {
            FilterDecision.Blocked(question.name, blockedPacket(udp, question))
        } else {
            FilterDecision.Forward(question.name, udp)
        }
    }

    /**
     * Checks the upstream answer before it reaches the app. An allowed name can be an
     * alias (CNAME) for an ad server, for example `ads.game-studio.com` pointing at an
     * ad network's host; those are blocked too.
     */
    fun upstreamReply(query: UdpPacket, dnsReply: ByteArray, length: Int = dnsReply.size): ReplyDecision {
        val list = blocklist
        val hidden = DnsMessage.cnameTargets(dnsReply, length).firstOrNull { list.isBlocked(it) }
        if (hidden != null) {
            val question = DnsMessage.parseQuery(query.payload)
            if (question != null) return ReplyDecision.Blocked(hidden, blockedPacket(query, question))
        }
        return ReplyDecision.Pass(query.reply(dnsReply.copyOf(length)).toBytes())
    }

    private fun blockedPacket(query: UdpPacket, question: DnsQuestion): ByteArray =
        query.reply(DnsMessage.blockedResponse(query.payload, 0, question)).toBytes()
}

package online.bottleneck.adfree.core

import java.io.StringReader
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DomainBlocklistTest {
    private val list = DomainBlocklist(
        blocked = listOf("doubleclick.net", "ads.example.com", "Tracker.IO."),
        allowed = listOf("good.doubleclick.net"),
    )

    @Test fun blocksExactAndSubdomains() {
        assertTrue(list.isBlocked("doubleclick.net"))
        assertTrue(list.isBlocked("ad.g.doubleclick.net"))
        assertTrue(list.isBlocked("ADS.example.com."))
        assertTrue(list.isBlocked("tracker.io"))
    }

    @Test fun doesNotBlockParentsOrLookalikes() {
        assertFalse(list.isBlocked("example.com"))
        assertFalse(list.isBlocked("notdoubleclick.net"))
        assertFalse(list.isBlocked("google.com"))
    }

    @Test fun allowlistWins() {
        assertFalse(list.isBlocked("good.doubleclick.net"))
        assertFalse(list.isBlocked("cdn.good.doubleclick.net"))
        assertTrue(list.withAllowed(emptyList()).isBlocked("bad.doubleclick.net"))
    }

    @Test fun parsesCommonFormats() {
        val text = """
            # comment
            127.0.0.1 localhost
            0.0.0.0 0.0.0.0
            0.0.0.0 ads.one.com # trailing comment
            127.0.0.1	ads.two.com
            ads.three.com
            ||ads.four.com^
            ||ads.five.com^${'$'}third-party
            ! adblock comment
            [Adblock Plus 2.0]
            not a domain line
            single
        """.trimIndent()
        assertEquals(
            listOf("ads.one.com", "ads.two.com", "ads.three.com", "ads.four.com"),
            DomainBlocklist.parseDomains(StringReader(text)),
        )
    }
}

class DnsFilterTest {
    private val app4 = InetAddress.getByName("10.111.222.1")
    private val dns4 = InetAddress.getByName("10.111.222.2")
    private val app6 = InetAddress.getByName("fd00:111:222::1")
    private val dns6 = InetAddress.getByName("fd00:111:222::2")
    private val filter = DnsFilter(DomainBlocklist(listOf("doubleclick.net")))

    private fun query(name: String, type: Int, id: Int = 0x1234, edns: Boolean = false): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun w16(v: Int) { out.write(v shr 8); out.write(v and 0xFF) }
        w16(id); w16(0x0100); w16(1); w16(0); w16(0); w16(if (edns) 1 else 0)
        for (label in name.split('.')) { out.write(label.length); out.write(label.toByteArray()) }
        out.write(0); w16(type); w16(1)
        if (edns) { out.write(0); w16(41); w16(4096); w16(0); w16(0); w16(0) }
        return out.toByteArray()
    }

    private fun ipPacket(payload: ByteArray, v6: Boolean = false, port: Int = 53) =
        if (v6) UdpPacket(app6, 40000, dns6, port, payload).toBytes()
        else UdpPacket(app4, 40000, dns4, port, payload).toBytes()

    @Test fun parsesQuery() {
        val q = assertNotNull(DnsMessage.parseQuery(query("Ad.DoubleClick.net", DnsMessage.TYPE_A)))
        assertEquals("ad.doubleclick.net", q.name)
        assertEquals(DnsMessage.TYPE_A, q.type)
        assertEquals(0x1234, q.id)
    }

    @Test fun rejectsResponsesAndGarbage() {
        val response = query("a.com", 1).also { it[2] = (it[2].toInt() or 0x80).toByte() }
        assertNull(DnsMessage.parseQuery(response))
        assertNull(DnsMessage.parseQuery(ByteArray(5)))
        assertNull(DnsMessage.parseQuery(query("a.com", 1).copyOf(15)))
    }

    @Test fun blocksIpv4AQueryWithZeroAddress() {
        val decision = assertIs<FilterDecision.Blocked>(
            filter.decide(ipPacket(query("ad.doubleclick.net", DnsMessage.TYPE_A, edns = true))),
        )
        assertEquals("ad.doubleclick.net", decision.domain)
        val reply = assertNotNull(UdpPacket.parse(decision.response))
        assertEquals(dns4, reply.source)
        assertEquals(53, reply.sourcePort)
        assertEquals(app4, reply.destination)
        assertEquals(40000, reply.destinationPort)
        val dns = reply.payload
        assertEquals(0x1234, DnsMessage.u16(dns, 0))
        assertEquals(0x8180, DnsMessage.u16(dns, 2)) // response, RD, RA, NOERROR
        assertEquals(1, DnsMessage.u16(dns, 6)) // one answer
        assertEquals(0, DnsMessage.u16(dns, 10)) // EDNS record dropped
        assertContentEquals(ByteArray(4), dns.copyOfRange(dns.size - 4, dns.size))
        assertValidIpv4Checksum(decision.response)
    }

    @Test fun blocksIpv6AaaaQuery() {
        val decision = assertIs<FilterDecision.Blocked>(
            filter.decide(ipPacket(query("doubleclick.net", DnsMessage.TYPE_AAAA), v6 = true)),
        )
        val reply = assertNotNull(UdpPacket.parse(decision.response))
        assertTrue(reply.isIpv6)
        assertEquals(app6, reply.destination)
        assertEquals(16, DnsMessage.u16(reply.payload, reply.payload.size - 18))
        assertValidUdpChecksum(decision.response)
    }

    @Test fun otherTypesGetEmptyAnswer() {
        val decision = assertIs<FilterDecision.Blocked>(
            filter.decide(ipPacket(query("doubleclick.net", 65))),
        )
        val dns = assertNotNull(UdpPacket.parse(decision.response)).payload
        assertEquals(0, DnsMessage.u16(dns, 6))
    }

    @Test fun forwardsAllowedDomainsAndWrapsReply() {
        val decision = assertIs<FilterDecision.Forward>(
            filter.decide(ipPacket(query("example.com", DnsMessage.TYPE_A))),
        )
        assertEquals("example.com", decision.domain)
        val upstream = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 99)
        val pass = assertIs<ReplyDecision.Pass>(filter.upstreamReply(decision.query, upstream, 12))
        val wrapped = assertNotNull(UdpPacket.parse(pass.response))
        assertEquals(app4, wrapped.destination)
        assertContentEquals(upstream.copyOf(12), wrapped.payload)
    }

    /** A resolver answer: [name] CNAME [target] (compressed), then target A 1.2.3.4. */
    private fun cnameAnswer(name: String, target: String): ByteArray {
        val q = query(name, DnsMessage.TYPE_A)
        val out = java.io.ByteArrayOutputStream()
        fun w16(v: Int) { out.write(v shr 8); out.write(v and 0xFF) }
        out.write(q, 0, 12)
        val bytes = out.toByteArray(); out.reset()
        bytes[2] = 0x81.toByte(); bytes[3] = 0x80.toByte(); bytes[7] = 2
        out.write(bytes); out.write(q, 12, q.size - 12)
        val targetAt = out.size() + 12
        w16(0xC00C); w16(5); w16(1); w16(0); w16(300)
        val labels = target.split('.')
        w16(labels.sumOf { it.length + 1 } + 1)
        for (label in labels) { out.write(label.length); out.write(label.toByteArray()) }
        out.write(0)
        w16(0xC000 or targetAt); w16(1); w16(1); w16(0); w16(300); w16(4)
        out.write(byteArrayOf(1, 2, 3, 4))
        return out.toByteArray()
    }

    @Test fun readsCnameTargets() {
        assertEquals(
            listOf("x.ad.doubleclick.net"),
            DnsMessage.cnameTargets(cnameAnswer("ads.studio.com", "x.ad.doubleclick.net")),
        )
        assertEquals(emptyList(), DnsMessage.cnameTargets(byteArrayOf(0, 1, 2)))
        val truncated = cnameAnswer("ads.studio.com", "x.ad.doubleclick.net").copyOf(40)
        assertEquals(emptyList(), DnsMessage.cnameTargets(truncated))
    }

    @Test fun blocksAdServersHiddenBehindCname() {
        val forward = assertIs<FilterDecision.Forward>(
            filter.decide(ipPacket(query("ads.studio.com", DnsMessage.TYPE_A))),
        )
        val blocked = assertIs<ReplyDecision.Blocked>(
            filter.upstreamReply(forward.query, cnameAnswer("ads.studio.com", "x.ad.doubleclick.net")),
        )
        assertEquals("x.ad.doubleclick.net", blocked.domain)
        val dns = assertNotNull(UdpPacket.parse(blocked.response)).payload
        assertContentEquals(ByteArray(4), dns.copyOfRange(dns.size - 4, dns.size))

        assertIs<ReplyDecision.Pass>(
            filter.upstreamReply(forward.query, cnameAnswer("ads.studio.com", "cdn.studio-cdn.com")),
        )
    }

    private fun tcpPacket(flags: Int, v6: Boolean = false, port: Int = 853): ByteArray {
        val tcp = ByteArray(20)
        DnsMessage.put16(tcp, 0, 41000); DnsMessage.put16(tcp, 2, port)
        tcp[4] = 0x11; tcp[5] = 0x22; tcp[6] = 0x33; tcp[7] = 0x44 // sequence number
        tcp[12] = 0x50; tcp[13] = flags.toByte()
        return if (v6) IpPackets.build(app6, dns6, 6, tcp, 16) else IpPackets.build(app4, dns4, 6, tcp, 16)
    }

    @Test fun refusesEncryptedDnsConnectionsWithReset() {
        for (v6 in listOf(false, true)) {
            val refused = assertIs<FilterDecision.Refused>(filter.decide(tcpPacket(0x02, v6)))
            val p = refused.response
            val t = if (v6) 40 else 20
            assertEquals(6, (p[if (v6) 6 else 9].toInt() and 0xFF))
            assertEquals(853, DnsMessage.u16(p, t)) // from the port they called
            assertEquals(41000, DnsMessage.u16(p, t + 2))
            assertEquals(0x11223345, java.nio.ByteBuffer.wrap(p, t + 8, 4).int) // ack = seq + 1
            assertEquals(0x14, p[t + 13].toInt()) // RST + ACK
            assertValidTransportChecksum(p, protocol = 6)
        }
        // Only connection attempts get a reset; never answer a reset or a plain ACK.
        assertIs<FilterDecision.Drop>(filter.decide(tcpPacket(0x10)))
        assertIs<FilterDecision.Drop>(filter.decide(tcpPacket(0x04)))
    }

    @Test fun dropsNonDnsTraffic() {
        assertIs<FilterDecision.Drop>(filter.decide(ipPacket(query("example.com", 1), port = 853)))
        assertIs<FilterDecision.Drop>(filter.decide(ByteArray(40)))
        assertIs<FilterDecision.Drop>(filter.decide(ipPacket(query("example.com", 1)).copyOf(30), 30))
    }

    private fun assertValidIpv4Checksum(packet: ByteArray) {
        assertEquals(0, UdpPacket.checksum(0, packet, 0, 20))
        assertValidUdpChecksum(packet)
    }

    private fun assertValidUdpChecksum(packet: ByteArray) = assertValidTransportChecksum(packet, 17)

    /** Recomputes the UDP/TCP checksum including the pseudo header; a correct packet sums to 0. */
    private fun assertValidTransportChecksum(packet: ByteArray, protocol: Int) {
        val v6 = (packet[0].toInt() shr 4) == 6
        val header = if (v6) 40 else 20
        val udpLength = packet.size - header
        val pseudo = java.io.ByteArrayOutputStream()
        if (v6) pseudo.write(packet, 8, 32) else pseudo.write(packet, 12, 8)
        pseudo.write(0); pseudo.write(protocol); pseudo.write(udpLength shr 8); pseudo.write(udpLength and 0xFF)
        if (v6) { pseudo.write(0); pseudo.write(0) } // pad to keep 16-bit alignment
        val all = pseudo.toByteArray() + packet.copyOfRange(header, packet.size)
        assertEquals(0, UdpPacket.checksum(0, all, 0, all.size))
    }
}

package online.bottleneck.adfree.core

import java.net.InetAddress

/**
 * A UDP datagram read from (or written to) the VPN's tun interface, inside an
 * IPv4 or IPv6 header. Only the fields needed to answer DNS queries are kept.
 */
class UdpPacket(
    val source: InetAddress,
    val sourcePort: Int,
    val destination: InetAddress,
    val destinationPort: Int,
    val payload: ByteArray,
) {
    val isIpv6: Boolean get() = source.address.size == 16

    /** A packet going the other way: from this packet's destination back to its source. */
    fun reply(payload: ByteArray) = UdpPacket(destination, destinationPort, source, sourcePort, payload)

    fun toBytes(): ByteArray {
        val udp = ByteArray(8 + payload.size)
        DnsMessage.put16(udp, 0, sourcePort)
        DnsMessage.put16(udp, 2, destinationPort)
        DnsMessage.put16(udp, 4, udp.size)
        System.arraycopy(payload, 0, udp, 8, payload.size)
        return IpPackets.build(source, destination, IpPackets.PROTOCOL_UDP, udp, checksumAt = 6)
    }

    companion object {
        /** Parses an IP packet. Returns null unless it is a complete, unfragmented UDP datagram. */
        fun parse(packet: ByteArray, length: Int = packet.size): UdpPacket? {
            val ip = IpPackets.parse(packet, length) ?: return null
            if (ip.protocol != IpPackets.PROTOCOL_UDP || ip.end - ip.start < 8) return null
            val p = ip.packet
            val udpLength = DnsMessage.u16(p, ip.start + 4)
            if (udpLength < 8 || ip.start + udpLength > ip.end) return null
            return UdpPacket(
                source = ip.source,
                sourcePort = DnsMessage.u16(p, ip.start),
                destination = ip.destination,
                destinationPort = DnsMessage.u16(p, ip.start + 2),
                payload = p.copyOfRange(ip.start + 8, ip.start + udpLength),
            )
        }

        internal fun checksum(initial: Int, b: ByteArray, from: Int, len: Int): Int =
            IpPackets.checksum(initial, b, from, len)
    }
}

/** IPv4/IPv6 header parsing and building shared by the UDP and TCP code. */
internal object IpPackets {
    const val PROTOCOL_TCP = 6
    const val PROTOCOL_UDP = 17

    /** The transport segment of an IP packet: bytes [start, end) of [packet]. */
    class Parsed(
        val packet: ByteArray,
        val protocol: Int,
        val source: InetAddress,
        val destination: InetAddress,
        val start: Int,
        val end: Int,
    )

    fun parse(p: ByteArray, length: Int): Parsed? {
        if (length < 1) return null
        return when ((p[0].toInt() and 0xF0) shr 4) {
            4 -> {
                if (length < 20) return null
                val headerLength = (p[0].toInt() and 0x0F) * 4
                val total = DnsMessage.u16(p, 2)
                if (headerLength < 20 || total > length || total < headerLength) return null
                val fragment = DnsMessage.u16(p, 6)
                if (fragment and 0x2000 != 0 || fragment and 0x1FFF != 0) return null
                Parsed(
                    p, p[9].toInt() and 0xFF,
                    InetAddress.getByAddress(p.copyOfRange(12, 16)),
                    InetAddress.getByAddress(p.copyOfRange(16, 20)),
                    headerLength, total,
                )
            }
            6 -> {
                // Extension headers are not used by DNS clients, so they are not followed.
                if (length < 40) return null
                val end = 40 + DnsMessage.u16(p, 4)
                if (end > length) return null
                Parsed(
                    p, p[6].toInt() and 0xFF,
                    InetAddress.getByAddress(p.copyOfRange(8, 24)),
                    InetAddress.getByAddress(p.copyOfRange(24, 40)),
                    40, end,
                )
            }
            else -> null
        }
    }

    /** Wraps a UDP or TCP segment in an IP header and fills in the segment's checksum. */
    fun build(src: InetAddress, dst: InetAddress, protocol: Int, segment: ByteArray, checksumAt: Int): ByteArray {
        val s = src.address
        val d = dst.address
        val v6 = s.size == 16
        val header = if (v6) 40 else 20
        val out = ByteArray(header + segment.size)
        if (v6) {
            out[0] = 0x60
            DnsMessage.put16(out, 4, segment.size)
            out[6] = protocol.toByte()
            out[7] = 64 // hop limit
            System.arraycopy(s, 0, out, 8, 16)
            System.arraycopy(d, 0, out, 24, 16)
        } else {
            out[0] = 0x45
            DnsMessage.put16(out, 2, out.size)
            DnsMessage.put16(out, 6, 0x4000) // don't fragment
            out[8] = 64 // TTL
            out[9] = protocol.toByte()
            System.arraycopy(s, 0, out, 12, 4)
            System.arraycopy(d, 0, out, 16, 4)
            DnsMessage.put16(out, 10, checksum(0, out, 0, 20))
        }
        System.arraycopy(segment, 0, out, header, segment.size)
        val pseudo = sum(sum(0, s, 0, s.size), d, 0, d.size) + protocol + segment.size
        var c = checksum(pseudo, out, header, segment.size)
        if (c == 0 && protocol == PROTOCOL_UDP) c = 0xFFFF
        DnsMessage.put16(out, header + checksumAt, c)
        return out
    }

    private fun sum(initial: Int, b: ByteArray, from: Int, len: Int): Int {
        var s = initial
        var i = from
        while (i + 1 < from + len) {
            s += DnsMessage.u16(b, i)
            i += 2
        }
        if (i < from + len) s += (b[i].toInt() and 0xFF) shl 8
        return s
    }

    fun checksum(initial: Int, b: ByteArray, from: Int, len: Int): Int {
        var s = sum(initial, b, from, len).toLong() and 0xFFFFFFFFL
        while (s shr 16 != 0L) s = (s and 0xFFFF) + (s shr 16)
        return s.toInt().inv() and 0xFFFF
    }
}

/**
 * Refuses TCP connections that reach the tunnel. Only DNS server addresses are routed
 * into the tunnel, so TCP here means encrypted DNS (DNS over TLS on port 853, DNS over
 * HTTPS on 443) or DNS over TCP. Answering a connection attempt with a reset makes the
 * app give up at once and fall back to plain DNS, which AdFree filters, instead of
 * waiting for a timeout.
 */
object TcpReset {
    private const val SYN = 0x02
    private const val RST = 0x04
    private const val ACK = 0x10

    /** Returns a reset packet for a connection attempt (SYN), or null for any other packet. */
    fun forSyn(packet: ByteArray, length: Int = packet.size): ByteArray? {
        val ip = IpPackets.parse(packet, length) ?: return null
        if (ip.protocol != IpPackets.PROTOCOL_TCP || ip.end - ip.start < 20) return null
        val p = ip.packet
        val t = ip.start
        val flags = p[t + 13].toInt() and 0xFF
        if (flags and SYN == 0 || flags and (ACK or RST) != 0) return null
        val seq = ((p[t + 4].toLong() and 0xFF) shl 24) or ((p[t + 5].toLong() and 0xFF) shl 16) or
            ((p[t + 6].toLong() and 0xFF) shl 8) or (p[t + 7].toLong() and 0xFF)
        val ack = (seq + 1) and 0xFFFFFFFFL

        val tcp = ByteArray(20)
        System.arraycopy(p, t + 2, tcp, 0, 2) // our source port is their destination port
        System.arraycopy(p, t, tcp, 2, 2)
        // Sequence number 0, acknowledging their SYN.
        tcp[8] = (ack shr 24).toByte(); tcp[9] = (ack shr 16).toByte()
        tcp[10] = (ack shr 8).toByte(); tcp[11] = ack.toByte()
        tcp[12] = 0x50 // 20-byte header
        tcp[13] = (RST or ACK).toByte()
        return IpPackets.build(ip.destination, ip.source, IpPackets.PROTOCOL_TCP, tcp, checksumAt = 16)
    }
}

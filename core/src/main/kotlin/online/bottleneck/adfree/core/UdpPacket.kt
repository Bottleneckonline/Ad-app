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

    fun toBytes(): ByteArray = if (isIpv6) buildIpv6() else buildIpv4()

    private fun buildIpv4(): ByteArray {
        val udpLength = 8 + payload.size
        val total = 20 + udpLength
        val out = ByteArray(total)
        out[0] = 0x45
        DnsMessage.put16(out, 2, total)
        DnsMessage.put16(out, 6, 0x4000) // don't fragment
        out[8] = 64 // TTL
        out[9] = PROTOCOL_UDP.toByte()
        System.arraycopy(source.address, 0, out, 12, 4)
        System.arraycopy(destination.address, 0, out, 16, 4)
        DnsMessage.put16(out, 10, checksum(0, out, 0, 20))
        writeUdp(out, 20, udpLength)
        val pseudo = sum(sum(sum(0, source.address, 0, 4), destination.address, 0, 4), byteArrayOf(0, PROTOCOL_UDP.toByte()), 0, 2) + udpLength
        DnsMessage.put16(out, 26, udpChecksum(pseudo, out, 20, udpLength))
        return out
    }

    private fun buildIpv6(): ByteArray {
        val udpLength = 8 + payload.size
        val out = ByteArray(40 + udpLength)
        out[0] = 0x60
        DnsMessage.put16(out, 4, udpLength)
        out[6] = PROTOCOL_UDP.toByte()
        out[7] = 64 // hop limit
        System.arraycopy(source.address, 0, out, 8, 16)
        System.arraycopy(destination.address, 0, out, 24, 16)
        writeUdp(out, 40, udpLength)
        val pseudo = sum(sum(0, source.address, 0, 16), destination.address, 0, 16) + udpLength + PROTOCOL_UDP
        DnsMessage.put16(out, 46, udpChecksum(pseudo, out, 40, udpLength))
        return out
    }

    private fun writeUdp(out: ByteArray, at: Int, udpLength: Int) {
        DnsMessage.put16(out, at, sourcePort)
        DnsMessage.put16(out, at + 2, destinationPort)
        DnsMessage.put16(out, at + 4, udpLength)
        System.arraycopy(payload, 0, out, at + 8, payload.size)
    }

    companion object {
        const val PROTOCOL_UDP = 17

        /** Parses an IP packet. Returns null unless it is a complete, unfragmented UDP datagram. */
        fun parse(packet: ByteArray, length: Int = packet.size): UdpPacket? {
            if (length < 1) return null
            return when ((packet[0].toInt() and 0xF0) shr 4) {
                4 -> parseIpv4(packet, length)
                6 -> parseIpv6(packet, length)
                else -> null
            }
        }

        private fun parseIpv4(p: ByteArray, length: Int): UdpPacket? {
            if (length < 20) return null
            val headerLength = (p[0].toInt() and 0x0F) * 4
            val totalLength = DnsMessage.u16(p, 2)
            if (headerLength < 20 || totalLength > length || totalLength < headerLength + 8) return null
            if (p[9].toInt() and 0xFF != PROTOCOL_UDP) return null
            val fragment = DnsMessage.u16(p, 6)
            if (fragment and 0x2000 != 0 || fragment and 0x1FFF != 0) return null
            return parseUdp(p, headerLength, totalLength, p.copyOfRange(12, 16), p.copyOfRange(16, 20))
        }

        private fun parseIpv6(p: ByteArray, length: Int): UdpPacket? {
            if (length < 48) return null
            if (p[6].toInt() and 0xFF != PROTOCOL_UDP) return null // extension headers are not used for DNS
            val end = 40 + DnsMessage.u16(p, 4)
            if (end > length) return null
            return parseUdp(p, 40, end, p.copyOfRange(8, 24), p.copyOfRange(24, 40))
        }

        private fun parseUdp(p: ByteArray, at: Int, end: Int, src: ByteArray, dst: ByteArray): UdpPacket? {
            val udpLength = DnsMessage.u16(p, at + 4)
            if (udpLength < 8 || at + udpLength > end) return null
            return UdpPacket(
                source = InetAddress.getByAddress(src),
                sourcePort = DnsMessage.u16(p, at),
                destination = InetAddress.getByAddress(dst),
                destinationPort = DnsMessage.u16(p, at + 2),
                payload = p.copyOfRange(at + 8, at + udpLength),
            )
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

        private fun fold(s0: Int): Int {
            var s = s0.toLong() and 0xFFFFFFFFL
            while (s shr 16 != 0L) s = (s and 0xFFFF) + (s shr 16)
            return s.toInt()
        }

        internal fun checksum(initial: Int, b: ByteArray, from: Int, len: Int): Int =
            fold(sum(initial, b, from, len)).inv() and 0xFFFF

        private fun udpChecksum(pseudo: Int, b: ByteArray, from: Int, len: Int): Int {
            val c = checksum(pseudo, b, from, len)
            return if (c == 0) 0xFFFF else c
        }
    }
}

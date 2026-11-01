import Foundation

/// A UDP datagram inside an IPv4 or IPv6 packet, as read from the tunnel.
public struct UdpPacket: Equatable, Sendable {
    public var source: [UInt8]
    public var sourcePort: UInt16
    public var destination: [UInt8]
    public var destinationPort: UInt16
    public var payload: [UInt8]

    public init(source: [UInt8], sourcePort: UInt16, destination: [UInt8], destinationPort: UInt16, payload: [UInt8]) {
        self.source = source
        self.sourcePort = sourcePort
        self.destination = destination
        self.destinationPort = destinationPort
        self.payload = payload
    }

    public var isIPv6: Bool { source.count == 16 }

    public func reply(_ payload: [UInt8]) -> UdpPacket {
        UdpPacket(source: destination, sourcePort: destinationPort, destination: source, destinationPort: sourcePort, payload: payload)
    }

    public static func parse(_ p: [UInt8]) -> UdpPacket? {
        guard let first = p.first else { return nil }
        switch first >> 4 {
        case 4:
            guard p.count >= 20 else { return nil }
            let headerLength = Int(p[0] & 0x0F) * 4
            let total = Int(u16(p, 2))
            guard headerLength >= 20, total <= p.count, total >= headerLength + 8, p[9] == 17 else { return nil }
            let fragment = u16(p, 6)
            guard fragment & 0x2000 == 0, fragment & 0x1FFF == 0 else { return nil }
            return parseUdp(p, at: headerLength, end: total, src: Array(p[12..<16]), dst: Array(p[16..<20]))
        case 6:
            guard p.count >= 48, p[6] == 17 else { return nil }
            let end = 40 + Int(u16(p, 4))
            guard end <= p.count else { return nil }
            return parseUdp(p, at: 40, end: end, src: Array(p[8..<24]), dst: Array(p[24..<40]))
        default:
            return nil
        }
    }

    private static func parseUdp(_ p: [UInt8], at: Int, end: Int, src: [UInt8], dst: [UInt8]) -> UdpPacket? {
        let length = Int(u16(p, at + 4))
        guard length >= 8, at + length <= end else { return nil }
        return UdpPacket(source: src, sourcePort: u16(p, at), destination: dst, destinationPort: u16(p, at + 2),
                         payload: Array(p[(at + 8)..<(at + length)]))
    }

    public func bytes() -> [UInt8] {
        let udpLength = 8 + payload.count
        var udp = [UInt8]()
        udp.reserveCapacity(udpLength)
        put16(&udp, sourcePort); put16(&udp, destinationPort); put16(&udp, UInt16(udpLength)); put16(&udp, 0)
        udp += payload

        var pseudo = source + destination
        if isIPv6 {
            pseudo += [0, 0, UInt8(udpLength >> 8), UInt8(udpLength & 0xFF), 0, 0, 0, 17]
        } else {
            pseudo += [0, 17, UInt8(udpLength >> 8), UInt8(udpLength & 0xFF)]
        }
        var sum = UdpPacket.checksum(pseudo + udp)
        if sum == 0 { sum = 0xFFFF }
        udp[6] = UInt8(sum >> 8); udp[7] = UInt8(sum & 0xFF)

        var header: [UInt8]
        if isIPv6 {
            header = [0x60, 0, 0, 0]
            put16(&header, UInt16(udpLength))
            header += [17, 64] + source + destination
        } else {
            header = [0x45, 0]
            put16(&header, UInt16(20 + udpLength))
            header += [0, 0, 0x40, 0, 64, 17, 0, 0] + source + destination
            let c = UdpPacket.checksum(header)
            header[10] = UInt8(c >> 8); header[11] = UInt8(c & 0xFF)
        }
        return header + udp
    }

    static func checksum(_ b: [UInt8]) -> UInt16 {
        var sum: UInt32 = 0
        var i = 0
        while i + 1 < b.count { sum += UInt32(b[i]) << 8 | UInt32(b[i + 1]); i += 2 }
        if i < b.count { sum += UInt32(b[i]) << 8 }
        while sum >> 16 != 0 { sum = (sum & 0xFFFF) + (sum >> 16) }
        return ~UInt16(sum)
    }
}

public struct DnsQuestion: Equatable, Sendable {
    public let id: UInt16
    public let name: String
    public let type: UInt16
    public let qclass: UInt16
    public let questionEnd: Int
}

public enum DnsMessage {
    public static let typeA: UInt16 = 1
    public static let typeAAAA: UInt16 = 28

    public static func parseQuery(_ m: [UInt8]) -> DnsQuestion? {
        guard m.count >= 17 else { return nil }
        let flags = u16(m, 2)
        guard flags & 0x8000 == 0, (flags >> 11) & 0xF == 0, u16(m, 4) == 1 else { return nil }
        var labels = [String]()
        var pos = 12
        var nameLength = 0
        while true {
            guard pos < m.count else { return nil }
            let len = Int(m[pos]); pos += 1
            if len == 0 { break }
            guard len & 0xC0 == 0, pos + len <= m.count else { return nil }
            nameLength += len + 1
            guard nameLength <= 255 else { return nil }
            labels.append(String(decoding: m[pos..<(pos + len)], as: UTF8.self))
            pos += len
        }
        guard pos + 4 <= m.count else { return nil }
        return DnsQuestion(id: u16(m, 0), name: labels.joined(separator: ".").lowercased(),
                           type: u16(m, pos), qclass: u16(m, pos + 2), questionEnd: pos + 4)
    }

    /// 0.0.0.0 for A, :: for AAAA, an empty answer for anything else.
    public static func blockedResponse(query: [UInt8], question: DnsQuestion) -> [UInt8] {
        let data: [UInt8]? = question.qclass != 1 ? nil
            : question.type == typeA ? [UInt8](repeating: 0, count: 4)
            : question.type == typeAAAA ? [UInt8](repeating: 0, count: 16) : nil
        var out = [UInt8]()
        put16(&out, question.id)
        put16(&out, 0x8000 | (u16(query, 2) & 0x0100) | 0x0080)
        put16(&out, 1); put16(&out, data == nil ? 0 : 1); put16(&out, 0); put16(&out, 0)
        out += query[12..<question.questionEnd]
        if let data {
            put16(&out, 0xC00C); put16(&out, question.type); put16(&out, 1)
            put16(&out, 0); put16(&out, 60); put16(&out, UInt16(data.count))
            out += data
        }
        return out
    }
}

public enum FilterDecision {
    case drop
    case blocked(domain: String, response: [UInt8])
    case forward(domain: String, query: UdpPacket)
}

/// Decides per DNS packet. Mirrors core/.../DnsFilter.kt in the Android app.
public final class DnsFilter: @unchecked Sendable {
    private let lock = NSLock()
    private var list: DomainBlocklist

    public init(blocklist: DomainBlocklist) { list = blocklist }

    public var blocklist: DomainBlocklist {
        get { lock.lock(); defer { lock.unlock() }; return list }
        set { lock.lock(); list = newValue; lock.unlock() }
    }

    public func decide(_ packet: [UInt8]) -> FilterDecision {
        guard let udp = UdpPacket.parse(packet), udp.destinationPort == 53,
              let question = DnsMessage.parseQuery(udp.payload) else { return .drop }
        if blocklist.isBlocked(question.name) {
            let dns = DnsMessage.blockedResponse(query: udp.payload, question: question)
            return .blocked(domain: question.name, response: udp.reply(dns).bytes())
        }
        return .forward(domain: question.name, query: udp)
    }

    public func wrapUpstreamReply(query: UdpPacket, reply: [UInt8]) -> [UInt8] {
        query.reply(reply).bytes()
    }
}

func u16(_ b: [UInt8], _ i: Int) -> UInt16 { UInt16(b[i]) << 8 | UInt16(b[i + 1]) }
func put16(_ b: inout [UInt8], _ v: UInt16) { b.append(UInt8(v >> 8)); b.append(UInt8(v & 0xFF)) }

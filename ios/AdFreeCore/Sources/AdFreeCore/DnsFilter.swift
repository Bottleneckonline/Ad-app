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
        guard let ip = IpPackets.parse(p), ip.proto == 17, ip.end - ip.start >= 8 else { return nil }
        return parseUdp(p, at: ip.start, end: ip.end, src: ip.src, dst: ip.dst)
    }

    private static func parseUdp(_ p: [UInt8], at: Int, end: Int, src: [UInt8], dst: [UInt8]) -> UdpPacket? {
        let length = Int(u16(p, at + 4))
        guard length >= 8, at + length <= end else { return nil }
        return UdpPacket(source: src, sourcePort: u16(p, at), destination: dst, destinationPort: u16(p, at + 2),
                         payload: Array(p[(at + 8)..<(at + length)]))
    }

    public func bytes() -> [UInt8] {
        var udp = [UInt8]()
        udp.reserveCapacity(8 + payload.count)
        put16(&udp, sourcePort); put16(&udp, destinationPort); put16(&udp, UInt16(8 + payload.count)); put16(&udp, 0)
        udp += payload
        return IpPackets.build(source: source, destination: destination, proto: 17, segment: udp, checksumAt: 6)
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

enum IpPackets {
    /// Wraps a UDP or TCP segment in an IPv4/IPv6 header and fills in its checksum.
    static func build(source: [UInt8], destination: [UInt8], proto: UInt8, segment: [UInt8], checksumAt: Int) -> [UInt8] {
        var segment = segment
        let v6 = source.count == 16
        var pseudo = source + destination
        let n = segment.count
        if v6 {
            pseudo += [0, 0, UInt8(n >> 8), UInt8(n & 0xFF), 0, 0, 0, proto]
        } else {
            pseudo += [0, proto, UInt8(n >> 8), UInt8(n & 0xFF)]
        }
        var sum = UdpPacket.checksum(pseudo + segment)
        if sum == 0 && proto == 17 { sum = 0xFFFF }
        segment[checksumAt] = UInt8(sum >> 8); segment[checksumAt + 1] = UInt8(sum & 0xFF)

        var header: [UInt8]
        if v6 {
            header = [0x60, 0, 0, 0]
            put16(&header, UInt16(n))
            header += [proto, 64] + source + destination
        } else {
            header = [0x45, 0]
            put16(&header, UInt16(20 + n))
            header += [0, 0, 0x40, 0, 64, proto, 0, 0] + source + destination
            let c = UdpPacket.checksum(header)
            header[10] = UInt8(c >> 8); header[11] = UInt8(c & 0xFF)
        }
        return header + segment
    }

    /// (protocol, source, destination, transport start, end) of an IP packet.
    static func parse(_ p: [UInt8]) -> (proto: UInt8, src: [UInt8], dst: [UInt8], start: Int, end: Int)? {
        guard let first = p.first else { return nil }
        switch first >> 4 {
        case 4:
            guard p.count >= 20 else { return nil }
            let headerLength = Int(p[0] & 0x0F) * 4
            let total = Int(u16(p, 2))
            guard headerLength >= 20, total <= p.count, total >= headerLength else { return nil }
            let fragment = u16(p, 6)
            guard fragment & 0x2000 == 0, fragment & 0x1FFF == 0 else { return nil }
            return (p[9], Array(p[12..<16]), Array(p[16..<20]), headerLength, total)
        case 6:
            guard p.count >= 40 else { return nil }
            let end = 40 + Int(u16(p, 4))
            guard end <= p.count else { return nil }
            return (p[6], Array(p[8..<24]), Array(p[24..<40]), 40, end)
        default:
            return nil
        }
    }
}

/// Answers a TCP connection attempt to a DNS address (encrypted DNS) with a reset, so the
/// app falls back to plain DNS at once. Mirrors TcpReset in core/.../UdpPacket.kt.
public enum TcpReset {
    public static func forSyn(_ p: [UInt8]) -> [UInt8]? {
        guard let ip = IpPackets.parse(p), ip.proto == 6, ip.end - ip.start >= 20 else { return nil }
        let t = ip.start
        let flags = p[t + 13]
        guard flags & 0x02 != 0, flags & (0x10 | 0x04) == 0 else { return nil }
        let seq = UInt32(p[t + 4]) << 24 | UInt32(p[t + 5]) << 16 | UInt32(p[t + 6]) << 8 | UInt32(p[t + 7])
        let ack = seq &+ 1
        var tcp = [UInt8](repeating: 0, count: 20)
        tcp[0] = p[t + 2]; tcp[1] = p[t + 3]; tcp[2] = p[t]; tcp[3] = p[t + 1]
        tcp[8] = UInt8(ack >> 24); tcp[9] = UInt8((ack >> 16) & 0xFF)
        tcp[10] = UInt8((ack >> 8) & 0xFF); tcp[11] = UInt8(ack & 0xFF)
        tcp[12] = 0x50
        tcp[13] = 0x14 // RST + ACK
        return IpPackets.build(source: ip.dst, destination: ip.src, proto: 6, segment: tcp, checksumAt: 16)
    }
}

/// Public DNS servers that some apps use directly. Mirrors PublicResolvers.kt.
public enum PublicResolvers {
    public static let ipv4 = [
        "8.8.8.8", "8.8.4.4", "1.1.1.1", "1.0.0.1", "9.9.9.9", "149.112.112.112",
        "208.67.222.222", "208.67.220.220", "94.140.14.14", "94.140.15.15",
        "76.76.2.0", "76.76.10.0", "185.228.168.9", "185.228.169.9",
        "77.88.8.8", "77.88.8.1", "223.5.5.5", "223.6.6.6", "119.29.29.29", "114.114.114.114",
    ]
    public static let ipv6 = [
        "2001:4860:4860::8888", "2001:4860:4860::8844", "2606:4700:4700::1111", "2606:4700:4700::1001",
        "2620:fe::fe", "2620:fe::9", "2620:119:35::35", "2620:119:53::53",
        "2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff",
    ]
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
    public static let typeCNAME: UInt16 = 5

    /// CNAME targets in a response's answer section; empty if it cannot be parsed.
    public static func cnameTargets(_ m: [UInt8]) -> [String] {
        guard m.count >= 12 else { return [] }
        var targets = [String]()
        var pos = 12
        for _ in 0..<Int(u16(m, 4)) {
            guard let name = readName(m, pos) else { return targets }
            pos = name.1 + 4
        }
        for _ in 0..<Int(u16(m, 6)) {
            guard let owner = readName(m, pos), owner.1 + 10 <= m.count else { return targets }
            let next = owner.1
            let type = u16(m, next)
            let rdLength = Int(u16(m, next + 8))
            let rdata = next + 10
            guard rdata + rdLength <= m.count else { return targets }
            if type == typeCNAME, let target = readName(m, rdata) { targets.append(target.0) }
            pos = rdata + rdLength
        }
        return targets
    }

    private static func readName(_ m: [UInt8], _ start: Int) -> (String, Int)? {
        var labels = [String]()
        var pos = start
        var end: Int?
        var jumps = 0
        while true {
            guard pos < m.count else { return nil }
            let len = Int(m[pos])
            if len == 0 {
                return (labels.joined(separator: ".").lowercased(), end ?? pos + 1)
            } else if len & 0xC0 == 0xC0 {
                jumps += 1
                guard pos + 1 < m.count, jumps <= 32 else { return nil }
                if end == nil { end = pos + 2 }
                pos = (len & 0x3F) << 8 | Int(m[pos + 1])
            } else if len & 0xC0 == 0 {
                guard pos + 1 + len <= m.count else { return nil }
                labels.append(String(decoding: m[(pos + 1)..<(pos + 1 + len)], as: UTF8.self))
                pos += len + 1
            } else {
                return nil
            }
        }
    }

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
    /// A TCP connection to a DNS address; write the reset back.
    case refused(response: [UInt8])
    case forward(domain: String, query: UdpPacket)
}

public enum ReplyDecision {
    case pass(response: [UInt8])
    /// The answer was an alias (CNAME) for a blocked domain.
    case blocked(domain: String, response: [UInt8])

    public var response: [UInt8] {
        switch self {
        case let .pass(r), let .blocked(_, r): return r
        }
    }
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
        if let reset = TcpReset.forSyn(packet) { return .refused(response: reset) }
        guard let udp = UdpPacket.parse(packet), udp.destinationPort == 53,
              let question = DnsMessage.parseQuery(udp.payload) else { return .drop }
        if blocklist.isBlocked(question.name) {
            let dns = DnsMessage.blockedResponse(query: udp.payload, question: question)
            return .blocked(domain: question.name, response: udp.reply(dns).bytes())
        }
        return .forward(domain: question.name, query: udp)
    }

    /// Checks a resolver's answer; blocks it if a CNAME points at a blocked domain.
    public func upstreamReply(query: UdpPacket, reply: [UInt8]) -> ReplyDecision {
        let list = blocklist
        if let hidden = DnsMessage.cnameTargets(reply).first(where: { list.isBlocked($0) }),
           let question = DnsMessage.parseQuery(query.payload) {
            let dns = DnsMessage.blockedResponse(query: query.payload, question: question)
            return .blocked(domain: hidden, response: query.reply(dns).bytes())
        }
        return .pass(response: query.reply(reply).bytes())
    }
}

func u16(_ b: [UInt8], _ i: Int) -> UInt16 { UInt16(b[i]) << 8 | UInt16(b[i + 1]) }
func put16(_ b: inout [UInt8], _ v: UInt16) { b.append(UInt8(v >> 8)); b.append(UInt8(v & 0xFF)) }

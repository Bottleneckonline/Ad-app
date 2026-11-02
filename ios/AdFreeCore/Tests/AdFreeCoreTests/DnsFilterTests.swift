import XCTest
@testable import AdFreeCore

final class DnsFilterTests: XCTestCase {
    private let app: [UInt8] = [10, 111, 222, 1]
    private let dns: [UInt8] = [10, 111, 222, 2]
    private let filter = DnsFilter(blocklist: DomainBlocklist(blocked: ["doubleclick.net"], allowed: ["good.doubleclick.net"]))

    private func query(_ name: String, type: UInt16) -> [UInt8] {
        var q: [UInt8] = [0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0]
        for label in name.split(separator: ".") { q.append(UInt8(label.utf8.count)); q += Array(label.utf8) }
        q += [0, UInt8(type >> 8), UInt8(type & 0xFF), 0, 1]
        return q
    }

    private func packet(_ payload: [UInt8], port: UInt16 = 53) -> [UInt8] {
        UdpPacket(source: app, sourcePort: 40000, destination: dns, destinationPort: port, payload: payload).bytes()
    }

    func testBlocklistMatchesSubdomainsAndAllowlistWins() {
        let list = DomainBlocklist(blocked: ["doubleclick.net"], allowed: ["good.doubleclick.net"])
        XCTAssertTrue(list.isBlocked("ad.g.DoubleClick.net."))
        XCTAssertFalse(list.isBlocked("notdoubleclick.net"))
        XCTAssertFalse(list.isBlocked("cdn.good.doubleclick.net"))
    }

    func testParsesFormats() {
        let text = "# c\n127.0.0.1 localhost\n0.0.0.0 ads.one.com\nads.two.com\n||ads.three.com^\n! x\nnot a domain"
        XCTAssertEqual(DomainBlocklist.parseDomains(text), ["ads.one.com", "ads.two.com", "ads.three.com"])
    }

    func testBlocksAQuery() throws {
        guard case let .blocked(domain, response) = filter.decide(packet(query("ad.doubleclick.net", type: 1))) else {
            return XCTFail("expected blocked")
        }
        XCTAssertEqual(domain, "ad.doubleclick.net")
        XCTAssertEqual(UdpPacket.checksum(Array(response[0..<20])), 0)
        let reply = try XCTUnwrap(UdpPacket.parse(response))
        XCTAssertEqual(reply.destination, app)
        XCTAssertEqual(reply.destinationPort, 40000)
        XCTAssertEqual(Array(reply.payload[2...3]), [0x81, 0x80])
        XCTAssertEqual(Array(reply.payload.suffix(4)), [0, 0, 0, 0])
    }

    func testForwardsAllowedAndDropsOther() {
        guard case let .forward(domain, query) = filter.decide(packet(query("example.com", type: 1))) else {
            return XCTFail("expected forward")
        }
        XCTAssertEqual(domain, "example.com")
        XCTAssertEqual(UdpPacket.parse(filter.upstreamReply(query: query, reply: [1, 2, 3]).response)?.payload, [1, 2, 3])
        if case .drop = filter.decide(packet(self.query("example.com", type: 1), port: 853)) {} else { XCTFail() }
    }

    func testBlocksCnameToAdServer() throws {
        let q = query("ads.studio.com", type: 1)
        guard case let .forward(_, udp) = filter.decide(packet(q)) else { return XCTFail("expected forward") }
        var answer = Array(q[0..<12])
        answer[2] = 0x81; answer[3] = 0x80; answer[7] = 1
        answer += q[12...]
        var target: [UInt8] = []
        for label in "x.ad.doubleclick.net".split(separator: ".") { target.append(UInt8(label.utf8.count)); target += Array(label.utf8) }
        target.append(0)
        answer += [0xC0, 0x0C, 0, 5, 0, 1, 0, 0, 1, 0, 0, UInt8(target.count)] + target
        XCTAssertEqual(DnsMessage.cnameTargets(answer), ["x.ad.doubleclick.net"])
        guard case let .blocked(domain, _) = filter.upstreamReply(query: udp, reply: answer) else {
            return XCTFail("expected blocked")
        }
        XCTAssertEqual(domain, "x.ad.doubleclick.net")
    }

    func testRefusesEncryptedDnsWithReset() throws {
        var tcp = [UInt8](repeating: 0, count: 20)
        tcp[0] = 0xA0; tcp[1] = 0x28; tcp[2] = 0x03; tcp[3] = 0x55 // 41000 -> 853
        tcp[4] = 0x11; tcp[5] = 0x22; tcp[6] = 0x33; tcp[7] = 0x44
        tcp[12] = 0x50; tcp[13] = 0x02
        let syn = IpPackets.build(source: app, destination: dns, proto: 6, segment: tcp, checksumAt: 16)
        guard case let .refused(reset) = filter.decide(syn) else { return XCTFail("expected refused") }
        XCTAssertEqual(UdpPacket.checksum(Array(reset[0..<20])), 0)
        XCTAssertEqual(Array(reset[20..<24]), [0x03, 0x55, 0xA0, 0x28])
        XCTAssertEqual(Array(reset[28..<32]), [0x11, 0x22, 0x33, 0x45])
        XCTAssertEqual(reset[33], 0x14)
    }
}

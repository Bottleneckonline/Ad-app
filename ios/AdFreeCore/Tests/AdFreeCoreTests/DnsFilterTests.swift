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
        XCTAssertEqual(UdpPacket.parse(filter.wrapUpstreamReply(query: query, reply: [1, 2, 3]))?.payload, [1, 2, 3])
        if case .drop = filter.decide(packet(self.query("example.com", type: 1), port: 853)) {} else { XCTFail() }
    }
}

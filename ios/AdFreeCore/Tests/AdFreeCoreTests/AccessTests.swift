import XCTest
@testable import AdFreeCore

final class AccessTests: XCTestCase {
    private let day: TimeInterval = 24 * 60 * 60
    private let start = Date(timeIntervalSince1970: 1_800_000_000)

    func testTrialCountsDownThenExpires() {
        XCTAssertEqual(Access.of(unlocked: false, firstLaunch: start, now: start), .trial(daysLeft: 7))
        XCTAssertEqual(Access.of(unlocked: false, firstLaunch: start, now: start + 1), .trial(daysLeft: 7))
        XCTAssertEqual(Access.of(unlocked: false, firstLaunch: start, now: start + day), .trial(daysLeft: 6))
        XCTAssertEqual(Access.of(unlocked: false, firstLaunch: start, now: start + 7 * day - 1), .trial(daysLeft: 1))
        XCTAssertEqual(Access.of(unlocked: false, firstLaunch: start, now: start + 7 * day), .expired)
        XCTAssertFalse(Access.of(unlocked: false, firstLaunch: start, now: start + 30 * day).canBlock)
    }

    func testUnlockedAlwaysBlocks() {
        XCTAssertEqual(Access.of(unlocked: true, firstLaunch: start, now: start + 365 * day), .unlocked)
        XCTAssertTrue(Access.unlocked.canBlock)
    }

    func testClockMovedBackKeepsFullTrial() {
        XCTAssertEqual(Access.of(unlocked: false, firstLaunch: start, now: start - 3 * day), .trial(daysLeft: 7))
    }
}

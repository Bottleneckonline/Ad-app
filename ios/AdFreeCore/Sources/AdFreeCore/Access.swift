import Foundation

/// Whether the user may turn blocking on: a free trial after first launch, then a one-time
/// unlock bought with an in-app purchase. Same rules as `Access.kt` on Android.
public enum Access: Equatable {
    /// Paid.
    case unlocked
    /// Inside the free trial; `daysLeft` is at least 1 while the trial runs.
    case trial(daysLeft: Int)
    /// Trial over and not paid.
    case expired

    public static let trialDays = 7

    public var canBlock: Bool { self != .expired }

    /// A clock that moved backwards counts as no time passed.
    public static func of(unlocked: Bool, firstLaunch: Date, now: Date, trialDays: Int = Access.trialDays) -> Access {
        if unlocked { return .unlocked }
        let day: TimeInterval = 24 * 60 * 60
        let elapsed = max(0, now.timeIntervalSince(firstLaunch))
        let trial = TimeInterval(trialDays) * day
        if elapsed >= trial { return .expired }
        return .trial(daysLeft: Int(((trial - elapsed) / day).rounded(.up)))
    }
}

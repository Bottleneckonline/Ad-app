import Foundation

/// Values shared by the app and the tunnel extension.
enum SharedConfig {
    static let appGroup = "group.online.bottleneck.adfree"
    static let allowlistKey = "allowlist"
    static let tunnelBundleId = "online.bottleneck.adfree.tunnel"

    /// Written by the app, read by the tunnel: whether the one-time unlock was bought.
    static let unlockedKey = "unlocked"
    /// Written by the app, read by the tunnel: first launch time (seconds since 1970).
    static let firstLaunchKey = "firstLaunch"

    /// The App Store in-app purchase (non-consumable) that unlocks AdFree for good.
    static let lifetimeProductId = "online.bottleneck.adfree.lifetime"

    static let privacyPolicyURL = URL(string: "https://bottleneckonline.github.io/Ad-app/privacy-policy.html")!
    static let supportURL = URL(string: "https://bottleneckonline.github.io/Ad-app/support.html")!
}

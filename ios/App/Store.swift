import AdFreeCore
import Foundation
import Security
import StoreKit

/// Free trial plus the one-time "AdFree Lifetime" in-app purchase (StoreKit 2).
///
/// The result is copied into the shared App Group so the tunnel extension can read it:
/// once the trial is over and nothing was bought, the tunnel keeps running but stops
/// filtering, so the phone's internet never breaks.
@MainActor
final class Store: ObservableObject {
    @Published private(set) var product: Product?
    @Published private(set) var unlocked = false
    @Published private(set) var busy = false
    @Published var message: String?

    private let shared = UserDefaults(suiteName: SharedConfig.appGroup)
    private var updates: Task<Void, Never>?

    init() {
        recordFirstLaunch()
        unlocked = shared?.bool(forKey: SharedConfig.unlockedKey) ?? false
        updates = Task { [weak self] in
            for await result in Transaction.updates {
                if case let .verified(transaction) = result {
                    await transaction.finish()
                    await self?.refreshEntitlement()
                }
            }
        }
        Task {
            await refreshEntitlement()
            await loadProduct()
        }
    }

    deinit { updates?.cancel() }

    var access: Access {
        let first = Date(timeIntervalSince1970: shared?.double(forKey: SharedConfig.firstLaunchKey) ?? 0)
        return Access.of(unlocked: unlocked, firstLaunch: first, now: Date())
    }

    func loadProduct() async {
        do {
            product = try await Product.products(for: [SharedConfig.lifetimeProductId]).first
        } catch {
            message = "Could not load the price from the App Store. Check your connection."
        }
    }

    func buy() async {
        guard let product else { await loadProduct(); return }
        busy = true
        defer { busy = false }
        do {
            switch try await product.purchase() {
            case let .success(.verified(transaction)):
                await transaction.finish()
                await refreshEntitlement()
            case .success(.unverified):
                message = "The App Store could not verify this purchase."
            case .pending:
                message = "Your purchase is waiting for approval."
            case .userCancelled:
                break
            @unknown default:
                break
            }
        } catch {
            message = error.localizedDescription
        }
    }

    /// "Restore Purchases", required by App Review for non-consumable purchases.
    func restore() async {
        busy = true
        defer { busy = false }
        do {
            try await AppStore.sync()
        } catch {
            message = error.localizedDescription
        }
        await refreshEntitlement()
        if !unlocked { message = "No earlier purchase was found for this Apple ID." }
    }

    func refreshEntitlement() async {
        var owned = false
        for await result in Transaction.currentEntitlements {
            if case let .verified(transaction) = result,
               transaction.productID == SharedConfig.lifetimeProductId,
               transaction.revocationDate == nil {
                owned = true
            }
        }
        unlocked = owned
        shared?.set(owned, forKey: SharedConfig.unlockedKey)
    }

    /// The trial start is kept in the Keychain as well, which survives deleting and
    /// reinstalling the app, so reinstalling does not restart the trial.
    private func recordFirstLaunch() {
        let first = Keychain.firstLaunch() ?? {
            let now = Date().timeIntervalSince1970
            Keychain.setFirstLaunch(now)
            return now
        }()
        shared?.set(first, forKey: SharedConfig.firstLaunchKey)
    }
}

private enum Keychain {
    private static let query: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: "online.bottleneck.adfree",
        kSecAttrAccount as String: "firstLaunch",
    ]

    static func firstLaunch() -> TimeInterval? {
        var q = query
        q[kSecReturnData as String] = true
        var item: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data, let text = String(data: data, encoding: .utf8) else { return nil }
        return TimeInterval(text)
    }

    static func setFirstLaunch(_ value: TimeInterval) {
        var q = query
        q[kSecValueData as String] = Data(String(value).utf8)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(q as CFDictionary, nil)
    }
}

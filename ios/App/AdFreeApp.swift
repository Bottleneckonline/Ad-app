import NetworkExtension
import SwiftUI

@main
struct AdFreeApp: App {
    var body: some Scene {
        WindowGroup { ContentView() }
    }
}

@MainActor
final class TunnelController: ObservableObject {
    @Published var status: NEVPNStatus = .invalid
    @Published var error: String?
    private var manager: NETunnelProviderManager?

    init() {
        Task { await load() }
        NotificationCenter.default.addObserver(forName: .NEVPNStatusDidChange, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.status = self?.manager?.connection.status ?? .invalid }
        }
    }

    var isOn: Bool { status == .connected || status == .connecting }

    func load() async {
        let managers = (try? await NETunnelProviderManager.loadAllFromPreferences()) ?? []
        manager = managers.first
        status = manager?.connection.status ?? .disconnected
    }

    func setEnabled(_ on: Bool) async {
        do {
            if on {
                let m = manager ?? NETunnelProviderManager()
                let proto = NETunnelProviderProtocol()
                proto.providerBundleIdentifier = SharedConfig.tunnelBundleId
                proto.serverAddress = "On-device"
                m.protocolConfiguration = proto
                m.localizedDescription = "AdFree"
                m.isEnabled = true
                m.isOnDemandEnabled = true
                m.onDemandRules = [NEOnDemandRuleConnect()]
                try await m.saveToPreferences()
                try await m.loadFromPreferences()
                try m.connection.startVPNTunnel()
                manager = m
            } else if let m = manager {
                m.isOnDemandEnabled = false
                try await m.saveToPreferences()
                m.connection.stopVPNTunnel()
            }
            error = nil
        } catch {
            self.error = error.localizedDescription
        }
    }

    func restart() async {
        guard isOn else { return }
        await setEnabled(false)
        await setEnabled(true)
    }
}

struct ContentView: View {
    @StateObject private var tunnel = TunnelController()
    @StateObject private var store = Store()
    @AppStorage(SharedConfig.allowlistKey, store: UserDefaults(suiteName: SharedConfig.appGroup))
    private var allowlist = ""

    var body: some View {
        NavigationView {
            Form {
                Section {
                    Toggle("Block ads", isOn: Binding(
                        get: { tunnel.isOn },
                        set: { on in Task { await tunnel.setEnabled(on) } }
                    ))
                    .disabled(!store.access.canBlock && !tunnel.isOn)
                    Text(tunnel.isOn && store.access.canBlock ? "Ad blocking is ON" : "Ad blocking is OFF").font(.headline)
                    if let error = tunnel.error { Text(error).foregroundColor(.red) }
                }
                licenseSection
                Section(header: Text("Never block these domains"), footer: Text("One domain per line.")) {
                    TextEditor(text: $allowlist)
                        .frame(minHeight: 100)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    Button("Apply") { Task { await tunnel.restart() } }
                }
                Section {
                    Link("Privacy Policy", destination: SharedConfig.privacyPolicyURL)
                    Link("Help and Support", destination: SharedConfig.supportURL)
                }
            }
            .navigationTitle("AdFree")
            .alert("AdFree", isPresented: Binding(get: { store.message != nil }, set: { if !$0 { store.message = nil } })) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(store.message ?? "")
            }
            .onReceive(tunnel.$status) { _ in
                // Trial ended while the tunnel was on: turn it off so iOS stops reconnecting it.
                if !store.access.canBlock && tunnel.isOn { Task { await tunnel.setEnabled(false) } }
            }
        }
    }

    @ViewBuilder private var licenseSection: some View {
        switch store.access {
        case .unlocked:
            Section { Text("AdFree is unlocked. Thank you for your support!") }
        case let .trial(daysLeft):
            Section(header: Text("Free trial"), footer: Text("One-time purchase. No subscription.")) {
                Text(daysLeft == 1 ? "1 day left" : "\(daysLeft) days left")
                buyButtons
            }
        case .expired:
            Section(header: Text("Free trial ended"), footer: Text("One-time purchase. No subscription.")) {
                Text("Unlock AdFree to keep blocking ads.")
                buyButtons
            }
        }
    }

    @ViewBuilder private var buyButtons: some View {
        Button(store.product.map { "Unlock AdFree for \($0.displayPrice)" } ?? "Unlock AdFree") {
            Task { await store.buy() }
        }
        .disabled(store.busy)
        Button("Restore Purchases") { Task { await store.restore() } }
            .disabled(store.busy)
    }
}

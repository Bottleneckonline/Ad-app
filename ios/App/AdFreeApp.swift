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
                    Text(tunnel.isOn ? "Ad blocking is ON" : "Ad blocking is OFF").font(.headline)
                    if let error = tunnel.error { Text(error).foregroundColor(.red) }
                }
                Section(header: Text("Never block these domains"), footer: Text("One domain per line.")) {
                    TextEditor(text: $allowlist)
                        .frame(minHeight: 100)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    Button("Apply") { Task { await tunnel.restart() } }
                }
            }
            .navigationTitle("AdFree")
        }
    }
}

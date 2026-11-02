import AdFreeCore
import Network
import NetworkExtension
import os

/// iOS version of the Android VPN service: a DNS-only tunnel. iOS sends DNS lookups
/// to a virtual DNS address routed into this extension; ad domains get 0.0.0.0 and
/// everything else is resolved upstream. All other traffic bypasses the tunnel.
final class PacketTunnelProvider: NEPacketTunnelProvider {
    private static let tunnelAddress = "10.111.222.1"
    private static let dnsAddress = "10.111.222.2"
    private static let upstream = NWEndpoint.Host("1.1.1.1")

    private let log = Logger(subsystem: "online.bottleneck.adfree.tunnel", category: "dns")
    private let queue = DispatchQueue(label: "adfree.dns", attributes: .concurrent)
    private var filter: DnsFilter?

    override func startTunnel(options: [String: NSObject]?, completionHandler: @escaping (Error?) -> Void) {
        let list = Self.loadBlocklist()
        filter = DnsFilter(blocklist: list)
        log.info("Loaded \(list.count) blocked domains")

        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: "127.0.0.1")
        let ipv4 = NEIPv4Settings(addresses: [Self.tunnelAddress], subnetMasks: ["255.255.255.255"])
        // Our DNS address plus well-known public resolvers that some apps use directly.
        ipv4.includedRoutes = ([Self.dnsAddress] + PublicResolvers.ipv4).map {
            NEIPv4Route(destinationAddress: $0, subnetMask: "255.255.255.255")
        }
        settings.ipv4Settings = ipv4
        let ipv6 = NEIPv6Settings(addresses: ["fd00:111:222::1"], networkPrefixLengths: [128])
        ipv6.includedRoutes = PublicResolvers.ipv6.map {
            NEIPv6Route(destinationAddress: $0, networkPrefixLength: 128)
        }
        settings.ipv6Settings = ipv6
        let dns = NEDNSSettings(servers: [Self.dnsAddress])
        dns.matchDomains = [""] // use our DNS server for every domain
        settings.dnsSettings = dns
        settings.mtu = 1500

        setTunnelNetworkSettings(settings) { [weak self] error in
            completionHandler(error)
            if error == nil { self?.readPackets() }
        }
    }

    override func stopTunnel(with reason: NEProviderStopReason, completionHandler: @escaping () -> Void) {
        completionHandler()
    }

    private func readPackets() {
        packetFlow.readPackets { [weak self] packets, protocols in
            guard let self, let filter = self.filter else { return }
            for (packet, proto) in zip(packets, protocols) {
                switch filter.decide([UInt8](packet)) {
                case .drop:
                    break
                case let .blocked(_, response), let .refused(response):
                    self.packetFlow.writePackets([Data(response)], withProtocols: [proto])
                case let .forward(_, query):
                    self.forward(query, filter: filter, proto: proto)
                }
            }
            self.readPackets()
        }
    }

    private func forward(_ query: UdpPacket, filter: DnsFilter, proto: NSNumber) {
        // Connections made by the extension itself do not go through the tunnel.
        let connection = NWConnection(host: Self.upstream, port: 53, using: .udp)
        connection.stateUpdateHandler = { state in
            if case .failed = state { connection.cancel() }
        }
        connection.start(queue: queue)
        connection.send(content: Data(query.payload), completion: .contentProcessed { _ in })
        connection.receiveMessage { [weak self] data, _, _, _ in
            defer { connection.cancel() }
            guard let self, let data else { return }
            let answer = filter.upstreamReply(query: query, reply: [UInt8](data))
            self.packetFlow.writePackets([Data(answer.response)], withProtocols: [proto])
        }
        queue.asyncAfter(deadline: .now() + 5) { connection.cancel() }
    }

    private static func loadBlocklist() -> DomainBlocklist {
        let defaults = UserDefaults(suiteName: SharedConfig.appGroup)
        let allowed = (defaults?.string(forKey: SharedConfig.allowlistKey) ?? "")
            .split(whereSeparator: \.isNewline).map(String.init)
        // The general list plus AdFree's own mobile ad network list (both in blocklists/).
        let domains = ["blocklist", "mobile-ads"].flatMap { name -> [String] in
            guard let url = Bundle.main.url(forResource: name, withExtension: "txt"),
                  let text = try? String(contentsOf: url, encoding: .utf8) else { return [] }
            return DomainBlocklist.parseDomains(text)
        }
        return DomainBlocklist(blocked: domains, allowed: allowed)
    }
}

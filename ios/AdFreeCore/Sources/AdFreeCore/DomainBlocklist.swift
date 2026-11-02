import Foundation

/// Blocked domains plus an allowlist. Blocking `doubleclick.net` also blocks
/// `ad.doubleclick.net`; the allowlist wins over the blocklist.
/// Mirrors core/.../DomainBlocklist.kt in the Android app.
public struct DomainBlocklist: Sendable {
    private let blocked: Set<String>
    private let allowed: Set<String>

    public var count: Int { blocked.count }

    public init<B: Sequence, A: Sequence>(blocked: B, allowed: A = [String]())
    where B.Element == String, A.Element == String {
        self.blocked = Set(blocked.compactMap(DomainBlocklist.normalize))
        self.allowed = Set(allowed.compactMap(DomainBlocklist.normalize))
    }

    public func isBlocked(_ domain: String) -> Bool {
        guard let name = DomainBlocklist.normalize(domain) else { return false }
        if matches(name, allowed) { return false }
        return matches(name, blocked)
    }

    private func matches(_ name: String, _ set: Set<String>) -> Bool {
        if set.isEmpty { return false }
        var candidate = Substring(name)
        while true {
            if set.contains(String(candidate)) { return true }
            guard let dot = candidate.firstIndex(of: "."), candidate.index(after: dot) < candidate.endIndex else {
                return false
            }
            candidate = candidate[candidate.index(after: dot)...]
        }
    }

    private static let ignored: Set<String> = [
        "localhost", "localhost.localdomain", "local", "broadcasthost", "0.0.0.0",
    ]

    public static func normalize(_ domain: String) -> String? {
        var name = domain.trimmingCharacters(in: .whitespaces).lowercased()
        while name.hasSuffix(".") { name.removeLast() }
        guard !name.isEmpty, name.utf8.count <= 253 else { return nil }
        for label in name.split(separator: ".", omittingEmptySubsequences: false) {
            guard (1...63).contains(label.utf8.count),
                  label.utf8.allSatisfy({ isLabelByte($0) }),
                  label.first != "-", label.last != "-" else { return nil }
        }
        return name
    }

    private static func isLabelByte(_ b: UInt8) -> Bool {
        (b >= 0x61 && b <= 0x7A) || (b >= 0x30 && b <= 0x39) || b == 0x2D || b == 0x5F
    }

    /// Parses hosts-file lines, plain domains, and `||domain^` adblock rules.
    public static func parseLine(_ raw: String) -> String? {
        var line = String(raw.split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false).first ?? "")
            .trimmingCharacters(in: .whitespaces)
        if line.isEmpty || line.hasPrefix("!") || line.hasPrefix("[") { return nil }
        if line.hasPrefix("||") {
            guard line.hasSuffix("^") else { return nil }
            line = String(line.dropFirst(2).dropLast())
        } else {
            let parts = line.split(whereSeparator: { $0 == " " || $0 == "\t" })
            if parts.count == 1 {
                line = String(parts[0])
            } else if parts.count >= 2, parts[0].allSatisfy({ $0.isNumber || $0 == "." || $0 == ":" }) {
                line = String(parts[1])
            } else {
                return nil
            }
        }
        guard let name = normalize(line), !ignored.contains(name), name.contains(".") else { return nil }
        return name
    }

    public static func parseDomains(_ text: String) -> [String] {
        text.split(whereSeparator: \.isNewline).compactMap { parseLine(String($0)) }
    }
}

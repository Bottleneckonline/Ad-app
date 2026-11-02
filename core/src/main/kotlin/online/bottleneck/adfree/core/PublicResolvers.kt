package online.bottleneck.adfree.core

/**
 * Well-known public DNS servers. Some apps and SDKs ignore the phone's DNS settings and
 * send lookups straight to one of these. Routing these addresses into the tunnel as well
 * means those lookups are filtered too, and encrypted DNS to them is refused (see
 * [TcpReset]) so the app falls back to plain, filterable DNS.
 */
object PublicResolvers {
    val IPV4 = listOf(
        "8.8.8.8", "8.8.4.4", // Google
        "1.1.1.1", "1.0.0.1", // Cloudflare
        "9.9.9.9", "149.112.112.112", // Quad9
        "208.67.222.222", "208.67.220.220", // OpenDNS
        "94.140.14.14", "94.140.15.15", // AdGuard
        "76.76.2.0", "76.76.10.0", // Control D
        "185.228.168.9", "185.228.169.9", // CleanBrowsing
        "77.88.8.8", "77.88.8.1", // Yandex
        "223.5.5.5", "223.6.6.6", // AliDNS
        "119.29.29.29", // DNSPod
        "114.114.114.114", // 114DNS
    )
    val IPV6 = listOf(
        "2001:4860:4860::8888", "2001:4860:4860::8844",
        "2606:4700:4700::1111", "2606:4700:4700::1001",
        "2620:fe::fe", "2620:fe::9",
        "2620:119:35::35", "2620:119:53::53",
        "2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff",
    )
}

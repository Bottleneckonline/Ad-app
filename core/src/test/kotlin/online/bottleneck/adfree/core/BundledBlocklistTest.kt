package online.bottleneck.adfree.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Checks the lists that ship in the apps (the files in blocklists/) against real ad SDK traffic. */
class BundledBlocklistTest {
    private val list = DomainBlocklist(
        BUNDLED_FILES.flatMap { File("../blocklists/$it").reader().use(DomainBlocklist::parseDomains) },
    )

    @Test fun isLarge() = assertTrue(list.size > 50_000, "only ${list.size} domains")

    @Test fun matchesRealAdSdkHosts() {
        val lines = javaClass.getResourceAsStream("/sdk-hosts.txt")!!.bufferedReader().readLines()
            .map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
        val wrong = lines.mapNotNull { line ->
            val (verdict, host) = line.split(Regex("\\s+"))
            val expected = verdict == "block"
            if (list.isBlocked(host) != expected) "$host should be ${if (expected) "blocked" else "allowed"}" else null
        }
        assertEquals(emptyList(), wrong)
        assertTrue(lines.count { it.startsWith("block") } > 80)
    }

    @Test fun doesNotBreakEverydayServices() {
        val wrong = listOf(
            "google.com", "www.google.com", "youtube.com", "play.googleapis.com",
            "firebaseinstallations.googleapis.com", "fcm.googleapis.com",
            "apple.com", "icloud.com", "whatsapp.net", "github.com", "facebook.com",
            "unity3d.com", "unity.com", "moloco.com", "amazon.com",
            "cloudflare.com", "cloudfront.net",
        ).filter { list.isBlocked(it) }
        assertEquals(emptyList(), wrong)
    }

    @Test fun blocksEncryptedDnsBootstrapNames() {
        listOf("dns.google", "cloudflare-dns.com", "mozilla.cloudflare-dns.com", "use-application-dns.net")
            .forEach { assertTrue(list.isBlocked(it), it) }
    }

    companion object {
        /** Keep in sync with Blocklists.FILES in the Android app and the iOS tunnel. */
        val BUNDLED_FILES = listOf("blocklist.txt", "mobile-ads.txt")
    }
}

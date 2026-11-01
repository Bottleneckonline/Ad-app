package online.bottleneck.adfree.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Sanity checks on the list that ships in the app (blocklists/blocklist.txt). */
class BundledBlocklistTest {
    private val list = DomainBlocklist(
        File("../blocklists/blocklist.txt").reader().use(DomainBlocklist::parseDomains),
    )

    @Test fun isLarge() = assertTrue(list.size > 50_000, "only ${list.size} domains")

    @Test fun blocksCommonInAppAndGameAdNetworks() {
        listOf(
            "googleads.g.doubleclick.net", // AdMob / Google Ads
            "pagead2.googlesyndication.com",
            "unityads.unity3d.com", // Unity Ads
            "applovin.com", // AppLovin
            "outcome-ssp.supersonicads.com", // ironSource
            "vungle.com", // Vungle / Liftoff
        ).forEach { assertTrue(list.isBlocked(it), it) }
    }

    @Test fun doesNotBreakEverydayServices() {
        listOf(
            "google.com", "www.google.com", "youtube.com", "play.googleapis.com",
            "apple.com", "icloud.com", "whatsapp.net", "github.com",
        ).forEach { assertFalse(list.isBlocked(it), it) }
    }
}

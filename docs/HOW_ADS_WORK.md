# How ads get into App Store and Google Play apps, and how AdFree blocks them

Research notes for AdFree, November 2026. The findings are tested in
`core/src/test/.../BundledBlocklistTest.kt` against hostnames taken from real SDK files.

## 1. How apps and games show ads

Almost no app builds its own ad system. The developer adds one or more **ad SDKs**
(code libraries from an ad company) to the app. The same SDKs are used on Android and
iOS, and they talk to the same servers on both.

A typical game works like this:

1. **Start-up.** The SDK contacts its config server (for example `ms.applovin.com`,
   `init.supersonicads.com`, `config.ads.vungle.com`) to learn which ad networks and
   placements to use.
2. **Mediation and bidding.** A *mediation* SDK (AppLovin MAX, Unity LevelPlay, AdMob
   mediation) asks several networks to bid for each ad slot, then picks the winner.
   Most mobile games use AppLovin MAX or LevelPlay with 5 to 15 networks behind them.
3. **Ad download.** The winning network sends the ad: an image, an HTML page, or a
   video, usually from its own CDN (`ssacdn.com`, `vid.applovin.com`,
   `cdn-f.adsmoloco.com`).
4. **Display.** The SDK shows a banner, a full-screen *interstitial*, or a *rewarded*
   video ("watch an ad for 50 coins"), often inside a web view.
5. **Tracking.** Impressions, clicks and installs are reported to event servers
   (`events.ads.vungle.com`, `ssp-events.chartboost.com`) and attribution services.

Every one of those steps needs a DNS lookup for an ad company's server first. That is
the point where AdFree steps in: no address, no ad.

### The networks that matter most

| Network | Typical apps | Main domains |
| --- | --- | --- |
| Google AdMob / Ad Manager | Most free apps on both stores | `doubleclick.net`, `googlesyndication.com`, `googleadservices.com`, `imasdk.googleapis.com` (video) |
| AppLovin MAX | The largest game mediation platform | `applovin.com`, `applvn.com`, `applov.in`, `appl.vn` |
| Unity Ads and LevelPlay (ironSource) | Unity games, hyper-casual games | `unityads.unity3d.com`, `iads.unity3d.com`, `mediation.unity3d.com`, `supersonicads.com`, `ssacdn.com`, `ironsrc.mobi` |
| Liftoff Monetize (Vungle) | Games | `vungle.com` |
| Meta Audience Network | Apps and games | `an.facebook.com`, plus `graph.facebook.com` (shared, see below) |
| Mintegral, Pangle, Moloco, InMobi, Chartboost, DT Exchange, Amazon, Start.io, BidMachine | Bidders behind mediation | see `blocklists/mobile-ads.txt` |

## 2. What we tested

`scripts/sdk-audit/audit.py` downloads the real Android SDK files that developers add
to their apps from Maven Central, unzips them (nothing is run), and collects every
hostname inside. Eleven SDKs were checked: AppLovin 13.7.0, Unity Ads 4.21.0, Unity
LevelPlay 9.7.0, Liftoff/Vungle 7.7.9, Meta Audience Network 6.22.0, InMobi 11.5.1,
Moloco 4.14.0, Amazon APS 12.0.3, Start.io 5.4.0, Chartboost 9.2.1, AdColony 4.8.2.
AdMob, Mintegral, Pangle and DT Exchange are not on Maven Central, so their documented
endpoints were used instead.

**Result: the general list AdFree started with missed 26 of 84 ad servers (31%).**
Examples: `gw-is.iads.unity3d.com` (LevelPlay's interstitial ad gateway), the ironSource
ad creative CDN `*.ssacdn.com`, Moloco's `sdkapi.dsp-api.moloco.com`, AppLovin's
`p.applov.in`, and Google's video ad SDK `imasdk.googleapis.com`.

The reason is that general lists name individual hosts, while ad companies keep adding
new ones. **AdFree now ships its own list (`blocklists/mobile-ads.txt`) that blocks the
whole domain of companies whose domains only serve ads**, so new hosts are covered
automatically. With it, all 84 are blocked, and a test keeps it that way.

## 3. How apps can get past a DNS blocker, and what AdFree does about it

| Way around the filter | What AdFree now does |
| --- | --- |
| New ad hostnames the list doesn't know yet | Blocks whole ad-company domains (above) |
| **Hard-coded DNS server.** The app or SDK sends lookups straight to `8.8.8.8`, `1.1.1.1` and so on, ignoring the phone's DNS | Routes 30 well-known public DNS addresses into AdFree's tunnel too, so those lookups get filtered |
| **Encrypted DNS** (DNS over HTTPS/TLS) that the filter can't read | Answers connections to DNS servers with an immediate refusal, and blocks the names of encrypted DNS services (`dns.google`, `cloudflare-dns.com`, ...). The app falls back to normal DNS, which AdFree filters |
| **Disguised ad servers (CNAME cloaking).** `ads.some-game.com` is really an alias for an ad network's server | Checks the answer from the DNS server and blocks it when the alias points at an ad domain |
| Android Private DNS set to a fixed provider | Cannot be overridden by an app; AdFree warns the user to set it to Automatic |

## 4. What no DNS or VPN blocker can remove

Be clear with users about these. They apply to every competing app too (AdGuard,
Blokada, NextDNS, RethinkDNS).

- **Ads from the app's own servers.** YouTube, Instagram, Facebook, TikTok, Snapchat,
  X, Reddit, Pinterest, Twitch and Spotify serve ads from the same servers as their
  content. Blocking those servers would break the app itself.
- **Meta Audience Network**, partly: some of its traffic uses `graph.facebook.com`, which
  Facebook login and sharing need. AdFree blocks the ad-only hosts.
- **The stores' own ads.** Sponsored results inside the App Store (Apple Search Ads)
  and Google Play are part of the store app.
- **Ads built into the app**, such as a game's own cross-promotion or ad videos bundled
  in the download.
- **Ads already downloaded.** SDKs cache a few ads ahead. Those can still show after
  AdFree is switched on, until the cached ones are used up.
- **Rewarded ads.** If an ad cannot load, the game cannot give the reward. This is the
  expected result of blocking. Users who want the rewards can add a game's ad domain to
  the allowlist (a per-app on/off switch is planned).
- **Apps that refuse to work without ads.** A few apps check whether ads loaded. The
  allowlist fixes those.

## 5. Store rules (unchanged)

- **Google Play** does not allow apps that block ads in other apps. AdFree for Android
  is distributed as a direct download (GitHub Releases, own website, F-Droid), as
  AdGuard and Blokada do.
- **Apple App Store** allows DNS-based blockers (AdGuard Pro, DNSCloak, NextDNS are
  listed) if the app explains clearly what it does and collects no browsing data. A
  paid Apple Developer account with the Network Extension capability is required.

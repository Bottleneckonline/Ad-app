# What this app can and cannot block

AdFree works by filtering **DNS**: before an app or game shows an ad, it has to look up
the address of the ad server (for example `googleads.g.doubleclick.net` or
`unityads.unity3d.com`). AdFree answers those lookups with `0.0.0.0`, so the ad never
loads. Everything happens on the phone; no traffic is sent to any AdFree server.

This is the same technique used by well-known blockers such as Blokada, AdGuard (DNS
mode), RethinkDNS and NextDNS. It is the strongest approach that works without rooting
or jailbreaking, but it is not perfect.

## Works well

- Banner, interstitial and rewarded ads from the big in-app and game ad networks
  (AdMob, Unity Ads, AppLovin, ironSource, Vungle/Liftoff, Meta Audience Network…).
- Trackers and analytics that use their own domains.
- All apps at once, including games, with no per-app setup.

## Cannot be blocked this way

- **Ads served from the same domain as the content.** YouTube, Facebook, Instagram,
  TikTok, Twitch and similar apps serve ads from the same servers as the videos and
  posts. Blocking them would break the app. No DNS or VPN blocker can remove these.
- **Ads built into the app itself** (for example a game's own "watch this" screen
  that is bundled in the app).
- **Apps that bypass the system DNS** with a resolver AdFree does not know about. AdFree
  catches the common ones (8.8.8.8, 1.1.1.1, Quad9, OpenDNS and others) and refuses
  encrypted DNS to them, so apps fall back to filtered DNS. A browser set to *always*
  use one specific secure DNS provider will show errors instead; set it to automatic.

See [HOW_ADS_WORK.md](HOW_ADS_WORK.md) for how in-app ads work and the SDK test results.
- Some games or apps refuse to work, or withhold rewards, when their ads fail to load.
  The allowlist exists for that: add the domain and the app works again.

## Android

- Uses Android's `VpnService`. Only one VPN can be active at a time, so AdFree cannot
  run together with another VPN app.
- If **Settings → Network → Private DNS** is set to a specific provider (for example
  `dns.google`), Android bypasses AdFree. "Automatic" or "Off" is fine. The app warns
  about this.
- **Google Play does not allow apps that block ads in other apps** (Play's "Device and
  Network Abuse" policy). Blokada, AdGuard and others ship their full ad blockers
  outside Play: as a direct APK download, on F-Droid, or through their own site.
  AdFree should plan the same. A Play version could only ship as a "privacy / tracker
  blocker" with restrictions, and would risk removal.
- Tip: in **Settings → Network → VPN → AdFree → Always-on VPN**, Android keeps AdFree
  running after reboots.

## iOS / iPadOS

- Uses a Network Extension **packet tunnel** that only carries DNS. Building it needs a
  paid Apple Developer account with the Network Extension capability.
- iOS gives extensions a small memory budget (around 50 MB). The full list fits today,
  but very large lists need a more compact format (planned).
- Apple allows content blockers on the App Store, but reviews VPN-based apps closely:
  the app must explain clearly what it does and must not collect browsing data.
  Several DNS blockers are on the App Store (AdGuard Pro, DNSCloak, NextDNS), so it is
  possible.
- Safari can additionally use a Safari Content Blocker for cosmetic web blocking; that
  only helps Safari, not apps or games.
- Same limits as Android: ads served from the content's own domain cannot be blocked.

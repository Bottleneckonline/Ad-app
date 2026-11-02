# App Store listing (copy and paste into App Store Connect)

Character limits are Apple's. Counts were checked when this was written.

## App information

| Field | Value |
| --- | --- |
| Name (30) | `AdFree: Ad Blocker for Apps` |
| Subtitle (30) | `Block ads in games and apps` |
| Bundle ID | `online.bottleneck.adfree` |
| Primary category | Utilities |
| Secondary category | Productivity |
| Age rating | 4+ (answer "None" to every content question) |
| Price | Free (the unlock is an in-app purchase) |
| Privacy Policy URL | `https://<your site>/privacy-policy.html` |
| Support URL | `https://<your site>/support.html` |
| Marketing URL | `https://<your site>/` |
| Copyright | `2026 <your name or company>` |

## In-app purchase

| Field | Value |
| --- | --- |
| Type | Non-Consumable |
| Reference name | AdFree Lifetime |
| Product ID | `online.bottleneck.adfree.lifetime` (must match exactly; it is in the code) |
| Price | Suggested: tier for $4.99 / €4.99 / £4.99 |
| Family Sharing | On |
| Display name | AdFree Lifetime |
| Description (45) | `Unlock AdFree for good. One-time purchase.` |
| Review screenshot | Screenshot of the AdFree screen showing the "Unlock AdFree" button |

## Promotional text (170)

```
Fewer ads in your games and apps. 7-day free trial, then one small payment. No subscription, no account, nothing leaves your phone.
```

## Description (4000)

```
AdFree blocks ads from the big ad networks in your games and apps, for the whole phone at once.

Before an app shows an ad, it has to look up the address of the ad server. AdFree answers those lookups with "nothing here" right on your iPhone, so the ad never loads. Everything else goes over your normal connection, untouched.

WHAT YOU GET
• Fewer banner, video and pop-up ads in games and apps
• Fewer trackers following you between apps
• Works for every app at once, no setup per app
• Allowlist: let a domain through when an app needs it
• Uses less mobile data and battery than loading ads

PRIVATE BY DESIGN
• Filtering happens on your iPhone. AdFree has no server that sees your traffic.
• No account, no analytics, no tracking, no ads.
• We never sell, use or share your data.

FAIR PRICE
• 7-day free trial with every feature
• Then one small one-time payment. No subscription.

GOOD TO KNOW
• AdFree uses the iOS VPN feature to filter on your device, so it can't run at the same time as another VPN.
• Ads that come from the same servers as the content (for example YouTube, Instagram, TikTok or Facebook ads) and ads built into a game itself can't be blocked by any app like this.
• Some games hold back rewards when their ads can't load. Add the game's ad domain to the allowlist if you want those rewards.
```

## Keywords (100, comma separated, no spaces)

```
adblock,ad blocker,block ads,games,no ads,tracker,privacy,dns,firewall,popup,banner,adfree
```

## What's New (version 1.0.0)

```
First release.
```

## App Privacy ("nutrition label")

Choose **Data Not Collected**. This is accurate: the app sends nothing to you or to any
analytics service. (Apple handles the purchase; that is not counted as your collection.)

## Export compliance

The app's Info.plist already says it uses no non-exempt encryption
(`ITSAppUsesNonExemptEncryption = NO`), so App Store Connect will not ask each upload.

## Notes for App Review (paste into "App Review Information → Notes")

```
AdFree is an on-device DNS filter. It uses NEPacketTunnelProvider only to route DNS
lookups (to a virtual address, 10.111.222.2) into the extension on the device. Lookups
for known ad and tracker domains are answered with 0.0.0.0; all other lookups are
forwarded unchanged to a public resolver (1.1.1.1 / 9.9.9.9). No other traffic enters
the tunnel. There is no AdFree server, no account and no data collection.

How to test:
1. Open the app and switch on "Block ads". Allow the VPN configuration when iOS asks.
2. Open any app or game that shows ads from AdMob, Unity Ads, AppLovin, etc. The ads
   no longer load.
3. The 7-day trial is active on first launch. The "Unlock AdFree" button buys the
   non-consumable "AdFree Lifetime"; "Restore Purchases" restores it.

The developer is enrolled as an organization, as required by guideline 5.4.
```

## Screenshots you need to take

App Store Connect needs at least one set for 6.9" iPhone (1320 × 2868 or 1290 × 2796) and,
because the app runs on iPad, 13" iPad (2064 × 2752). Suggested shots:

1. Main screen with "Ad blocking is ON" and a few hundred blocked.
2. A game before/after (with and without AdFree) side by side.
3. "Recently blocked" list.
4. Allowlist.
5. The free-trial / one-time price section.

Take them in the iOS Simulator (Xcode → File → Save Screen) or on a phone.

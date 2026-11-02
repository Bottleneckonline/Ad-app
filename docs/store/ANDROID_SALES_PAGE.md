# Android sales (Lemon Squeezy) – product setup and copy

AdFree for Android is sold outside Google Play, because Play does not allow apps that
block ads in other apps (see docs/LAUNCH_CHECKLIST.md). Lemon Squeezy is the shop: it
is the "merchant of record", so it charges the card, collects and pays VAT and sales tax
in every country, sends the receipt, and emails a license key. You get a payout.

## Product settings

| Setting | Value |
| --- | --- |
| Product name | AdFree for Android |
| Pricing | Single payment. Suggested $4.99 |
| Files | Upload `AdFree-v1.0.0.apk` from the "Release Android" workflow |
| License keys | **On**. Activation limit: 3. Length: unlimited (never expires) |
| Confirmation button | "Download AdFree" linking to the file |
| Store / product ids | After saving, copy the numeric **Store ID** (Settings → Stores) and **Product ID** (product page URL / API) into the GitHub variables `LS_STORE_ID` and `LS_PRODUCT_ID` |
| Checkout link | Copy the product's checkout ("Share") link into the GitHub variable `BUY_URL` |

The app accepts a key only if Lemon Squeezy says it belongs to *that* store and product.

## Product description

```
AdFree blocks ads from the big ad networks in your Android games and apps, for the
whole phone at once.

• Fewer banner, video and pop-up ads in games and apps
• Fewer trackers following you between apps
• Works for every app at once, no setup per app
• Allowlist for apps that need their ads
• Private: filtering happens on your phone, no account, no tracking

How it works: AdFree runs a tiny on-device VPN that only carries DNS lookups. Lookups
for ad servers are answered with "nothing here", so the ad never loads.

Try it free for 7 days: install the app, and enter your license key when you decide to
keep it. One payment, no subscription. Your key works on up to 3 of your devices.

Requirements: Android 7.0 or newer. Cannot run at the same time as another VPN app.
Ads served from the same servers as the content (YouTube, Instagram, TikTok, Facebook)
cannot be blocked by any app like this.
```

## Install instructions (put in the receipt email / confirmation page)

```
1. Open the download link on your Android phone and download AdFree.apk.
2. Tap the downloaded file. If Android asks, allow your browser to "install unknown
   apps", then tap Install.
3. Open AdFree, paste your license key and tap Activate.
4. Switch on "Block ads" and allow the VPN request.

Updates: we email you when a new version is out; install it over the old one.
```

Note on Android developer verification: from 30 September 2026 phones in Brazil,
Indonesia, Singapore and Thailand only install apps outside Play from verified
developers, and Google plans to extend this worldwide in 2027. Register as a developer
and register the package name `online.bottleneck.adfree` and your signing key in the
Android Developer Console (see the launch checklist) so installs stay one-tap.

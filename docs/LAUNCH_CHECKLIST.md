# AdFree launch checklist

How AdFree is sold:

| | iPhone / iPad | Android |
| --- | --- | --- |
| Where | App Store | Your own sales page (Lemon Squeezy), direct APK download |
| Price model | Free download, 7-day trial, then one-time in-app purchase "AdFree Lifetime" | Free 7-day trial in the app, then a one-time license key |
| Who collects tax | Apple | Lemon Squeezy (merchant of record) |
| Fee taken | 15% (Small Business Program) | 5% + 50¢ per sale |

Google Play is **not** on the list: Play's policy bans apps that block or interfere with
other apps' ads, and its VPN policy says "any manipulation of ads that can impact
monetization will result in rejection". Repeated rejections can get a developer account
terminated. Blokada, AdGuard and others sell their Android ad blockers outside Play for
this reason.

Items marked **🔒 You** need your identity, money or a signature, so only you can do them.
Everything else is already in the repository.

---

## 1. Business basics

- [ ] **🔒 You – Decide who the seller is.** Apple's guideline 5.4 allows apps that use the
  VPN feature only from developers enrolled **as an organization**, not as an
  individual. That means a legal entity (for example a sole-trader business name that
  is registered, an LLC/Ltd/GmbH) and a free **D-U-N-S number** for it
  ([request it via Apple](https://developer.apple.com/enroll/duns-lookup/), takes up to
  ~2 weeks). If you are already enrolled as an individual, Apple can convert the account
  to an organization.
- [ ] **🔒 You – Choose the support email** that appears on the website and in the stores.
- [ ] **🔒 You – Pick the price.** Suggested $4.99 one-time on both platforms.

## 2. Website (privacy policy, support, sales page)

The pages are in `site/` (plain HTML, works on any static host).

- [ ] Replace the placeholders in `site/*.html`: `PUBLISHER_NAME`, `SUPPORT_EMAIL`,
      `BUY_URL_ANDROID` (Lemon Squeezy checkout link) and `APP_STORE_URL` (after approval).
      `grep -rn "PUBLISHER_NAME\|SUPPORT_EMAIL\|BUY_URL_ANDROID\|APP_STORE_URL" site/`
      must print nothing.
- [ ] **🔒 You – Host it.** The repository is private, and GitHub Pages for private
      repositories needs a paid GitHub plan. Free options: Cloudflare Pages or Netlify
      (connect the repo, publish folder `site`), or make the repo public and turn on
      Pages from `site/`. A custom domain (e.g. `adfree.app`) looks more trustworthy.
- [ ] Put the final URLs in `ios/App/SharedConfig.swift` (`privacyPolicyURL`,
      `supportURL`). They currently assume
      `https://bottleneckonline.github.io/Ad-app/`.

## 3. iPhone / iPad (App Store)

- [ ] **🔒 You – Apple Developer Program**, as an organization: $99/year,
      [developer.apple.com/programs/enroll](https://developer.apple.com/programs/enroll/).
- [ ] **🔒 You – Agreements, Tax, and Banking** in App Store Connect: accept the Paid
      Apps agreement, add bank account and tax forms. In-app purchases do not work until
      this is "Active".
- [ ] **🔒 You – App Store Small Business Program**: apply so Apple takes 15% instead
      of 30%.
- [ ] **🔒 You – Identifiers** (Certificates, Identifiers & Profiles):
  - App ID `online.bottleneck.adfree` with **Network Extensions** and **App Groups**.
  - App ID `online.bottleneck.adfree.tunnel` with the same two capabilities.
  - App Group `group.online.bottleneck.adfree`, assigned to both App IDs.
- [ ] **🔒 You – App Store Connect API key**: Users and Access → Integrations → App Store
      Connect API → new key with **Admin** role (needed so the build robot can create
      signing certificates). Download the `.p8` once.
- [ ] Add GitHub secrets/variables (repo → Settings → Secrets and variables → Actions):
  - secret `ASC_KEY_ID`, secret `ASC_ISSUER_ID`
  - secret `ASC_KEY_P8_BASE64` = output of `base64 -i AuthKey_XXXX.p8`
  - variable `APPLE_TEAM_ID` (10 characters, from the Membership page)
- [ ] **🔒 You – Create the app** in App Store Connect (My Apps → +), bundle ID
      `online.bottleneck.adfree`, name from `docs/store/APP_STORE_LISTING.md`.
- [ ] **🔒 You – Create the in-app purchase** exactly as in
      `docs/store/APP_STORE_LISTING.md` (product ID `online.bottleneck.adfree.lifetime`).
- [ ] Run **Actions → Release iOS → Run workflow**. The build appears in TestFlight
      after Apple's processing (10–30 minutes).
- [ ] **🔒 You – Test on a real iPhone via TestFlight**: turn blocking on, play a game
      with ads, buy the unlock with a Sandbox account, delete and reinstall, Restore
      Purchases.
- [ ] Fill in the listing, screenshots, App Privacy ("Data Not Collected") and review
      notes from `docs/store/APP_STORE_LISTING.md`. Attach the in-app purchase to the
      version, then **Submit for Review**.

## 4. Android (direct download)

- [ ] **🔒 You – Create the release signing key** (once, and keep it forever: every
      update must be signed with it, and losing it means customers must reinstall):

      keytool -genkeypair -v -keystore adfree-release.jks -alias adfree \
        -keyalg RSA -keysize 4096 -validity 10000

      Store the `.jks` file and both passwords in a password manager **and** a second
      safe place. Never commit it (`.gitignore` already blocks `*.jks`).
- [ ] Add GitHub secrets: `ANDROID_KEYSTORE_BASE64` (`base64 -w0 adfree-release.jks`, on
      a Mac `base64 -i adfree-release.jks`), `ANDROID_KEYSTORE_PASSWORD`,
      `ANDROID_KEY_ALIAS` (`adfree`), `ANDROID_KEY_PASSWORD`.
- [ ] **🔒 You – Lemon Squeezy account** ([lemonsqueezy.com](https://www.lemonsqueezy.com)):
      complete identity verification and payout (bank/PayPal) details. Store activation
      needs approval from Lemon Squeezy.
- [ ] Create the product as in `docs/store/ANDROID_SALES_PAGE.md` (license keys on).
      Then add GitHub variables `LS_STORE_ID`, `LS_PRODUCT_ID`, `BUY_URL`.
- [ ] Run **Actions → Release Android → Run workflow** (or push tag `v1.0.0`). Download
      `AdFree-….apk` from the run and upload it as the Lemon Squeezy product file.
- [ ] **🔒 You – Test on a real Android phone**: install the APK, turn blocking on, buy
      with a Lemon Squeezy test-mode key, activate, reboot with Always-on VPN.
- [ ] **🔒 You – Android developer verification**: register as a developer in the
      [Android Developer Console](https://developer.android.com/developer-verification)
      and register package `online.bottleneck.adfree` with your signing key's
      certificate. Required for one-tap installs in Brazil, Indonesia, Singapore and
      Thailand since 30 Sept 2026, worldwide from 2027.

## 5. Launch day

- [ ] Website shows both download buttons; privacy policy and support pages are live.
- [ ] App Store version approved and released.
- [ ] Lemon Squeezy store switched from test mode to live.
- [ ] A friend buys on each platform with a real card; refund it afterwards.

## 6. After launch

- **Updates (Android):** bump `versionName` in `app/build.gradle.kts`, push a tag
  `v1.0.1`, upload the new APK to Lemon Squeezy and email customers.
  (`versionCode` is the workflow run number, so it always goes up.)
- **Updates (iOS):** bump `MARKETING_VERSION` in `ios/project.yml`, run Release iOS,
  submit in App Store Connect.
- **Blocklists:** run `scripts/update-blocklist.sh` before each release.
- **Refunds:** refund from the Lemon Squeezy dashboard; the app locks again at its next
  weekly license check. Apple handles iOS refunds and StoreKit revokes the unlock.

## Optional later: a Google Play version

A Play version could only be a **privacy / tracker and malware blocker that leaves ads
alone**, declared under the VpnService "device security (firewall)" category, with
Google Play Billing. That needs a separate tracker-only blocklist and a separate
`play` build flavor. Possible, but it is a different (weaker) product with real
rejection risk, so it is not part of this release.

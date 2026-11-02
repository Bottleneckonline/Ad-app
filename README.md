# AdFree

An Android and iOS app that blocks ads in games and apps, for the whole phone.

AdFree runs a tiny on-device VPN that only carries DNS lookups. When an app asks for
an ad server's address, AdFree answers "nothing here" (`0.0.0.0`) and the ad never
loads. All other traffic goes over the normal network untouched, and nothing is sent
to any AdFree server. See [docs/PLATFORM_LIMITS.md](docs/PLATFORM_LIMITS.md) for what
this can and cannot block, and for the app store rules.

## Status: milestone 1

| Part | State |
| --- | --- |
| Shared filtering core (Kotlin, `core/`) | Done, unit tested |
| Android app (`app/`) | Working: on/off switch, live stats, recently blocked list, allowlist, Always-on VPN support |
| Blocklists (`blocklists/`) | 77,758 ad and tracker domains from [StevenBlack/hosts](https://github.com/StevenBlack/hosts) (MIT), plus AdFree's own list of mobile ad network domains (`mobile-ads.txt`) |
| Real SDK test | 84 ad servers found inside 11 real ad SDKs are all blocked; see [docs/HOW_ADS_WORK.md](docs/HOW_ADS_WORK.md) |
| iOS app (`ios/`) | Skeleton: Swift core (tested), packet tunnel extension and SwiftUI screen. Needs an Apple Developer account to run on a device |

## Build the Android app

Needs JDK 17+ and the Android SDK (Android Studio installs both).

```sh
./gradlew :core:test           # run the filter tests (no Android SDK needed)
./gradlew :app:assembleDebug   # build app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug    # install on a connected phone
```

Every pull request is built by GitHub Actions, and the debug APK is attached to the run
as `adfree-debug-apk`, so you can install it without building anything yourself.

## Build the iOS app

Needs a Mac with Xcode 15+ and [XcodeGen](https://github.com/yonaskolb/XcodeGen).

```sh
swift test --package-path ios/AdFreeCore   # run the Swift filter tests
cd ios && xcodegen && open AdFree.xcodeproj
```

To run on an iPhone, set your team in `ios/project.yml` (`DEVELOPMENT_TEAM`) and enable
the **Network Extensions (Packet Tunnel)** and **App Groups** capabilities for both
bundle ids in your Apple Developer account.

## How it works

```
 app/game ── DNS lookup ──▶ virtual DNS 10.111.222.2 (only route into the VPN)
                                   │
                         AdFree DnsFilter (core/)
                    ┌──────────────┴──────────────┐
             ad domain                      everything else
       answer 0.0.0.0 / ::            forward to 1.1.1.1 / 9.9.9.9
                                                     │
                                  answer is an alias for an ad domain? block it
```

Lookups that apps send straight to public DNS servers (8.8.8.8 and others) are routed
into the tunnel too, and encrypted DNS connections are refused so apps fall back.

- `core/` is plain Kotlin with no Android code: blocklist matching
  (`DomainBlocklist`), DNS parsing and answers (`DnsMessage`), IP/UDP packets
  (`UdpPacket`) and the per-packet decision (`DnsFilter`).
- `app/` wires it to Android's `VpnService` (`AdBlockVpnService`) and a simple screen
  (`MainActivity`).
- `ios/AdFreeCore` is the same logic in Swift, used by the iOS packet tunnel.

## Update the blocklists

```sh
./scripts/update-blocklist.sh            # refresh blocklist.txt from StevenBlack/hosts
python3 -I scripts/sdk-audit/audit.py    # list the servers inside current ad SDKs
```

`blocklists/mobile-ads.txt` is edited by hand. When the SDK audit finds a new ad
server, add it to `core/src/test/resources/sdk-hosts.txt` and, if the test fails, to
`mobile-ads.txt`.

## Next milestones

1. Download blocklist updates in the app instead of only bundling them, and let users
   pick lists (ads only, ads + trackers, ads + trackers + malware).
2. Per-app view: which app tried to load which ad domain.
3. Per-app switch, for games where the user wants rewarded ads.
4. iOS: compact on-disk list format for the extension's memory limit, stats screen,
   TestFlight build.
5. Release builds: signed APK on GitHub Releases (Google Play does not allow ad
   blockers, see the limits doc) and an App Store submission.

#!/usr/bin/env python3
"""Downloads real ad SDKs and lists the server hostnames baked into them.

    python3 -I scripts/sdk-audit/audit.py            # print hosts per SDK
    python3 -I scripts/sdk-audit/audit.py --json out.json

Each SDK is fetched from Maven Central (the same files app developers add to
their Android games), unzipped in a temp folder (never executed), and every
string that looks like a hostname is collected. iOS builds of these SDKs talk
to the same servers. The reviewed results live in sdk_hosts.txt, which the
core tests check against the shipped blocklist.
"""
import io
import json
import re
import sys
import tempfile
import time
import urllib.request
import zipfile

MAVEN = "https://repo1.maven.org/maven2"

# (name, group, artifact). The latest release is used.
SDKS = [
    ("AppLovin MAX", "com.applovin", "applovin-sdk"),
    ("Unity Ads", "com.unity3d.ads", "unity-ads"),
    ("Unity LevelPlay (ironSource)", "com.unity3d.ads-mediation", "mediation-sdk"),
    ("Liftoff Monetize (Vungle)", "com.vungle", "vungle-ads"),
    ("Meta Audience Network", "com.facebook.android", "audience-network-sdk"),
    ("InMobi", "com.inmobi.monetization", "inmobi-ads-kotlin"),
    ("Moloco", "com.moloco.sdk", "moloco-sdk"),
    ("Amazon Publisher Services", "com.amazon.android", "aps-sdk"),
    ("Start.io", "com.startapp", "inapp-sdk"),
    ("Chartboost", "com.chartboost", "chartboost-sdk"),
    ("AdColony (Digital Turbine)", "com.adcolony", "sdk"),
]

TLDS = r"(?:com|net|io|mobi|co|org|ru|cn|tv|ai|ad|ads|app|me|info|xyz|link|cloud|tech|biz|us|jp|sg|in|de|uk)"
URL_HOST = re.compile(rb"(?:https?|wss?)://([a-z0-9][a-z0-9-]*(?:\.[a-z0-9-]+)+)", re.I)
BARE_HOST = re.compile(rb"(?<![a-z0-9_.-])((?:[a-z0-9-]+\.)+" + TLDS.encode() + rb")(?![a-z0-9_-])")


def fetch(url, tries=6):
    for i in range(tries):
        try:
            with urllib.request.urlopen(url, timeout=60) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code != 429 or i == tries - 1:
                raise
            time.sleep(5 * (i + 1))


def latest(group, artifact):
    meta = fetch(f"{MAVEN}/{group.replace('.', '/')}/{artifact}/maven-metadata.xml").decode()
    m = re.search(r"<release>([^<]+)</release>", meta) or re.findall(r"<version>([^<]+)</version>", meta)[-1:]
    return m.group(1) if hasattr(m, "group") else m[0]


def blobs(data):
    """Yields every file inside an aar/jar, descending into nested jars."""
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        for name in z.namelist():
            content = z.read(name)
            if name.endswith(".jar"):
                yield from blobs(content)
            else:
                yield content


def hosts_in(data):
    found = set()
    for blob in blobs(data):
        for rx in (URL_HOST, BARE_HOST):
            for m in rx.finditer(blob):
                host = m.group(1).decode("ascii", "ignore").lower().strip(".")
                if "." in host and not host[0].isdigit():
                    found.add(host)
    return found


def main():
    results = {}
    for name, group, artifact in SDKS:
        version = latest(group, artifact)
        base = f"{MAVEN}/{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}"
        try:
            data = fetch(base + ".aar")
        except urllib.error.HTTPError:
            data = fetch(base + ".jar")
        results[f"{name} {version}"] = sorted(hosts_in(data))
        print(f"## {name} {version}: {len(results[f'{name} {version}'])} hosts", file=sys.stderr)
    if len(sys.argv) > 2 and sys.argv[1] == "--json":
        with open(sys.argv[2], "w") as f:
            json.dump(results, f, indent=1)
    else:
        for sdk, hosts in results.items():
            print(f"\n# {sdk}")
            print("\n".join(hosts))


if __name__ == "__main__":
    main()

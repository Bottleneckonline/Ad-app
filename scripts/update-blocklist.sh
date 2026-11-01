#!/usr/bin/env bash
# Regenerates blocklists/blocklist.txt, which is bundled into the Android app (and later the iOS app)
# from the StevenBlack unified hosts list (MIT licensed, ads + malware domains).
#
#   ./scripts/update-blocklist.sh
set -euo pipefail
cd "$(dirname "$0")/.."

SOURCE_URL="https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
OUT="blocklists/blocklist.txt"
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT

curl -fsSL "$SOURCE_URL" -o "$tmp"
mkdir -p blocklists
{
  echo "# AdFree bundled blocklist, one domain per line."
  echo "# Source: $SOURCE_URL (MIT License, https://github.com/StevenBlack/hosts)"
  echo "# Generated: $(date -u +%Y-%m-%d)"
  awk '$1 == "0.0.0.0" && $2 != "0.0.0.0" { print tolower($2) }' "$tmp" | sort -u
} > "$OUT"

echo "Wrote $(grep -vc '^#' "$OUT") domains to $OUT"

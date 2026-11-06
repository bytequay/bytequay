#!/bin/bash
set -euo pipefail

: "${APPLE_TEAM_ID:?Release Team ID is required}"
: "${APPLE_SIGNING_IDENTITY:?Release signing identity is required}"
: "${BYTEQUAY_SIGNING_KEYCHAIN:?Signing keychain is required}"
: "${BYTEQUAY_NOTARY_PROFILE:?Notarization profile is required}"
: "${RUNNER_TEMP:?Temporary directory is required}"

verify_app() {
  codesign --verify --deep --strict --verbose=2 "$1"
  details=$(codesign --display --verbose=4 "$1" 2>&1)
  printf '%s\n' "$details" | grep -Fx "TeamIdentifier=$APPLE_TEAM_ID"
  printf '%s\n' "$details" | grep -F "Authority=$APPLE_SIGNING_IDENTITY"
  printf '%s\n' "$details" | grep -F '(runtime)'
  printf '%s\n' "$details" | grep -E '^Timestamp='
  xcrun stapler validate "$1"
  spctl --assess --type execute --verbose=2 "$1"
}

shopt -s nullglob
apps=(frontend/out/ByteQuay-darwin-*/ByteQuay.app)
if [ "${#apps[@]}" -ne 1 ]; then
  echo 'Expected exactly one packaged ByteQuay app' >&2
  exit 1
fi
verify_app "${apps[0]}"

count=0
while IFS= read -r -d '' zip; do
  extracted=$(mktemp -d "$RUNNER_TEMP/bytequay-zip.XXXXXX")
  ditto -x -k "$zip" "$extracted"
  verify_app "$extracted/ByteQuay.app"
  rm -rf "$extracted"
  count=$((count + 1))
done < <(find frontend/out/make -type f -name '*.zip' -print0)
[ "$count" -gt 0 ] || { echo 'No release ZIP found' >&2; exit 1; }

count=0
while IFS= read -r -d '' dmg; do
  codesign --force --timestamp --sign "$APPLE_SIGNING_IDENTITY" --keychain "$BYTEQUAY_SIGNING_KEYCHAIN" "$dmg"
  result="$RUNNER_TEMP/bytequay-notary-result.json"
  xcrun notarytool submit "$dmg" --keychain-profile "$BYTEQUAY_NOTARY_PROFILE" --keychain "$BYTEQUAY_SIGNING_KEYCHAIN" \
    --wait --timeout 30m --output-format json > "$result"
  if ! jq -e '.status == "Accepted"' "$result" > /dev/null; then
    xcrun notarytool log "$(jq -r '.id' "$result")" --keychain-profile "$BYTEQUAY_NOTARY_PROFILE" --keychain "$BYTEQUAY_SIGNING_KEYCHAIN"
    exit 1
  fi
  xcrun stapler staple "$dmg"
  xcrun stapler validate "$dmg"
  codesign --verify --strict --verbose=2 "$dmg"
  spctl --assess --type open --context context:primary-signature --verbose=2 "$dmg"
  count=$((count + 1))
done < <(find frontend/out/make -type f -name '*.dmg' -print0)
[ "$count" -gt 0 ] || { echo 'No release DMG found' >&2; exit 1; }

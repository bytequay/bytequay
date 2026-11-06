#!/bin/bash
set -euo pipefail

for name in APPLE_CERTIFICATE_P12_BASE64 APPLE_CERTIFICATE_PASSWORD APPLE_SIGNING_IDENTITY APPLE_TEAM_ID APPLE_API_KEY_P8 APPLE_API_KEY_ID APPLE_API_ISSUER BYTEQUAY_SIGNING_KEYCHAIN BYTEQUAY_NOTARY_PROFILE RUNNER_TEMP; do
  if [ -z "${!name:-}" ]; then
    echo "Missing required release credential: $name" >&2
    exit 1
  fi
done

printf 'BYTEQUAY_SIGNING_KEYCHAIN=%s\n' "$BYTEQUAY_SIGNING_KEYCHAIN" >> "${GITHUB_ENV:?GitHub Actions environment file is required}"
umask 077
certificate="$RUNNER_TEMP/bytequay-certificate.p12"
api_key="$RUNNER_TEMP/bytequay-api-key.p8"
trap 'rm -f "$certificate" "$api_key"' EXIT
printf '%s' "$APPLE_CERTIFICATE_P12_BASE64" | base64 --decode > "$certificate"
printf '%s' "$APPLE_API_KEY_P8" > "$api_key"
keychain_password=$(openssl rand -base64 32)
echo "::add-mask::$keychain_password"
security create-keychain -p "$keychain_password" "$BYTEQUAY_SIGNING_KEYCHAIN"
security set-keychain-settings -lut 21600 "$BYTEQUAY_SIGNING_KEYCHAIN"
security unlock-keychain -p "$keychain_password" "$BYTEQUAY_SIGNING_KEYCHAIN"
security list-keychains -d user -s "$BYTEQUAY_SIGNING_KEYCHAIN" "$HOME/Library/Keychains/login.keychain-db"
security import "$certificate" -k "$BYTEQUAY_SIGNING_KEYCHAIN" -P "$APPLE_CERTIFICATE_PASSWORD" -T /usr/bin/codesign
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$keychain_password" "$BYTEQUAY_SIGNING_KEYCHAIN" > /dev/null
curl --fail --silent --show-error --location https://www.apple.com/certificateauthority/DeveloperIDG2CA.cer -o "$RUNNER_TEMP/DeveloperIDG2CA.cer"
security import "$RUNNER_TEMP/DeveloperIDG2CA.cer" -k "$BYTEQUAY_SIGNING_KEYCHAIN"
security find-identity -v -p codesigning "$BYTEQUAY_SIGNING_KEYCHAIN" | grep -F -- "$APPLE_SIGNING_IDENTITY"

# Validation happens before the expensive build; the temporary key file is then removed.
xcrun notarytool store-credentials "$BYTEQUAY_NOTARY_PROFILE" --keychain "$BYTEQUAY_SIGNING_KEYCHAIN" \
  --key "$api_key" --key-id "$APPLE_API_KEY_ID" --issuer "$APPLE_API_ISSUER"

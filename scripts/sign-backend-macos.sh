#!/bin/bash
set -euo pipefail

: "${APPLE_SIGNING_IDENTITY:?Release signing identity is required}"
: "${BYTEQUAY_SIGNING_KEYCHAIN:?Signing keychain is required}"
backend="${1:?Backend JAR is required}"
stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT

# Apple inspects native code inside nested JARs as well as the app bundle.
sqlite=$(unzip -Z1 "$backend" | grep -E '^BOOT-INF/lib/sqlite-jdbc-[^/]+\.jar$')
[[ "$sqlite" != *$'\n'* ]] || { echo 'Expected one SQLite JDBC JAR' >&2; exit 1; }
mkdir -p "$stage/BOOT-INF/lib" "$stage/sqlite"
unzip -p "$backend" "$sqlite" > "$stage/$sqlite"
unzip -q "$stage/$sqlite" 'org/sqlite/native/Mac/*/*.dylib' -d "$stage/sqlite"

cd "$stage/sqlite"
libraries=(org/sqlite/native/Mac/*/*.dylib)
for library in "${libraries[@]}"; do
  codesign --force --timestamp --sign "$APPLE_SIGNING_IDENTITY" --keychain "$BYTEQUAY_SIGNING_KEYCHAIN" "$library"
  codesign --verify --strict "$library"
done
zip -q "$stage/$sqlite" "${libraries[@]}"
cd "$stage"
# Spring Boot requires its nested dependency JARs to remain uncompressed.
zip -q -0 "$backend" "$sqlite"
unzip -tq "$backend"

#!/usr/bin/env bash

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RESET="$ROOT/scripts/reset-dev-data.sh"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/bytequay-reset-test.XXXXXX")"
trap 'rm -rf -- "$TEST_ROOT"' EXIT

fail() {
  echo "reset-dev-data test failed: $1"
  exit 1
}

home="$TEST_ROOT/home"
data="$home/Library/Application Support/ByteQuay"
mkdir -p "$data/tools" "$data/skills" "$data/repos/project" "$data/Partitions"
touch "$data/tools/codegraph" "$data/skills/managed-skill" "$data/repos/project/work"
touch "$data/bytequay.db" "$data/credentials.key" "$data/.hidden-state" "$data/Partitions/cookie"

HOME="$home" BYTEQUAY_DEV_RESET_CONFIRMED=1 "$RESET"

[[ -f "$data/tools/codegraph" ]] || fail "managed tools were removed"
[[ -f "$data/skills/managed-skill" ]] || fail "managed skills were removed"
[[ ! -e "$data/repos" ]] || fail "managed repository copies were kept"
[[ ! -e "$data/bytequay.db" ]] || fail "database was kept"
[[ ! -e "$data/credentials.key" ]] || fail "credential key was kept"
[[ ! -e "$data/.hidden-state" ]] || fail "hidden user state was kept"
[[ ! -e "$data/Partitions" ]] || fail "browser state was kept"

# Repeating the reset is safe and keeps the same system-owned directories.
HOME="$home" BYTEQUAY_DEV_RESET_CONFIRMED=1 "$RESET"
[[ -f "$data/tools/codegraph" ]] || fail "second reset removed managed tools"

symlink_home="$TEST_ROOT/symlink-home"
symlink_target="$TEST_ROOT/symlink-target"
mkdir -p "$symlink_home/Library/Application Support" "$symlink_target"
touch "$symlink_target/sentinel"
ln -s "$symlink_target" "$symlink_home/Library/Application Support/ByteQuay"
if HOME="$symlink_home" BYTEQUAY_DEV_RESET_CONFIRMED=1 "$RESET" >/dev/null 2>&1; then
  fail "symlinked data directory was accepted"
fi
[[ -f "$symlink_target/sentinel" ]] || fail "symlink target was modified"

if HOME=/ BYTEQUAY_DEV_RESET_CONFIRMED=1 "$RESET" >/dev/null 2>&1; then
  fail "root HOME was accepted"
fi
if HOME="$home" "$RESET" >/dev/null 2>&1; then
  fail "unconfirmed reset was accepted"
fi

echo "reset-dev-data test passed"

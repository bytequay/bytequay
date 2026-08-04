#!/usr/bin/env bash
# Internal helper for dev.sh's confirmed fresh-start reset.

set -euo pipefail

if [[ "${BYTEQUAY_DEV_RESET_CONFIRMED:-}" != "1" ]]; then
  echo "[dev] error: local data reset was not confirmed by dev.sh"
  exit 2
fi

if [[ -z "${HOME:-}" || "$HOME" != /* || "$HOME" == "/" || ! -d "$HOME" ]]; then
  echo "[dev] error: refusing to reset with an invalid HOME"
  exit 2
fi

USER_HOME="$(cd -P "$HOME" && pwd)"
if [[ -z "$USER_HOME" || "$USER_HOME" == "/" ]]; then
  echo "[dev] error: refusing to reset with an unsafe home directory"
  exit 2
fi

APP_DATA_DIR="$USER_HOME/Library/Application Support/ByteQuay"
if [[ -L "$APP_DATA_DIR" ]]; then
  echo "[dev] error: refusing to reset a symlinked data directory: $APP_DATA_DIR"
  exit 2
fi

[[ -d "$APP_DATA_DIR" ]] || exit 0

# Saved credentials survive a reset — re-entering every API key and PAT is what
# would make a fresh start too expensive to bother with. The rows live in the
# database that is about to be deleted, so stash a copy for dev.sh to copy them
# back from once Flyway has rebuilt the schema. The -wal/-shm siblings come
# along so a hard-killed backend's uncommitted writes are recovered too.
KEEP_DIR="${BYTEQUAY_DEV_RESET_KEEP_DIR:-}"
if [[ -n "$KEEP_DIR" && -d "$KEEP_DIR" && -f "$APP_DATA_DIR/bytequay.db" ]]; then
  cp -- "$APP_DATA_DIR"/bytequay.db* "$KEEP_DIR/"
fi

# System prompts and required managed skills are bundled with the app. Keep
# downloaded system tools/skill caches too, and the key that decrypts the
# preserved credentials; Flyway recreates required seed rows. Everything else
# here is user state.
shopt -s dotglob nullglob
for entry in "$APP_DATA_DIR"/*; do
  name="${entry##*/}"
  case "$name" in
    tools|skills|credentials.key) continue ;;
  esac
  rm -rf -- "$entry"
done

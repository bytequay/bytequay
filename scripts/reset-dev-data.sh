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

# System prompts and required managed skills are bundled with the app. Keep
# downloaded system tools/skill caches too; Flyway recreates required seed
# rows. Everything else here is user state.
shopt -s dotglob nullglob
for entry in "$APP_DATA_DIR"/*; do
  name="${entry##*/}"
  case "$name" in
    tools|skills) continue ;;
  esac
  rm -rf -- "$entry"
done

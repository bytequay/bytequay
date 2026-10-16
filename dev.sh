#!/usr/bin/env bash
# dev.sh — start the Spring Boot backend and the Electron/React frontend together.
# Ctrl+C stops both.
#
# Requirements: a local Maven install (`mvn`) and Node.js (for `npm`).
# First run: `(cd frontend && npm install)` if you haven't already.

set -uo pipefail
# Job control stays off: with it on, each foreground command (curl, sleep,
# node) would own the terminal while it runs and swallow Ctrl+C instead of
# this script's INT trap. The two long-lived children lead their own process
# groups instead, so cleanup() can kill a whole subtree with `kill -- -$pid`
# and tear them down deliberately (electron-forge swallows SIGINT).

ROOT="$(cd "$(dirname "$0")" && pwd)"
backend_port=""

reset_data=false
for arg in "$@"; do
  case "$arg" in
    --reset-data) reset_data=true ;;
    *) echo "[dev] unknown option: $arg (supported: --reset-data)"; exit 1 ;;
  esac
done

pids=()
backend_runtime_dir=""

process_group_alive() {
  local pid="$1"
  kill -0 -- "-$pid" 2>/dev/null || kill -0 "$pid" 2>/dev/null
}

cleanup() {
  # Guard against the trap firing twice (INT then EXIT).
  [[ -n "${cleaned:-}" ]] && return
  cleaned=1
  echo ""
  echo "[dev] shutting down..."
  for pid in "${pids[@]:-}"; do
    # Negative PID targets the child's whole process group, so
    # electron-forge's Vite + Electron descendants die with it rather
    # than being re-parented to init and lingering. Falls back to a
    # plain kill + pkill for any child not in its own group.
    kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
    pkill -P "$pid" 2>/dev/null || true
  done
  # Give graceful exit a bounded window — a stuck JVM (Spring Boot
  # @PreDestroy waiting forever, a non-daemon thread, or just a wedged
  # state) would otherwise let `wait` below hang the script and the
  # user is left mashing Ctrl+C forever. Poll up to ~5s, then SIGKILL.
  local deadline=$((SECONDS + 5))
  while (( SECONDS < deadline )); do
    local alive=0
    for pid in "${pids[@]:-}"; do
      process_group_alive "$pid" && { alive=1; break; }
    done
    [[ "$alive" == "0" ]] && break
    sleep 0.2
  done
  for pid in "${pids[@]:-}"; do
    if process_group_alive "$pid"; then
      echo "[dev] $pid did not honour SIGTERM — forcing"
      kill -KILL -- "-$pid" 2>/dev/null || kill -KILL "$pid" 2>/dev/null || true
    fi
  done
  # Reap zombies but don't block forever — the SIGKILLs above mean
  # any child should be unstuck by now.
  wait 2>/dev/null || true
  if [[ -n "$backend_runtime_dir" ]]; then
    rm -f "$backend_runtime_dir/bytequay-backend.jar" "$backend_runtime_dir/port"
    rmdir "$backend_runtime_dir" 2>/dev/null || true
  fi
}

trap cleanup EXIT
# Exit rather than carry on: a step interrupted before its children start
# would otherwise start them after cleanup() has already run.
trap 'exit 130' INT TERM

wait_for_backend() {
  # 60s total — enough headroom for a cold mvn that still compiles + runs
  # Error Prone on top of Spring Boot's own ~3s init. checkstyle and
  # license-check are skipped below so they don't eat into this window.
  local deadline=$((SECONDS + 60))
  local delay=0.25
  local port response

  # --noproxy '*' bypasses any http_proxy / HTTPS_PROXY env vars the
  # user may have set globally (corporate networks, local MITM tools
  # like mitmproxy). Without it, even an up-and-serving backend can
  # surface as a 502 from the proxy and dev.sh would keep waiting.
  while (( SECONDS < deadline )); do
    if ! kill -0 "$backend_pid" 2>/dev/null; then
      echo "[dev] error: backend exited before becoming ready"
      return 1
    fi
    if [[ -s "$backend_runtime_dir/port" ]]; then
      port="$(<"$backend_runtime_dir/port")"
      if [[ "$port" =~ ^[1-9][0-9]{0,4}$ ]] && (( port <= 65535 )); then
        if response="$(curl -fsS --noproxy '*' --max-time 1 "http://127.0.0.1:$port/hello" 2>/dev/null)" \
            && [[ "$response" == "ByteQuay-backend ok" ]]; then
          backend_port="$port"
          return 0
        fi
      fi
    fi
    sleep "$delay"
  done

  echo "[dev] error: backend did not report a ready port within 60 seconds"
  return 1
}

if ! command -v mvn >/dev/null 2>&1; then
  echo "[dev] error: 'mvn' not found on PATH. Install Maven (brew install maven) or open the backend in IntelliJ."
  exit 1
fi

if ! command -v npm >/dev/null 2>&1; then
  echo "[dev] error: 'npm' not found on PATH. Install Node.js."
  exit 1
fi

SESSION_DIRECTORY="$HOME/Library/Application Support/ByteQuay"
SESSION_TOKEN_FILE="$SESSION_DIRECTORY/desktop-session-token"
session_token="${BYTEQUAY_DESKTOP_SESSION_TOKEN:-}"
if [[ -z "$session_token" ]]; then
  session_token="$(/usr/bin/uuidgen | tr -d '-')$(/usr/bin/uuidgen | tr -d '-')"
fi
mkdir -p "$SESSION_DIRECTORY"
# --reset-data parks the runtime database in a timestamped backup directory and
# lets Hibernate create a fresh one; Hibernate's schema update never rewrites an
# existing CHECK constraint, so an incompatible model change needs this. Cloned
# repositories and the Codex home are kept.
if [[ "$reset_data" == true ]]; then
  backup="$SESSION_DIRECTORY/database-backup-$(date +%Y%m%dT%H%M%S)"
  mkdir -p "$backup"
  mv "$SESSION_DIRECTORY"/bytequay-*.db* "$backup"/ 2>/dev/null || true
  echo "[dev] runtime database moved to $backup"
fi
(umask 077 && printf '%s\n' "$session_token" > "$SESSION_TOKEN_FILE")
chmod 600 "$SESSION_TOKEN_FILE"
export BYTEQUAY_DESKTOP_SESSION_TOKEN="$session_token"

if [[ ! -d "$ROOT/frontend/node_modules" ]]; then
  echo "[dev] frontend/node_modules missing — running 'npm install' first..."
  ( cd "$ROOT/frontend" && npm install )
fi

node "$ROOT/scripts/ide-bundle.mjs"
export BYTEQUAY_IDE_HOME="$ROOT/frontend/vendor/ide"

echo "[dev] starting backend (Spring Boot prefers :9527, then an available OS-assigned port)..."
# Build the three-module reactor, then replace the background subshell with
# the packaged JVM. The exec keeps the process-group cleanup above reliable,
# while using the same JAR layout that Electron packages.
#
# The JVM runs in its own session, without this terminal as its controlling
# terminal, as when Finder launches the app. Otherwise an interactive shell
# started under it, such as `zsh -il`, takes the terminal's foreground and
# leaves it with the JVM, so Ctrl+C never reaches this script.
# setsid needs a child that leads no process group, as one forked without job
# control does; such a child also starts with INT and QUIT ignored, which perl
# restores. The session makes the JVM a group leader for cleanup().
#
# -Dcheckstyle.skip + -Dlicense.skip drop the validate-phase plugins
# bound in backend/pom.xml that otherwise run before every dev start
# and routinely push mvn past the 60s readiness window above. CI and
# `mvn verify` still enforce both — these are dev-only shortcuts.
# Force the app JAR to rebuild when only a dependency module changed; otherwise
# Maven may leave Spring Boot's repackaged JAR with old agent-cli classes.
JVM_ARGS=(--enable-native-access=ALL-UNNAMED -Xmx2000m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=128m -XX:+ExitOnOutOfMemoryError)
# Maven replaces the target JAR during later builds. Keep the running JVM's
# lazy-loaded classes on an unchanged copy until this dev session exits.
backend_runtime_dir="$(mktemp -d "${TMPDIR:-/tmp}/bytequay-backend.XXXXXX")" || exit 1
( cd "$ROOT/backend" \
    && mvn -q -pl app -am package \
        -DskipTests \
        -Dcheckstyle.skip=true \
        -Dlicense.skip=true \
        -Dmaven.jar.forceCreation=true \
    && cp app/target/bytequay-backend.jar "$backend_runtime_dir/bytequay-backend.jar" \
    && BYTEQUAY_BACKEND_PORT_FILE="$backend_runtime_dir/port" exec /usr/bin/perl -MPOSIX -e \
        '$SIG{$_} = "DEFAULT" for qw(INT QUIT); setsid; exec @ARGV or die "$ARGV[0]: $!\n"' \
        java "${JVM_ARGS[@]}" -jar "$backend_runtime_dir/bytequay-backend.jar" --bytequay.experiments.goals=true ) &
backend_pid=$!
pids+=("$backend_pid")

if ! wait_for_backend; then
  exit 1
fi
export BYTEQUAY_BACKEND_PORT="$backend_port"
echo "[dev] backend ready at http://127.0.0.1:$backend_port"

echo "[dev] starting frontend (Electron + Vite)..."
# Background the frontend and wait on it explicitly. A bash `wait` is
# interrupted by a trapped signal right away, so Ctrl+C runs cleanup()
# on the first press — unlike a *foreground* `npm start`, where bash
# defers the INT trap until the command returns and electron-forge can
# sit on the signal indefinitely. `wait` also returns on its own when
# the Electron window is closed, so quitting the app still shuts the
# backend down via the EXIT trap. Job control is on only for this fork,
# which gives Electron + Vite a process group of their own.
set -m
( cd "$ROOT/frontend" && npm start ) &
frontend_pid=$!
set +m
pids+=("$frontend_pid")
frontend_status=0
wait "$frontend_pid" || frontend_status=$?

exit "$frontend_status"

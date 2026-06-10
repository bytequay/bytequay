#!/usr/bin/env bash
# dev.sh — start the Spring Boot backend and the Electron/React frontend together.
# Ctrl+C stops both.
#
# Requirements: a local Maven install (`mvn`) and Node.js (for `npm`).
# First run: `(cd frontend && npm install)` if you haven't already.

set -uo pipefail
# Enable job control so each `&` child becomes its own process-group
# leader. That's what lets cleanup() kill a whole subtree with
# `kill -- -$pid` (Electron + Vite + the JVM), and it keeps the
# terminal's Ctrl+C aimed at this script alone — cleanup() then tears
# the children down deliberately instead of relying on the signal
# reaching them (electron-forge swallows SIGINT).
set -m

ROOT="$(cd "$(dirname "$0")" && pwd)"
BACKEND_PORT=53123

pids=()

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
  # Catch any Spring Boot JVM still holding the backend port.
  if lsof -ti ":$BACKEND_PORT" >/dev/null 2>&1; then
    echo "[dev] stray process still on :$BACKEND_PORT — killing it"
    lsof -ti ":$BACKEND_PORT" | xargs kill -9 2>/dev/null || true
  fi
  wait 2>/dev/null || true
}
trap cleanup EXIT INT TERM

wait_for_backend() {
  # 60s total — enough headroom for a cold mvn that still compiles + runs
  # Error Prone on top of Spring Boot's own ~3s init. checkstyle and
  # license-check are skipped below so they don't eat into this window.
  local attempts=240
  local delay=0.25

  # --noproxy '*' bypasses any http_proxy / HTTPS_PROXY env vars the
  # user may have set globally (corporate networks, local MITM tools
  # like mitmproxy). Without it, even an up-and-serving backend can
  # surface as a 502 from the proxy and dev.sh would keep waiting.
  for ((i=1; i<=attempts; i++)); do
    if curl -fsS --noproxy '*' "http://127.0.0.1:$BACKEND_PORT/hello" >/dev/null 2>&1; then
      return 0
    fi
    sleep "$delay"
  done

  echo "[dev] error: backend did not become ready on :$BACKEND_PORT"
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

# Preflight: refuse to start if backend port is already taken. Offers the most
# common root cause — a leftover JVM from a previous run — a visible exit path.
if lsof -ti ":$BACKEND_PORT" >/dev/null 2>&1; then
  holder="$(lsof -ti ":$BACKEND_PORT")"
  echo "[dev] error: port $BACKEND_PORT is already in use (pid(s): $holder)"
  echo "[dev] kill it with:  lsof -ti :$BACKEND_PORT | xargs kill -9"
  exit 1
fi

if [[ ! -d "$ROOT/frontend/node_modules" ]]; then
  echo "[dev] frontend/node_modules missing — running 'npm install' first..."
  ( cd "$ROOT/frontend" && npm install )
fi

echo "[dev] starting backend (Spring Boot on :$BACKEND_PORT)..."
# -Dspring-boot.run.fork=false keeps the JVM in the mvn process so Ctrl+C
# cleanly terminates both. Without it, mvn forks a child JVM that can
# outlive mvn's own shutdown.
#
# spring-boot.run.jvmArguments enforces the same ~2 GB heap ceiling
# that frontend/src/backendProcess.ts passes in packaged mode, so a
# leaky session can't eat the whole machine in dev either. Keep the
# values in sync between this file and backendProcess.ts.
#
# -Dcheckstyle.skip + -Dlicense.skip drop the validate-phase plugins
# bound in backend/pom.xml that otherwise run before every dev start
# and routinely push mvn past the 60s readiness window above. CI and
# `mvn verify` still enforce both — these are dev-only shortcuts.
JVM_ARGS="-Xmx2000m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=128m -XX:+ExitOnOutOfMemoryError"
( cd "$ROOT/backend" \
    && mvn -q spring-boot:run \
        -Dspring-boot.run.fork=false \
        -Dspring-boot.run.jvmArguments="$JVM_ARGS" \
        -Dcheckstyle.skip=true \
        -Dlicense.skip=true ) &
pids+=($!)

if ! wait_for_backend; then
  exit 1
fi

echo "[dev] starting frontend (Electron + Vite)..."
# Background the frontend and wait on it explicitly. A bash `wait` is
# interrupted by a trapped signal right away, so Ctrl+C runs cleanup()
# on the first press — unlike a *foreground* `npm start`, where bash
# defers the INT trap until the command returns and electron-forge can
# sit on the signal indefinitely. `wait` also returns on its own when
# the Electron window is closed, so quitting the app still shuts the
# backend down via the EXIT trap.
( cd "$ROOT/frontend" && npm start ) &
frontend_pid=$!
pids+=("$frontend_pid")
wait "$frontend_pid"

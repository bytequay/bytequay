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
  # Give graceful exit a bounded window — a stuck JVM (Spring Boot
  # @PreDestroy waiting forever, a non-daemon thread, or just a wedged
  # state) would otherwise let `wait` below hang the script and the
  # user is left mashing Ctrl+C forever. Poll up to ~5s, then SIGKILL.
  local deadline=$((SECONDS + 5))
  while (( SECONDS < deadline )); do
    local alive=0
    for pid in "${pids[@]:-}"; do
      kill -0 "$pid" 2>/dev/null && { alive=1; break; }
    done
    [[ "$alive" == "0" ]] && break
    sleep 0.2
  done
  for pid in "${pids[@]:-}"; do
    if kill -0 "$pid" 2>/dev/null; then
      echo "[dev] $pid did not honour SIGTERM — forcing"
      kill -KILL -- "-$pid" 2>/dev/null || kill -KILL "$pid" 2>/dev/null || true
    fi
  done
  # Catch any Spring Boot JVM still holding the backend port.
  if lsof -ti ":$BACKEND_PORT" >/dev/null 2>&1; then
    echo "[dev] stray process still on :$BACKEND_PORT — killing it"
    lsof -ti ":$BACKEND_PORT" | xargs kill -9 2>/dev/null || true
  fi
  # Reap a stranded ds4-server. The backend writes its spawned PID to
  # a marker file at start and removes it on clean stop; if the JVM
  # died hard (kill -9, OOM, our SIGKILL fallback) the file is left
  # behind and the ds4 subprocess is orphaned. SIGKILL it and clean
  # the file so the next dev.sh boot starts from zero state. We only
  # touch the spawned-by-us case — an attached external ds4 doesn't
  # have the marker.
  pid_file_macos="$HOME/Library/Application Support/ds4/ds4-server.pid"
  pid_file_linux="$HOME/.ds4/ds4-server.pid"
  for pid_file in "$pid_file_macos" "$pid_file_linux"; do
    if [[ -f "$pid_file" ]]; then
      ds4_pid="$(cat "$pid_file" 2>/dev/null || true)"
      if [[ -n "$ds4_pid" ]] && kill -0 "$ds4_pid" 2>/dev/null; then
        echo "[dev] stranded ds4-server (pid $ds4_pid) — killing it"
        kill -KILL "$ds4_pid" 2>/dev/null || true
      fi
      rm -f "$pid_file"
    fi
  done
  # Reap zombies but don't block forever — the SIGKILLs above mean
  # any child should be unstuck by now.
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

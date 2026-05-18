#!/usr/bin/env bash
# dev.sh — start the Spring Boot backend and the Electron/React frontend together.
# Ctrl+C stops both.
#
# Requirements: a local Maven install (`mvn`) and Node.js (for `npm`).
# First run: `(cd frontend && npm install)` if you haven't already.

set -uo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
BACKEND_PORT=53123

pids=()

cleanup() {
  echo ""
  echo "[dev] shutting down..."
  for pid in "${pids[@]:-}"; do
    kill "$pid" 2>/dev/null || true
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
  local attempts=80
  local delay=0.25

  for ((i=1; i<=attempts; i++)); do
    if curl -fsS "http://127.0.0.1:$BACKEND_PORT/hello" >/dev/null 2>&1; then
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
JVM_ARGS="-Xmx2000m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=128m -XX:+ExitOnOutOfMemoryError"
( cd "$ROOT/backend" && mvn -q spring-boot:run -Dspring-boot.run.fork=false -Dspring-boot.run.jvmArguments="$JVM_ARGS" ) &
pids+=($!)

if ! wait_for_backend; then
  exit 1
fi

echo "[dev] starting frontend (Electron + Vite)..."
( cd "$ROOT/frontend" && npm start )

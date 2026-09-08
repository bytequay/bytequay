#!/usr/bin/env bash
# Run with bash scripts/test-dev-startup.sh; no JVM or desktop session is started.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
eval "$(sed -n '/^wait_for_backend() {$/,/^}$/p' "$ROOT/dev.sh")"
backend_runtime_dir="$(mktemp -d "${TMPDIR:-/tmp}/bytequay-dev-test.XXXXXX")"
trap 'rm -f "$backend_runtime_dir/port" "$backend_runtime_dir/request"; rmdir "$backend_runtime_dir"' EXIT
backend_pid=123
backend_alive=true
curl_status=0
reply="ByteQuay-backend ok"

kill() { [[ "$backend_alive" == true ]]; }
sleep() { SECONDS=$((SECONDS + 60)); }
curl() {
  printf '%s\n' "$@" > "$backend_runtime_dir/request"
  printf '%s' "$reply"
  return "$curl_status"
}

expect_not_ready() {
  backend_port=""
  if wait_for_backend >/dev/null; then
    echo "Unexpectedly accepted backend readiness" >&2
    exit 1
  fi
  [[ -z "$backend_port" ]]
}

for port in 9527 49222; do
  printf '%s\n' "$port" > "$backend_runtime_dir/port"
  wait_for_backend
  [[ "$backend_port" == "$port" ]]
  [[ "$(tail -1 "$backend_runtime_dir/request")" == "http://127.0.0.1:$port/hello" ]]
done

reply="Another service"
expect_not_ready
reply="ByteQuay-backend ok"
curl_status=22
expect_not_ready
curl_status=0

for port in 0 65536 '9527/unexpected' ''; do
  printf '%s' "$port" > "$backend_runtime_dir/port"
  rm -f "$backend_runtime_dir/request"
  expect_not_ready
  [[ ! -e "$backend_runtime_dir/request" ]]
done

rm -f "$backend_runtime_dir/port"
expect_not_ready
printf '9527\n' > "$backend_runtime_dir/port"
backend_alive=false
expect_not_ready
[[ ! -e "$backend_runtime_dir/request" ]]
echo "dev startup port checks passed"

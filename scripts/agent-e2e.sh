#!/usr/bin/env bash

set -euo pipefail

interactive=false
minimal_worktree=false
provider=codex
while [[ $# -gt 0 ]]; do
    case "$1" in
        --interactive)
            interactive=true
            ;;
        --minimal-worktree)
            minimal_worktree=true
            ;;
        --provider)
            shift
            if [[ $# -eq 0 || ("$1" != codex && "$1" != claude) ]]; then
                echo "--provider requires codex or claude" >&2
                exit 2
            fi
            provider="$1"
            ;;
        --help)
            echo "Usage: $0 --interactive [--provider codex|claude] [--minimal-worktree]"
            exit 0
            ;;
        *)
            echo "Usage: $0 --interactive [--provider codex|claude] [--minimal-worktree]" >&2
            exit 2
            ;;
    esac
    shift
done

if [[ "$interactive" != true ]]; then
    echo "The deterministic shell mode was removed; pass --interactive with a real provider." >&2
    echo "Usage: $0 --interactive [--provider codex|claude] [--minimal-worktree]" >&2
    exit 2
fi

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
backend_root="$repository_root/backend"
port="${BYTEQUAY_AGENT_E2E_PORT:-0}"
base_url=""
temporary_directory="$(mktemp -d)"
session_token="bytequay-agent-${temporary_directory##*/}"
backend_pid=""
event_stream_pid=""
event_stream_open=false
interactive_started=false

cleanup()
{
    if [[ -n "$event_stream_pid" ]] && kill -0 "$event_stream_pid" 2>/dev/null; then
        kill "$event_stream_pid"
        wait "$event_stream_pid" 2>/dev/null || true
    fi
    if [[ "$event_stream_open" == true ]]; then
        exec 3<&-
    fi
    if [[ -n "$backend_pid" ]] && kill -0 "$backend_pid" 2>/dev/null; then
        kill "$backend_pid"
        wait "$backend_pid" 2>/dev/null || true
    fi
    rm -rf "$temporary_directory"
    if [[ "$interactive_started" == true ]]; then
        echo "Backend stopped; temporary repository, database, runtime, and logs removed."
    fi
}
trap cleanup EXIT
trap 'printf "\n"; exit 130' INT
trap 'exit 143' TERM

print_event()
{
    local data="$1"
    printf '%s\n' "$data" | jq -r '
        if .type == "assistant_message" and .payload.phase == "FINAL" then
            "agent> \(.payload.text)"
        elif .type == "assistant_message" then
            empty
        elif .type == "command" and .payload.state == "STARTED" then
            "command> \(.payload.argv | join(" "))"
        elif .type == "command" and .payload.state == "PROGRESS" then
            "output> \(.payload.output // "")"
        elif .type == "command" then
            (if (.payload.output // "") != "" then
                "output> \(.payload.output)"
            else
                empty
            end),
            "command> \(.payload.state | ascii_downcase) (exit \(.payload.exitCode // "unknown"))"
        elif .type == "tool" then
            "tool> \(.payload.name) \(.payload.state | ascii_downcase)"
        elif .type == "plan_updated" then
            .payload.items[] | "plan> \(.state | ascii_downcase): \(.text)"
        elif .type == "error" then
            "error> \(.payload.message)"
        elif .type == "approval_requested" then
            "approval> \(.payload.title)"
        elif .type == "lifecycle" then
            "status> \(.payload.message)"
        else
            "event> #\(.sequence) \(.type)"
        end'
}

read_turn_events()
{
    local approval_decision
    local approval_id
    local data
    local line
    local phase
    local type
    while IFS= read -r -t 1800 line <&3; do
        if [[ "$line" == data:* ]]; then
            line="${line#data:}"
            data="${line# }"
            print_event "$data"
            type="$(printf '%s\n' "$data" | jq -r '.type')"
            phase="$(printf '%s\n' "$data" | jq -r '.payload.phase // ""')"
            if [[ "$type" == approval_requested ]]; then
                approval_id="$(printf '%s\n' "$data" | jq -er '.payload.approvalId')"
                printf 'approval> %s\n' "$(printf '%s\n' "$data" | jq -r '.payload.detail')"
                if IFS= read -r -p "allow? [y/N] " approval_decision \
                        && [[ "$approval_decision" =~ ^[Yy]$ ]]; then
                    approval_decision=ALLOW
                else
                    approval_decision=DENY
                fi
                curl -fsS --connect-timeout 2 --max-time 30 \
                    -H "X-ByteQuay-Session: $session_token" \
                    -H 'Content-Type: application/json' \
                    -d "$(jq -n --arg decision "$approval_decision" '{decision: $decision}')" \
                    "$base_url/agent/approvals/$approval_id" >/dev/null
            fi
            if [[ "$type" == "lifecycle" && ("$phase" == "TURN_COMPLETED" || "$phase" == "INTERRUPTED") ]]; then
                return 0
            fi
        fi
    done
    echo "event> response stream ended before the turn completed" >&2
    return 1
}

print_turn()
{
    local turn_id="$1"
    local turn_json
    local turn_state=""
    for _ in {1..40}; do
        turn_json="$(curl -fsS --connect-timeout 2 --max-time 30 \
            -H "X-ByteQuay-Session: $session_token" \
            "$base_url/agent/turns/$turn_id")"
        turn_state="$(printf '%s\n' "$turn_json" | jq -er '.state')"
        case "$turn_state" in
            COMPLETED|FAILED|CANCELLED|INTERRUPTED) break ;;
        esac
        sleep 0.25
    done
    printf 'turn> %s %s\n' "$turn_id" "$turn_state"
}

interactive_session()
{
    local event_pipe="$temporary_directory/events.pipe"
    local message
    local turn_count=0
    local turn_id
    local turn_request

    mkfifo "$event_pipe"
    curl -sS -N \
        -H "X-ByteQuay-Session: $session_token" \
        "$base_url/agent/tasks/$task_id/stream?after=0" \
        > "$event_pipe" &
    event_stream_pid=$!
    exec 3< "$event_pipe"
    event_stream_open=true
    interactive_started=true

    echo "ByteQuay $provider agent shell"
    echo "Task: $task_id"
    echo "Commands: /events, /backend-log, /help, /quit; Ctrl-C also quits"
    while IFS= read -r -p "you> " message; do
        case "$message" in
            "") continue ;;
            /quit|quit|exit) break ;;
            /help)
                echo "Enter a message, or use /events, /backend-log, or /quit."
                continue
                ;;
            /events)
                curl -fsS --connect-timeout 2 --max-time 30 \
                    -H "X-ByteQuay-Session: $session_token" \
                    "$base_url/agent/tasks/$task_id/events?after=0&limit=1000" \
                    | jq -r '.[] | "event> #\(.sequence) \(.type) \(.payload | tojson)"'
                continue
                ;;
            /backend-log)
                tail -n 30 "$temporary_directory/process.log"
                continue
                ;;
        esac

        turn_count=$((turn_count + 1))
        turn_request="$(jq -n --arg message "$message" '{message: $message}')"
        turn_id="$(curl -fsS --connect-timeout 2 --max-time 30 \
            -H "X-ByteQuay-Session: $session_token" \
            -H 'Content-Type: application/json' \
            -H "Idempotency-Key: interactive-turn-$turn_count" \
            -d "$turn_request" \
            "$base_url/agent/tasks/$task_id/turns" | jq -er '.id')"
        if ! read_turn_events; then
            break
        fi
        print_turn "$turn_id"
    done
}

for command in curl git java jq mvn "$provider"; do
    command -v "$command" >/dev/null || {
        echo "Missing required command: $command" >&2
        exit 1
    }
done
if [[ "$minimal_worktree" == false ]]; then
    command -v rsync >/dev/null || {
        echo "Missing required command: rsync" >&2
        exit 1
    }
fi
codex_executable=codex
claude_executable=claude
codex_enabled=false
claude_enabled=false
if [[ "$provider" == codex ]]; then
    codex_executable="$(command -v codex)"
    codex_enabled=true
fi
if [[ "$provider" == claude ]]; then
    claude_executable="$(command -v claude)"
    claude_enabled=true
fi

worktree="$temporary_directory/worktree"
if [[ "$minimal_worktree" == false ]]; then
    mkdir "$worktree"
    git -C "$repository_root" ls-files -co --exclude-standard \
        | rsync -a --files-from=- "$repository_root/" "$worktree/"
else
    mkdir "$worktree"
    printf 'agent e2e\n' > "$worktree/README.md"
fi
git -C "$worktree" init -q -b main
git -C "$worktree" config user.name "ByteQuay E2E"
git -C "$worktree" config user.email "e2e@bytequay.local"
git -C "$worktree" add .
git -C "$worktree" commit -q -m "Initial commit"
git -C "$worktree" remote add origin https://github.com/bytequay/agent-e2e.git

MAVEN_OPTS="${MAVEN_OPTS:+$MAVEN_OPTS }--sun-misc-unsafe-memory-access=allow" \
    mvn -q -f "$backend_root/pom.xml" -pl app -am -DskipTests package

java --enable-native-access=ALL-UNNAMED -jar "$backend_root/app/target/bytequay-backend.jar" \
    --server.port="$port" \
    --server.shutdown=immediate \
    --spring.datasource.url="jdbc:sqlite:$temporary_directory/e2e.db?journal_mode=WAL&busy_timeout=30000&foreign_keys=ON" \
    --spring.jpa.hibernate.ddl-auto=create \
    --spring.datasource.hikari.maximum-pool-size=1 \
    --spring.datasource.hikari.minimum-idle=1 \
    --logging.file.name="$temporary_directory/backend.log" \
    --bytequay.scheduling.enabled=false \
    --bytequay.agent.desktop-session-token="$session_token" \
    --bytequay.agent.runtime-root="$temporary_directory/runtime" \
    --bytequay.agent.codex-enabled="$codex_enabled" \
    --bytequay.agent.codex-executable="$codex_executable" \
    --bytequay.agent.claude-enabled="$claude_enabled" \
    --bytequay.agent.claude-executable="$claude_executable" \
    > "$temporary_directory/process.log" 2>&1 &
backend_pid=$!

for _ in {1..60}; do
    if [[ -z "$base_url" ]]; then
        actual_port="$(sed -nE 's/.*Tomcat started on port ([0-9]+).*/\1/p' "$temporary_directory/process.log" | tail -n 1)"
        if [[ -n "$actual_port" ]]; then
            base_url="http://127.0.0.1:$actual_port"
        fi
    fi
    if [[ -n "$base_url" ]] && curl -fsS --connect-timeout 1 --max-time 2 "$base_url/hello" >/dev/null 2>&1; then
        break
    fi
    if ! kill -0 "$backend_pid" 2>/dev/null; then
        cat "$temporary_directory/process.log" >&2
        exit 1
    fi
    sleep 0.5
done
curl -fsS --connect-timeout 2 --max-time 10 "$base_url/hello" >/dev/null || {
    cat "$temporary_directory/process.log" >&2
    exit 1
}

task_request="$(jq -n \
    --arg laneId "$provider" \
    --arg worktree "$worktree" \
    --arg repository bytequay/agent-e2e \
    --arg title "Agent shell E2E" \
    '{laneId: $laneId, worktree: $worktree, repository: $repository, title: $title}')"
task_id="$(curl -fsS --connect-timeout 2 --max-time 30 \
    -H "X-ByteQuay-Session: $session_token" \
    -H 'Content-Type: application/json' \
    -d "$task_request" \
    "$base_url/agent/tasks" | jq -er '.id')"

interactive_session

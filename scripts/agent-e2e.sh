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
            if [[ $# -eq 0 || ( "$1" != codex && "$1" != claude ) ]]; then
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
session_token=""
authenticated_curl_config="$temporary_directory/authenticated-curl.conf"
process_log="$temporary_directory/process.log"
diagnostic_secrets=()
backend_pid=""
event_stream_pid=""
event_stream_open=false
interactive_started=false
database="$temporary_directory/e2e.db"
workspace_id=agent-e2e
workspace_repository_id=1
workspace_root="$temporary_directory/workspaces"
workspace_directory="$workspace_root/$workspace_id"
workspace_repository="$workspace_directory/repository"

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

collect_diagnostic_secrets()
{
    local first
    local index
    local name
    local second
    local value
    diagnostic_secrets=("$session_token")
    shopt -s nocasematch
    while IFS= read -r name; do
        case "$name" in
            *token*|*secret*|*password*|*api_key*|*apikey*|*credential*)
                value="${!name:-}"
                if [[ ${#value} -ge 4 ]]; then
                    diagnostic_secrets+=("$value")
                fi
                ;;
        esac
    done < <(compgen -e)
    shopt -u nocasematch

    # Redact longer values first so a short credential cannot leave the tail of
    # another credential visible when one happens to contain the other.
    for ((first = 0; first < ${#diagnostic_secrets[@]}; first++)); do
        for ((second = first + 1; second < ${#diagnostic_secrets[@]}; second++)); do
            if [[ ${#diagnostic_secrets[second]} -gt ${#diagnostic_secrets[first]} ]]; then
                value="${diagnostic_secrets[first]}"
                diagnostic_secrets[first]="${diagnostic_secrets[second]}"
                diagnostic_secrets[second]="$value"
            fi
        done
    done
}

print_backend_diagnostics()
{
    local byte_count
    local line
    local pattern
    local secret
    if [[ ! -f "$process_log" ]]; then
        echo "No backend diagnostic output is available." >&2
        return
    fi
    # Bound both dimensions: one giant log line must not bypass the line cap.
    byte_count="$(wc -c < "$process_log")"
    if ((byte_count > 32768)); then
        # The byte window can start inside a credential. Discard that incomplete line so a
        # suffix whose prefix fell outside the window can never evade whole-secret redaction.
        tail -c 32768 "$process_log" 2>/dev/null | sed '1d' | tail -n 30
    else
        tail -c 32768 "$process_log" 2>/dev/null | tail -n 30
    fi | while IFS= read -r line || [[ -n "$line" ]]; do
        for secret in "${diagnostic_secrets[@]}"; do
            # Bash replacement searches with a glob pattern. Escape its metacharacters so a
            # credential such as `key*[prod]` cannot redact unrelated diagnostic text.
            pattern="$secret"
            pattern="${pattern//\\/\\\\}"
            pattern="${pattern//\*/\\*}"
            pattern="${pattern//\?/\\?}"
            pattern="${pattern//\[/\\[}"
            line="${line//$pattern/[REDACTED]}"
        done
        printf '%s\n' "$line"
    done
}

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
                jq -n --arg decision "$approval_decision" '{decision: $decision}' \
                    | curl -q --config "$authenticated_curl_config" -fsS --connect-timeout 2 --max-time 30 \
                    -H 'Content-Type: application/json' \
                    --data-binary @- \
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
        turn_json="$(curl -q --config "$authenticated_curl_config" -fsS --connect-timeout 2 --max-time 30 \
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
    local task_version
    local turn_count=0
    local turn_id
    local turn_request

    mkfifo "$event_pipe"
    curl -q --config "$authenticated_curl_config" -sS -N \
        "$base_url/agent/tasks/$task_id/stream?after=0" \
        > "$event_pipe" &
    event_stream_pid=$!
    exec 3< "$event_pipe"
    event_stream_open=true
    interactive_started=true

    echo "ByteQuay $provider agent shell"
    echo "Task: $task_id"
    echo "Commands: /events, /backend-log, /help, /quit; Ctrl-C also quits"
    if ! read_turn_events; then
        return
    fi
    while IFS= read -r -p "you> " message; do
        case "$message" in
            "") continue ;;
            /quit|quit|exit) break ;;
            /help)
                echo "Enter a message, or use /events, /backend-log, or /quit."
                continue
                ;;
            /events)
                curl -q --config "$authenticated_curl_config" -fsS --connect-timeout 2 --max-time 30 \
                    "$base_url/agent/tasks/$task_id/events?after=0&limit=1000" \
                    | jq -r '.[] | "event> #\(.sequence) \(.type) \(.payload | tojson)"'
                continue
                ;;
            /backend-log)
                print_backend_diagnostics
                continue
                ;;
        esac

        turn_count=$((turn_count + 1))
        task_version="$(curl -q --config "$authenticated_curl_config" -fsS --connect-timeout 2 --max-time 30 \
            "$base_url/agent/tasks/$task_id?workspaceId=$workspace_id" | jq -er '.version')"
        turn_request="$(jq -n \
            --arg message "$message" \
            --argjson expectedVersion "$task_version" \
            '{message: $message, expectedVersion: $expectedVersion}')"
        turn_id="$(printf '%s' "$turn_request" \
            | curl -q --config "$authenticated_curl_config" -fsS --connect-timeout 2 --max-time 30 \
            -H 'Content-Type: application/json' \
            -H "Idempotency-Key: interactive-turn-$turn_count" \
            --data-binary @- \
            "$base_url/agent/tasks/$task_id/turns" | jq -er '.id')"
        if ! read_turn_events; then
            break
        fi
        print_turn "$turn_id"
    done
}

for command in curl git java jq mvn sqlite3 uuidgen; do
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
session_token="bytequay-agent-$(uuidgen | tr '[:upper:]' '[:lower:]')"
(
    umask 077
    printf 'header = "X-ByteQuay-Session: %s"\n' "$session_token" > "$authenticated_curl_config"
)
collect_diagnostic_secrets
codex_executable="${BYTEQUAY_AGENT_E2E_CODEX_EXECUTABLE:-codex}"
codex_enabled=false
claude_executable="${BYTEQUAY_AGENT_E2E_CLAUDE_EXECUTABLE:-claude}"
claude_enabled=false
# The app's own Codex home, so the shell reuses the login ByteQuay already holds.
codex_home="${BYTEQUAY_AGENT_E2E_CODEX_HOME:-$HOME/Library/Application Support/ByteQuay/codex-home}"
claude_home="${BYTEQUAY_AGENT_E2E_CLAUDE_HOME:-$HOME}"
case "$provider" in
    codex)
        codex_executable="$(command -v "$codex_executable")" || {
            echo "Missing required command: $codex_executable" >&2
            exit 1
        }
        codex_enabled=true
        ;;
    claude)
        claude_executable="$(command -v "$claude_executable")" || {
            echo "Missing required command: $claude_executable" >&2
            exit 1
        }
        claude_enabled=true
        ;;
esac

mkdir -p "$workspace_repository"
if [[ "$minimal_worktree" == false ]]; then
    git -C "$repository_root" ls-files -co --exclude-standard \
        | rsync -a --files-from=- "$repository_root/" "$workspace_repository/"
else
    printf 'agent e2e\n' > "$workspace_repository/README.md"
fi
printf '%s\n%s\n' "$workspace_id" "$workspace_repository_id" \
    > "$workspace_directory/.bytequay-workspace"
git -C "$workspace_repository" init -q -b main
git -C "$workspace_repository" config user.name "ByteQuay E2E"
git -C "$workspace_repository" config user.email "e2e@bytequay.local"
git -C "$workspace_repository" add .
git -C "$workspace_repository" commit -q -m "Initial commit"
git -C "$workspace_repository" remote add origin https://github.com/bytequay/agent-e2e.git
git -C "$workspace_repository" update-ref refs/remotes/origin/main HEAD

MAVEN_OPTS="${MAVEN_OPTS:+$MAVEN_OPTS }--sun-misc-unsafe-memory-access=allow" \
    mvn -q -f "$backend_root/pom.xml" -pl app -am -DskipTests package

(
    export BYTEQUAY_DESKTOP_SESSION_TOKEN="$session_token"
    if [[ "$provider" == claude ]]; then
        # Claude owns its login under HOME. The driver still supplies empty inherited
        # setting sources and a generated task-local settings file for every turn.
        export HOME="$claude_home"
    fi
    exec java --enable-native-access=ALL-UNNAMED -jar "$backend_root/app/target/bytequay-backend.jar" \
        --server.port="$port" \
        --server.shutdown=immediate \
        --spring.datasource.url="jdbc:sqlite:$database?journal_mode=WAL&busy_timeout=30000&foreign_keys=ON" \
        --spring.jpa.hibernate.ddl-auto=create \
        --spring.datasource.hikari.maximum-pool-size=1 \
        --spring.datasource.hikari.minimum-idle=1 \
        --logging.file.name="$temporary_directory/backend.log" \
        --bytequay.scheduling.enabled=false \
        --bytequay.agent.runtime-root="$temporary_directory/runtime" \
        --bytequay.workspace.root="$workspace_root" \
        --bytequay.agent.codex-enabled="$codex_enabled" \
        --bytequay.agent.codex-executable="$codex_executable" \
        --bytequay.agent.codex-home="$codex_home" \
        --bytequay.agent.claude-enabled="$claude_enabled" \
        --bytequay.agent.claude-executable="$claude_executable"
) > "$process_log" 2>&1 &
backend_pid=$!

for _ in {1..60}; do
    if [[ -z "$base_url" ]]; then
        actual_port="$(sed -nE 's/.*Tomcat started on port ([0-9]+).*/\1/p' "$process_log" | tail -n 1)"
        if [[ -n "$actual_port" ]]; then
            base_url="http://127.0.0.1:$actual_port"
        fi
    fi
    if [[ -n "$base_url" ]] && curl -q -fsS --connect-timeout 1 --max-time 2 "$base_url/hello" >/dev/null 2>&1; then
        break
    fi
    if ! kill -0 "$backend_pid" 2>/dev/null; then
        print_backend_diagnostics >&2
        exit 1
    fi
    sleep 0.5
done
curl -q -fsS --connect-timeout 2 --max-time 10 "$base_url/hello" >/dev/null || {
    print_backend_diagnostics >&2
    exit 1
}

sqlite3 -cmd '.timeout 30000' "$database" \
    "INSERT INTO watched_repo_workspaces (
        display_order,
        created_at,
        last_code_sync_at,
        last_metadata_sync_at,
        monitored_repository_id,
        selected_repository_id,
        updated_at,
        upstream_repository_id,
        default_branch,
        id,
        local_repository_path,
        monitored_owner,
        monitored_repo,
        name,
        origin_clone_url,
        selected_owner,
        selected_repo,
        topology,
        upstream_clone_url,
        upstream_default_branch,
        upstream_owner,
        upstream_repo)
    VALUES (
        0,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP,
        $workspace_repository_id,
        $workspace_repository_id,
        CURRENT_TIMESTAMP,
        NULL,
        'main',
        '$workspace_id',
        '$workspace_repository',
        'bytequay',
        'agent-e2e',
        'Agent shell E2E',
        'https://github.com/bytequay/agent-e2e.git',
        'bytequay',
        'agent-e2e',
        'DIRECT',
        NULL,
        NULL,
        NULL,
        NULL);"

provider_status="$(curl -q --config "$authenticated_curl_config" -fsS --connect-timeout 2 --max-time 30 \
    "$base_url/agent/providers/status")"
readiness="$(printf '%s\n' "$provider_status" | jq -r --arg provider "$provider" \
    '.[] | select(.laneId == $provider) | .readiness')"
if [[ "$readiness" != READY && "$readiness" != DEGRADED ]]; then
    sign_in_command="$(printf '%s\n' "$provider_status" | jq -r --arg provider "$provider" \
        '.[] | select(.laneId == $provider) | .signInCommand // empty')"
    echo "$provider is not ready (${readiness:-missing status})." >&2
    if [[ -n "$sign_in_command" ]]; then
        printf 'Sign in with: %s\n' "$sign_in_command" >&2
    fi
    exit 1
fi

model="${BYTEQUAY_AGENT_E2E_MODEL:-}"
effort="${BYTEQUAY_AGENT_E2E_EFFORT:-}"
if [[ -z "$model" ]]; then
    for _ in {1..120}; do
        model_settings="$(curl -q -fsS --connect-timeout 2 --max-time 30 \
            "$base_url/api/settings/models")"
        model="$(printf '%s\n' "$model_settings" | jq -r --arg provider "$provider" \
            '.providers[] | select(.id == $provider) | .selectedModel // empty')"
        if [[ -n "$model" ]]; then
            if [[ -z "$effort" ]]; then
                effort="$(printf '%s\n' "$model_settings" | jq -r --arg provider "$provider" \
                    '.providers[] | select(.id == $provider) | .selectedEffort // empty')"
            fi
            break
        fi
        sleep 0.5
    done
fi
if [[ -z "$model" ]]; then
    echo "$provider did not expose a model." >&2
    exit 1
fi

first_message=""
while [[ "$first_message" =~ ^[[:space:]]*$ ]]; do
    if ! IFS= read -r -p "first task> " first_message; then
        exit 0
    fi
done
task_id="$(uuidgen | tr '[:upper:]' '[:lower:]')"
create_idempotency_key="agent-e2e-create-$task_id"
task_request="$(jq -n \
    --arg taskId "$task_id" \
    --arg laneId "$provider" \
    --arg workspaceId "$workspace_id" \
    --arg message "$first_message" \
    --arg model "$model" \
    --arg effort "$effort" \
    --arg idempotencyKey "$create_idempotency_key" \
    '{
        taskId: $taskId,
        workspaceId: $workspaceId,
        laneId: $laneId,
        message: $message,
        idempotencyKey: $idempotencyKey,
        defaults: ({model: $model, permissionPolicy: "AUTO"}
            + if $effort == "" then {} else {effort: $effort} end)
    }')"
created_task_id="$(printf '%s' "$task_request" \
    | curl -q --config "$authenticated_curl_config" -fsS --connect-timeout 2 --max-time 30 \
    -H 'Content-Type: application/json' \
    --data-binary @- \
    "$base_url/agent/tasks" | jq -er '.id')"
if [[ "$created_task_id" != "$task_id" ]]; then
    echo "Backend returned another task ID" >&2
    exit 1
fi

interactive_session

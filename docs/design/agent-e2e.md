# Agent Shell End-to-End Test

> **Current behavior:** the script still submits a temporary caller-created
> worktree. CP3 in
> [Agent Service Implementation Checkpoints](agent-service-impl-tmp.md) converts
> it to create a temporary managed workspace and submit only its workspace ID;
> the backend then provisions and cleans the task worktree through the shared
> [Managed Worktree Service](worktree-service.md).

The agent shell exercises the complete backend path with either the Codex or
Claude CLI provider. The shell sends task, turn, approval, event, and status
requests only to the temporary Spring Boot backend. The backend owns the
provider process and converts its output into provider-neutral agent events.

## Run the shell

Run from the repository root. The selected provider CLI must already be
installed and authenticated. The script also requires `curl`, `git`, `java`,
`jq`, Maven, and `rsync` for the default full-worktree mode.

```bash
# Codex is the default provider
./scripts/agent-e2e.sh --interactive

# Use Claude Code
./scripts/agent-e2e.sh --interactive --provider claude
```

By default, the script copies the current checkout into a temporary Git
repository so the agent can inspect the ByteQuay code. Use a minimal temporary
repository when testing conversation and transport behavior without the
project files:

```bash
./scripts/agent-e2e.sh --interactive --provider codex --minimal-worktree
```

Enter as many messages as needed. The prompt waits for the current turn to
finish, prints its events and final response, and then accepts the next
message. Available commands are:

- `/events` — print all persisted normalized events for the task.
- `/backend-log` — print the latest backend log lines.
- `/help` — print the shell help.
- `/quit` — exit; `Ctrl-C`, `quit`, and `exit` also stop the shell.

The shell intentionally has no deterministic mode. Automated HTTP, replay,
SSE, idempotency, completion, and cleanup coverage lives in
`TestAgentHttpApi`; this script exercises a real provider only.

## Message and shutdown flow

```mermaid
sequenceDiagram
    actor User
    participant Shell as "agent-e2e.sh"
    participant API as "Spring backend"
    participant Turn as "DefaultAgentTurnService"
    participant Driver as "Codex/Claude driver"
    participant CLI as "Codex/Claude CLI"
    participant Events as "AgentEventService"
    participant Log as "FileTaskLogStore"

    Shell->>API: Start temporary backend
    Shell->>API: POST /agent/tasks
    API-->>Shell: taskId
    Shell->>API: GET /tasks/{taskId}/stream
    API-->>Shell: Persistent SSE connection

    loop Every message entered
        User->>Shell: Enter message
        Shell->>API: POST /tasks/{taskId}/turns
        API->>Turn: start(command)
        Turn->>Driver: Open or resume session
        Driver->>CLI: Execute turn in temporary worktree
        API-->>Shell: turnId

        loop Provider produces output
            CLI-->>Driver: Provider-specific typed message
            Driver->>Events: Normalized ProviderAgentEvent
            Events->>Log: Append durable JSONL event
            Events-->>Shell: Publish event through SSE
            Shell-->>User: Print tool, command, log, or final response
        end

        opt Provider requests permission
            Events-->>Shell: Approval event
            Shell-->>User: Ask whether to allow
            User->>Shell: Allow or deny
            Shell->>API: POST /agent/approvals/{approvalId}
            API-->>CLI: Provider decision
        end

        CLI-->>Turn: Turn result
        Turn->>Turn: Persist metrics and terminal state
        Events-->>Shell: TURN_COMPLETED
        Shell->>API: GET /agent/turns/{turnId}
        API-->>Shell: Terminal turn state
        Shell-->>User: Print state and next prompt
    end

    alt User enters /quit
        User->>Shell: /quit
        Shell->>Shell: Leave interactive loop
    else User presses Ctrl-C
        User->>Shell: SIGINT
        Shell->>Shell: Exit with status 130
    end

    Shell->>Shell: Stop SSE process
    Shell->>API: Send SIGTERM to backend
    API->>Driver: Close provider driver
    Driver->>CLI: Cancel and close provider process
    Shell->>Shell: Delete temporary worktree, database, runtime, and logs
```

Exiting the test shell does not call
`POST /agent/tasks/{taskId}/complete`. Its exit trap removes the isolated test
environment immediately, so shell shutdown does not exercise completion
summaries or seven-day retention cleanup.

After the managed-worktree migration, the fixture still owns and removes its
outer temporary application-data root, but it no longer creates or submits the
agent worktree itself. It provisions a saved workspace through backend APIs,
creates the task with `workspaceId`, verifies the generated branch from safe
task facts, and requests normal completion when the branch is clean so the
shared cleanup path is exercised. A dirty completion conflict is printed and
must never be converted into an implicit forced delete.

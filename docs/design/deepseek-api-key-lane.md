# DeepSeek API-key task lane

This approved design extends the current [Agent Service](agent-service.md)
with a DeepSeek key chosen for one agent task. The saved-key lane, its
automation paths, and shared Codex permission behavior have passed offline
verification. A live local-commit check has passed; the remaining opt-in
live-key checks are tracked in the implementation checklist. The current Codex
and Claude lanes remain available.

## Scope

A user saves a DeepSeek key in Models settings, selects that key and a DeepSeek
model when creating a task, and runs attended turns in the task's managed
worktree. The provider lane stays fixed for that task; later turns can change
to another supported DeepSeek model but cannot silently change keys.

The lane is selectable for manually created interactive worktree tasks. Its
new-task automation choices inherit the saved Workflow settings, like other
lanes. Finished development work is committed locally; Auto draft PR can
publish the verified revision, and Auto CI fix can attach the existing bounded
CI Autofix flow. The user can change these choices before starting the task,
and queued turns retain their accepted settings.

An explicit CI repair-agent choice can select a saved DeepSeek key. **Same agent
as development** inherits the task's saved-key lane, not its selected development
model. A model-less CI task uses the catalog default `deepseek-flash`, and the
readiness gate checks that exact model before run allocation. Development and
Review provider defaults, issue-backed starts, PR assignment, and other automatic
task starters do not select DeepSeek in this change. Comment rewriting stays
outside this change. OpenAI and Anthropic key lanes follow later.

This is an additional configuration of the existing Codex App Server adapter,
not a new model loop, agent service, or Maven module. The task, turn, worktree,
event, approval, replay, and cleanup contracts stay with the current service.

## Key identity and user flow

- Keep the existing encrypted key store and masked key display. Give each saved
  key a permanent opaque ID independent of its user-facing alias. Assign and
  persist an ID for every existing saved key. No migration-history
  framework is needed for the current Hibernate-managed schema.
- Represent an active key as a lane such as deepseek-key-<opaque-id>. The task
  already persists an immutable lane ID, so it needs no second credential
  field. The secret and alias never appear in task events or provider
  references.
- Show one provider choice per saved DeepSeek key, labeled
  **DeepSeek · alias**, in the existing task picker. Keep key management in
  Models settings. A newly added key appears without restarting ByteQuay.
  The picker offers only models the DeepSeek lane supports. Development and
  Review setting updates reject this lane, and their automatic default
  chooser continues to consider only the existing CLI lanes.
- Removing a key deletes its saved secret and blocks later turn admission on
  older tasks. A turn already admitted or running may finish; the user can
  Stop it if they need it to cease promptly. Historical transcripts and
  worktrees remain readable, and tasks show **DeepSeek key removed**.
  Re-adding the same alias creates a new ID and never rebinds those tasks.
  The first release does not replace a secret in place.

The application composition resolves DeepSeek lanes from the current key store
alongside the existing static CLI drivers. It does not add a general provider
plugin framework. Missing or removed lanes return a typed unavailable result
instead of failing the task screen's provider lookup.

## Codex App Server launch

Each active turn still owns one App Server process in its worktree and resumes
its existing provider thread in a later process. The DeepSeek launch uses a
ByteQuay-owned Codex home separated by key, with strict configuration and a
curated model catalog. It does not modify the user's Codex home or run
DeepSeek's setup script.

| Configuration | DeepSeek lane value |
| --- | --- |
| Model provider | deepseek |
| Codex base URL | Task-scoped ByteQuay proxy on 127.0.0.1 |
| Proxy upstream | https://api.deepseek.com/ |
| Wire API | responses |
| Credential source | Saved key held by ByteQuay's proxy, outside the Codex process |
| Model catalog | ByteQuay-owned catalog for deepseek-flash and deepseek-v4-pro |
| Web search | Shared Codex launch setting (`live` for attended turns, disabled for CI/text-only); no DeepSeek web-search capability advertised |
| Child agents | disabled for the first release |
| Tool permissions | Same Codex CLI permission calculation for the selected task scope |
| Task tools | Task-scoped loopback MCP bridge to the existing fixed ByteQuay tools |

Read the selected secret immediately before launching each App Server process.
Hold it only in a small task-scoped loopback Responses proxy. The proxy adds
the saved key to requests sent to DeepSeek and accepts only the Responses
requests that the Codex compatibility fixture proves necessary. Close it with
the App Server session. Codex receives a local provider URL; it never receives
the DeepSeek key in its environment, arguments, or config file.
An already running session may finish after its saved key is removed, while
the next turn fails admission. Provider output, errors, and logs use the
existing secret redactor. The isolation guard verifies the effective provider,
local endpoint, catalog, web setting, and common Codex permission settings
before a thread starts.

`AgentPermissionScope` derives the same provider-neutral decision from policy,
session access, and review ownership for Claude and both Codex-backed lanes.
`CodexLaunchProfile` derives the same process, thread, and turn sandbox and
approval settings for subscription and DeepSeek; `CodexCliDriver` sends
them without a provider-specific permission branch. Attended development uses
the current Codex `workspace-write` sandbox, ByteQuay approval routing,
validated linked-worktree Git roots, temporary-directory allowances, and
validated Maven, Gradle, and npm cache roots. Full development, review, and CI
worktree turns have command network access for dependency downloads. CI repair
uses `on-request` approval and the shared `GitWorktreeWriteRoots`. Text-only
and read-only scopes stay offline.
DeepSeek adds no named filesystem profile, developer-tool path exception,
model-facing shell denial of home or Git configuration, or separate
shell-network rule. Network access is turn-wide and cannot distinguish a build
download from a GitHub write; the shared application authorization rules still
apply. Its separate per-key `CODEX_HOME` and strict provider
configuration remain validated through Codex's effective configuration check.
Ordinary entries in the per-key Codex home do not block the lane. The saved API
key stays outside Codex through the task proxy; that credential boundary does
not change tool permissions.

The fixed development and CI tools use the existing task-scoped permission
broker through a loopback MCP bridge. ByteQuay keeps the broker bearer in its
own process; Codex receives only the local endpoint. The broker applies its
existing checks for each action: the current task, root readiness for a report,
the exact publication window for a draft, and the authorized run/job for CI
tools. The bridge closes with the App Server session. No general tool framework
or model-selected GitHub destination is added.

The offline Codex 0.156.1 fixture found that a model-requested native
`exec_command` inherited a key from the App Server environment even when
`config/read` showed the shell allowlist. The proxy keeps the saved key out of
that process boundary. This follows OpenAI's guidance to
[keep long-lived credentials outside self-hosted agent environments](https://developers.openai.com/api/docs/guides/agents-api/environments/security).

DeepSeek's [Codex integration](https://api-docs.deepseek.com/quick_start/agent_integrations/codex/)
documents the Responses provider and model catalog. ByteQuay supplies those
provider settings while reusing the current Codex CLI permission calculation.

## Models, readiness, and ordinary failures

The initial catalog exposes deepseek-flash and deepseek-v4-pro with only
capabilities verified for the installed Codex version. Replace the stale
DeepSeek model names currently shown in Models settings. The catalog supplies
Codex-specific model metadata; an authenticated DeepSeek
[GET /models](https://api-docs.deepseek.com/api/list-models/) checks that
DeepSeek accepts the key for that endpoint and lists the selected model.
It does not prove that a later Responses turn will succeed. Codex login
status is not a DeepSeek-key check.

The existing provider-readiness gate runs before task creation and each later
turn, before allocating a worktree or accepting a new turn. A missing or
invalid key is an actionable key error, not a request to sign in to Codex.
Temporary API failures have distinct diagnostics. If a turn reports
insufficient balance, an invalid key, or a model error despite the preflight,
retain the task and its history, report the failure without the secret, and
require an explicit user retry after the cause is resolved. Never resend a
turn automatically.

## Permission and capability boundary

DeepSeek's [Responses compatibility guide](https://api-docs.deepseek.com/guides/responses_api/)
says hosted web search is ignored and developer messages are treated as user
messages. That limits advertised model capabilities, not task command
permissions. The native Codex reviewer and the shared
[development command policy](agent-service.md#shared-development-command-policy)
govern attended commands for both Codex-backed lanes. Provider authentication
selects neither shell access nor GitHub authority.

The user-selected automation settings authorize only the existing bounded
follow-ups. The agent makes the local commit and chooses draft text, while
ByteQuay verifies the clean worktree, outgoing revision, repository, remote,
branch, and exact PR before publication. The scoped draft tool uses ByteQuay's
stored GitHub credential through its broker; it does not pass that stored
credential to Codex. Codex/DeepSeek agent processes do not receive GitHub token
variables, GitHub CLI config variables, or the SSH agent socket. CI repair uses
`on-request` approval and the shared Git worktree filesystem profile; ByteQuay
validates committed repairs and owns
exact-lease pushes, permitted reruns, and ready promotion under the
accepted CI scope and deadline. A missing or changed target stops the action.
No generated text alone authorizes a GitHub write.

Advertise only capabilities proven by the DeepSeek lane: development and CI
worktree turns, task-scoped tools, local commits, tool activity, approvals, and
resumable turns. Do not advertise web search, subagents, image input, or the
optional CodeGraph tool in this release. Hidden reasoning text must never enter
normalized events, storage, or the UI. Broader capabilities require a later
design change and a live compatibility gate.

## Implementation and acceptance

The change is confined to the current modules:

1. **core / persistence-sqlite:** durable key ID, encrypted-secret lookup by
   ID, and assignment of IDs to existing saved keys.
2. **app / agent-core:** live key-lane resolution, provider status, key-aware
   task and CI selection, accepted automation snapshots, and deletion/readiness
   behavior. Reuse the existing delivery coordinator and CI Autofix service.
   Keep the immutable task lane and worktree admission flow.
3. **agent-cli:** parameterize the Codex driver and launch profile for the
   DeepSeek lane; add the task-scoped Responses proxy and loopback task-tool
   bridge; update model catalog loading, common permission verification,
   redaction, and per-key runtime cleanup.
   The installed Codex binary is version-checked and upgraded once, not once
   per saved key.
4. **frontend:** show DeepSeek key lanes in the existing picker, expose the
   existing Automation and CI controls, and render invalid or removed-key
   recovery without losing historical tasks.

Offline tests cover lane binding, removal, readiness errors, provider settings,
permission parity with the current Codex CLI lane for attended and CI tasks,
proxy forwarding and closure, secret exclusion from the Codex process and
model-facing tools, task-tool bearer exclusion, attended local commits, broker
tool calls, accepted automation settings, event filtering, and resume against
the same lane. Scripted backend tests verify the shared scoped draft and CI
paths without contacting GitHub. An opt-in real-key gate must complete a
local edit and commit, approval, second turn, and cancellation. A live
GitHub-write gate remains separate and needs an explicitly authorized disposable
destination. Run backend `mvn verify` and the frontend lint, typecheck, test,
and package gates for the implementation.

## Later key lanes

OpenAI and Anthropic keys can reuse the saved-key identity and task picker.
Their runtime credential boundary and compatibility gate need separate design
and verification so no long-lived key reaches a model-facing shell. Neither
provider is part of this DeepSeek-first implementation.

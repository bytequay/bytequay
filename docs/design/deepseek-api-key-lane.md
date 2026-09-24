# DeepSeek API-key task lane

This approved design extends the current [Agent Service](agent-service.md)
with a DeepSeek key chosen for one agent task. The implementation has passed
offline verification; its opt-in live-key gate remains open. The current
Codex and Claude lanes remain the released behavior.

## Scope

A user saves a DeepSeek key in Models settings, selects that key and a DeepSeek
model when creating a task, and runs attended turns in the task's managed
worktree. The provider lane stays fixed for that task; later turns can change
to another supported DeepSeek model but cannot silently change keys.

The first release covers manually started, attended worktree tasks. Development
and Review defaults, PR assignment, CI Autofix, automatic delivery, and other
unattended starts will not select this lane until their permissions and
completion behavior have separate live evidence. Comment rewriting stays
outside this change. OpenAI and Anthropic key lanes follow later.

The task composer presents this lane as **No automatic delivery**. Task creation and
every later turn, including a queued turn or explicit settings override,
require DevelopmentAutomation.LOCAL_ONLY. This is a lane-specific check in the
backend: today's interactive-turn path otherwise enables auto-commit by
default even when task defaults request local-only work.

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
| Web search | disabled |
| Child agents | disabled for the first release |
| Tool filesystem | Named Codex profile: workspace write, minimal runtime reads, broad home reads and ByteQuay app data denied |

Read the selected secret immediately before launching each App Server process.
Hold it only in a small task-scoped loopback Responses proxy. The proxy adds
the saved key to requests sent to DeepSeek and accepts only the Responses
requests that the Codex compatibility fixture proves necessary. Close it with
the App Server session. Codex receives a local provider URL; it never receives
the DeepSeek key in its environment, arguments, or config file.
An already running session may finish after its saved key is removed, while
the next turn fails admission. Provider output, errors, and logs use the
existing secret redactor. The isolation guard must verify the effective
provider, local endpoint, catalog, web setting, shell allowlist, and other
current launch restrictions before a thread starts.

The DeepSeek tool profile uses Codex's named permissions: it denies filesystem
reads by default, allows the minimal system paths needed to run tools, and
allows worktree writes. It leaves shell networking disabled. App Server thread
and turn requests select this profile without a legacy sandbox override.
This keeps ByteQuay's SQLite data and credential master key outside the
model-facing shell's readable paths. The fixture must prove the effective
profile and a failed read of a fake ByteQuay credential file while a worktree
edit still succeeds. An approved command also remains unable to read that
file; the native fixture checks this before the lane is available.

The offline Codex 0.156.1 fixture found that a model-requested native
`exec_command` inherited a key from the App Server environment even when
`config/read` showed the shell allowlist. The proxy keeps the saved key out of
that process boundary. This follows OpenAI's guidance to
[keep long-lived credentials outside self-hosted agent environments](https://developers.openai.com/api/docs/guides/agents-api/environments/security).

DeepSeek's [Codex integration](https://api-docs.deepseek.com/quick_start/agent_integrations/codex/)
documents the Responses provider and model catalog. ByteQuay will generate
its own minimal strict profile and point its provider at the task proxy.

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
messages. The current attended GitHub scope rules rely partly on provider
instruction following. The first DeepSeek lane therefore uses the existing
workspace-write sandbox with shell network disabled and sends command
escalations to the user reviewer rather than automatic review. The DeepSeek
profile sets on-request approval and the user reviewer consistently at launch,
thread start/resume, and turn start; the isolation guard verifies those
effective values. It does not inject GitHub authentication environment
variables or run unattended publication. Any network write needs a direct
user approval. Existing ByteQuay UI actions remain the route for explicit PR
publication and review. This narrower profile follows DeepSeek's API behavior,
not API-key authentication itself.

Advertise only capabilities proven by the DeepSeek lane. Start with local
editing, tool activity, approvals, and resumable turns. Do not advertise web
search, subagents, image input, or the optional CodeGraph tool in the first
release. Hidden reasoning text must never enter normalized events, storage, or
the UI. Broader capabilities require a later design change and a live
compatibility gate.

## Implementation and acceptance

The change is confined to the current modules:

1. **core / persistence-sqlite:** durable key ID, encrypted-secret lookup by
   ID, and assignment of IDs to existing saved keys.
2. **app / agent-core:** live key-lane resolution, provider status, key-aware
   task selection, enforced local-only turn settings, and deletion/readiness
   behavior. Keep the existing immutable task lane and worktree admission
   flow.
3. **agent-cli:** parameterize the Codex driver and launch profile for the
   DeepSeek lane; add the task-scoped Responses proxy, and update model catalog
   loading, isolation verification, redaction, and per-key runtime cleanup.
   The installed Codex binary is version-checked and upgraded once, not once
   per saved key.
4. **frontend:** show DeepSeek key lanes in the existing picker and render
   invalid or removed-key recovery without losing historical tasks.

Offline tests cover lane binding, removal, readiness errors, strict App Server
configuration, proxy forwarding and closure, the worktree-only tool read
boundary, secret exclusion from the Codex process and model-facing tools,
event filtering, and resume against the same lane. An opt-in real-key gate
must complete a local edit, approval, second turn, cancellation, and an
attempted network write that cannot proceed without direct user approval.
Run backend mvn verify and the frontend lint, typecheck, test, and package
gates for the implementation.

## Later key lanes

OpenAI and Anthropic keys can reuse the saved-key identity and task picker.
Their runtime credential boundary and compatibility gate need separate design
and verification so no long-lived key reaches a model-facing shell. Neither
provider is part of this DeepSeek-first implementation.

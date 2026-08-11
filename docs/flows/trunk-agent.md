# Trunk Agent

Status: normative greenfield replacement specification
Parent architecture: [Development-flow architecture](./README.md)

## 1. Purpose

The Trunk Agent is the user's repository-level conversational entry point. It turns
an initial request into one confirmed goal and starts one durable Task.

Trunk exists because the user should be able to begin work, clarify intent, and see
later attention notifications without first navigating to a Task page. Its semantic
responsibility ends when the goal is clear. It is not the parent process of the
[Task Agent](./task-agent.md), and the Task Agent does not inherit hidden Trunk
conversation context.

This is a new component. It does not call, wrap, mirror, or translate an earlier
workflow. There is no compatibility mode, dual write, data migration, or fallback to
an older task starter.

## 2. First-principles result

Starting autonomous development requires exactly four facts:

1. which repository is in scope;
2. what outcome the user wants;
3. which material constraints or exceptions the user accepted;
4. that the user intends to start work now.

Only the agent and user can establish the meaning of the request. The program should
validate the tool call and create durable work; it should not classify conversational
prose or parse a completed agent response.

Therefore Trunk has one irreversible workflow tool:

```text
start_task(goal)
```

`goal` is a single, self-contained natural-language statement. It must include any
accepted constraint or exception that would materially change implementation. The
repository is bound by the current Trunk context, not repeated in model output.

## 3. Non-goals

- Planning or implementing the code change.
- Creating a child coding session directly.
- Choosing a branch, worktree path, base commit, execution capacity, or model run ID.
- Producing a machine-read alignment verdict.
- Editing repository files or running tests.
- Approving local review, publication, GitHub replies, thread resolution, or merge.
- Monitoring CI or GitHub.
- Relaying messages between active agents.
- Treating the entire Trunk transcript as implicit Task instructions.

## 4. Owned durable data

The agent owns no workflow aggregate. The program already stores normal conversation
messages. On successful `start_task`, the [Workflow Runtime](./workflow-runtime.md)
stores the exact goal once on the new `Task`, plus its start `Operation`, dispatch
ticket, and a best-effort post-commit in-process wake. That start operation is
`PROVISION_TASK`; it does not start a model. Do not add a second
Task-start-command or wake-outbox table containing the same facts.

The program derives an idempotent `requestKey` from the authenticated tool invocation.
That key is audit/duplicate-delivery metadata; it is not a request for another agent
to replay or interpret the Trunk transcript. The Task Agent's semantic input is the
confirmed `goal` plus repository facts it reads itself.

Trunk completion prose is stored automatically as opaque conversation content. No
program transition depends on its wording.

## 5. Exact inputs

Before each Trunk turn, the program supplies:

- current repository identity and display name;
- visible user/Trunk conversation;
- whether a `start_task` command has already succeeded in the current run;
- access to repository-scoped [Project Intelligence](./project-intelligence.md);
- notifications for Tasks that need user attention.

Trunk is not preloaded with a generated project summary. It queries source-cited
knowledge when relevant and can ask the user to clarify uncertainty.

## 6. Lifecycle: start to stop

1. **Receive request.** The user speaks to repository Trunk.
2. **Check clarity.** Trunk identifies the desired outcome, affected scope, observable
   acceptance result, and any material permission or design choice. It does not force
   a ceremony when the user's request is already precise.
3. **Consult project context when relevant.** Trunk calls
   `search_project_context(query, path_hints?)` for architectural or policy-sensitive
   requests. It cites the result and explains possible alignment, tension, or
   uncertainty in ordinary language.
4. **Clarify with the user.** If a missing choice would materially change the Task,
   Trunk asks before starting. The user's answer is incorporated into the confirmed
   goal. Project Intelligence never answers for the user.
5. **Choose the explicit entry.** For ordinary development, Trunk calls
   `start_task(goal)` once. For upstream range import, it calls
   `request_upstream_sync_preview(...)`; no Task exists until the user reviews and
   confirms the exact preview digest.
6. **Provision asynchronously.** A normal start immediately returns a generated
   `taskId`; the [Workflow Runtime](./workflow-runtime.md) creates its one branch and
   isolated worktree. An upstream preview returns `requestId`; after the separate
   user confirmation, the runtime creates the Task at the resolved target base and
   provisions it through the same durable path.
7. **Acknowledge.** Trunk exposes the Task link/status or pending preview link. It
   does not wait for implementation or become an agent-message bus.
8. **Stop this responsibility.** The Trunk turn ends. Later user-gate or failure
   notifications may appear in Trunk/Home, but detailed work continues on the single
   Task page.

## 7. Agent tools and program APIs

### Trunk tool surface

| Tool | Exact contract |
|---|---|
| `search_project_context(query, path_hints?)` | Returns current source-cited project notes; advisory only |
| `start_task(goal)` | Validates one nonblank confirmed goal and atomically accepts one normal Task-start command |
| `request_upstream_sync_preview(goal, source_remote, from_exclusive, to_inclusive, target_ref)` | Optional structured upstream-sync entry; persists a preview operation and returns its request ID without creating a Task or running Git |

Trunk has no repository-write, shell, Git, check, subagent, review, timeline, gate,
GitHub, push, reply, resolution, merge, or cleanup tool. The upstream-preview
tool supplies user-visible refs only; the dispatched program resolves them to
immutable SHAs and the user must confirm the exact preview before a Task exists.

### `start_task` validation

The tool rejects the call immediately when:

- `goal` is absent, blank, or above the configured text size limit;
- the Trunk is not bound to a live repository;
- the same run already started a different Task;
- repository launch is administratively disabled.

This is normal tool validation while the agent is still active. It is not parsing the
agent's eventual prose. The agent can correct a rejected call in the same turn.

### Program APIs

| API | Responsibility |
|---|---|
| `TaskCommands.startTask(requestKey, repositoryId, goalText)` | Idempotently persist Task identity, exact goal, one `PROVISION_TASK` operation, and its ticket; the authenticated `requestKey` already links the audit conversation, and the program sends a best-effort wake after commit |
| `UpstreamSyncCommands.requestPreview(requestKey, repositoryId, goalText, sourceRemote, fromExclusive, toInclusive, targetRef)` | Optional path: persist a pre-Task preview operation/ticket; the dispatcher performs Git work and the user later confirms the stored digest through the Upstream Sync contract |
| `TaskProvisioning.execute(claim)` | Resolve the frozen configured base ref locally, bind its exact SHA, create and prove the derived branch/worktree, and only then create the persistent Task Agent session and pending initial work |
| `WorkflowCommands.enqueueTurn(taskId, INITIAL, launchManifest)` | Normal Tasks only: persist the initial work fact and ensure reconciliation after provisioning; `WorkSelector`, not this call, creates the first Task writer run |
| `TaskAttentionService.listForRepository(repositoryId)` | Surface user-gate/failure notifications without waking Trunk |

`TaskCommands.startTask` returns after durable acceptance; it does not hold the Trunk
turn open while the agent works.

The executable provisioning owner is composed, but `start_task` and
`TaskCommands` are not yet connected to production. Current production creates
no greenfield Task. Provisioning performs no fetch, credential use, provider
call, or model call; fresh remote synchronization and Trunk admission remain a
later cutover prerequisite.

The optional upstream tool ends the Trunk turn at a durable preview request. It
does not call `start_task`; the exact confirmation command in
[Upstream Sync](./upstream-sync.md) creates the Task at the program-resolved target
base. This separate entry avoids hiding typed Git refs inside a free-text goal.

## 8. Contracts with other components

### [Project Intelligence](./project-intelligence.md)

- Trunk queries it only when context is relevant.
- Trunk, not the program, applies notes to the user's request.
- If notes conflict or are uncertain, Trunk explains that and asks the user.
- The final accepted choice is written into `goal`; no hidden judgement record is
  passed downstream.

### [Workflow Runtime](./workflow-runtime.md)

- Runtime owns command idempotency, Task identity, provisioning, durable dispatch,
  capacity, leases, recovery, and cancellation.
- Trunk invokes one command and receives facts. It cannot call an agent process
  directly or retry provisioning by creating another Task.

### [Task Agent](./task-agent.md)

- Task receives the exact confirmed goal, repository/worktree facts, and its own
  query/read capabilities.
- Task does not receive the complete Trunk transcript, Trunk's hidden reasoning, or a
  generated implementation plan.
- If Task discovers a material ambiguity, it uses `request_user_input` through the
  Task page instead of asking Trunk to reinterpret the goal.

### [PR Timeline](./pr-timeline.md) and [User Gates](./user-gates.md)

- The program projects Task creation into the durable timeline. Trunk never writes a
  timeline event.
- A Task needing local review, feedback approval, or merge approval creates a user
  notification visible from Trunk/Home.
- Trunk cannot authorize a gate on the user's behalf. Gate authorization is a direct
  authenticated UI command over an exact gate revision.

## 9. Invariants

1. One successful `start_task` call creates exactly one Task.
2. Retrying the same accepted call returns the same `taskId`.
3. Task launch always goes through durable dispatch; a controller or agent never
   starts a coding process directly.
4. A Task has one branch and one worktree; failure to establish isolation fails the
   launch.
5. The confirmed goal is the only agent-authored semantic launch payload.
6. All identifiers, repository bindings, timestamps, execution policy, and launch
   state are program-owned.
7. Trunk final prose is opaque and cannot start or mutate a Task.
8. Project Intelligence availability or interpretation never gates `start_task`.
9. Trunk cannot approve any external effect.
10. An upstream preview creates no Task; only the user's exact preview
    confirmation may create one.

## 10. Failure and recovery

| Failure | Required behavior |
|---|---|
| Malformed `start_task` call | Return an immediate tool error; keep Trunk active so it can retry |
| Duplicate delivery/retry | `requestKey` returns the existing Task and `taskId` |
| Program stops after Task/start-operation commit | Dispatcher resumes provisioning from the durable operation |
| Branch/worktree creation fails | Mark launch failed, clean only proven partial resources, and notify user; never fall back to a shared workspace |
| Initial Task-Agent dispatch fails | Keep Task and worktree durable; retry the dispatch without creating a new Task |
| Project Intelligence unavailable | Disclose unavailability; inspect repository sources or clarify with user; do not invent an alignment verdict |
| User changes goal before launch dispatch | Cancel the unstarted Task and create a new confirmed Task only through another explicit `start_task` call |
| User changes goal after work begins | Record a user decision in the Task; do not mutate historical launch input or silently create a sibling Task |

## 11. Acceptance traces

### A. Clear request

1. User gives a precise goal.
2. Trunk needs no extra ceremony and calls `start_task(goal)`.
3. Tool returns `taskId=T1`, state `PROVISIONING`.
4. Program creates branch/worktree and durably dispatches Task Agent.
5. Trunk exposes T1 and ends its turn.

### B. Request conflicts with repository guidance

1. Trunk queries Project Intelligence and receives a source-cited note.
2. Trunk explains the tension; no program verdict is created.
3. User chooses the documented direction or explicitly accepts a scoped exception.
4. Trunk includes that choice in the self-contained `goal` and calls `start_task`.
5. Task can independently re-read the source.

### C. Wrong tool shape

1. Trunk calls `start_task` with a missing goal.
2. Tool returns a validation error immediately.
3. Trunk corrects the call while still active.
4. Program never examines Trunk's final message for a substitute payload.

### D. Crash after acceptance

1. Task T1 and its start operation commit.
2. Process stops before worktree provisioning.
3. On restart, dispatcher claims the same command and provisions T1 once.
4. No duplicate Task or live agent appears.

### E. Task later needs approval

1. Task opens an exact user gate.
2. Notification appears in Home/Trunk without waking a Trunk model run.
3. User opens T1 and reviews the actual artifacts.
4. Only the user's gate command authorizes the external effect.

## 12. Evidence: accept, merge, reject

| Source | Decision | Reason |
|---|---|---|
| [Grok subagents](https://github.com/xai-org/grok-build/blob/8a14c91d88875a831a38b3a066b1683116bcb31c/crates/codegen/xai-grok-pager/docs/user-guide/16-subagents.md) | **Accept** keeping tight user back-and-forth in the primary conversation | Intent clarification is interactive and loses value when delegated |
| [Codex subagents](https://learn.chatgpt.com/docs/agent-configuration/subagents) | **Merge** explicit child/run boundaries with ByteQuay's durable Task boundary | A Task should have visible identity and lifecycle, not be an invisible continuation of Trunk prose |
| [Codex repository instructions](https://developers.openai.com/codex/guides/agents-md/) | **Accept** agents reading scoped repository instructions themselves | Task should verify the checkout rather than inherit a potentially stale Trunk summary |
| Full-conversation inheritance | **Reject** | It mixes exploratory discussion and discarded options into implementation input; the confirmed goal is the smaller reliable contract |
| Program-generated intent classification | **Reject** | It requires semantic parsing and makes the program decide something only the user/agent can judge |
| Trunk as a live parent/relay | **Reject** | It adds another wake-up and handoff to every downstream event without adding authority or context |

## 13. Tradeoffs

- A single goal is easier to reason about than inherited chat context, but Trunk must
  restate material accepted decisions clearly.
- Asynchronous provisioning makes Task start resilient, but the user may briefly see
  `PROVISIONING` rather than immediate agent output.
- Trunk/Home notifications improve discoverability, but the detailed review still
  happens on the Task page where exact artifacts are visible.
- Failing launch when worktree isolation fails is less convenient than a shared-space
  fallback, but it preserves the one-writer safety contract.

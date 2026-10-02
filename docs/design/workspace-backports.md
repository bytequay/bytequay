# Workspace references and backport chains

Status: approved design; implementation has not started. The
[temporary checklist](workspace-backports-impl-tmp.md) tracks delivery.

Keep one workspace per repository. Add references for reading related code,
a handoff to another workspace's normal conversation, and a saved backport
chain. Existing development, cherry-pick, PR delivery, CI, and rebase services
continue to perform the work.

## 1. User flows

### Start from P2

The user asks in a P2 conversation: "Check whether this issue also exists in
P4. If it does, fix it there, then backport through P3 to P2."

1. The P2 agent reads P4's code and notes through a configured reference.
2. If the bug exists in P4, ByteQuay starts a fresh P4 conversation with a
   focused problem description. If not, it reports the finding without
   starting the upstream fix or backport chain.
3. P4's existing development flow produces its PR and handles CI.
4. After that PR merges, the existing cherry-pick flow runs in P3 and
   produces the P3 PR.
5. After the P3 PR merges, the same flow runs in P2 using P3's landed changes.

This produces one upstream fix PR and two backport PRs. Each workspace owns
its worktree, agent execution, credentials, target branch, and PR.

### Start from P4

The user starts an ordinary P4 conversation and asks for the fix. Before or
after its PR merges, they can add: "After this merges, backport to P3, then
backport P3's landed changes to P2."

Both cases join the same chain:

```text
P4 conversation -> P4 PR
                     | merged
Existing cherry-pick in P3 -> existing push / PR / CI
                     | P3 PR merged
Existing cherry-pick in P2 -> existing push / PR / CI
                     | P2 PR merged
Complete
```

Starting a conversation remains the way to request development. There is no
new "create task" button. Backports reuse the existing cherry-pick execution
and its conflict agent; they are not wrapped in another development conversation.

## 2. Reuse boundaries

| Existing part | Responsibility retained |
| --- | --- |
| `InteractiveAgentTaskCreator` and turn admission | Create the destination conversation and its managed worktree |
| `DevelopmentFollowupCoordinator` | Commit finished development, publish its PR, repair CI, and mark ready |
| `CherryPickPreviewService` / `CherryPickExecutionService` | Freeze and validate the selection, apply commits, resolve conflicts, account for results, and recover execution |
| `WorkspacePullRequestService` | Push the generated backport branch and create its PR |
| `CiAutofixService` / `BranchRebaseService` | Own CI repairs, required rebases, pushes, and their existing recovery |

The chain coordinator only decides when to invoke these services. It does not
run Git mutations itself, duplicate their states, or hold an agent process
open while waiting for a merge. The existing development continuation stays
in-process and retains its current restart behavior.

References do not change the existing workspace `origin` / `upstream` routing.
That routing determines development and PR destinations. A P2 reference to
P3 must not silently turn P3 into P2's development base or PR target.

Related contracts: [workspaces](watched-repo.md),
[agent service](agent-service.md), [development delivery](development-delivery.md),
[cherry-pick](cherry-pick-range.md), [CI Autofix](ci-autofix-service.md),
[rebase](rebase-service.md), and [repository memory](repository-memory.md).

## 3. Workspace references

Store a small list in workspace settings. Each entry contains the referenced
workspace ID and a short, user-editable explanation of the relationship and
when to consult it. Workspace names are display labels, not identities.

```text
P1 references P2
P2 references P3 and P4
P3 references P4
P4 references none
```

References are explicit, directional, and non-transitive. Reject self-links
and duplicates. Each referenced workspace must exist and be readable by the
user. References are not a build dependency graph and do not grant publication
authority. A user may request a backport to another accessible workspace
without giving the source agent access to that destination's notes.

At the start of a development turn, ByteQuay supplies the current workspace,
the direct reference list, each explanation, and concrete reading instructions:

> P2 adds internal changes above P3; P4 is the original Trino project.
> Start with P2. Consult P3 or P4 for inherited behavior, then check P2's
> differences before applying that explanation here.
> Reference code is available at the listed paths and revisions. Use normal
> file/Git tools to read it. Use the context-note tool with the reference's
> workspace identifier to read its notes. Edit only the task worktree.

Resolve paths on the backend from existing managed repositories. Include the
resolved repository identity and commit for each reference; use ordinary Git
reads at that commit when the shared checkout may change. Reuse the existing
sync/read services rather than creating reference worktrees or another index.
An unavailable reference is reported as unavailable, never silently replaced.

Extend the existing notebook tool's list, search, and exact-read operations
with an optional reference workspace selector. Omission means the task's own
workspace. Check direct membership and existing context enable settings on
each call. Return source workspace, repository, commit, and note version;
include the workspace in lookup keys and receipts to avoid collisions.

Check a P4 note against the captured P4 revision, not the P2 task HEAD. That
check does not prove the explanation applies to P2. Reference access exposes
read operations only; note proposals, edits, grades, and learning requests
continue to belong to the task's own workspace. Do not widen cherry-pick or
CI helper permissions as a side effect of adding development references.

Detailed guidance stays in existing notes. For example, a P2 Iceberg note can
point to a P4 note for inherited behavior and a P2 note for internal changes.
Reuse existing note identity/version lookup; add no separate concept graph or
automatic note-copying system. Referenced text remains source material, not
authority to change the task's instructions or permissions.

This feature controls supplied context and tool access. It deliberately keeps
today's filesystem permissions; it does not promise that an agent cannot
discover other local repositories through its existing shell access.

## 4. Conversation handoff

Add a fixed task-bound operation for the current root conversation to request
work in a named workspace. Reuse the existing local-tool transport, conversation
creator, first-turn admission, provider readiness, and destination settings.
Creation uses a stable request key so a repeated tool call returns the same
destination conversation.

The original user request must authorize the destination and the work. An
agent's own suggestion is not authorization. The new conversation receives a
focused problem statement and selected supporting evidence, never the full
source transcript, its reference list, or its private notebook by default.
The application can retain a private source/destination link for navigation.

Keep the original Jira/GitHub issue association with the original conversation.
Use ordinary conversation creation for the handoff; do not reassign the ticket
or duplicate its current single-conversation association. Pass only the issue
information needed and authorized for the destination work.

Starting from P2 does not make P2 the owner of P4's work. P4 receives its own
workspace context and direct references. P4's published PR can become the
source of the saved chain without another user submission.

## 5. One saved chain

Add one `BackportChain` aggregate and storage port in `core`, its SQLite
adapter in `persistence-sqlite`, and a coordinator in `app`. Use the existing
Hibernate schema conventions. The ordered steps can be stored with the chain;
there is no separate generic workflow, step engine, or Maven module.

The record holds only facts needed to wait and resume safely:

| Fact | Purpose |
| --- | --- |
| Chain ID, request key, source conversation and/or PR | Attach the request before publication or to an existing PR without duplicate creation |
| Ordered destinations with workspace, repository identity, and branch | Freeze the requested route; never retarget after settings changes |
| Accepted user request and execution choices | Preserve authority for the named operations and the selected agent/settings |
| Per-step landed source and existing execution/PR IDs | Reuse real results and start each backport once |
| Stopped flag, blocking reason, record version | Stop dispatch, explain a hold, and prevent conflicting updates |

The current step is the first one without a verified merged destination result.
Derive display progress from existing execution, delivery, CI, and PR facts.
Do not copy their phase/status fields into another lifecycle. The final PR
being open is shown as waiting for merge; the chain completes after it merges.

A fixed conversation operation saves or appends backport destinations using
the existing tool transport. Bind it to the accepted user turn, current root,
source, and exact destinations. Appending changes only unstarted work and
requires the new user request. Generated messages cannot add destinations or
expand write authority. A chat promise alone does not create a chain.

The chain request authorizes cherry-pick, branch publication, PR creation, and
the existing bounded CI actions in those destinations. Provider approvals,
credentials, and current repository permission checks still apply. This
extends the existing delivery confirmation boundary with saved chain authority;
it does not make every background caller implicitly confirmed.

The request does not authorize automatic PR merges. Continue to use the
existing user/reviewer merge path, and advance only after fresh GitHub evidence
that the exact predecessor PR merged. Closed without merge blocks the next step.

## 6. Advance through existing services

A small monitor follows the existing `MonitorLoop` pattern. It checks the
exact PR currently awaited, with existing rate-limit and scheduling policies,
independently of whether a workspace panel is open. Reuse GitHub readers;
do not redesign general PR refresh or keep a conversation running to poll.

The coordinator advances one chain under the existing locking/store conventions:

1. Check that the chain is enabled and its recorded destination still matches
   the workspace's actual routing. Resolve a source conversation's PR through
   verified publication facts; never guess from its title or branch name.
2. Once that exact PR is merged, resolve and save its landed changes.
3. Resolve the source through the destination's existing remote-source support,
   synchronize, and call the existing preview and execution services. Use a
   stable chain/step execution key and reuse any already-created execution.
4. When the execution completes successfully, call existing push and PR
   delivery under the saved authority, then reuse CI Autofix. Store result IDs.
5. Wait for that PR to merge. Its landed result becomes the next step's source.

The executor retains immutable source order, presence checks, exact target
checks, conflict history, and worktree cleanup. A moved target invalidates a
preview. Under the saved chain request the coordinator may create a fresh
preview for the same frozen source and destination, but must not start from
stale SHAs or widen the selection. Existing manual confirmation behavior stays
unchanged. Missing source routing blocks with an actionable configuration error;
never replace the destination's contribution upstream to make the fetch work.

Backport delivery currently omits automatic CI for linked-upstream workspaces.
Complete that connection in the existing delivery service, including its CI
status projection, using the CI service's current fork checks and stable run
key. Do not create a second CI loop in the chain coordinator. Preserve the
existing backport PR type rather than wrapping it in development draft promotion.

## 7. Resolve what actually landed

Add one narrow helper that turns a verified merged PR into exact source commits
accepted by the existing cherry-pick preview. Retain the source repository,
PR, branch, merge identity, and ordered landed commits with that result.

Read fresh merge facts and verify the commits in fetched source history.
Squash and rebase merges can change commit IDs, so the old PR head and its
pre-merge commit list are not sufficient. Include adaptations and conflict
fixups that became part of the merged result. Commit-to-PR grouping remains
display metadata; it is not alone a proof of the landed range.

Test each supported merge shape with real Git history and recorded provider
responses. The current executor accepts a range of non-merge commits. Do not
add merge-commit replay, silently flatten history, or include unrelated commits
to fit that interface. If an exact compatible range cannot be established,
pause for explicit source selection. Reuse the existing preview for that choice.

For P4 -> P3 -> P2, resolve the P3 PR after it merges. Never substitute the
original P4 range, the old P3 task head, or only a cherry-pick item's target SHA:
the existing conflict result can include its adjacent fixup commit.

If all changes are already present or the resulting change is empty, do not
create an empty PR. A final step can report that verified no-op as complete.
An intermediate step can advance only with an exact landed destination source
for the next hop; otherwise pause for selection rather than bypassing P3.

## 8. Stop, restart, and uncertain results

Persist the chain before it dispatches work. Use stable request/execution keys
and existing IDs to cover the gap between dispatch and saving its result.
Reject changed parameters on a reused request key. Check saved authority and
the stopped flag before every new external action.

On restart, waiting chains resume checks and adopt existing execution/PR facts.
Running cherry-pick and standalone CI retain their existing recovery rules.
The chain does not restart a failed development continuation or an exhausted
CI run. Existing active work and writer-ownership checks remain authoritative.

An uncertain push or PR-create response never justifies a blind retry. Check
the exact destination branch, commit, and PR identity through existing readers.
Adopt only a proven match; otherwise block for inspection. Do not add an
automatic retry framework or claim saved execution IDs alone solve remote
publication uncertainty.

Stop records the stop before further dispatch and uses existing stop/cancel
operations for active work owned by the chain. It does not stop the independent
source conversation, close PRs, or discard files/branches. Resume is explicit
and rechecks the current facts and authority. Missing/deleted workspaces or
changed routing block; cleanup/deletion must preserve or explicitly stop
dependent chains rather than silently retargeting them.

## 9. Small UI and delivery order

Workspace settings lists references and their explanations. Reuse existing
code/commit/PR/context views for browsing each reference, clearly identifying
the displayed workspace. A conversation shows the requested route and links
to the existing execution, PR, or blocking reason for each step, with Stop and
Resume through the existing controls. It does not introduce a workflow canvas
or a new development entry point.

Implement and verify the chain from an existing PR first. Then add reference
context and the P2-to-P4 handoff using the same chain. The temporary checklist
defines five implementation checkpoints; those checkpoints are not runtime states.

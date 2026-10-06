# Workspace relationships and backport chains

Status: implemented and verified with local Git, provider and browser fixtures.
All five checkpoints are complete; no live GitHub writes or merges were used.

Keep one workspace per repository. Add two relationship kinds, let a
conversation read and request work in a related workspace, and put a small
saved chain above the existing cherry-pick flow.

## 1. Two relationship kinds

| Relationship from our workspace | Example | What it allows |
| --- | --- | --- |
| Fork | Private Trino -> Apache Trino | Read code and memory, compare versions, investigate there, and backport into our workspace when requested |
| Dependency | Trino -> Apache Iceberg | Read code and memory, compare versions, and investigate there; no cherry-pick or backport between them |

A fork means our source builds on that project. It need not be a GitHub fork.
There is no third relationship kind. A Fork can be selected as the default
backport source for its destination; a Dependency cannot.

Save a list in the existing workspace settings through `AppSettingsStore`.
Each entry has `workspaceId`, `kind` (`FORK` or `DEPENDENCY`),
`backportByDefault`, and `guidance`: why this workspace matters and when to consult it. Put version
hints in that guidance, such as "Iceberg's version is declared in pom.xml";
do not add a separate version configuration or dependency lockfile.

For the private fork chain:

```text
P1 --dependency--> P2
P2 --fork-------> P3 and P4
P3 --fork-------> P4
P4 has no outgoing relationships
```

Only direct outgoing relationships supply context. P2 can read P3 and P4;
P3 cannot read P2 through this feature. Do not follow relationships recursively.
Reject self-links, duplicate destinations, and unavailable workspace identities.
Changing a relationship does not change the other workspace's configuration.

A relationship names the related workspace's monitored project. Resolve its
managed checkout and logical repository identity on the backend; a personal
`origin` used for publishing is not a different project to consult. Existing
`Workspace.Topology`, `origin`, and `upstream` still control contribution
routing. Adding a relationship never changes them. A backport PR must target
the requested project's branch; if existing routing points elsewhere, report
the mismatch instead of silently redirecting the work.
For an internal fork that should receive its own PRs, use the existing
upstream configuration to make that project the monitored destination first.
`setUpstream` already supports changing or clearing that contribution link;
this feature adds no second PR-routing mechanism.

Each chain hop requires a direct Fork relationship from destination to source:
P3 -> P4 permits the P4-to-P3 hop; P2 -> P3 permits the next hop. The backend
can validate this without giving P4 access to downstream memory. Dependency
relationships prohibit transfers between the linked repositories in either
direction. Check actual repository identities in shared cherry-pick admission
and delivery, so a manual request or another remote name cannot bypass this.
Existing manual routes outside these relationships keep their current behavior.

## Explicit backport requests

`@backport` at the beginning of a user message marks a backport request in the
new-task and conversation composers. Only Workspace Home and the Tasks tab's
new-task composer offer a **Backport** pill beside the repository avatar and a
suggestion list for a leading `@` or matching prefix such as `@backp`. Click,
Enter or Tab selects the suggestion; Escape dismisses it. The pill and the
Backport chains example fill and focus the composer, preserve the rest of the
draft and avoid duplicating the marker. Selection never sends a message;
only Send starts work. Provider readiness gates Send, not drafting or the
Backport shortcut. Plain-language backport requests remain supported.

In Related workspaces, **Use as default backport source** selects one Fork
source per destination. This setting supplies routing, never standing authority
to copy changes or publish. A user request such as `@backport oss/repo #1234`
can follow these saved edges. `@backport #1234` uses an unambiguous source from
the current workspace and configured route; otherwise ask which repository.
Explicit destinations restrict the request, including only necessary intermediate
hops. Repository names or aliases must resolve unambiguously before saving.

The root-bound `DISCOVER_BACKPORT` operation returns watched workspace names,
repository identities and Fork edges with default flags. It does not expose
transitive code, guidelines or memory. The agent reads the saved graph before
interpreting order. Siblings backport from the same source independently; a
nested destination consumes its parent's landed result after merge. If words
such as "then" conflict with saved sibling routes, ask whether the user wants
parallel copies of the original PR or a sequential route carrying adaptations.
A requested sequential hop still requires a direct Fork relationship.

`SAVE_CHAIN` accepts either a source conversation or an existing source PR
(workspace identity and PR number), and one source-hop index per destination.
Index -1 means the original source; every other index must point to an earlier
hop. Omission preserves the ordinary sequential request. Unique destinations
and earlier-only references prevent cycles and repeated publication of shared
prefixes. Existing PR sources are verified against their monitored repository
and base branch without requiring a ByteQuay-created source conversation.

The tool boundary decodes each action into its own immutable request record.
Unknown actions, fields belonging to another action, duplicate keys, trailing
JSON and scalar coercions fail before dispatch. Service admission still checks
the captured turn, workspace relationships and live source state.

The saved chain freezes these dependencies and authority. The coordinator skips
waiting hops so ready siblings can run concurrently, while each dependent hop
waits for its own parent to merge. Stop, attention and recovery remain chain-wide.
Source-range selection names the affected hop. The card labels each hop's
source and shows parallel routes without implying a sequential order. No new
workflow engine, agent polling loop or automatic merge is introduced.

## 2. Fit the current code

The old repository-context notebooks, knowledge notes, learning runs, and
note lookup APIs have been deleted. Do not restore them. "Read the other
workspace's knowledge" now means read its code, docs, guidelines, and saved
[repository memory](repository-memory.md).

| Current code | Reuse or small change |
| --- | --- |
| `MemoryService`, `MemoryBridgeResolverImpl`, `MemoryBridge` | Extend existing `repository_memory` SEARCH/READ to a direct related workspace |
| `RepositoryGuidelineReader` and the turn-input resolver | Keep current-workspace guidelines; add concise relationship discovery instructions |
| `InteractiveAgentTaskCreator` and `WorktreeService` | Reuse normal conversations and exact-commit worktrees; allow a chosen investigation revision |
| `DevelopmentFollowupCoordinator` | Keep development, publication, and its existing restart behavior |
| Cherry-pick preview/execution, workspace PR delivery, CI Autofix, Branch Rebase | Run each backport hop; add only the missing chain coordination |

The memory store and SEARCH/READ/REPORT/REMEMBER tool exist. Enabled development
conversations can use the tool even before local memory exists, so REMEMBER can
save their first rule or recipe. Related SEARCH/READ use the same tool and honor
both workspaces' memory switches. Reuse the current memory UI and capture contracts.

The current conversation creator resolves the default branch, although the
worktree service already accepts an exact commit. Backport delivery still skips
CI activation and CI status for linked-upstream workspaces. These are concrete
integration changes, not reasons for a new task or workflow engine.

Related contracts: [workspaces](watched-repo.md), [agent service](agent-service.md),
[worktrees](worktree-service.md), [development delivery](development-delivery.md),
[cherry-pick](cherry-pick-range.md), [CI](ci-autofix-service.md), and
[rebase](rebase-service.md).

## 3. Reading and comparing

### Tell the agent where and when to look

Every development turn receives its direct relationships, guidance, and
backend-resolved repository paths and commits. Use the existing provider input
path for both lanes. For example:

> This is Trino. Apache Iceberg is a Dependency. For Iceberg library behavior
> or an upgrade failure, read that repository at the version Trino uses.
> Its checkout is at the supplied path. Use normal file/Git reads for code;
> use repository_memory with its workspaceId for saved rules and sources.
> Ask for an investigation there if tests need its build. Do not cherry-pick
> between Trino and Iceberg.

For a Fork, guidance instead explains which behavior is inherited and where
our changes differ. The agent decides relevance from that guidance and the
current question. No topic classifier or automatic research job is needed.

Ordinary code reads use the existing managed checkout at a captured commit,
without checking out another branch or creating a worktree. Fetch missing
history through existing repository sync. Report an unavailable repository or
revision; never silently substitute another one. Reading another project's
rules does not make them rules for the current project.

### Reuse the memory tool

Add optional `workspaceId` to SEARCH and READ. Omission means the current
workspace. On each call, validate the task and direct relationship, then use
`MemoryService`. SEARCH without a query already pages through all memory;
reuse it instead of adding LIST. Return the source workspace, memory ID,
version, sources, and reports. Expose all saved kinds and scopes in that
workspace, not just a preselected topic.

Related access is read-only. REPORT, REMEMBER and any future memory-writing actions
remain bound to the owning workspace; passing another workspace must be
rejected. Update the existing tool schema and bindings for both providers.
Keep memory's own enablement and receipt rules as they are implemented. A
development turn must have memory enabled, and related reads also honor the
source workspace's development-memory switch. Add no second store, copy of
memory, or per-relationship memory toggle.

Keep current-workspace guideline and memory delivery separate from related
reads. Supply the relationship list automatically, then read related memory
on demand. A related workspace does not inherit our transcript, repository
memory, or outgoing links. Source text remains reference data. Existing
filesystem permissions remain unchanged; this is not filesystem isolation.

### Compare actual versions

For a dependency upgrade, the agent reads the declaration and resolved build
evidence in the current task, identifies old/new library versions, and finds
their source revisions. Compare old P2 with new P2, then inspect how P1 uses
the changed behavior. For a fork, compare a known upstream baseline with the
chosen newer upstream revision and inspect our local differences.

Resolve tags/refs to exact commits in the related repository and retain the
labels, SHAs, and evidence with the request. Diff reads and test worktrees use
those SHAs even if a tag moves. Missing history or an uncertain package-to-source
mapping is a visible limitation, not permission to use the default branch.
No package-manager abstraction or upgrade watcher is required.

Memory is current saved advice, not a historical snapshot for each release.
Read its sources and verify relevant claims against the chosen code revision.
Use memory's source flags where available; do not claim the deleted notebook's
revision checks still exist or rebuild them in this feature.

### Current implementation boundary

`WorkspaceRelationships` saves each direct list in application settings under
`workspace.relationships.<workspaceId>`. Row writes take the existing workspace
lock. Settings and sidebar links reuse the Commits and Memory routes. Missing
workspaces stay visible for removal. Same monitored projects are rejected with
an explanation of the existing upstream routing.

`RelatedWorkspaceContext` appends discovery data to each user turn through the
shared turn-input resolver's preparation step for development conversations,
on both providers; how to use the links is part of the task's managed
instructions.
It resolves local monitored refs without fetching. Related guideline text is
read on demand and does not enter the current repository's guideline receipts.
`repository_memory` accepts `workspaceId` on SEARCH/READ; omitted means local.
Every call rechecks the open task, switches and direct membership. Review and
replay scope stays unchanged. Related memory is not automatically delivered.

Preview, execution admission and push/PR delivery share dependency rejection.
New cherry-pick executions persist their source repository so remote changes
cannot rewrite its identity. Older executions without that identity must be
previewed again before delivery when the target participates in a Dependency;
their work is preserved. Existing manual transfers unrelated to these pairs
keep their behavior. The feature does not change filesystem permissions.

## 4. Request work in the other workspace

A conversation can request a focused investigation or fix in a direct related
workspace. Add one fixed, root-bound handoff operation through the existing
tool transport. It creates an ordinary destination conversation and can read
that conversation's status and the result of the requested work.

Reuse conversation creation, first-turn admission, provider readiness, model
settings, and worktree ownership. Record the source/request key -> destination
task/first-turn link before starting work, so retries return the same work.
Compare the saved request before replay; include destination, purpose, revision,
and accepted settings in the fingerprint. This is a link, not a second
conversation lifecycle.

An investigation supplies a backend-validated commit in the destination
repository. Extend the creator's base selection for that case, then use
`WorktreeService` unchanged. A fix uses normal development base selection.
Testing both revisions uses one ordinary conversation/worktree per revision;
never reset another task's checkout or edit a managed shared checkout.

For investigation, turn off Auto draft PR, Auto CI fix, and Auto review summary
in the new task's saved development settings and accepted turns. Keep normal
local development commits. Do not confuse this with the internal
`DevelopmentAutomation.LOCAL_ONLY`, which also disables commits for private
flows. An investigation request grants no push or PR authority; a requested
fix/publication uses the existing development authorization and delivery rules.

Send only the question, selected revision, reproduction steps, and necessary
evidence authorized by the user's request. Do not copy the parent conversation
or memory. Keep the original Jira/GitHub issue association in the original
workspace. The destination loads its own guidelines, memory, and relationships.

The source can read the linked work's status and completed-turn answer through
the same operation, using existing turn/log readers. An ordinary conversation
stays open after answering; do not wait for `AgentTask.summary`, which exists
only after task completion. Identify the result turn and return tested commits,
commands, outcomes, and limitations. Missing/expired results are reported.
No general cross-workspace messaging system or new result store is needed.

The accepted user request supplies authority for the named work. An agent's
suggestion or a memory entry does not. Keep provider approvals, existing stop
controls, and root/turn checks. A plain investigation never creates a backport
chain. Starting a conversation remains the user entry point; the Start backport
conversation button fills the existing composer without sending.

### Implemented handoff

`related_workspace_work` accepts START and READ on the requesting root turn.
`RelatedWorkspaceWork` stores request links in application settings, freezes the
destination model and automation settings, and reuses `InteractiveAgentTaskCreator`
and `AgentTurnService`. A generated related-work turn cannot start another
handoff. A later explicit user turn can make a new request.

Investigations require a full locally available commit SHA. The ordinary creator
synchronizes the destination and verifies that same SHA before creating its
worktree. It never falls back to the default branch. START retries compare the
saved request, recheck the direct relationship and repository identity, and adopt
an already accepted first turn after a lost response. READ is limited to that
source conversation's saved link and current direct relationship.

Results come from the requested turn's root final answer in retained task logs,
not a second result store. Missing history is explicit. Large answers are shortened
with a link to the original conversation. The UI follows the approved prototype:
inline cards, a destination ByteQuay request bubble, and Tasks → Linked work groups.
Commands and limitations use a disclosure when the answer supplies that heading;
other answers remain readable as ordinary Markdown. Stop uses the existing
runtime stop action and its generation check.

Codex uses the existing root dynamic-tool channel; children cannot call it through
the shared MCP token. New conversations retain it across resume. Older Codex
conversations created without dynamic tools keep working but cannot gain this
operation on resume: start a new conversation for a handoff. Claude uses the
existing child-identity hook; older releases without verified child identity keep
native helper agents disabled while this operation is available. Neither path
adds a filesystem isolation model.

## 5. Put a saved chain above cherry-pick

The two fork flows share one route:

```text
From P2: investigate P4 -> fresh P4 fix conversation -> P4 PR
From P4: ordinary fix conversation -----------------> P4 PR
                                                       | merged
                                      existing backport in P3 -> P3 PR
                                                                  | merged
                                                 existing backport in P2 -> P2 PR
                                                                             | merged
                                                                          complete
```

The user may attach the route before or after the source PR exists. A negative
P4 finding starts no fix or chain. P4 can also backport directly to P2 when
P2 declares P4 as a Fork source. A Dependency never enters this flow.
When starting in P2, bind the chain to the returned P4 conversation, not P2's
conversation. Attach only that conversation's verified published PR.

### What is new

One `BackportChain` record, its store, and a small coordinator. Place the domain
and storage port in `core`, the SQLite adapter in `persistence-sqlite`, and
coordination in `app`, using existing Hibernate and `MonitorLoop` conventions.
Store only:

- The source conversation and/or verified PR, request key, and accepted authority.
- Ordered destination workspace/repository/branch identities and execution choices.
- Each step's resolved landed commits and existing execution/PR IDs.
- A stop flag, blocking reason, and version for concurrent updates.

Derive progress from existing execution, CI, and PR facts. A fixed root-bound
chain operation saves/appends the route under the accepted user request. Append
only unstarted work; do not rewrite completed steps. Freeze destinations when
the request is accepted, and reject changed fields on a reused key.

The existing root-only `related_workspace_work` tool accepts `DISCOVER_BACKPORT`,
`SAVE_CHAIN` and `READ_CHAIN`. Omit `sourceTaskId` to use this conversation; supply the linked
FIX conversation ID for an upstream fix. Destinations accept watched workspace
IDs or unambiguous `owner/repository` names, so a P4 conversation needs no reverse
memory or code access to name its downstream route. Appending uses the saved
chain ID and a new operation key.

`BackportChainService` coordinates one step per monitor tick. The SQLite record
stores accepted requests and frozen routing/model choices, plus the exact target
SHA and PR draft before child dispatch. The existing cherry-pick record also
retains its model and effort so recovery can create a missing child with the
same choices. The coordinator never starts a wrapper development conversation.

### How it advances

1. Recheck the direct Fork relationship, saved authority, and destination routing.
   Wait for the exact source PR to merge, using the existing GitHub readers.
2. Resolve what actually landed in that repository. Save the exact source range.
3. Reuse the destination's remote-source support, preview, and execution with a
   stable chain/step key. Reuse an existing execution instead of starting twice.
4. Reuse push/PR delivery and CI Autofix, including its Branch Rebase service.
5. Wait for that PR to merge. Its landed result is the next step's source.

P2 receives P3's landed result, including its adaptations and conflict fixups,
not the original P4 commits or an old PR head. The final step also waits for
merge. The chain never merges a PR automatically or keeps an agent running to
wait for a merge.

The executor checks the last `-x` origin line that Git appended, preserving
earlier origins as provenance. Its existing conflict-marker guard stays in place:
raw marker-bearing snapshots in landed history pause for attention, even when
a later fixup removes them. Clean source histories and clean squash results
follow the ordinary path.

Add one narrow merged-PR source resolver. GitHub merge facts plus fetched Git
history must establish the exact landed range. Squash/rebase merges can rewrite
SHAs; PR commit count or grouping alone is insufficient. Feed proven non-merge
ranges into the existing preview. Unsupported or ambiguous history pauses for
explicit commit selection; do not add merge-commit replay or guess a range.

### Implemented landed-source resolution

`MergedPullRequestResolver` resolves a PR in an already configured source remote.
`GitHubPullRequestReadClient` reads fresh merge facts, the complete original
commit list, and the PR again to reject changing evidence. It follows the
[GitHub REST contract](https://docs.github.com/en/rest/pulls/pulls#get-a-pull-request)
for the landed SHA and rejects PRs beyond the commits endpoint's 250-commit limit.
It does not use cached commit grouping or assume the current base SHA was the
base at merge time.

The resolver fetches the named base and original PR head into temporary private
refs, checks the original head and landed ancestry, and removes those refs.
It never changes a checked-out branch. An unchanged linear sequence keeps its
exact SHAs. Rewritten sequences must match every original file transition;
a squash must match the aggregate transition. Comparison uses full blob IDs,
file modes and paths, including binary files, without whitespace normalization.
Multiple possible ranges, merges, empty original commits, incomplete history,
and changed files that prevent exact proof require manual selection in Commits.
In particular, a base edit to the same file can require selection even when
Git could rebase it cleanly.

`MergedPullRequestRange` captures repository ID, PR, base branch, merge time,
landed SHA, original commits and the exact landed range as one serializable
value. The chain stores this evidence before starting its child. The existing preview accepts
this value and verifies its repository and complete commit sequence; refreshing
a preview cannot substitute the old PR head or widen the range.

### Existing execution and delivery

Reuse an existing source remote, or register a missing one through
`WorkspaceRemoteSourceService` under the accepted route. Use the source's
backend-resolved repository URL; never replace `origin` or contribution
`upstream`. An expired preview or moved target requires a fresh preview of the
same saved source and destination. Keep manual confirmation unchanged; chain
publication uses its specific saved authority through the existing delivery gate.

Complete linked-upstream CI activation and status in the existing delivery
service, reusing its stable CI key and fork validation. Do not duplicate CI,
rebase, conflict resolution, or draft promotion in the chain coordinator.

### Stop and recovery

Persist before dispatch. Serialize each chain and reuse child IDs/stable keys
across crashes; existing child services keep their own recovery. Repeated ticks
must not publish twice. The current development continuation is not made
restartable by this feature. Waiting chains may resume merge checks after restart;
failed development/CI and uncertain actions require attention, not blind replay.

Before retrying an uncertain push/PR creation, verify the exact branch, commit,
and PR through existing readers. Adopt a proven result or pause. Stop records
its flag before further dispatch and uses existing cancellation for chain-owned
work; it does not close PRs, discard work, or stop the independent source chat.
Resume is explicit and revalidates current facts. Failed or cancelled children
remain stopped; Resume does not revive them. A publication whose outcome cannot
be proven remains blocked and keeps its branch and execution for inspection.

For an ambiguous source, the existing range picker saves a version-bound explicit
selection. The backend checks that the source PR merged (or the preceding hop
was a no-op), the source route is unchanged, and the selected tip is on its source
branch. Selection alone stays paused; Resume starts the saved cherry-pick.
Tasks → Backport chains and the requesting turn show the same compact card,
with source/child/PR links and Stop/Resume. No separate Create task flow is added.

Recheck relationships and routing before every new step and publication. Removal,
change to Dependency, a closed-unmerged PR, or a missing workspace pauses the
chain without deleting work. Workspace deletion must stop or refuse active
related work. Settings updates use the existing workspace lock; no whole-graph
lock is needed. Existing writer claims serialize mutations within one worktree.

If a hop is empty/already present, create no empty PR. The final hop may complete
as a verified no-op. An intermediate hop still needs a proven landed source in
that destination for the next hop; otherwise pause for selection.

## 6. Small UI and delivery order

The approved [UI prototype](https://claude.ai/artifact/ANGa1cuFczrHjBoACSDVxt)
is the source of truth for this feature's layout and interactions. Its mock
records and scenario-switching toolbar are preview-only. Use existing app
primitives for the workspace picker, Fork/Dependency segments, compact related
rows, linked-work cards and Tasks sub-tab. Preserve current conversation entry;
the Start backport conversation button only fills and focuses the existing composer.


| Existing surface | Addition |
| --- | --- |
| Workspace settings | Related workspace, Fork/Dependency, and one guidance text field |
| Workspace navigation | Links to the related code/history and Memory views, clearly labeled with the workspace |
| Conversation | Linked investigation/fix, tested revisions/results, and a compact backport route with execution/PR links or the blocking reason |
| Existing action controls | Stop/Resume for the chain; no backport action for a Dependency |

Show version comparison in the conversation using existing Git/diff output;
no dedicated comparison dashboard is required. Reuse the Memory page as its
current implementation lands, without adding another memory browser.

Dependency investigation is usable without the chain feature. Relationship
reading, conversation handoff, landed-source resolution, and chain execution
reuse their existing service boundaries rather than introducing checkpoint
states into the runtime.

# ByteQuay: small GitHub issue development release

Status: v1 implemented. The design was committed before implementation.

This is the approved first-release scope. The broader [product design](issue-development.md) remains the roadmap. This feature extends the existing [agent service](agent-service.md) and [task UI](agent-task-ui.md); it adds no second execution engine.

## V1 outcome and scope

Ship exactly three user capabilities:

1. Mark or unmark an existing GitHub issue as an **Epic**.
2. Start ordinary ByteQuay development from an issue card/detail when it is not an Epic.
3. Show eligible issue-backed work items in both existing **Needs attention** views, with the same development action.

An unmarked GitHub issue is pending work that can start. Marking it as an Epic makes it non-runnable. V1 does not need a separate planned-task lifecycle, new Work screen, or complete Epic hierarchy.

The only new UI is an Epic action/badge, development action/source attachment, and corresponding actions in the existing attention components. All task execution uses the current composer, readiness checks, worktree preparation, automation, review and delivery behavior.

## Product behavior

### Marking an issue

- Add **Mark as Epic / Unmark as Epic** to the issue's existing actions, and show a small **Epic** badge when marked.
- Use icon actions with tooltips and accessible labels after the timestamp on issue cards. In the detail pane, group the GitHub, development and Epic icons after the updated timestamp.
- Default is unmarked; there is no separate “convert to work item” or import step.
- Store the marker locally in ByteQuay. The action does not edit GitHub labels, issue type, assignment or comments.
- Proposed scope: one marker per canonical GitHub issue within this ByteQuay installation, shared across workspaces monitoring that issue. This refines the earlier per-workspace proposal so global and workspace views cannot disagree. Execution links retain their workspace identity.
- Use stable issue identity and host/repository context, not title or issue number alone.
- Marking an Epic prevents future launches from that issue. It never deletes, cancels or rewrites previously linked tasks; their history remains accessible through **View task**.
- Epic marking classifies a large scope issue locally. It creates no guided Goal and unmarking never archives one. Existing Goals and their accepted work remain independently accessible through the Goal window. Creating a Goal or linking work to it remains an explicit action ([Agent guided goals](goal-guidance.md)). Epic marking adds no parent/child editor.
- The public marker contract uses `isEpic` and `/epic`. The SQLite mapping retains its existing physical table/column names to preserve saved selections; no data reset or migration machinery is introduced.


### Starting development

| Issue condition | Card behavior |
| --- | --- |
| Open, unmarked, no linked development | **Start development** |
| Marked as Epic | Epic badge; no development-start action |
| Linked development exists | **View task** is the default action |
| Source closed or inaccessible | No ordinary new-start action; preserve existing task/source history |

**Start development** opens the existing task composer with an attached issue reference and prefilled user instruction. A compact source card sits immediately above the input, both before creation and during the task conversation. Hovering or focusing it reveals the issue title, repository, number and Markdown description; the preview remains usable while the pointer or focus is inside it. View issue and the pre-launch refresh action remain available. The user can add instructions and submit using current provider/model and Automation controls.

Use title, description, URL and repository identity for the accepted source snapshot. ByteQuay supplies issue context through the fixed, read-only `read_issue_context` tool. It returns the task's saved source and available local metadata/discussion, with missing or truncated context disclosed. Provider instructions direct Codex and Claude to this tool for issue information, rather than retrieving the issue through GitHub CLI, HTTP or browsing. Tool reads never fetch GitHub and cannot select another task or issue. Source instructions are supplied again when a task resumes. A new comment-selection interface is deferred. Retain current default-branch and isolated-worktree behavior, including the distinction between monitored upstream and selected checkout/fork.

The backend validates the issue, workspace and Epic marker before admission. Preserve the source/context the user accepted; if a fresh source read reveals substantive changes, refresh the attachment before submission rather than silently changing the assignment. Retries reuse the accepted snapshot.

Persist task creation and its source association through the same replay-safe creation operation, before admitting the initial turn. Double clicks, lost responses and retry must produce one task and one initial turn. Recheck existing linked work so a stale card opens that task rather than accidentally creating another. Epic updates and launch admission must have a consistent order: once Epic marking succeeds, new launches are rejected server-side.

After launch, open the ordinary task screen. Render the initial development request as a compact **Start development · repository#number** row using the draft-PR request style. Match it through durable initial-turn identity; for an older response without that field, only a confirmed complete history prefix identifies the first root request. Never classify messages by parsing their text. Hide generated prompts and source payloads while retaining uploaded images. The compact source card remains above the composer, with **View issue** available after completion. **View task** on the issue card selects the linked task in the correct workspace’s Tasks tab and remains available after task completion; it never starts another task. Canceling the composer creates no task or worktree. Provider/repository setup failure preserves the user's draft and uses existing remediation.

Keep existing source close and task completion behavior. V1 does not introduce automatic issue closure, completion tracking, a new merge flow, or automatic closing-keyword generation.

### Needs attention

Needs attention includes updates from cached issues in a watched repository that
the viewer opened, that are assigned to the viewer, or that a circle member
opened, Epics and issues with linked development included. Ack and snooze
continue to control visibility. The open-issue cache remains the source;
reading attention does not fetch remote issue details.

- Both global Home and workspace Home use the same eligibility and action logic.
- Show assignment only when it matches the viewer. Preserve the existing Open/Ack/Snooze actions; development availability still follows Epic and linked-task metadata.
- After launch, the source issue retains its updates and exposes **View task**.
- Epic marking removes the start action without hiding subsequent issue updates or clearing Ack/Snooze.
- Preserve current visible row limits and PR ordering. Do not add desktop notifications or change notification preferences.
- Deduplicate repeated upstream issues on global Home using canonical source identity. Keep the displayed workspace route explicit and stable; never start in an unrelated last-active workspace. Reuse the existing workspace chooser if a choice is required.
- Read the whole cached repository issue set before applying shared card ranking and limits, so the first 100 rows cannot hide an update.
- Refresh the two views after classification, successful launch and issue-sync completion. Prefer existing invalidation/event mechanisms; if a cached refresh fallback is needed, reuse the current attention refresh lifecycle.
- Correct mixed-list navigation minimally: separate **All pull requests** and **All issues** destinations using existing screens instead of sending every “See all” action to PRs. No new combined page is required in v1.

## Minimum persistence and API changes

1. **Issue classification metadata:** stable source identity, `isEpic`, update timestamp. Keep this separate from the transient open-issue cache.
2. **Issue-to-task association:** task/workspace IDs, canonical issue identity, display URL/key, accepted source snapshot, user's instructions, initial request identity and creation timestamp. Follow the existing durable PR-to-task association conventions; do not generalize all source providers now.
3. **Existing issue projections:** expose Epic status and linked development reference needed by the card and attention views.
4. **Existing task-create request:** optional issue source attachment. Extend the canonical creation path, not a second issue execution endpoint/engine.

An open issue's cache row can be removed after closure or sync. Classification and task provenance must survive that removal. No bulk migration of every cached issue into a new planning model is necessary; absence of Epic metadata means unmarked.

## Development sequence

### Change 1 — durable Epic marking

**Build:** issue metadata storage, local update operation, projections, Epic badge and toggle on the existing issue surface. Publish local invalidation so both Homes update.

**Likely locations:** existing core/app/SQLite storage conventions, `WorkspaceController`, shared frontend types/bridge, `WorkspaceIssuesScreen.tsx`.

**Complete when:** marking survives refresh/restart and source-cache eviction; unmarking restores ordinary eligibility; no GitHub write or execution is triggered.

### Change 2 — issue-backed task creation

**Depends on:** Change 1.

**Build:** durable issue/task association and snapshot, optional source in the create contract, backend Epic/source validation and replay handling, issue-card launch into the existing composer, source link in the task screen, View task on an already-linked issue.

**Likely locations:** `CreateTaskRequest`/frontend types, `InteractiveAgentHttpContract`, `AgentController`, `InteractiveAgentTaskCreator`, `useAgentTaskCreation`, `AgentTasksView`, `WorkspaceIssuesScreen`, `WorkspaceShell`, `AgentTaskScreen`, and required `main.ts`/`preload.ts` bridge wiring.

**Complete when:** a normal issue starts one ordinary task in the correct workspace; stale Epic launch is rejected; duplicate submission or lost-response retry reuses the task and context; normal free-text and PR-originated tasks continue to work.

### Change 3 — work items in both attention views

**Depends on:** Changes 1 and 2.

**Build:** shared update eligibility and development actions, global deduplication, cached reads before card limits, refresh/invalidation, and correct existing-screen navigation for PR versus issue overflow.

**Likely locations:** `home/attention.ts`, `AttentionList`, `AttentionPreview`, `HomePage`, `AgentHome`, and the existing cached-issue query.

**Complete when:** cached issue updates appear in both Homes and eligible issues start through the same composer; marking as Epic or starting it changes the action without suppressing later updates; Ack/Snooze retain their behavior.

Each change should be independently reviewable. Keep all three in the v1 release acceptance boundary.

## Verification and implementation constraints

Use focused checks for persistence, admission/replay, and attention eligibility rather than a new test framework:

1. Mark/unmark persists and does not modify GitHub.
2. Epic launch is rejected through the backend, including stale UI and concurrent role changes.
3. Normal launch retains upstream identity while using the intended checkout/fork and current automation settings.
4. Double submit and retry produce one task/first turn and retain the original accepted snapshot.
5. Source provenance remains readable after closure or cache removal; earlier linked tasks survive classification changes.
6. Both Homes include updates regardless of assignment, Epic status, or linked work, while preserving the corresponding action restrictions, deduplication and Ack/Snooze. Include an issue beyond the former first 100 cached rows.
7. Existing free-text creation, PR-originated tasks, review and task completion remain valid.
8. Both providers receive the task-bound issue tool on initial and resumed sessions. Tool reads use only ByteQuay data, reject source/task selectors, disclose unavailable context and retain the accepted issue after cache removal.
9. The source card stays above the composer and supports hover/focus preview; the initial request card replaces only its own user message, without hiding subsequent instructions, images or review requests.

Run the repository's required backend checks and frontend lint/type checks/tests/build appropriate to the final changes. Exercise issue card → composer → task → source link and both Home entry points.

Implementation verification: full backend `mvn verify` (2,347 tests), frontend lint and TypeScript checks, all 1,012 frontend tests, and `npm run package` passed. Isolated Chromium fixtures cover Epic toggling, composer cancellation, source refresh, double submission/lost-response retry, linked-workspace navigation, completed-task source navigation, attention actions, source hover/keyboard preview and the compact initial request card, including older source responses. Visual checks passed at normal and narrower content widths, with the current Plan, source attachment and composer visible together. Provider tests cover local issue context and native plan guidance on initial and resumed sessions; HTTP and persistence checks retain the accepted source and initial-request identity after cache removal. Plan checks cover progress, explicit clearing, child-agent scope, atomic snapshot persistence, new-turn isolation and a snapshot whose original event is outside loaded history. Plain Markdown checklists remain display-only. The existing scroll-animation fixture now waits for precise message alignment before its unchanged assertion.

The current repository has uncommitted changes in several integration files, including task contracts, task UI and persistence. Inspect and preserve those changes during implementation. Follow repository requirements for the canonical agent service, Hibernate-managed schema, plain CSS and the prohibition on restoring the removed development-flow engine.

## Following releases

[Agent guided goals](goal-guidance.md) owns guided outcomes, research, courses
and discovery. Epic organization below remains a separate delivery-planning roadmap;
marking an Epic does not create a guided Goal. Shared work-item states are
Planned, Working, Done and Dropped.

| Stage | Scope |
| --- | --- |
| **V2: Epic organization and UI** | Epic details, children, existing-task binding, always-visible Epic cards on global/workspace Home, unified Work area. |
| **V3: Progress and history** | Planned/Working/Done/Dropped work-item state, delivery and source-close reconciliation, Epic rollups, activity facts and period statistics. Historical coverage starts from recorded facts; do not promise reconstructed six-month data. |
| **V4: Better discovery** | Configured source-role rules, business-value/readiness ranking, duplicate and already-fixed evidence, bounded agent triage, meaningful-event notification rules. |
| **Jira integration** | Add authentication/source fields and transition support, reusing the proven classification and issue-to-development flow. It can follow v1 without waiting for every reporting feature. |

V1's role markers and source/task links give these stages a durable foundation. They do not require building those later systems now.

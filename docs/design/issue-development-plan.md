# ByteQuay: small GitHub issue development release

Status: implementation plan, not implemented.

This is the approved first-release scope. The broader [product design](issue-development.md) remains the roadmap. This feature extends the existing [agent service](agent-service.md) and [task UI](agent-task-ui.md); it adds no second execution engine.

## V1 outcome and scope

Ship exactly three user capabilities:

1. Mark or unmark an existing GitHub issue as a **Goal**.
2. Start ordinary ByteQuay development from an issue card/detail when it is not a Goal.
3. Show eligible issue-backed work items in both existing **Needs attention** views, with the same development action.

An unmarked GitHub issue is the pending work item. Marking it as a Goal makes it non-runnable. V1 does not need a separate planned-task lifecycle, new Work screen, or complete Goal hierarchy.

The only new UI is a Goal action/badge, development action/source attachment, and corresponding actions in the existing attention components. All task execution uses the current composer, readiness checks, worktree preparation, automation, review and delivery behavior.

## Product behavior

### Marking an issue

- Add **Mark as Goal / Unmark as Goal** to the issue's existing actions, and show a small **Goal** badge when marked.
- Default is unmarked; there is no separate “convert to work item” or import step.
- Store the marker locally in ByteQuay. The action does not edit GitHub labels, issue type, assignment or comments.
- Proposed scope: one marker per canonical GitHub issue within this ByteQuay installation, shared across workspaces monitoring that issue. This refines the earlier per-workspace proposal so global and workspace views cannot disagree. Execution links retain their workspace identity.
- Use stable issue identity and host/repository context, not title or issue number alone.
- Marking a Goal prevents future launches from that issue. It never deletes, cancels or rewrites previously linked tasks; their history remains accessible through **View task**.
- Goal marking does not yet create a parent/child editor or a Home Goal card. Those are the next release.

### Starting development

| Issue condition | Card behavior |
| --- | --- |
| Open, unmarked, no linked development | **Start development** |
| Marked as Goal | Goal badge; no development-start action |
| Linked development exists | **View task** is the default action |
| Source closed or inaccessible | No ordinary new-start action; preserve existing task/source history |

**Start development** opens the existing task composer with an attached issue reference and prefilled issue context. The user can add instructions and submit using current provider/model and Automation controls. The issue action is the direct entry point; no separate issue-import workflow is introduced.

Use title, description, URL and repository identity for the v1 source context. A new comment-selection interface is deferred. Retain current default-branch and isolated-worktree behavior, including the distinction between monitored upstream and selected checkout/fork.

The backend validates the issue, workspace and Goal marker before admission. Preserve the source/context the user accepted; if a fresh source read reveals substantive changes, refresh the attachment before submission rather than silently changing the assignment. Retries reuse the accepted snapshot.

Persist task creation and its source association through the same replay-safe creation operation, before admitting the initial turn. Double clicks, lost responses and retry must produce one task and one initial turn. Recheck existing linked work so a stale card opens that task rather than accidentally creating another. Goal updates and launch admission must have a consistent order: once Goal marking succeeds, new launches are rejected server-side.

After launch, open the ordinary task screen. Show a compact **View issue** source link there, and **View task** on the issue card. View task selects the linked task in the correct workspace’s Tasks tab and remains available after task completion; it never starts another task. Canceling the composer creates no task or worktree. Provider/repository setup failure preserves the user's draft and uses existing remediation.

Keep existing source close and task completion behavior. V1 does not introduce automatic issue closure, completion tracking, a new merge flow, or automatic closing-keyword generation.

### Needs attention

Use the existing assigned-issue rule with minimal additional filters:

`open issue + assigned to signed-in user + not Goal + no linked development + not currently handled/snoozed`

Assignment is a recommendation filter, not a prerequisite for manually starting another accessible issue from its issue card.

- Both global Home and workspace Home use the same eligibility and action logic.
- Show **Assigned to you** as the reason, with **Start development** and the existing Open/Ack/Snooze actions. Do not present these items as value-ranked or verified unfixed.
- After launch, remove the unstarted-work suggestion. The source issue still exposes **View task**, and existing task/PR surfaces handle ongoing work.
- Goal marking removes the start suggestion; unmarking reevaluates normal eligibility without clearing Ack/Snooze.
- Preserve current visible row limits and PR ordering. Do not add desktop notifications or change notification preferences.
- Deduplicate repeated upstream issues on global Home using canonical source identity. Keep the displayed workspace route explicit and stable; never start in an unrelated last-active workspace. Reuse the existing workspace chooser if a choice is required.
- Filter the cached issue set before applying the candidate limit. Fix the current first-100-before-assignment blind spot with a narrow cached-query filter, rather than adding a new discovery pipeline or GitHub crawler.
- Refresh the two views after classification, successful launch and issue-sync completion. Prefer existing invalidation/event mechanisms; if a cached refresh fallback is needed, reuse the current attention refresh lifecycle.
- Correct mixed-list navigation minimally: separate **All pull requests** and **All issues** destinations using existing screens instead of sending every “See all” action to PRs. No new combined page is required in v1.

## Minimum persistence and API changes

1. **Issue classification metadata:** stable source identity, `isGoal`, update timestamp. Keep this separate from the transient open-issue cache.
2. **Issue-to-task association:** task/workspace IDs, canonical issue identity, display URL/key, accepted initial context and creation timestamp. Follow the existing durable PR-to-task association conventions. The current checkout has a `PullRequestTask` pattern that is useful precedent; do not generalize all source providers now.
3. **Existing issue projections:** expose Goal status and linked development reference needed by the card and attention views.
4. **Existing task-create request:** optional issue source attachment. Extend the canonical creation path, not a second issue execution endpoint/engine.

An open issue's cache row can be removed after closure or sync. Classification and task provenance must survive that removal. No bulk migration of every cached issue into a new planning model is necessary; absence of Goal metadata means unmarked.

## Development sequence

### Change 1 — durable Goal marking

**Build:** issue metadata storage, local update operation, projections, Goal badge and toggle on the existing issue surface. Publish local invalidation so both Homes update.

**Likely locations:** existing core/app/SQLite storage conventions, `WorkspaceController`, shared frontend types/bridge, `WorkspaceIssuesScreen.tsx`.

**Complete when:** marking survives refresh/restart and source-cache eviction; unmarking restores ordinary eligibility; no GitHub write or execution is triggered.

### Change 2 — issue-backed task creation

**Depends on:** Change 1.

**Build:** durable issue/task association and snapshot, optional source in the create contract, backend Goal/source validation and replay handling, issue-card launch into the existing composer, source link in the task screen, View task on an already-linked issue.

**Likely locations:** `CreateTaskRequest`/frontend types, `InteractiveAgentHttpContract`, `AgentController`, `InteractiveAgentTaskCreator`, `useAgentTaskCreation`, `AgentTasksView`, `WorkspaceIssuesScreen`, `WorkspaceShell`, `AgentTaskScreen`, and required `main.ts`/`preload.ts` bridge wiring.

**Complete when:** a normal issue starts one ordinary task in the correct workspace; stale Goal launch is rejected; duplicate submission or lost-response retry reuses the task and context; normal free-text and PR-originated tasks continue to work.

### Change 3 — work items in both attention views

**Depends on:** Changes 1 and 2.

**Build:** shared eligibility and development actions, global deduplication, cached-query filtering before limits, refresh/invalidation, and correct existing-screen navigation for PR versus issue overflow.

**Likely locations:** `home/attention.ts`, `AttentionList`, `AttentionPreview`, `HomePage`, `AgentHome`, and the existing cached-issue query.

**Complete when:** an eligible assigned issue appears in both Homes and starts through the same composer; marking as Goal or starting it removes the new-work suggestion everywhere; Ack/Snooze and PR attention retain their behavior.

Each change should be independently reviewable. Keep all three in the v1 release acceptance boundary.

## Verification and implementation constraints

Use focused checks for persistence, admission/replay, and attention eligibility rather than a new test framework:

1. Mark/unmark persists and does not modify GitHub.
2. Goal launch is rejected through the backend, including stale UI and concurrent role changes.
3. Normal launch retains upstream identity while using the intended checkout/fork and current automation settings.
4. Double submit and retry produce one task/first turn and retain the original accepted snapshot.
5. Source provenance remains readable after closure or cache removal; earlier linked tasks survive classification changes.
6. Both Homes agree on assignment, Goal exclusion, linked-work suppression, deduplication and Ack/Snooze. Include an eligible issue beyond the former first 100 cached rows.
7. Existing free-text creation, PR-originated tasks, review and task completion remain valid.

Run the repository's required backend checks and frontend lint/type checks/tests/build appropriate to the final changes. Manually exercise issue card → composer → task → source link and both Home entry points.

The current repository has uncommitted changes in several integration files, including task contracts, task UI and persistence. Inspect and preserve those changes during implementation. Follow repository requirements for the canonical agent service, Hibernate-managed schema, plain CSS and the prohibition on restoring the removed development-flow engine.

## Following releases

| Stage | Scope |
| --- | --- |
| **V2: Goal organization and UI** | Goal details, children, existing-task binding, always-visible Goal cards on global/workspace Home, unified Work area. |
| **V3: Progress and history** | Idle/Working/Finished work-item state, delivery and source-close reconciliation, Goal rollups, activity facts and period statistics. Historical coverage starts from recorded facts; do not promise reconstructed six-month data. |
| **V4: Better discovery** | Configured source-role rules, business-value/readiness ranking, duplicate and already-fixed evidence, bounded agent triage, meaningful-event notification rules. |
| **Jira integration** | Add authentication/source fields and transition support, reusing the proven classification and issue-to-development flow. It can follow v1 without waiting for every reporting feature. |

V1's role markers and source/task links give these stages a durable foundation. They do not require building those later systems now.

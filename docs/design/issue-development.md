# ByteQuay: work from discovery to delivery

Product vision and later-stage design

The [Agent guided goals proposal](goal-guidance.md) develops the conversation,
discovery, accepted-work and outcome-tracking parts of this roadmap into a
concrete next release. Its [implementation plan](goal-guidance-impl-tmp.md)
distinguishes proposed work from the current implementation.

**Release scope update:** the user has narrowed v1 to local Goal marking, starting development from an issue card, and work items in the existing Needs attention views. The [small v1 development plan](issue-development-plan.md) is the authoritative implementation scope. The broader requirements below are retained for later stages; their earlier first-release wording is superseded by that plan.

## Product model

Use **Goal → Work item** for planning. A Goal groups an outcome and its child work items; it never runs. A Work item is a leaf with a **Run** action and no children. ByteQuay’s existing **Task** remains the agent execution, conversation, worktree, review and PR history. One work item can have several execution tasks or attempts; statistics count work items.

Planning records must exist independently of execution: creating a goal, organizing work or binding an existing task allocates no worktree and starts no agent. Keep goal membership separate from `parentTaskId`, which already serves CI repair.

## One Work area and both Homes

Add a unified **Work** area with **Goals / Work items / Activity**. Global Home shows all unarchived goals across workspaces; workspace Home shows all unarchived goals in that workspace. Completed goals remain visible until explicitly archived. This replaces optional pin destinations: both surfaces open the same records, and Home may group completed goals without hiding them.

A goal contains title, description, workspace, optional source and ordered work items. Its Home card expands inline to show those details, children and progress, with a deeper detail view available. It provides **Add work item** and **Bind existing task**, never Run or Run all. A work item belongs to one goal or is ungrouped. Moving membership preserves development history. The planning hierarchy has two levels; goals cannot contain other goals.

Binding a task reuses its work item, or creates a local work item from its title and available source context when none exists. Show that consequence in the picker. Retain the original task, conversation, PR and execution state. Moving from another goal names that goal. Do not duplicate or restart execution.

An issue’s planning role comes from explicit source parent/child relationships, configured type/label mappings, or the user’s choice. An umbrella-like title alone produces a **Suggested goal**, not an automatically displayed goal. Users can accept that suggestion. A source’s original type and hierarchy remain metadata.

## What the current audit establishes

Assigned issues already appear in both attention views. Current limits are six global rows and three workspace rows. Issue synchronization starts after one minute and repeats every fifteen minutes; it paginates open issues into SQLite with a 5,000-result cap, while the attention UI examines only the first 100 per workspace. Home does not subscribe to issue updates while mounted. PR data refreshes through events and thirty-second polling. “See all” incorrectly opens PR pages.

Current handled-state keys use repository/number and localStorage/Electron sharing; overlapping upstreams can duplicate rows across workspaces. Acknowledgement rearms on new activity, snooze respects its deadline, and issue activity currently relies on `updatedAt`. Desktop notifications cover monitored PRs, not issues. Existing Recent records user touches and has clearing/retention behavior; it is not a factual analytics history.

Closing an issue removes its open-cache row. Durable planning, source links, recommendations and history therefore cannot depend on that row surviving.

## Automatic discovery and useful recommendations

Discovery runs automatically within **existing monitored repositories/workspaces**. It does not silently monitor every accessible repository. Reuse issue refresh and relevant source, task and PR changes as evaluation triggers. Proposed default: batch ordinary reevaluation at the existing fifteen-minute cadence; meaningful events can update sooner. These are product policies, not an automation created by this document.

Normalize available facts before recommending work: current source state, assignment, explicit priority, confirmed duplicate events, closing-PR relationships and merge state, blockers, linked work items and execution tasks. Existing cache fields alone are insufficient.

Use deterministic rules first, followed by targeted agent judgment:

| Evidence gate: suppress a new-work recommendation | Uncertain evidence: retain with **Check first** |
| --- | --- |
| Closed, deleted, inaccessible or outside monitored scope | Similar title, description or affected code |
| Existing linked work is running or awaiting review | A possibly related task or PR |
| Current authoritative duplicate marker identifies another issue | A comment merely says “duplicate,” or similarity suggests one |
| Confirmed delivery covers this source revision and intended scope | A merged PR appears relevant but complete coverage is uncertain |
| User selected Don’t suggest, or snooze remains active | Missing context, unclear blockers or suspected prior fix |

A duplicate marker that was undone no longer excludes the issue. A closing PR is meaningful evidence only with its actual relationship, merge result and scope evidence; partial work must not suppress the remaining requirement. An accepted ByteQuay delivery is stronger evidence than an agent’s inference from a patch. These checks reduce duplicate or already-fixed recommendations; they cannot guarantee their absence. Say **“No known matching fix within checked coverage,”** and expose that coverage.

Rank eligible candidates by **readiness and value**, without a magic score: relevance to the active workspace/user, explicit urgency, concrete user impact or unblocking value, clear acceptance criteria, and manageable scope. Each recommendation explains why it matters, why it appears ready, and any uncertainty. Age and comment volume alone do not establish value.

Proposed ordering after readiness checks: explicit user/source priority, then active-goal or dependency-unblocking value, then assignment and workspace relevance; bounded effort and freshness break ties. Require at least one concrete relevance/value reason before adding a start recommendation to Home. Source priority comes from explicit fields or configured repository-label mappings, not a universal interpretation of arbitrary label names. Keep declared priority, observed facts and inferred impact distinguishable. An easy cosmetic issue must not displace an explicitly urgent fix just because it is smaller.

**Bounded read-only agent triage is a required capability.** Apply it to an uncertain shortlist after deterministic filtering: inspect relevant issue context, candidate PRs and narrowly relevant code. Cache evidence and conclusions against source revisions. Rerun only when relevant evidence changes or the user requests another check. Never repeatedly scan whole repositories or send every issue to an agent. If inspection cannot establish readiness, keep Check first and name the missing evidence.

## Shared attention and low-noise notifications

Proposed defaults: show up to **three issue recommendations per workspace** and **five globally**, after urgent PR review/failure items. These are visibility limits, not discovery limits. “See all” opens the Work attention view, with filters and checked-coverage information, rather than PR pages.

Both Homes read one backend-persisted recommendation projection, with canonical source identities and workspace associations. The same upstream issue monitored by multiple workspaces appears once globally. Choose its execution workspace at launch when ambiguous. Counts, explanations and handled state agree across surfaces and refresh when issue sync completes.

Keep actions distinct:

- **Acknowledge:** hide the current recommendation until meaningful new activity.
- **Snooze:** hide until the deadline, then reevaluate rather than blindly notify.
- **Don’t suggest:** suppress until the user restores it.

Rearm acknowledgement for substantive scope/relevance changes, reassignment to the user, a confirmed blocker clearing or reopening after delivery. A changed timestamp, routine comment, score fluctuation or repeated fetch alone is insufficient. Recommendations may disappear because eligibility/evidence changed; that does not create a permanent dismissal. Persist the reason and evidence version of the last notification.

The first release provides in-app Needs attention. Issue desktop notifications are a separate opt-in addition. Batch changes and avoid repetitive alerts. Discovery and triage never create tasks, start development, post comments or close sources automatically.

## Starting development and reusing the current flow

Add **Start development** to issue cards and the existing issue detail, and **Run** to work-item leaves. Add Goal and Development sections to the current source view rather than another issue browser. Persist a work item when accepted into planning, bound to a task or launched; recommendations do not import every cached issue as planned work.

1. Open the canonical task composer with the source attachment and optional extra instructions. Preserve provider/model choices, workspace defaults, **Auto draft PR**, **Auto CI fix**, CI repair configuration and **Keep commits local for this turn**.
2. Resolve the intended workspace and preserve monitored upstream separately from the selected checkout/fork. Keep current default-branch/worktree behavior; no new branch picker is required. Missing setup uses the existing remedy, never the last active repository.
3. Refresh source context before submission. Show substantive changes and retain the accepted title, description and selected comments as the launch snapshot. Extra instructions do not edit the issue.
4. Use existing readiness checks and replay-safe task creation plus first turn. Persist the work-item/source attachment with creation. Double-clicks, lost responses and retries must reuse the same operation/task identity and snapshot.
5. Open the ordinary task experience with source and goal links. Existing task present: default to **View task**, **Review changes** or existing resume controls. **Start another task** remains explicit.

Canceling setup creates no execution task, worktree or source write. Failure preserves the draft. Multiple attempts and partial PRs stay attached to one work item. Preserve the existing automation authorizations and canonical review/delivery services; do not restore removed development-flow systems or add a second executor.

Recheck active source-linked work across workspaces when starting. A suggestion can become stale while displayed; if another execution has begun, offer Open task in its workspace. Serialize ordinary starts for the same work item, and reserve concurrent independent execution for explicit Start another task. A stale Home card must not bypass this check.

## States, delivery and source closure

| Work-item state | Meaning |
| --- | --- |
| **Idle** | Accepted/planned, development not started |
| **Working** | Started but not delivered; secondary text can show Running, Waiting, Blocked or Review |
| **Finished** | Accepted outcome, with all intended merged deliverables verified in the agreed repository/branch |

For the GitHub release this is merge-based delivery, not deployment verification. A closed unmerged PR, cancelled task, duplicate disposition, successful agent response or ByteQuay task **Complete** does not establish delivery. Existing Complete retains its local task/worktree-cleanup meaning. A follow-up or reopened work item returns to Working and keeps its history.

A nonempty goal is Finished when every in-scope work item is Finished; it is Idle when empty or all are Idle, otherwise Working. Adding scope or reopening a child updates this rollup. Cancellation/duplicate disposition is recorded separately and never counted as delivered. Removing such work from scope records the scope change.

After delivery, offer **Close issue**, naming the source and relevant PRs. Source closure is separate from work-item delivery. Refresh first; already closed is reconciled successfully. Failed closure shows **Finished · source update pending**, retries only the source update and preserves delivery. External closure alone does not prove delivery.

Use ordinary references while a separate closure step is intended. Do not silently introduce closing keywords or closing relationships that bypass that step, particularly for partial work. GitHub may close issues through existing source-side automation; reflect confirmed remote state.

## Activity and statistics from day one

Work supports **Today / This week / This month / Last 6 months / Custom**, with exact inclusive dates and timezone shown. Proposed defaults: the user’s timezone, Monday week start, calendar month, and six calendar months back through today. Today ends now; custom date ranges include both selected days. Store event timestamps in UTC and apply displayed timezone boundaries consistently.

Distinguish **current state totals** from **period activity**. Count distinct work items created, first started and first delivered within the selected interval. Task retries, sessions, PRs, commits and source closures do not add work-item completions. Keep the first accepted-delivery event once; reopening changes current state and appears as separate activity, not another first completion. A deliberately separate follow-up is a new work item with explicit scope.

Goal trends must use historical membership and scope, not today’s denominator applied backward. Show additions, removals and reopenings alongside delivery; removing work must not appear as newly delivered progress. Goals are never added to work-item totals.

Persist these facts from the first release:

- Goal/work-item identity, workspace, membership changes, scope changes and archive/disposition history.
- Canonical source identity: provider, host/site, repository/project identity, stable issue ID, display key/URL, revisions and last checked time.
- Accepted launch snapshots, execution-task/attempt links, PR relationships, intended deliverables and accepted delivery evidence.
- State events, first-start/first-delivery timestamps, reopenings and source-update outcomes.
- Recommendation evidence/coverage, revision, acknowledgement, snooze, dismissal and last-notified trigger.

Keep these records independent of open-issue caches and Recent. Disclose **“Tracked since [date]; earlier history is incomplete.”** Do not infer historical completions from today’s closed issues or imply six months of coverage immediately. Any later backfill must label its coverage and confidence.

## Delivery boundaries and verification

GitHub-first includes planning, both Homes, unified Work/activity, durable history, shared recommendations, deterministic evidence rules, bounded triage, existing-composer launch, task binding, review and closure reconciliation. Test one-record consistency across Homes, replay-safe launch, upstream/fork selection, capped coverage, duplicate-marker undo, partial delivery, closure failure, cache deletion, reopening and changing goal scope.

Jira later supplies connection, source context, fields and allowed workflow transitions to the same model. Epic/story/task mappings are configurable; backlog and sprint are metadata. Defer arbitrary hierarchy import, workflow builders, cross-provider mirroring, bulk goal execution and new deployment pipelines.

GitHub behavior references: [confirmed duplicate markers and undo](https://docs.github.com/en/issues/tracking-your-work-with-issues/administering-issues/marking-issues-or-pull-requests-as-a-duplicate); [PR links, closing keywords and default-branch closure](https://docs.github.com/en/issues/tracking-your-work-with-issues/using-issues/linking-a-pull-request-to-an-issue).

The accompanying interactive concept uses sample data; it is neither a production implementation nor a pixel-exact rendering of current ByteQuay.

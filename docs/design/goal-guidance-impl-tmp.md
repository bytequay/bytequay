# Agent guided goals implementation plan

Status: Planned. No implementation step below is complete. The
[Goal guidance design](goal-guidance.md) owns the product and behavior contract;
this checklist records delivery order, concrete actions and proof required.

The first useful release must let a user describe a goal, receive a grounded
proposal, accept and start one work item, and return to a recorded result and
next recommendation. The three lessons are part of that loop, not separate
architecture projects.

## Delivery order

| Step | User-visible outcome | Dependency |
| --- | --- | --- |
| 1 Correct existing progress | Failed or abandoned work no longer looks achieved | Independent |
| 2 Introduce outcome identity | One work item connects sources, task attempts and results | 1 |
| 3 Add Goal conversation | Describe and refine a saved plan with an agent | 2 |
| 4 Discover and start work | Real recommendations enter the existing execution flow | 2 and 3 |
| 5 Record results and guide the next step | Outcomes, summaries and proposals update automatically | 2 through 4 |
| 6 Complete organization and acceptance | Both Homes and the Goal panel agree about next work and progress | 1 through 5 |

Keep each behavior change independently reviewable and passing. Separate mechanical
extractions from functional changes. Do not ship a manual board as the completed
agent-guidance feature.

## Step 1 Correct existing progress

Lesson one can start without waiting for the planning agent.

- [ ] In `GoalServiceImpl`, distinguish ended tasks from achieved outcomes; retain
  failure and unmerged-closure reasons rather than projecting both as success.
- [ ] Replace the missing-open-cache-is-closed inference with Unknown. Add an
  authoritative source read only through the existing explicit refresh path.
- [ ] Update `GoalItemState`, goal contracts, `model.ts`, `GoalPanel.tsx` and
  `GoalLayer.tsx` together. Present current reference counts as activity; keep a
  separate factual merged-PR count and remove unsupported achievement percentages.
- [ ] Update focused backend and frontend cases for failed task, interrupted
  attempt, closed unmerged PR, merged PR and missing issue.

Done when: a Goal containing a failed task and an abandoned PR cannot show them
as two delivered outcomes, and a missing issue is visibly unknown. This is an
interim activity presentation that Step 2 replaces with actual work progress.

## Step 2 Introduce one work item per outcome

This completes the identity part of lesson two and the final progress rules of
lesson one.

- [ ] Add final Work item values, source/task/deliverable associations and factual
  history to `core`, with SQLite adapters in `persistence-sqlite`. Keep Goal
  grouping separate from execution parent/child relationships.
- [ ] Define canonical source ownership, one primary work-item association per
  task, unique accepted-proposal mappings, idempotent accept/bind commands and
  revision checks. Preserve source snapshots independently of source caches.
- [ ] Change the GitHub/Jira unique source-per-task assumption deliberately:
  one source resolves to one work item; that item may hold several explicit
  attempts with their original snapshots. Update `IssueDevelopmentStore`,
  `JiraIssueTaskStore`, their adapters and both admission services together.
- [ ] Default to opening existing work. Serialize another-attempt admission and
  require the prior attempt to be terminal with delivery settled; an idle open
  task remains the existing attempt. Integrate the work-item link with the
  existing task/source creation transaction.
- [ ] Add Planned, Working, Done and Dropped with selected merged-delivery or
  accepted-result rules. Track partial deliverables and uncertain coverage.
- [ ] Replace Goal reference rows with one work-item card and expandable links.
  Bind existing tasks without restarting them; moving Goals preserves history
  and requires resolving affected blocker links.
- [ ] Change source unmarking to preserve existing Goal conversation and work;
  add explicit archive semantics instead of deleting them through `unseed`.
- [ ] Inspect meaningful existing Goal data before replacing the schema. Preserve
  task/source/PR histories, document the chosen data disposition, and never run
  an implicit reset. Add no migration framework or intermediate compatibility API.

Done when: an issue, two explicit attempts and a PR produce one progress unit;
retries reuse identity; same-source launches across workspaces cannot race; a
partial merge and dropped work cannot masquerade as delivery.

Primary checks: SQLite uniqueness/transaction tests, cross-workspace admission,
lost-response retry, source cache eviction, task binding, partial delivery,
reopen, unmark preservation and Goal scope changes.

## Step 3 Add the Goal conversation and proposals

- [ ] Add durable Goal exchanges and versioned proposals beside the existing Goal
  records. Save user input, objective, constraints, acceptance decisions and
  response coverage; no task transcript is used as the Goal's database.
- [ ] Add a bounded Goal planner in `app` using the existing provider SPI and
  `TEXT_ONLY` profile. Follow the naming-helper resource and verified-close
  pattern; register request/helper ownership for cancellation and restart.
- [ ] Use the workspace Development model default, visible per-Goal override and
  readiness checks. Prove Codex and Claude tool-free behavior with fixtures.
- [ ] Build bounded context from canonical plan/results and recent conversation.
  Retain full user-visible history and pinned decisions; do not silently truncate
  constraints or depend on provider session memory.
- [ ] Validate typed model replies, evidence IDs, reference ownership, proposed
  dependency cycles and size limits before saving. Invalid output leaves the
  previous plan intact and produces a retryable error.
- [ ] Extend `GoalController`, main/preload bridge and `frontend/src/goals` with
  New goal from a request, Conversation, editable suggested title and Accept /
  Edit / Skip proposal controls. Manual Goal creation remains available.
- [ ] Keep Accept plan separate from Start. Draft proposals do not affect progress.

Done when: “Help me make my first contribution” creates a persistent conversation
and proposed path without any execution task, Git branch, worktree or GitHub write.
An edited or duplicated acceptance cannot overwrite newer decisions or duplicate
work items. Closing and reopening the app retains the conversation and plan.

## Step 4 Discover relevant work and hand it to execution

- [ ] Build a Goal context reader over existing issue, Jira, PR and task stores.
  Use exact monitored/mapped workspace scope and identify incomplete coverage.
- [ ] Add deterministic eligibility and relevance filtering before a bounded model
  shortlist. Preserve high-priority items before display limits. Never suppress
  uncertain duplicates or possible fixes as if they were confirmed.
- [ ] Refresh shortlisted sources through existing services. Add only the missing
  duplicate/closing-PR evidence reads needed for the readiness decision. Record
  source revision, freshness and skipped coverage.
- [ ] Supply at most 100 compact candidates, inspect at most five selected sources
  and display at most three recommendations. Reject model-invented IDs and URLs.
  Cache conclusions by input revision; unchanged candidates need no new model call.
- [ ] Include reason, scope, prerequisites, completion criteria and uncertainty
  on each proposal. Allow source-free learning/investigation items.
- [ ] Where code has not been inspected, offer an explicit investigation through
  the ordinary task flow. Pass its result back to the Goal; never label a
  metadata-only assessment as code verification.
- [ ] Extend canonical task creation with an optional work-item reference and the
  accepted Goal context. Preserve current composer defaults, source refresh,
  readiness, worktree selection, publication authority and replay behavior.
- [ ] At Start, recheck source identity, role, state, work-item revision and linked
  work. Open an existing task when appropriate; preserve the draft on failure.

Done when: one actual GitHub issue and one mapped Jira ticket can each be found,
explained, accepted and started through the existing task UI; a local learning
item can be planned without an external ticket. A changed or already-started
source cannot slip through stale recommendations.

## Step 5 Record outcomes and propose the next step

- [ ] Connect task settlement and existing source refresh to idempotent factual
  receipts. Capture final result/check evidence before runtime retention removes
  it. Reconcile missed receipts after restart and guard linked-task retention or
  deletion until capture succeeds. Preserve missing-evidence and agent-reported
  distinctions.
- [ ] Verify selected delivery rules through application facts. Provide explicit
  result acceptance for learning and investigation, and coverage review where a
  merge does not establish the entire accepted scope.
- [ ] Generate a concise summary from those receipts: what changed, evidence,
  lessons, unresolved work and next recommendation. Saving a summary cannot
  change completion state or accepted scope.
- [ ] Reconsider after meaningful linked work/source changes. Persist the input
  fingerprint, coalesce events and respect quiet hours and the design's call
  limits. Add Pause guidance and Update now; panel polling remains local.
- [ ] Use request and input revision checks to discard stale proposed changes.
  An interrupted request stays retryable; do not launch or resend work on startup.
- [ ] Save Goal lessons locally with citations. Route a user-selected Remember
  action through the existing repository-memory policy, without a new memory engine.
- [ ] Exercise archived Goal, task deletion, log expiry, summary failure and
  uncertain provider cleanup. Preserve receipts and user decisions in every case.

Done when: completing or merging accepted work records one supported outcome,
updates the panel and produces a useful next proposal without manual bookkeeping.
Repeated refreshes do not repeat model calls or notifications. A restart and
runtime-log cleanup do not erase the history.

## Step 6 Complete organization and verify the whole loop

This finishes lesson three using the current surfaces.

- [ ] Add local priority, accepted order and same-Goal `blockedBy`; reject cycles
  and keep blockers separate from execution failures. Dropping a blocker requires
  an explicit dependency decision.
- [ ] Extend the existing list/board with Next, Working, Blocked and Done views.
  Show source priority separately and explain the recommended ordering.
- [ ] Feed global and workspace Needs attention from one work-item projection.
  Deduplicate source/recommendation cards for the same action; preserve urgent
  execution attention, Ack and Snooze.
- [ ] Add results/history display with evidence links, scope changes, reopenings
  and factual coverage. Do not count retries, comments or PRs as extra outcomes.
- [ ] Run browser journeys at normal and narrow panel sizes, including keyboard
  use, background updates, preserved drafts, stale responses and offline recovery.
- [ ] Run `mvn verify`; frontend lint, `npx tsc --noEmit`, tests and packaging.
  Complete opt-in live planning/source handoff smoke checks for supported lanes
  and report live-service limits separately from fixture results.
- [ ] Update the canonical agent, architecture, issue and Jira design sections to
  the implemented behavior and remove superseded descriptions. Mark checklist
  entries complete only with their actual evidence.

Done when: the full Goal conversation → proposal → accepted work → task → verified
result → summary → next proposal journey works, and all panels agree about it.

## Later additions

Only after usage establishes a need: cross-workspace Goals, scheduled discovery
while the app is not running, longer-term trend reports with adequate recorded
history, custom saved views, cycles/modules, YAML import/export and external
agent integrations. None is necessary for the first complete guidance loop.

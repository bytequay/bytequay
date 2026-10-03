# Agent guided goals implementation plan

Status: Work-item foundation implemented. Agent-guidance work remains planned. The
[Goal guidance design](goal-guidance.md) owns the product and behavior contract;
this checklist records delivery order, concrete actions and proof required.

The first useful release must help an unsure user clarify a goal, receive a
grounded path, accept and start prepared work, and return to a recorded result
and next recommendation. The agent leads clarification, discovery and ongoing
assessment. The user need not invent prompts, manually search for every ticket
or ask for the next step after every result. The three lessons are part of that
loop, not separate architecture projects.

## Delivery order

| Step | User-visible outcome | Dependency |
| --- | --- | --- |
| 1 Correct existing progress | Failed or abandoned work no longer looks achieved | Independent |
| 2 Introduce outcome identity | One work item connects sources, task attempts and results | 1 |
| 3 Add agent led clarification and guidance | The existing Goal panel asks useful questions and proposes a path | 2 |
| 4 Discover and prepare work | The agent finds candidates and prepares the next task without a new prompt | 2 and 3 |
| 5 Record results and reassess the path | Status changes produce summaries, recovery and revised next steps automatically | 2 through 4 |
| 6 Complete organization and acceptance | Both Homes and the Goal panel agree about next work and progress | 1 through 5 |

Keep each behavior change independently reviewable and passing. Separate mechanical
extractions from functional changes. Do not ship a manual board as the completed
agent-guidance feature.

## Next implementation slice

Deliver one complete agent-led path through the existing Goal surface before
adding more board controls. The floating and expanded view must share the
same guidance and conversation. The current storage and manual controls are a
foundation, not the intended finished interaction.

1. Extend Goal replies with the current assessment, one clarification with
   suggested answers, a proposed path and a supported next action. Persist the
   answer, input revision and decisions through the existing Goal service.
2. Replace the current empty/name-first opening with contextual directions and
   an optional text answer. A broad existing title receives useful clarification.
   Submitting a suggested answer starts the conversation directly.
3. Make guidance the default `GoalPanel` content: situation, primary agent
   intervention, compact path, then recent result. Move statistics, search,
   creation forms and the board to secondary Work/History views. Keep the
   `GoalLayer` floating control, pinning and draggable/resizable zoom behavior.
4. Wire Accept path and prepared Start to persisted proposals and canonical task
   admission. The existing composer opens fully prepared; no new prompt is
   required. Acceptance of scope and execution remain distinct decisions.
5. Connect one real task-result event to a durable receipt, current assessment
   and prepared next recommendation. Prove that a failed attempt recommends
   recovery and a finished milestone advances guidance without a user prompt.

This slice spans Steps 3 through 5. A scripted conversation, attractive static
panel, renamed manual buttons or an unconnected chat box does not complete it.
The following steps retain the full identity, source, recovery and verification
requirements.

## Step 1 Correct existing progress

The progress correction ships directly with work-item progress; there is no
intermediate reference-count percentage to maintain.

- [x] Separate ended tasks, unmerged PR closures and unknown source data from Done.
- [x] Replace missing-open-cache-is-closed with Unknown; reads stay local.
- [x] Update the Goal contracts, summary, panel and layer together. Count work
  items, show linked PR activity separately and exclude Dropped from the denominator.
- [x] Cover failed/completed tasks, closed and merged PRs, missing issues, partial
  delivery and one-outcome counting in backend and frontend checks.

## Step 2 Introduce one work item per outcome

- [x] Add Work item, stable source/task links, accepted source snapshots and
  decision history in `core`, with SQLite persistence and identity uniqueness.
- [x] Add local create/bind commands, retry identity and revision checks. Goal
  membership is separate from execution parent/child relationships.
- [x] Reserve source/task ownership during existing GitHub/Jira task creation,
  in its existing transaction before the first turn. Refuse Done, Dropped or
  other-workspace work before the worktree, and keep a Jira ticket's assignment
  with its work item's workspace.
- [x] Add Planned, Working, Done and Dropped. Explicit acceptance records criteria,
  evidence and scope coverage; merged delivery additionally needs a merged PR and
  no open or unavailable linked PR, recording closed unmerged PRs as abandoned.
- [x] Replace reference rows with work-item cards and linked evidence. Create
  source-free work, edit scope, link existing tasks, reopen, detach and move Goals.
- [x] Keep a source's Goal on unmarking while it holds work, archive it when
  empty, restore the kept Goal when the source is marked again, and retain
  work/history when archiving.
- [x] Inspect development data before changing the Goal mapping: no Goals or
  source-task rows existed. No runtime data was changed or reset.
- [ ] Add explicit another-attempt admission to GitHub/Jira stores and both
  services. Keep opening the current task by default; require the previous attempt
  to be terminal with delivery settled before a new task can be admitted.
- [ ] Add unique accepted-proposal mappings when Goal proposals arrive in Step 3.
- [ ] Add explicit accepted deliverable/base/criteria mappings and automatic
  merged-delivery evaluation with outcome harvesting in Step 5. The current
  result-acceptance action always requires the user's scope confirmation.
- [ ] Add blocker-aware moves when same-Goal dependencies arrive in Step 6.

Checks cover SQLite revision conflicts and ownership rollback, source cache loss,
reference grouping, abandoned and open PRs at acceptance, command retry, reopen,
repeated source marking, archival and moving work without losing evidence.
SQLite-backed launch checks prove one work item per issue attempt and refusal of
Dropped or other-workspace work before the source fetch; a Jira check proves
reassignment is refused while a work item owns the ticket. A Chromium journey exercises a
failed result save, the same-operation retry, reopening and detached history.
The remaining unchecked items are not represented as implemented behavior.

Verification: backend `mvn verify`, frontend lint and TypeScript checks, and
all 1,423 frontend tests passed, including the work-item Chromium journey.
Desktop packaging also passed.

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
- [ ] Include current assessment, one consequential clarification, short suggested
  answers and a prepared supported action in that same reply. Reuse known
  context; do not repeat answered questions or infer unprovided preferences.
- [ ] Extend `GoalController`, main/preload bridge and `frontend/src/goals` with
  suggested-answer and free-text messages, editable suggested title and Accept /
  Adjust / Skip proposal controls. An existing vague Goal receives clarification
  without requiring a separate Plan with agent prompt. Manual entry stays secondary.
- [ ] Refactor `GoalPanel` around current situation, one primary agent intervention,
  compact path and recent result. Empty Goals show helpful directions, not 0/0,
  empty board columns and source-search instructions. Preserve current app styling.
- [ ] Preserve `GoalLayer` pill, pinning, floating window and expansion. Show one
  meaningful attention cue and keep drafts/selection stable across modes. Keep
  one panel mounted across resizing and expansion, hidden from interaction when closed.
- [ ] Keep Accept plan separate from Start. Draft proposals do not affect progress.

Done when: opening an empty Goal or one named “Iceberg connector expertise” offers
useful directions; a suggested answer can lead to a saved goal definition and
proposed path without typing a prompt. A free-text request works equally well.
Neither flow allocates an execution task, Git branch, worktree or GitHub write.
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
- [ ] Trigger discovery as the next useful step of an active guidance request or
  meaningful source/work change. Users need not open search and formulate a new
  query. Explain insufficient coverage instead of presenting arbitrary candidates.
- [ ] Where code has not been inspected, offer an explicit investigation through
  the ordinary task flow. Pass its result back to the Goal; never label a
  metadata-only assessment as code verification.
- [ ] Extend canonical task creation with an optional work-item reference and the
  accepted Goal context. Preserve current composer defaults, source refresh,
  readiness, worktree selection, publication authority and replay behavior.
- [ ] Prepare the execution request from accepted scope, evidence and constraints.
  Start opens the existing composer ready for review and submission; the user
  need not rewrite the agent's proposed task in another input.
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
- [ ] Reassess the next intervention from real status: running, failed, blocked,
  waiting for review, result ready or milestone delivered. Prefer recovery and
  outstanding user decisions over fresh discovery. A meaningful change updates
  guidance without “what next?” or manual refresh.
- [ ] Propose specific plan differences when scope/order/dependencies should
  change, with their reason. Refresh recommendations within accepted constraints
  automatically; preserve accepted decisions until the user changes them.
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
- [ ] Include an unsure-user journey using suggested answers only, a vague-title
  clarification, prepared task launch with no new prompt, automatic failure
  recovery guidance and proposed replanning after a blocker changes. Check visual
  hierarchy in floating and expanded view; the next useful action must be readable
  before statistics or board management. Opening the panel alone adds no model call.
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

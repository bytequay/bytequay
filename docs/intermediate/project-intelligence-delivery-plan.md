# Project Intelligence delivery plan

Status: **APPROVED PLAN; IMPLEMENTATION NOT STARTED**

Created: **2026-07-30**

Normative product contract:
[project-intelligence-design.md](./project-intelligence-design.md)

Runtime contract:
[development-flow-design.md](./development-flow-design.md)

The development-flow migration is complete. This is a post-migration
enhancement plan: it extends immutable inputs and product projections while
preserving the V2 owners, typed Turns, Operations, ExecutionDispatcher,
CapacityManager, and four-Stage lifecycle.

## Outcome

Deliver Project Intelligence in six small, independently useful increments:

1. make current full-review intelligence workspace-safe, immutable, and
   truthful;
2. let users settle and correct Project Direction;
3. use one exact direction basis across Plan, development, Brain review, and
   remote repair, with a human correction path for wrong Plan self-review
   reasoning;
4. apply settled Direction to full-review objectives and correction;
5. help the user keep up through Project Briefing;
6. add Review Voice only after enough explicit user edit evidence exists.

The shortest path is to extend current learning, Memory, Plan, and review
surfaces. No new dashboard, Stage, agent type, scheduler, knowledge store, or
runtime framework is required.

## Delivery rules

1. Implement against main, not the discarded project-stewardship branch.
2. Preserve unrelated work already present in the working tree.
3. Use the next available forward-only SQLite migration numbers.
4. Every migration includes database-level integrity and immutability guards
   where ownership or snapshot correctness depends on them.
5. Add no new top-level dependency.
6. Do not reopen the completed development-flow migration.
7. Do not start work through AgentScheduler or a direct Task lifecycle
   executor.
8. Learning may remain explicitly classified as bounded workspace maintenance
   while it is independent of Task and Stage lifecycle. If it later requires
   scarce execution admission, add a normal durable Operation and
   DispatchTicket through the existing dispatcher; do not invent another
   scheduler.
9. Keep every slice shippable and fail open when intelligence retrieval is
   absent after exact owner authorization; missing or ambiguous ownership fails
   closed.
10. No slice may publish to GitHub automatically.

## Current main-branch inventory

### Learning and knowledge

Current owners:

- ProjectLearningService owns durable learning-run progression and resume.
- LearningCatchUpJob performs bounded catch-up.
- MergedPrCatalog limits the primary history source to merged pull requests.
- PrEvidenceFetcher collects reviews, inline comments, files, commits, and
  timeline evidence.
- LessonExtractor produces typed candidate lessons.
- KnowledgeIngestor stores canonical items and provenance.
- KnowledgeRetrievalService ranks active items.
- SessionKnowledgeProvider renders audience context.
- DocumentIndexer records document section locations and digests.
- DirectoryScopeService derives approval-first directory suggestions.

Current persistence:

- V192 project-learning runs, document refs, PR catalogue, and capsule;
- V196 detailed PR evidence;
- V202 canonical knowledge, provenance, and applicability;
- V203 latest session context projection;
- V214 Code Area suggestions, decisions, and assignments.

Important gaps:

- repo_doc_section contains locations and a digest, not bounded section text;
- WorkspaceKnowledgeService can mutate knowledge content in place, and legacy
  statement digests are not a complete immutable version boundary;
- SessionKnowledgeProvider repository-only methods can choose the wrong
  workspace and use LIMIT 1;
- session_context_projection stores the latest audience projection, not an
  immutable decision input;
- closed-but-unmerged pull requests are absent;
- OutcomeChainReconstructor does not identify addressed-by-commit, so
  first-sight automatic activation cannot satisfy its complete-chain rule.

### V2 Plan and development

Current owners:

- PlanRuntimeCoordinator launches planning and mandatory self-review turns.
- PlanMcpService accepts structured Plan and self-review results.
- the Plan store keeps immutable Plan revisions, one self-review per revision,
  concerns, follow-ups, and stewardship follow-ups;
- V2AutomationPlanService blocks automatic approval while open stewardship
  exists;
- LocalDevelopmentRuntimeCoordinator owns writer and Brain loops;
- TaskBrainConversationRuntime owns Task Brain conversation turns.

Important gaps:

- Plan prompts do not receive one frozen Project Intelligence projection;
- the generic stewardship field is not connected to approved Direction;
- CHANGES_REQUESTED immediately launches an agent redraft, so there is no
  human path to reject wrong self-review reasoning on the current revision;
- plan_followup has a free-text resolution but no typed, attributable
  adjudication outcome;
- implementation and Brain review do not share the approved Plan's exact
  direction basis;
- agent-initiated project exploration is optional and mutable;
- remote repair does not receive a frozen governing basis.

The current approval query already fences the latest exact Plan revision,
self-review, policy revision, and content digest. Preserve those fences. The
only new verdict alternative is the narrowly fenced HUMAN adjudication path
below; POLICY and AUTOMATION continue to require APPROVED self-review evidence.

### Full and quick review

Current owners:

- InvestigationReviewService owns ReviewSession, ReviewRound, assignments,
  objectives, hypotheses, findings, verification, and finalization;
- InvestigationReviewRunner launches bounded reviewer turns;
- InvestigationReviewStore persists the typed hierarchy;
- V291 stores the immutable source snapshot;
- V292 and V293 own standalone and per-command durable snapshot admission.

Current useful behavior:

- deterministic safety objectives are always planned;
- up to three active learned objectives may be appended;
- full review may read only persisted bodies of changed files;
- quick review is diff-only;
- findings need exact changed-line evidence, severity 4 or 5, and deterministic
  validation before publication;
- the user chooses the GitHub verdict.

Important gaps:

- reviewKnowledgeForRepository and renderForRepository perform separate live
  repository-only retrieval;
- planning guidance can differ from seat prompt guidance;
- no exact workspace and intelligence digest is frozen with the round;
- all retrieved learned objectives are treated as applicable;
- objectiveResolution can return investigated-clean because a step completed,
  even without an explicit evidence-backed resolution;
- the tool protocol has no guarded resolve-hypothesis operation;
- review UI cannot inspect or challenge the source intelligence.

### Frontend

Keep and extend:

- workspace/WorkspaceMemoryPage.tsx;
- workspace/WorkspaceTodayPage.tsx;
- workspace/NewThreadDialog.tsx;
- review/AgentReviewPlanCard.tsx;
- pulls/AgentColumn.tsx;
- review/StartAgentReviewDialog.tsx;
- review/SubmitReviewPopover.tsx.

Current gaps:

- no Direction candidate or governing state;
- no challenge, correction, or scoped exception;
- no frozen-projection provenance in Plan or review;
- no Project Briefing;
- no Review Voice;
- Today maps every non-null learning state to paused;
- pause, resume, and retry API calls exist but are not connected to controls;
- full review copy describes shared memory even though private workspace brain
  is intentionally excluded.

## Target persistence

Use the existing knowledge tables. Add only the records needed for authority
and reproducibility.

### Knowledge semantic identity

Treat each knowledge_item id and statement_digest as one immutable semantic
version:

- backfill and require a canonical statement digest;
- add an optional supersedes_item_id lineage link;
- block in-place updates to statement, rationale, recipe, audiences, and
  applicability;
- replace WorkspaceKnowledgeService.updateContent with create-replacement
  behavior;
- allow lifecycle, confidence, and confirmation counters to advance;
- retain exact old items while a Direction decision references them;
- let frozen projections remain self-contained even if an unrelated,
  non-governing item is later deleted.

An edit creates a new item and applicability rows, then links it to the old
item. This makes the item id the content version and avoids a second knowledge
store.

### Direction decisions

Add one append-only project_direction_decision table:

| Field | Purpose |
|---|---|
| id | stable decision id |
| workspace_id | exact workspace owner |
| knowledge_item_id | canonical item being decided |
| workspace_revision | monotonic Direction aggregate revision |
| action | APPROVE, CHALLENGE, REMOVE, or SUPERSEDE |
| statement_digest | exact statement the user saw |
| previous_direction_digest | compare-and-swap basis |
| direction_digest | canonical aggregate digest after this decision |
| replacement_item_id | optional corrected item |
| reason | optional user explanation |
| actor_id | accountable user identity |
| created_at_ms | audit time |

The latest successful Workspace revision is the membership projection. There
is no separate Direction prose or scope store: the statement lives in
knowledge_item and scope lives in knowledge_applicability. A corrected
statement creates a replacement item and a SUPERSEDE decision.

Keep as guidance, Need evidence, Reject, and Retire remain knowledge-item
curation commands owned by the existing knowledge service:

- Keep as guidance activates the item without Direction membership;
- Need evidence keeps it pending and records the reason in its curation
  metadata and activity;
- Reject or Retire uses the existing lifecycle and records the reason;
- none of these writes a Direction decision.

Database guards must ensure:

- the item belongs to the same workspace and repository;
- a legacy repository-scoped item with no workspace is first materialized as
  an exact workspace-owned item rather than shared as governing state;
- Approve direction either references an active item with provenance or
  atomically creates an active user-owned replacement from the pending
  candidate with user provenance;
- the statement digest matches the item at decision time;
- Workspace revision is exactly previous revision plus one;
- previous_direction_digest matches the current aggregate;
- Workspace revision and aggregate digest are unique;
- concurrent decisions against one predecessor cannot both commit;
- replacement links remain in one workspace and item lineage;
- rows are immutable;
- deletion follows workspace cascade only.

The application canonicalizes the resulting membership and computes
direction_digest. SQLite compares the expected prior revision/digest, enforces
sequence and uniqueness, and stores the supplied new digest; it does not
calculate the cryptographic hash.

### Bounded document text

Extend repo_doc_section with bounded normalized section text, or add one
one-to-one companion row when SQLite migration safety makes that cleaner.

Requirements:

- exact workspace, repository, path, heading, line range, commit, and digest;
- a strict per-section and per-document size cap;
- no binary or generated content;
- source classification for current spec, proposed change, archive,
  alternative, ADR, architecture, contributing, build, or general docs;
- re-index replacement by exact digest;
- bounded, digest-idempotent extraction into pending canonical knowledge items
  with exact document provenance;
- no automatic Direction approval.

### Plan projection

Add one immutable plan_revision_intelligence row keyed by plan_revision_id:

- workspace_id;
- schema_version;
- projection_json;
- projection_digest;
- direction_revision;
- applicable_direction_digest;
- direction_count;
- conflict_count;
- created_at_ms.

Add a matching intelligence_digest to post-cutover plan_revision rows; it is
nullable only for historical rows. Remove the current content-history uniqueness
rule: plan_revision id plus its monotonic revision number is historical identity,
while retry idempotency comes from the stable revision-producing command or
Turn. Replaying that exact command returns its original revision. A different
command whose prose and intelligence digest equal the immediately current
revision is rejected as NO_SEMANTIC_CHANGE; after an intervening revision,
returning to older prose and the same basis creates a valid new revision. This
also permits unchanged Plan prose to become a semantic revision when its basis
changes. Before self-review admission, database guards require the revision
intelligence_digest and plan_revision_intelligence projection_digest to match.

The row is inserted before the self-review request for that revision. Existing
historical revisions receive no synthetic direction; reads treat absence as an
empty historical projection.

The Plan draft TaskTurn resolves the projection synchronously before its
immutable launch_input is inserted. The planning prompt reads that exact value.
When record_plan accepts the resulting revision, it copies the same canonical
projection and digest into plan_revision_intelligence in the owner transaction.
Retries reuse launch_input; they do not retrieve current knowledge.

Every revision-producing path follows the rule:

- initial draft, user-requested self-review redraft, and replan draft freeze the
  projection in their TaskTurn launch_input and copy it on record_plan;
- a replan seed, user edit, or any synchronous revision freezes and inserts the
  projection in the Plan-owner transaction;
- a Brain-produced revision freezes the projection before its exact revision
  Turn and copies it on accepted delivery.

No path may request self-review until its projection exists.

The projection JSON freezes complete bounded item statements, item ids and
statement digests, authority, applicability, provenance references, source
digests, currentness, ordering, and omission reasons. It must not require a
later live knowledge join to reconstruct the prompt.

### Plan Direction exception

Add one immutable plan_direction_exception row per approved STEWARDSHIP
exception:

- task_id;
- plan_revision_id;
- unique plan_followup_id for the exact STEWARDSHIP concern;
- knowledge_item_id and statement_digest;
- direction_revision;
- reason;
- approved_by;
- approved_at_ms;
- expires_when, fixed to Plan revision superseded or Task terminal.

It is keyed to the exact Task, revision, typed STEWARDSHIP concern, and Direction
item. The concern's item and statement digest must match the exception and the
revision's frozen projection. Human approval requires one matching exception
for every open STEWARDSHIP concern, inserted with the approval in one
transaction. Each row expires when the revision is superseded or the Task
becomes terminal. An exception allows human approval but never makes the Plan
eligible for automatic approval. It sets the existing Task
stewardship-exception policy, so automatic readiness and auto-merge remain
disabled until a later aligned Plan revision explicitly clears the exception.

The exception path requires a successful APPROVED self-review whose
stewardship concern matches the item. CHANGES_REQUESTED and BLOCKED are not
exception-eligible. The Plan owner inserts the exception and human approval in
one transaction under the exact Task, Plan revision, content digest,
intelligence digest, and current applicable-Direction digest. The current digest
must equal the revision's frozen digest; an exception cannot cover an item or
basis change that the revision never froze and reviewed. The existing human
approvePlan path must reject an open Direction concern when that exact exception
is absent.

### Plan self-review adjudication

Do not add an adjudication table. Reuse each stable plan_followup row created
for the revision's sole self-review and extend plan_followup with:

- basis_kind, nullable for history and nonblocking/failure follow-ups, but
  required for every new CONCERN or STEWARDSHIP as PLAN_REASONING or
  PROJECT_DIRECTION respectively;
- knowledge_item_id and statement_digest, required only for PROJECT_DIRECTION
  STEWARDSHIP and forbidden for PLAN_REASONING CONCERN;
- direction_revision, required with a Direction reference;
- resolution_kind, nullable except for typed resolution and initially limited
  to DISMISSED_INCORRECT for CONCERN rows;
- resolved_by, nullable while open and required for typed resolution;
- the existing resolution as the required human explanation;
- the existing resolved_at_ms as the decision time.

Guards make source linkage and typed resolution immutable. The Plan owner
verifies every PROJECT_DIRECTION reference against the exact frozen projection;
applicable governing conflicts can be persisted only as non-dismissible
STEWARDSHIP. DISMISSED_INCORRECT is valid only for a PLAN_REASONING CONCERN owned
by the exact current revision after a successful CHANGES_REQUESTED self-review.
A CHANGES_REQUESTED result must contain at least one concern; an empty or
misclassified result enters the existing blocker path.

The generic follow-up update command remains limited to kind FOLLOW_UP. It
cannot resolve or defer CONCERN or STEWARDSHIP. Adjudication requires every
exact CONCERN for the self-review to be OPEN at command start and present in the
submitted set; any pre-resolved or deferred row rejects the entire command.

Add one Plan-owner adjudicate-and-approve command. In one serialized
transaction it:

1. validates the exact Task, epoch and optimistic version, current Plan Stage,
   generation and optimistic version, Plan revision, content digest,
   self-review id and verdict, intelligence digest, and current
   applicable-Direction digest;
2. requires one attributed DISMISSED_INCORRECT decision and nonblank reason for
   every exact open CONCERN on the revision;
3. rejects BLOCKED, failed, canceled, superseded, no-verdict, partial, stale, or
   open-STEWARDSHIP cases;
4. resolves every concern and inserts the existing HUMAN plan_approval; and
5. completes the existing Plan approval handoff.

No partial resolution commits when any fence or concern fails. Extend the
plan_approval insert guard with only this HUMAN alternative. POLICY and
AUTOMATION still require a successful APPROVED verdict, and the existing
APPROVED-plus-Direction-exception path remains separate. The adjudication does
not rerun self-review, mutate a knowledge item, or decide a Direction action.
Its typed outcome is retained as review-quality feedback but cannot become
Project Direction or Review Voice automatically.

### Review projection

Add one immutable review_round_intelligence row keyed by round_id:

- workspace_id, nullable only for unscoped quick review;
- schema_version;
- projection_json;
- projection_digest;
- item_count;
- created_at_ms.

Every new round gets a row:

- quick review stores the canonical empty projection with null workspace;
- full review stores the exact authorized workspace and bounded selection.

The insert is part of the same accepted owner boundary that persists the V291
source snapshot and creates round objectives. Seats cannot be admitted without
both rows.

Database guards must verify:

- the round exists;
- workspace shape matches quick or full review ownership;
- JSON is valid;
- one row exists per round;
- the row is immutable.

Before insert, the application canonicalizes the JSON, computes the
cryptographic digest, and verifies that the stored bytes match. SQLite guards
shape and immutability; they do not pretend to calculate that digest.

### Hypothesis resolution

Add immutable resolution fields or a one-to-one resolution record for each
hypothesis:

- supported, refuted, or unresolved;
- exact evidence ids;
- explanation;
- resolving assignment turn;
- resolved_at_ms.

Prefer extending the existing hypothesis record only if its current lifecycle
permits one guarded transition without weakening immutability. Otherwise use a
small one-to-one resolution table.

The database and service require:

- evidence belongs to the same review, round, assignment, and hypothesis;
- supported or refuted has at least one exact frozen observation;
- unresolved has a reason;
- one terminal resolution;
- no completed-step shortcut.

### Briefing and voice

Do not add these tables in the first migration.

When Project Briefing is delivered, store one structured brief with the
existing per-PR evidence bundle or a one-to-one companion keyed by the
repo_pr_source identity. Do not create a mutable aggregate summary table.

When Review Voice is delivered, store:

- eligible user-owned draft-to-published edit signals;
- current-user-authored published comment signals with exact account
  attribution;
- explicit user style preferences;
- one versioned, resettable user profile;
- the profile revision used for a draft.

Do not reuse repo_review_conf until its obsolete shape and ownership are
explicitly migrated or retired.

## Slice 0 — truthful immutable full-review intelligence

Goal: fix current correctness before adding governance.

### Backend

1. Add an exact workspace-scoped retrieval entry point. It accepts
   workspace_id, repository, audience, frozen changed paths, title, and
   description.
2. Reject full-review admission before round creation when Workspace ownership
   or repository binding is missing or ambiguous.
3. Remove ReviewSession and ReviewRound use of repository-only
   SessionKnowledgeProvider lookup. Delete learnedRepository's ambiguous
   selection path once no caller needs it.
4. Resolve one bounded projection during accepted full-round creation.
5. Persist the canonical empty projection for quick review.
6. Build deterministic objectives from the frozen projection only.
7. Pass the same projection to every investigation prompt and continuation.
8. Remove the live renderForRepository call from investigationPrompt.
9. Keep deterministic objectives first and cap intelligence objectives at
   three.
10. Add resolve_hypothesis to the reviewer tool contract.
11. Change objectiveResolution so only explicit terminal resolutions or
    surviving verified findings produce a terminal covered state.
12. After an exact Workspace is authorized, treat failed or empty intelligence
    retrieval as the canonical empty projection; treat missing code evidence as
    unresolved.
13. Keep the independent verifier blind to the intelligence projection.

### Frontend

1. Replace shared memory wording in StartAgentReviewDialog with project
   knowledge and approved direction.
2. In AgentReviewPlanCard and AgentColumn, show:
   - Deterministic or Project Intelligence source;
   - frozen-at-round-start state;
   - supported, refuted, or unresolved;
   - provenance only when available.
3. Do not add challenge actions until Slice 1 has a durable correction path.

### Tests

Backend:

- same repository in two workspaces never crosses knowledge;
- missing or ambiguous Workspace binding rejects full review before a round;
- failed retrieval after exact Workspace authorization produces empty
  intelligence;
- quick review persists an empty projection;
- full review freezes exact items, revisions, provenance, and digest;
- item activation or retirement after start does not alter a round;
- planner and seat prompts contain the same projection;
- live repository retrieval is never called after snapshot acceptance;
- deterministic objectives survive empty or failed retrieval;
- a completed step without resolution is not investigated-clean;
- supported and refuted resolutions require same-round evidence;
- verifier input contains no intelligence projection;
- restart and finalization reload the persisted projection only.
- a pre-cutover Turn retries only from its existing launch_input, while a
  request for another seat starts a new post-cutover round.

Frontend:

- quick/full capability wording is accurate;
- objective source and frozen state render;
- unresolved objectives never appear clean.

### Exit criteria

- no ReviewRound seat reads live Project Intelligence;
- no repository-only workspace selection remains in the active review path;
- missing or ambiguous Workspace binding cannot admit full review;
- quick review receives no Project Intelligence;
- objective coverage is evidence-backed;
- existing finding, verifier, budget, capacity, and publish behavior is
  unchanged.

## Slice 1 — settle and correct Project Direction

Goal: make intended direction explicit without making inference authoritative.

### Backend

1. Enforce immutable knowledge semantic identity, required statement digests,
   replacement lineage, and create-replacement editing.
2. Add the append-only Direction decision migration, monotonic Workspace
   revision, canonical digest, and compare-and-swap repository.
3. Persist bounded document section text and source classification.
4. Run bounded, digest-idempotent section extraction through the existing
   learning owner and ingest pending canonical items with exact document
   provenance.
5. Recognize current direction candidate sources, including established
   constitution and current-spec directory conventions.
6. Exclude proposed changes, archives, alternatives, and rejected work from
   automatic governing eligibility.
7. Add deterministic candidate grouping, duplicate detection, contradiction
   flags, and currentness checks.
8. Add workspace-scoped query and command endpoints:
   - list candidates and governing items;
   - approve;
   - edit and approve through replacement;
   - keep as guidance;
   - need evidence;
   - reject;
   - challenge;
   - retire;
   - inspect provenance and affected projections.
9. Make Approve direction atomically activate a user-owned replacement when
   its source candidate is still pending.
10. Publish normal local activity records for Direction and knowledge
    curation decisions.
11. Stop challenged, stale, or conflicting items from new projection
    selection.
12. Preserve historical projection references.

Commands are synchronous database decisions. Candidate extraction may use the
existing bounded learning owner; it does not mutate Task or Stage state.

### Frontend

Extend workspace/WorkspaceMemoryPage.tsx rather than building a new page:

- Direction summary at the top;
- Governing and Candidates sections;
- clear state chips;
- compact statement, applicability, confidence, and source count;
- expandable exact provenance and conflicts;
- Approve direction;
- Edit and approve;
- Keep as guidance;
- Need evidence;
- Reject;
- Challenge or replace;
- confirmation only for actions that remove governing influence.

Wire the existing learning pause, resume, and retry APIs and correct Today's
learning-state labels in the same slice because users need trustworthy
learning status while settling direction.

### Tests

- no candidate governs without an explicit approval decision;
- approving a pending candidate creates an active user-owned replacement and
  one exact Direction revision atomically;
- wrong-workspace item decisions fail;
- statement-digest mismatch fails;
- two commands against one Direction revision cannot both commit;
- semantic knowledge edits create replacements and cannot mutate governing or
  historical item content;
- challenged and superseded items leave new selections immediately;
- historical projections still render the exact old statement;
- source-path recognition classifies current specs separately from proposals
  and archives;
- document section extraction is digest-idempotent and preserves exact
  provenance;
- conflicting candidates remain non-governing;
- replacement preserves lineage and authorship;
- UI actions and filters use the correct state;
- learning controls show and invoke real state transitions.

### Exit criteria

- an existing project can settle a small governing set without writing a spec
  from scratch;
- every governing item is explicit, current, scoped, and traceable;
- bad direction is reversible;
- the Memory surface is the only curation home.

## Slice 2 — Plan, development, and Brain alignment

Goal: give one Plan revision one governing basis and carry it through the
existing four-Stage lifecycle.

### Plan

1. For initial draft, user-requested self-review redraft, replan draft, and
   Brain-produced revision Turns, resolve the exact projection before Turn
   insertion and freeze it in launch_input.
2. On accepted agent revision delivery, attach that exact projection to the
   resulting plan_revision before requesting mandatory self-review.
3. For replan seed, user edit, and every other synchronous revision source,
   resolve and attach the projection in the Plan-owner transaction.
4. Include the attached projection in the self-review prompt.
5. Add structured self-review output for:
   - applicable direction ids;
   - alignment;
   - IS/SHOULD/WHY conflict;
   - root-cause concerns typed as PLAN_REASONING;
   - requested clarification;
   - proposed scoped exceptions typed as PROJECT_DIRECTION with exact frozen
     knowledge item id and statement digest.
   Enforce CHANGES_REQUESTED plus CONCERN for a Plan that should change,
   APPROVED plus STEWARDSHIP for an otherwise viable deliberate Direction
   deviation, and BLOCKED when no responsible judgement is possible. Guidance
   alone cannot create a blocking record; malformed combinations use the
   existing blocker path.
6. Require CHANGES_REQUESTED to contain at least one exact concern. Persist the
   stable CONCERN rows and enter the existing AWAITING_APPROVAL checkpoint; do
   not launch a redraft automatically.
7. Add two human decisions at that checkpoint:
   - Revise Plan launches the existing redraft path and creates a new revision;
   - Adjudicate and approve atomically dismisses every concern as incorrect
     review reasoning and records HUMAN approval on the same revision.
8. If the user identifies wrong or inapplicable intelligence, open the relevant
   Memory correction and then create a new Plan revision. Adjudication never
   changes knowledge or Direction.
9. Reuse the existing stewardship follow-up for an open Direction concern. A
   deliberate deviation uses the separate scoped-exception path, not a concern
   dismissal.
10. In the serialized approval transaction, re-resolve the currently applicable
   Direction digest for the frozen Plan intent and compare it with the
   projection's digest.
11. Permit automatic approval only when:
   - the existing exact revision and digest gate passes;
   - self-review approves;
   - there is no open stewardship concern;
   - the projection contains no unresolved applicable governing conflict;
   - the applicable Direction digest is still current.
12. Persist a task-scoped plan_direction_exception for human approval of a
   deliberate deviation. Keep automatic Plan approval, readiness, and merge
   disabled under the existing stewardship-exception policy. Require an
   APPROVED self-review with the matching stewardship concern, and insert the
   exception plus human approval atomically under all revision and digest
   fences. CHANGES_REQUESTED and BLOCKED cannot use this path.
13. If applicable Direction changes before approval, always create a new Plan
    revision and one new mandatory self-review. The revision may retain
    identical prose when its projection digest changed. Never rerun or grant an
    exception against the old basis.
14. When the user chooses a deliberate deviation from CHANGES_REQUESTED, seed
    the revision with the exact item, bounded deviation, rationale, rejected
    aligned alternative, risks, and compensating checks. The reviewer returns
    APPROVED plus typed STEWARDSHIP only when no independent Plan defect remains.

### Local Development and Brain

1. Load the approved Plan projection and exact approval evidence by Plan
   revision. For adjudicated approval, include the bounded concern, reason,
   actor, and time.
2. Add both to LocalDevelopmentRuntimeCoordinator writer and Brain prompts.
3. Add it to TaskBrainConversationRuntime when the conversation concerns the
   active implementation.
4. Expose note_plan_concern for an implementation-discovered direction
   conflict.
5. A concern returns to normal Plan/replan ownership; it does not create a
   Stage or let the worker mutate Plan state.
6. Keep explore_project as optional supporting exploration, not the governing
   snapshot.
7. An adjudication prevents blind replay of the same Plan-review concern, but it
   never suppresses a new Brain finding supported by implementation evidence.

### Remote Development

1. Load the exact persisted approved Plan projection and approval evidence and
   freeze their bounded content, identity, and digests into every remote
   feedback and repair Turn's launch_input.
2. New external facts may create a normal replan request.
3. A remote Turn performs no live Direction lookup.
4. A repair agent cannot silently change Direction or grant an exception.

### Frontend

Add a compact strip to the current Plan card:

- Aligned;
- No governing direction;
- Direction concern;
- Human-approved exception.

The detail shows exact frozen items, digest, applicability, and provenance.
Actions use existing Plan controls: revise, replan, answer, approve exception,
or open the Memory item. Do not add a Direction Stage row.

In the existing self-review area, CHANGES_REQUESTED shows the exact concerns
and two primary actions: Revise Plan and Adjudicate and approve. The dialog
requires a reason for each concern and routes four plain choices:

- Review reasoning is wrong: same-revision adjudication;
- Plan should change: normal revision;
- Intelligence is wrong or not applicable: Memory correction, then revision;
- Deliberate Direction deviation: start a revision that records the exact item,
  bounded deviation, rationale, rejected aligned alternative, risks, and
  compensating checks; use the scoped exception only after APPROVED review.

After success, show Human-approved after review correction with actor, time,
and expandable explanations. This is an approval status, not a Direction state.

### Tests

- a Plan with no direction behaves exactly as before;
- guidance cannot block automation;
- applicable governing conflict opens a stewardship follow-up;
- every stewardship follow-up carries the exact frozen item and statement
  digest, cannot be persisted as PLAN_REASONING, and cannot be adjudicated;
- initial draft, redraft, replan, Brain revision, and user edit all attach a
  projection before self-review;
- an item changed before approval requires a new revision;
- an unrelated Direction decision that does not change the applicable digest
  does not force a replan;
- concurrent Direction decisions are fenced by Workspace revision;
- the exact-one-self-review-per-revision invariant holds;
- CHANGES_REQUESTED waits at AWAITING_APPROVAL and never launches an automatic
  redraft;
- malformed verdict/CONCERN/STEWARDSHIP combinations enter the existing blocker
  path instead of launching work or guessing an exception;
- adjudicating every exact concern stores typed actor/reason/time evidence and
  HUMAN approval atomically on the same revision;
- an omitted concern, changed fence, BLOCKED/failure/no-verdict result, or open
  stewardship concern rejects adjudication with no partial mutation;
- generic resolution or deferral of CONCERN/STEWARDSHIP is rejected, and a
  pre-resolved/deferred concern cannot satisfy adjudication;
- POLICY and AUTOMATION cannot use the adjudication alternative;
- choosing wrong intelligence changes it only through Memory and requires a
  new projection, Plan revision, and self-review;
- a deliberate-deviation revision captures item, bounded rationale,
  alternatives, risks, and checks; it becomes exception-eligible only with
  APPROVED plus the exact typed STEWARDSHIP concern;
- human exception records every concern/item link, Task, Plan revision, reason,
  approver, and expiry atomically; automatic Plan approval, readiness, and merge
  remain disabled;
- the human Direction-exception path rejects an open Direction concern without
  the matching atomic exception and never overrides CHANGES_REQUESTED or
  BLOCKED;
- writer and Brain receive the approved revision's projection and approval
  evidence, including bounded adjudication context when present;
- later knowledge changes do not mutate an approved Plan context;
- note_plan_concern returns through the Plan owner;
- remote feedback and repair use the exact Plan projection and approval evidence
  frozen in launch_input and cannot alter or reload the governing basis.

### Exit criteria

- one Plan revision has one reproducible governing basis;
- wrong Plan self-review reasoning can be corrected by the user without a fake
  Plan edit or a second review of the same revision;
- a symptom-only patch is explicitly checked against root direction;
- the normal Plan, development, Brain, and remote loops remain the only
  lifecycle;
- automation fails open on missing guidance and holds only for valid Direction.

## Slice 3 — direction-aware full review and correction

Goal: use governing direction as a high-value review criterion while keeping
code evidence sovereign.

Slice 0 already supplies the frozen projection mechanism. This slice adds
Direction authority and correction behavior.

### Backend

1. Order applicable Direction before guidance within the three-objective cap.
2. Persist authority, applicability reason, and source references on each
   derived objective.
3. Require direction-based findings to cite both:
   - one frozen Direction criterion;
   - exact frozen changed-source evidence.
4. Add review-local commands:
   - challenge the item for future projections;
   - ignore the objective in the current UI;
   - open provenance;
   - start normal Continue or Re-review.
5. A challenge writes the normal Direction decision. It never edits the
   current round.
6. A new round recomputes only from current Direction.
7. Preserve independent safety and correctness findings across a challenge.
8. Keep verdict and publication entirely user-owned.

### Frontend

Extend existing review components:

- Direction versus Guidance badge;
- applicability and provenance drawer;
- Frozen at round start label;
- Challenge for future rounds;
- Ignore in this view;
- Continue or Re-review after correction;
- clear distinction among Wrong direction, Incomplete implementation, and
  Insufficient evidence.

SubmitReviewPopover remains the verdict and publication boundary.

### Tests

- Direction gets first chance within the cap but cannot displace mandatory
  objectives;
- a direction-only assertion cannot become a finding;
- a valid violation needs changed-source evidence and verification;
- a challenge leaves current round immutable;
- next round omits challenged Direction;
- independent findings remain;
- user verdict and publication are unchanged;
- no challenge action performs a GitHub write.

### Exit criteria

- full review can distinguish wrong path, incomplete path, and unknown;
- bad intelligence cannot repeatedly block future rounds after correction;
- review remains reproducible and evidence-led.

## Slice 4 — Project Briefing

Goal: help the user maintain an accurate long-term mental model of the project.

### Backend

1. Add bounded patch evidence to the existing merged-PR evidence bundle where
   needed for a factual change statement.
2. Extend the existing lesson extraction call to return an optional structured
   brief. Do not make a second model call for the same PR.
3. Persist one brief per PR evidence identity with schema version, input
   digest, coverage, and generation status.
4. Provide factual fallback from merge metadata and changed paths.
5. Add read-only queries for recent, 7-day, and 30-day views.
6. Rank importance deterministically from affected breadth, governing-area
   relevance, compatibility impact, and repeated evidence. Store the inputs
   used for the rank.
7. Never label merged as released without release evidence.
8. Never feed brief prose into Project Intelligence retrieval.

### Frontend

Extend WorkspaceTodayPage.tsx with a compact Project Briefing card:

- What recently became true;
- Important landed changes;
- affected area;
- why it matters;
- confidence and evidence coverage;
- View 7 days and View 30 days;
- exact source link;
- Landed versus Released label.

The detail view groups by date and approved Code Area, not by a new project
taxonomy.

### Tests

- no model call occurs on a read endpoint;
- extraction retry is digest-idempotent;
- factual fallback appears when extraction fails;
- closed or open PRs never appear under Landed;
- merged work is not called Released without evidence;
- low evidence coverage is visible;
- briefing prose never appears in an agent projection;
- duplicate merge events do not duplicate cards.

### Exit criteria

- the user can understand recent important changes without reading every PR;
- every statement is traceable and correctly qualified;
- the feature reuses learning evidence and adds no second crawler.

## Slice 5 — Review Voice, gated by evidence

Goal: make drafts sound like the user without allowing style to alter
judgement.

Do not start this slice until the product has enough explicit, attributable
draft-to-published edits or current-user-authored comments to evaluate a
profile. The initial threshold is a product metric, not an excuse to ingest
other reviewers' comments.

### Backend

1. Capture the generated semantic finding and the user's final published text
   only after explicit publication.
2. Ingest only published comments whose authenticated GitHub author is the
   current user, plus explicit preferences the user saves.
3. Reject signals without exact current-user attribution.
4. Derive one versioned personal profile with bounded traits.
5. Apply Voice only after protected finding semantics are frozen.
6. Render through bounded templates that insert claim, impact, action, anchor,
   evidence, severity, and verdict fields unchanged. Do not perform a
   free-form model rewrite.
7. Provide enable, disable, preview, and reset.
8. Record the profile revision used for each draft.

### Frontend

Add settings only:

- enabled state;
- bounded brevity, directness, and explanation-order preferences with Save;
- eligible signal count;
- concise profile preview;
- before-and-after example;
- reset;
- last updated and revision.

Do not add voice controls to every finding card.

### Tests

- another user's or reviewer's prose is ineligible;
- authenticated current-user comments and explicit preferences are eligible;
- saved bounded preferences create a new profile revision and round-trip
  through Settings;
- unpublished drafts are not training signals;
- severity, requested action, anchor, evidence, and verdict cannot change;
- every protected structured field is present unchanged in rendered output;
- disable and reset affect future drafts only;
- insufficient evidence leaves current fixed output unchanged.

### Exit criteria

- Review Voice is personal, reversible, and semantically inert;
- the fixed concise review style remains the fallback.

## UI delivery audit and design gate

The current frontend provides foundations, not the target Project Intelligence
experience:

- `WorkspaceMemoryPage` can curate learned merged-PR lessons, but has no
  Direction membership, authority states, provenance drill-down, or correction
  flow;
- `PipelinePlanCard` has generic approval and revision controls, but no frozen
  Direction strip, exact self-review concerns, adjudication, or exception
  evidence;
- Agent Review can label an objective as Project Intelligence, but does not
  distinguish Direction from Guidance or expose frozen provenance, resolution,
  Challenge, and Ignore;
- `WorkspaceTodayPage` shows Task landings, not the repository-level Project
  Briefing;
- learning pause, resume, and retry operations exist, but there is no usable
  control surface and the onboarding copy does not represent all real states;
- the current whole-repository default and approval-first Code Area choice in
  the new-Trunk dialog are sufficient for the first delivery.

Before implementing the missing Direction-dependent UI, produce exactly three
state sheets using the existing component families:

1. **Memory and learning** — Candidate, Governing, Conflict, Challenged,
   Superseded, and Retired Direction states; compact provenance; approval,
   evidence, rejection, challenge, and replacement actions; and real learning
   status and controls using the existing Settings patterns.
2. **Plan decision** — the four Direction-strip states, expanded frozen basis,
   exact CHANGES_REQUESTED concerns, Revise Plan versus Adjudicate and approve,
   every correction/deviation route, and attributed post-adjudication evidence.
3. **Agent Review objective** — Deterministic, Direction, and Guidance labels;
   Frozen at round start; provenance and applicability; Supported, Refuted, and
   Unresolved; Challenge for future rounds; and Ignore in this view.

These are state specifications for existing surfaces, not new pages. The Plan
sheet is the design gate for development because its alternatives carry
different authority and lifecycle effects that must not be collapsed into one
override action.

No additional first-delivery design surface is required for Project Briefing;
its existing Today and detail visual references plus the tracked contract below
are sufficient. Learning controls reuse Settings, Code Areas keep their current
approval-first interaction, and Review Voice settings remain deferred until
the evidence gate for Slice 5 is met. Optional local mockups never replace this
tracked, self-contained UI contract.

## UI build brief

Use this brief when implementing the missing UI. It is intentionally limited
to existing surfaces.

> Extend ByteQuay's existing workspace Memory, Plan card, Agent Review panel,
> and Today page for Project Intelligence. Keep the current layout, typography,
> components, and interaction patterns; add no new top-level navigation and no
> workflow stage.
>
> On Memory, add a compact Project Direction summary followed by Governing and
> Candidate lists. Each item shows state, statement, applicable area,
> confidence, and source count. Expanded details show exact provenance,
> conflicts, and affected frozen projections. Actions are Approve direction,
> Edit and approve, Keep as guidance, Need evidence, Reject, and Challenge or
> replace. Destructive or governing changes require clear confirmation.
>
> On the Plan card, add a one-line Direction strip with Aligned, No governing
> direction, Direction concern, or Human-approved exception. Expanded content
> shows the exact frozen projection and actions that reuse existing replan and
> approval controls. An exception clearly disables automatic Plan approval,
> readiness, and merge. In the existing self-review area, a Changes requested
> result shows each concern and offers Revise Plan or Adjudicate and approve.
> Adjudication requires an explanation for every concern and shows Review
> reasoning is wrong, Plan should change, Intelligence is wrong or not
> applicable, and Deliberate Direction deviation as explicit routes. After
> choosing deliberate deviation, seed a revised Plan with the exact item,
> bounded rationale, alternatives, risks, and compensating checks. After
> acceptance, show who approved the reviewed Plan and why; do not imply that
> Project Intelligence changed.
>
> In Agent Review, label objectives Deterministic, Direction, or Guidance. Show
> Frozen at round start, applicability, provenance, and Supported, Refuted, or
> Unresolved resolution. Add Challenge for future rounds and Ignore in this
> view, while preserving the existing explicit verdict and publish controls.
>
> On Today, add a Project Briefing card showing recent landed changes and two
> or three important items with area, why it matters, confidence, coverage,
> and source link. Provide 7-day and 30-day detail views. Never use Released
> unless release evidence exists.
>
> All empty, loading, failed, paused, stale, conflict, and partial-evidence
> states must be understandable without technical language. Whole repository
> stays the Code Area default. Do not imply that a Code Area changes the
> worktree or source boundary. Do not imply that intelligence publishes or
> decides the review verdict.

Before UI implementation, inspect the current component and the corresponding
local mockup assets when available. Match hierarchy and interaction, not
pixel-perfect styling. Do not add a component library.

## API shape

Names may follow current controller conventions, but the product needs only
these semantic operations.

### Read

- list workspace knowledge and Direction state;
- inspect one item's provenance, currentness, conflicts, and affected
  projections;
- read the exact Plan projection;
- read the current Plan decision model with Task epoch and optimistic version,
  Stage id, generation, and optimistic version, Plan revision id and content
  digest, self-review id/status/verdict,
  intelligence and applicable-Direction digests, adjudication eligibility and
  reason, exact concern ids/descriptions/basis and any Direction item/statement
  digest, resolution kind/reason/actor/time, and accepted approval evidence;
- read the exact ReviewRound projection;
- list recent briefing entries;
- read learning run state;
- later, read Review Voice status and preview.

### Commands

- decide a Direction candidate;
- replace or challenge Direction;
- approve a task-scoped exception through the Plan owner;
- request a revised Plan after CHANGES_REQUESTED through the existing Plan
  owner;
- adjudicate every current Plan concern and approve through one Plan-owner
  command;
- pause, resume, or retry learning;
- start Continue or Re-review through existing review commands;
- later, save bounded Review Voice preferences or enable, disable, and reset
  Review Voice.

The Direction-exception command includes a stable clientCommandId, the exact
Plan fences, and the complete set of {planFollowupId, knowledgeItemId,
statementDigest, directionRevision, reason} decisions. The Plan owner verifies
that the set covers every open typed STEWARDSHIP row and matches the frozen
projection before recording all exceptions and HUMAN approval atomically.

The adjudicate-and-approve request freezes the displayed decision:

- clientCommandId;
- taskId, taskEpoch, and taskVersion;
- stageId, stageGeneration, and stageVersion;
- planRevisionId and contentDigest;
- selfReviewId;
- intelligenceDigest and expectedApplicableDirectionDigest;
- decisions as the complete set of
  {concernId, resolutionKind: DISMISSED_INCORRECT, reason}.

The Plan owner derives the actor from the current local user identity; the
request cannot choose an arbitrary actor. Success returns APPLIED or REPLAYED,
approval id and kind, every immutable concern resolution, and the next Stage
identity. Decisions are canonicalized by
concernId before hashing. Reusing clientCommandId with the same canonical
payload replays the original result; changing any fence, decision set, or reason
is rejected. A fresh command against stale displayed fields also rejects and
returns the new read model.

All commands are idempotent through stable command ids or the existing owner
protocol. Read endpoints are projection-only and perform no model, Git, or
GitHub work.

## Observability

Add structured counters and logs for:

- projection selection success, empty, ambiguity, and failure;
- item count, direction count, and omitted-by-limit count;
- projection digest mismatch;
- live retrieval attempted after admission, which is an error;
- Direction candidate decisions and challenges;
- Plan automation held by applicable Direction;
- Plan self-review revision requests, accepted adjudications, and rejected
  adjudication reason classes without logging the human explanation;
- hypotheses supported, refuted, unresolved, and invalid resolution attempts;
- briefing extraction success, fallback, coverage, and stale digest;
- later, Voice render success or safe fallback without logging private prose.

Do not log complete knowledge statements, review text, credentials, or source
file bodies.

## Rollout and compatibility

### Existing rows

- Historical Plan revisions without a projection are displayed as Legacy
  empty intelligence. They are not backfilled with current knowledge.
- Historical plan_followup rows retain null typed basis/adjudication fields and
  cannot authorize adjudication or a Direction exception. An already-admitted
  pre-cutover redraft
  continues normally; it is not canceled or rewritten into an adjudication
  wait.
- Existing ReviewRounds retain their historical objectives. They do not gain a
  fabricated projection.
- A nonterminal pre-cutover ReviewAssignmentTurn may finish or retry only from
  its already-immutable launch_input. It does not retrieve live intelligence.
- A pre-cutover round that needs another Turn, seat, Continue, Re-review, or
  answer starts a normal new post-cutover round and freezes both required rows;
  it never fabricates historical intelligence or appends a seat to the old
  round.
- New quick rounds store an explicit canonical empty projection.
- Existing knowledge items remain valid candidates but never become Direction
  without a new user decision.
- Existing Code Area decisions remain unchanged.

### Feature rollout

Each slice is enabled when its migration, service, API, UI, and tests ship.
Avoid a matrix of long-lived feature flags. A short-lived local development
flag is acceptable only when needed to land a migration and UI atomically; it
must be removed before the slice is considered complete.

### Failure behavior

- learning failure: show state; existing development and review continue;
- retrieval failure: empty guidance; deterministic workflow continues;
- missing or ambiguous Workspace: reject Plan/full-review admission; quick
  review remains the only unscoped case;
- projection persistence failure: do not admit the relevant self-review or
  full-review seat;
- stale Direction before projection: omit or flag conflict;
- stale Direction after projection: preserve historical input and require a
  new revision/round for change;
- stale or partial Plan adjudication: reject the command without persisting any
  concern resolution or approval;
- Briefing extraction failure: factual fallback;
- Voice rendering failure: original semantic draft.

## Verification matrix

Every slice runs:

- focused backend unit and repository tests;
- SQLite migration tests from a populated pre-migration database;
- controller authorization and idempotency tests;
- relevant frontend component tests;
- full backend test suite;
- frontend typecheck and test suite;
- git diff check and documentation consistency search.

Review snapshot tests must also prove:

- no Git, GitHub, filesystem, or live knowledge read after accepted capture;
- all continuation and recovery paths load the frozen database rows;
- uncaptured source paths fail closed;
- full review remains serialized under the existing Workspace Local Git
  capacity rules;
- quick review remains unscoped and does not count as a Task.

Plan tests must also prove:

- exact Task and Stage optimistic versions, Stage generation, Task epoch,
  revision, digest, and self-review fences remain intact;
- a changed applicable Direction basis always creates a new revision and cannot
  be covered by an exception;
- identical Plan prose with a changed projection digest creates a new semantic
  revision;
- A/X, B/X, then A/X creates three monotonic revisions; replaying each stable
  revision command creates no duplicate, and a fresh immediate A/X no-op is
  rejected;
- only an exact HUMAN command can approve CHANGES_REQUESTED, and only after all
  of its concerns are atomically dismissed as incorrect;
- exact adjudication command replay returns the original approval and evidence;
  reuse with a changed payload or a fresh stale command rejects;
- generic or pre-existing resolution/deferral of a CONCERN or STEWARDSHIP never
  satisfies adjudication;
- BLOCKED, failed, no-verdict, partial, stale, and open-stewardship cases remain
  ineligible;
- neither intelligence nor a worker mutates lifecycle state.

## Long-term follow-ups

Revisit only after the six slices provide usage evidence:

1. Closed-but-unmerged learning:
   - capture explicit rejection rationale as pending negative evidence;
   - never activate it as truth;
   - distinguish abandoned, superseded, duplicate, and rejected.
2. First-sight activation:
   - reconstruct addressed-by-commit evidence safely;
   - retain the two-independent-confirmations fallback.
3. Semantic Code Areas:
   - permit overlapping areas and multiple assignments only if directory
     scopes prove insufficient;
   - keep approval and retrieval-only semantics.
4. Direction packs:
   - export and import user-settled Direction with provenance;
   - never let an import bypass workspace review.
5. Release intelligence:
   - connect tags and release artifacts so Briefing can distinguish landed,
     shipped, and adopted.
6. Project-specific Voice overlay:
   - add only if measured edits show stable per-project differences.
7. Direction drift checks:
   - compare current specs, approved Direction, and repeated code outcomes;
   - surface questions, never auto-rewrite Direction.
8. Learning admission:
   - if background extraction competes materially with Task execution, model
     it as a normal workspace maintenance Operation with DispatchTicket and
     CapacityManager admission.

These are not first-delivery prerequisites.

## Definition of complete

The redesign is complete when:

- [ ] exact Workspace selection precedes every Plan or full-review projection,
      while quick review stays explicitly unscoped;
- [ ] semantic knowledge edits create immutable replacements;
- [ ] Direction decisions have one monotonic compare-and-swap revision;
- [ ] quick review always has an explicit empty projection;
- [ ] every new full ReviewRound has one immutable source snapshot and one
      immutable intelligence projection;
- [ ] review seats perform no live intelligence retrieval;
- [ ] hypothesis coverage requires explicit frozen evidence;
- [ ] users can settle, inspect, challenge, correct, and retire Direction;
- [ ] one Plan revision has one immutable direction basis and one mandatory
      self-review;
- [ ] Plan approval always rejects a stale applicable Direction digest; scoped
      exceptions cover only items in the unchanged frozen basis;
- [ ] CHANGES_REQUESTED waits for a human decision, and exact all-concern
      adjudication can record HUMAN approval without rerunning self-review;
- [ ] adjudication is immutable, all-or-nothing, and cannot update Project
      Intelligence or serve POLICY/AUTOMATION approval;
- [ ] Direction stewardship concerns have exact frozen item linkage and cannot
      be reclassified or dismissed through adjudication;
- [ ] adjudication reads expose every required fence and stable concern id, and
      command replay is exact and conflict-safe;
- [ ] development, Brain, and remote repair use the approved Plan basis and
      exact approval evidence without treating adjudication as a global rule;
- [ ] Direction influences review angles but not evidence, severity,
      verification, publishability, or verdict;
- [ ] approved Code Areas remain retrieval-only;
- [ ] Project Briefing explains recent landed changes without read-time model
      work or false release claims;
- [ ] Review Voice is either safely delivered after sufficient evidence or
      remains explicitly deferred;
- [ ] every GitHub write still requires explicit user action;
- [ ] no new Stage, scheduler, executor, agent type, or lifecycle owner exists;
- [ ] the development-flow migration remains complete.

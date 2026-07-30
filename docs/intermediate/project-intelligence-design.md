# Project Intelligence architecture

Status: **LOCKED TARGET; REDESIGN DELIVERY NOT STARTED**

Version: **1.1**

Decision date: **2026-07-30**

Related runtime contract:
[development-flow-design.md](./development-flow-design.md)

Delivery plan:
[project-intelligence-delivery-plan.md](./project-intelligence-delivery-plan.md)

This document is the tracked, self-contained product and architecture contract
for Project Intelligence. It replaces the overlapping local notes about
project learning, stewardship, review intelligence, briefings, review tone,
and directory suggestions.

The development-flow contract remains authoritative for lifecycle ownership,
typed Turns, Operations, dispatch, capacity, source snapshots, recovery, and
cleanup. This document controls how intelligence is learned, settled,
projected, corrected, and shown. If the contracts appear to conflict, the
development-flow safety boundary wins and this document must be corrected.

## Purpose

An agent can produce code faster than a person can maintain a durable mental
model of a project. It can also fix a visible symptom while moving the design
in the wrong direction: a local branch may pass every test yet deepen a
misplaced abstraction or preserve an implementation that contradicts the
project's intended architecture.

Project Intelligence addresses both problems:

1. help agents understand what the project is, why it is shaped that way, and
   which constraints apply to the current work;
2. help people keep up with what recently became true and which changes matter;
3. make governing direction explicit, reviewable, and reversible instead of
   treating inferred history as law;
4. improve the first judgement of development and review without weakening
   evidence, verification, or human control.

The governing rule is:

> Resolve once, freeze once, use everywhere. Intelligence selects attention;
> frozen source evidence decides the outcome.

Project Intelligence is not another workflow engine. It adds no Stage,
scheduler, executor, agent type, worktree rule, or lifecycle state.

## Product language

Only four user-facing names are used:

- **Project Intelligence** — the complete workspace-owned learning system.
- **Project Direction** — the small, explicitly user-approved governing subset
  of Project Intelligence.
- **Project Briefing** — the human-facing account of recent and important
  landed changes.
- **Review Voice** — the later, personal phrasing profile learned from the
  user's own review edits.

These terms are deliberately separate:

- learned knowledge is not automatically governing direction;
- a briefing is not agent context;
- review voice is not reviewer judgement;
- an approved Code Area is not a repository root or sandbox boundary.

## Scope

The first complete design covers:

- progressive learning from repository documents and merged pull requests;
- evidence, provenance, applicability, currentness, and lifecycle;
- settlement and correction of Project Direction;
- immutable intelligence projections for Plan revisions and full review
  rounds;
- human adjudication of incorrect Plan self-review reasoning;
- bounded influence on development and review;
- human briefings;
- approval-first Code Area suggestions;
- a safe future Review Voice capability;
- UI states and audit requirements.

The following are explicitly deferred:

- learning project truth from closed-but-unmerged pull requests;
- automatically declaring a document or inferred lesson to be governing;
- semantic or overlapping Code Areas beyond the current directory model;
- a per-project review-voice model before a useful personal signal exists;
- release-note claims without release evidence;
- arbitrary repository search, caller analysis, or Git history from a frozen
  review seat;
- autonomous publication of review comments.

## Main-branch baseline

The current application already has a useful foundation:

- workspace learning runs are durable and can resume;
- repository documents and merged pull-request evidence are catalogued;
- lessons are normalized into canonical knowledge items with provenance,
  applicability, and pending, active, decayed, or retired lifecycle;
- active knowledge can be retrieved for plan, development, review, and CI-fix
  audiences;
- users can accept or retire learned items in the Memory surface;
- directory-based Code Areas can be suggested from analyzed pull-request
  history and require user approval;
- full investigation review keeps deterministic safety objectives and may add
  up to three Project Intelligence objectives;
- publishable findings still require exact changed-line evidence and
  deterministic verification.

The foundation has material gaps:

- review planning and review seats can perform separate live, repository-only
  retrievals, so one round may receive inconsistent guidance;
- repository-only lookup is ambiguous when two workspaces watch the same
  repository;
- the latest session projection is mutable and is not an immutable
  per-Plan-revision or per-ReviewRound input;
- Plan, implementation, Brain review, and remote repair do not share one exact
  direction projection;
- a successful Plan self-review with CHANGES_REQUESTED immediately starts an
  agent redraft, so the user cannot correct a wrong review judgement while
  preserving the reviewed Plan;
- a completed investigation step can currently be mistaken for a clean
  objective without an explicit evidence-backed resolution;
- Project Direction, challenge, correction, and scoped exception do not yet
  exist;
- current knowledge edits can mutate semantic content in place, so governing
  and historically referenced items need replacement-only identity;
- document indexing retains headings and digests but not enough bounded text
  to propose direction candidates from repository specifications;
- Review Voice and Project Briefing do not yet exist;
- closed-but-unmerged pull requests are not learned;
- first-sight automatic lesson activation is effectively unavailable because
  addressed-by-commit evidence is not reconstructed.

This design extends that foundation. It does not revive discarded branch code
or historical runtime ownership.

## Conceptual model

The model is intentionally small:

~~~text
repository documents + merged PR evidence
                  |
                  v
          canonical knowledge_item
                  |
          +-------+--------+
          |                |
          v                v
 active guidance      Project Direction
                      (approved membership)
          |                |
          +-------+--------+
                  |
       applicability resolution
                  |
                  v
       immutable projection + digest
          |                    |
          v                    v
   Plan / development     full ReviewRound

merged PR evidence -----------------> Project Briefing
user draft-to-publish edits --------> Review Voice
~~~

There is one canonical knowledge store. Project Direction is membership and
approval metadata over eligible knowledge items, not a duplicate prose store.
Briefing records and Review Voice are separate because they have different
audiences and must never gain governing authority by accident.

## Claim-specific authority

A single trust ranking is unsafe. Current code may faithfully show what the
project does while also containing the design defect that the user wants to
remove. Authority therefore depends on the claim:

| Question | Primary evidence |
|---|---|
| What does the project do now? (**IS**) | frozen code, tests, configuration, and observed results |
| What is it intended to do? (**SHOULD**) | current specs, constitutions, ADRs, and user-settled Project Direction |
| Why did it become this way? (**WHY**) | merged changes, review resolutions, commits, and linked rationale |
| What governs this task? | applicable, current, explicitly approved Project Direction |

Rules:

1. Current code never proves intended direction by itself.
2. A repository document is evidence of SHOULD, not governing authority merely
   because it exists.
3. Merged history is evidence of accepted outcomes and rationale, not an
   infallible specification.
4. Only user-settled Project Direction may govern.
5. A conflict between IS, SHOULD, and WHY is surfaced as possible design drift.
   The agent must not silently choose one.
6. Source and direction currentness are checked before a new projection is
   frozen. Existing frozen projections remain auditable historical inputs.

## Evidence and learning

### Day-one experience

Once a workspace has a verified local clone, learning begins as bounded
workspace maintenance. The user does not need to start a development Task or a
runtime review.

The sequence is:

1. index eligible repository documents and build manifests;
2. catalogue merged pull requests;
3. fetch bounded files, commits, reviews, inline comments, and timeline
   evidence;
4. extract typed candidate lessons with exact provenance;
5. place new lessons in pending or active state according to deterministic
   confidence rules;
6. update retrieval capsules and Code Area evidence;
7. incrementally process later merge events and perform bounded catch-up.

The app remains usable while learning continues. Learning status is visible and
pause, resume, retry, and evidence failures are explicit.

### Long-term maintenance loop

Long-term maintenance is a closed evidence loop, not a resident agent:

~~~text
observe landed work
  -> explain what changed and why
  -> propose knowledge and direction candidates
  -> let the user settle governing direction
  -> freeze that direction into a Plan or full review
  -> verify against current code evidence
  -> learn from the accepted outcome or correct bad intelligence
~~~

This loop runs at normal product boundaries: clone/catch-up, merge, Plan
creation, full review, user correction, and briefing. It helps every future
Task ask whether a symptom fix follows the intended design, without granting a
background agent authority to rewrite code or direction.

### Sources

Eligible sources include:

- repository-owned specifications, constitutions, architecture decisions,
  contributor guidance, module documentation, and build manifests;
- merged pull-request metadata, changed paths, commits, reviews, inline
  comments, and resolution evidence;
- explicit user-authored workspace knowledge;
- later, user-owned review edit signals for Review Voice only.

The application may recognize established source conventions without taking a
runtime dependency on their tools:

- [GitHub Spec Kit](https://github.com/github/spec-kit/blob/main/README.md) uses
  .specify/memory/constitution.md for foundational principles; that path may
  provide direction candidates.
- [OpenSpec](https://github.com/Fission-AI/OpenSpec/blob/main/docs/concepts.md)
  treats openspec/specs as current source-of-truth material and
  openspec/changes as proposed work; current specs may provide candidates,
  while proposed changes do not govern.
- OpenSpec archives, rejected alternatives, and similar historical material
  remain evidence only.

Recognition is path and content classification. ByteQuay does not invoke those
projects' CLIs, adopt their lifecycle, or make their files governing
automatically.

### Learning result

The result of learning is not free-form chat memory. It is a bounded collection
of typed knowledge items such as:

- architecture principle;
- invariant or compatibility contract;
- recurring review concern;
- design rationale;
- test or build rule;
- domain concept or glossary entry.

Every item carries:

- workspace and repository identity;
- immutable semantic item id and statement digest;
- normalized statement;
- kind and intended audiences;
- applicability, including paths or approved Code Areas when known;
- lifecycle and confidence;
- source provenance and source digest;
- first and last confirmation;
- contradiction, supersession, and retirement state.

An item with missing or stale provenance cannot become Project Direction.

An item's semantic content and applicability are immutable once created.
Changing its statement, rationale, recipe, audience, or scope creates a
replacement item linked to the prior item. Lifecycle, confidence, and
confirmation counters may advance without changing semantic identity. Frozen
projections embed the exact statement and digest, so later replacement or
retirement cannot rewrite historical input.

### Merged, closed, and released

- Merged pull requests are eligible learning evidence.
- Closed-but-unmerged pull requests are not project truth. A future feature may
  retain them as pending negative or alternative evidence, with an explicit
  rejection reason and no automatic activation.
- A merged change is landed, not necessarily released.
- A briefing may say released only when release evidence exists.

### Activation

Activation remains conservative:

- explicit user-authored knowledge may be active immediately;
- a user may explicitly accept a learned item;
- repeated independent, complete evidence may activate an item under the
  existing confidence policy;
- incomplete or contradictory evidence stays pending;
- superseded or challenged evidence decays or retires.

Automatic activation is never equivalent to Project Direction approval.

## Project Direction

### What it is

Project Direction is the small set of current, scoped statements that the user
has approved as governing future decisions. Examples include:

- keep the engine independent of connector-specific behavior;
- the SPI is a compatibility boundary;
- prefer one canonical ownership path over edge-case branches;
- a named module may depend inward but not outward.

Direction should express durable constraints, not restate every implementation
detail.

### How an existing project settles direction

The user does not need to author a specification from scratch. ByteQuay
bootstraps settlement:

1. propose candidates from eligible current documents, repeated merged
   outcomes, and explicit workspace knowledge;
2. group duplicates and show exact provenance plus possible contradictions;
3. ask the user to choose Approve direction, Edit and approve, Keep as
   guidance, Need evidence, or Reject;
4. when approving a pending candidate, atomically materialize an active,
   user-owned item revision with user provenance, then store Direction
   membership over that exact item;
5. keep unsettled candidates non-governing;
6. periodically surface changed-source and contradiction checks.

Editing creates a replacement knowledge revision with user authorship. It does
not rewrite learned history.

### Eligibility and currentness

A direction item must be:

- active;
- supported by inspectable provenance;
- explicitly approved by a user;
- scoped to the workspace;
- applicable to the current repository area or whole repository;
- not contradicted by a newer governing item;
- current against its source revision.

Within an exact Workspace, missing, stale, or conflicting direction fails open:
the agent labels the uncertainty and does not use the item as a blocking gate.
Missing or ambiguous Workspace ownership is an authorization failure for Plan
and full review, not an excuse to guess.

### Challenge and correction

Wrong intelligence must be easy to repair.

The user can:

- challenge the item's correctness;
- correct it through a replacement;
- narrow or expand its applicability;
- keep it as non-governing guidance;
- retire it;
- approve a task-scoped exception.

A challenge immediately prevents the item from entering new projections. It
does not rewrite a completed review or erase independent findings. Audit
history retains the prior statement, provenance, challenge, replacement, and
affected projection ids.

A task-scoped exception is explicit human approval. It records the direction
item and statement digest, exact STEWARDSHIP concern, Task and Plan revision,
reason, approver, and lifecycle. It expires when that revision is superseded or
the Task becomes terminal. It permits human Plan approval only: automatic Plan
approval remains held, and the Task's existing stewardship-exception policy
keeps automatic readiness and merge disabled. A later Plan revision must align
or receive a new exception. An agent cannot grant its own exception. The
self-review must still be APPROVED and may carry exact typed stewardship
concerns; CHANGES_REQUESTED or BLOCKED cannot be overridden through this path.
The Plan owner records one matching exception for every open STEWARDSHIP concern
and the human approval atomically under the same Task, revision, content digest,
intelligence digest, and current-Direction fences. Human approval cannot bypass
an open Direction concern without that exact concern-to-item record.

Every Workspace Direction ledger has a monotonic revision and canonical digest.
A decision uses compare-and-swap against the current revision, so concurrent
approve and challenge commands cannot create two current tips. A Plan
projection stores both the ledger revision and the digest of its applicable
Direction. Before Plan approval, the Plan owner resolves the applicable digest
again in the serialized approval transaction. A mismatch always requires a new
Plan revision. The scoped exception above applies only to a deliberate deviation
from an item already present in the still-current frozen projection; it never
accepts a basis change.

### Human adjudication of Plan self-review

The mandatory Plan self-review is evidence for the user, not a second product
owner. A successful CHANGES_REQUESTED verdict therefore enters the existing
AWAITING_APPROVAL checkpoint with its exact concerns instead of automatically
starting another draft. The Plan card offers two paths:

1. **Revise Plan** starts the existing redraft path. The result is a new Plan
   revision with one new mandatory self-review.
2. **Adjudicate and approve** lets the user state that the review reasoning is
   wrong. The user must give a reason for every current concern. The Plan owner
   atomically records every concern as DISMISSED_INCORRECT and inserts one HUMAN
   approval for the same revision.

Adjudication is deliberately narrow:

- it applies only to a successful CHANGES_REQUESTED self-review for the exact
  current Plan revision;
- every CONCERN from that self-review must be dismissed in the same command;
  one omitted, changed, or still-open concern rejects the entire command;
- every exact CONCERN must still be OPEN. Generic follow-up resolution and
  deferral cannot mutate CONCERN or STEWARDSHIP rows;
- BLOCKED, failed, canceled, superseded, or successful-without-verdict review
  results are not eligible;
- an open STEWARDSHIP concern is not a review-reasoning dismissal. The user
  must correct Direction and create a new Plan revision, or use the existing
  scoped Direction-exception path when its APPROVED-review preconditions hold;
- POLICY and AUTOMATION approval still require an APPROVED self-review;
- the exact Task id, epoch, and optimistic version, Stage id, generation, and
  optimistic version, Plan revision, content digest, self-review, intelligence
  digest, and current applicable-Direction digest are checked in the serialized
  command;
- no self-review is rerun, and no partial dismissal is persisted if approval
  fails.

The self-review contract keeps the paths distinguishable: CHANGES_REQUESTED
plus CONCERN means the Plan itself should change; APPROVED plus STEWARDSHIP
means the Plan is otherwise viable but deliberately differs from applicable
Direction; BLOCKED means the reviewer cannot make a responsible judgement.
Guidance alone cannot create any blocking form. A malformed combination fails
closed into the existing review blocker instead of guessing the user's intent.
Every STEWARDSHIP row carries the exact frozen Direction item and statement
digest; CONCERN rows are explicitly PLAN_REASONING and cannot carry a Direction
reference.

To request a deliberate Direction exception after CHANGES_REQUESTED, Revise
Plan creates a substantive candidate that states the exact Direction item,
bounded deviation, rationale, rejected aligned alternative, risks, and
compensating checks. If the Plan is otherwise viable, self-review returns
APPROVED plus the typed STEWARDSHIP concern. An independent Plan defect still
returns CHANGES_REQUESTED; the exception request cannot hide it.

The adjudication record reuses the existing Plan concern and approval records.
It stores a typed resolution, required explanation, actor, and time and becomes
immutable when accepted. It is review-quality feedback only: it does not edit,
challenge, retire, narrow, or replace Project Intelligence automatically. If
the user says the intelligence or Direction is wrong or not applicable, the UI
opens the corresponding Memory correction action; that change affects a new
Plan revision with a new frozen projection and one self-review.

This contract concerns Plan self-review before development. Full Agent Review
already leaves findings, included comments, publication, and the GitHub verdict
under explicit user control. It does not need a parallel adjudication workflow.

## Applicability and Code Areas

Applicability is resolved from the exact workspace, repository, frozen changed
paths, task intent, and approved Code Areas.

Rules:

1. Workspace identity is mandatory. Repository-only lookup is not allowed.
2. Plan and full-review admission reject missing or ambiguous Workspace
   ownership. Quick review is the only unscoped case.
3. Whole repository is the default.
4. Suggested Code Areas require user approval before assignment.
5. An approved Code Area narrows retrieval and improves explanation only.
6. It never changes the repository root, working directory, worktree,
   filesystem sandbox, source snapshot, or capacity boundary.
7. One item may apply to multiple areas; a directory name is evidence of
   applicability, not semantic truth.

Sibling Trunks and Tasks use the same workspace corpus but receive independent
frozen projections for their exact intent and changed paths. A Trunk or thread
directory is not a new knowledge owner. This lets the reviewer choose different
angles for core, engine, SPI, or connector work without leaking mutable context
between concurrent Tasks.

The existing directory suggestion feature remains the first implementation.
Overlapping semantic areas are a later evidence-driven enhancement.

## Immutable intelligence projection

Agents never receive a live, open-ended memory query as the governing input to
a decision.

Before a Plan draft Turn or full ReviewRound begins, the owning command
resolves one bounded projection and stores it in the immutable launch or round
input:

- exact workspace and repository identity;
- projection id and schema version;
- audience and owner id;
- knowledge item ids and statement digests;
- statements and kinds;
- guidance or direction authority;
- applicability reason and matched paths/areas;
- provenance references and source digests;
- Direction ledger revision and applicable Direction digest;
- currentness and conflict state at capture;
- deterministic ordering and omission reasons;
- projection digest and capture time.

The projection contains enough statement and provenance data to be usable
without re-reading mutable knowledge tables. Later activation, retirement, or
correction affects only a new Plan revision or ReviewRound.

The existing latest-by-audience session projection may remain a convenience
cache, but it is never the proof of what a Plan or review saw.

### Bounded selection

Selection is deterministic:

1. applicable Project Direction first;
2. applicable active invariants and compatibility contracts;
3. recurring concerns and principles;
4. audience-specific supporting rationale when space remains.

Hard size limits apply. Omitted items are recorded by reason. Retrieval failure
produces an empty projection and preserves the base workflow.

## Influence on development

### Plan

Each Plan draft Turn freezes one exact direction projection in its immutable
launch input. When the agent records the resulting Plan revision, that same
projection is attached to the revision before its mandatory self-review. The
planning agent and self-review therefore use identical input. They use it to:

- identify affected architecture and areas;
- distinguish symptom repair from design correction;
- state applicable constraints;
- surface IS/SHOULD/WHY conflicts;
- choose a root-cause path rather than accumulate edge-case branches;
- record stewardship concerns and requested clarification.

A user-edited or otherwise synchronously created revision resolves and attaches
its projection in the revision-owner command before self-review. Every revision
source has one basis; none falls back to a later live lookup.

Direction may prevent automatic Plan approval only when it is approved,
current, applicable, conflict-free, and part of that revision's frozen
projection. Guidance never blocks.

V2 permits exactly one mandatory self-review per Plan revision. A
CHANGES_REQUESTED result waits for the user to revise or adjudicate; it does not
start a redraft automatically. If direction is challenged or its basis changes
before approval, the system creates or supersedes with a new Plan revision and
performs one new self-review. A basis change is a semantic Plan revision even
when the Plan prose is unchanged. A scoped exception is available only while
the applicable Direction digest still equals the revision's frozen digest.
Retry idempotency belongs to the same stable command or Turn, not to content
history: replay returns the original revision, a new command that is identical
to the immediately current prose and projection is rejected as a no-op, and
returning to an older prose-plus-projection after an intervening revision
creates a valid new revision. The system never reruns self-review against the
same revision.

After human Plan approval, the frozen projection remains the audit basis for
that revision. When approval used adjudication, its exact task-scoped concern
resolutions accompany the approval as immutable downstream context. They do not
change the projection. Later direction changes do not silently invalidate work
already in flight. The user may explicitly replan or stop.

### Local Development and Brain review

Implementation and Brain review receive the exact approved Plan projection and
approval evidence. For adjudicated approval, that evidence includes the bounded
concern, human reason, actor, and time. They do not add a second live guidance
selection or make the task-scoped decision global.

They use it to:

- preserve intended boundaries while implementing;
- test the design path, not only the reported symptom;
- detect a local patch that conflicts with the Plan's governing direction;
- avoid replaying a dismissed Plan-review concern without new implementation
  evidence;
- explain deviations and request replan or an exception.

The projection does not give agents permission to inspect source outside the
normal Task/worktree and tool policy. Adjudication does not suppress a new Brain
finding supported by the implemented code; it only records why the prior Plan
concern did not require revision.

### Remote Development

Every remote feedback or repair Turn loads the exact persisted approved Plan
projection and approval evidence and freezes their bounded content into that
Turn's immutable launch input beside the relevant new external facts. It
performs no live Direction lookup. New review feedback can motivate a replan;
it cannot silently replace the governing basis or erase an adjudication.

## Influence on review

The review contract is:

> Project Intelligence selects and explains review angles. Frozen code
> evidence determines findings. Deterministic verification determines
> publishability. The user determines the verdict.

### Admission and snapshot

1. The command resolves an exact workspace before any intelligence selection.
2. Quick review stays unscoped and diff-only. Its intelligence projection is
   empty.
3. Full review captures the immutable source snapshot required by C48 and C49.
4. In the same accepted round-creation boundary, the owner resolves and stores
   one immutable intelligence projection and digest.
5. The planner, every investigator, finalization, and the UI read that
   projection only. A seat performs no live Project Intelligence retrieval.
6. The independent finding verifier remains blind to Project Intelligence. It
   receives the candidate finding and frozen code evidence only.

### What intelligence may change

It may change:

- initial review angles;
- affected-area hypotheses;
- objective wording and ordering;
- bounded reviewer recipes;
- which applicable risk is examined first;
- explanations of why an angle was selected.

It may not change:

- quick versus full review class;
- seat count, budget, or capacity lane;
- mandatory safety objectives;
- source scope or frozen capabilities;
- evidence requirements;
- severity rules;
- verifier independence;
- failure classes;
- publishability;
- the user's final verdict.

The deterministic base objectives always remain. Full review may add at most
three intelligence objectives; applicable Project Direction is considered
before guidance.

### Hypothesis truthfulness

An objective is not clean because an agent completed a step.

Every hypothesis must end as:

- **supported** — exact frozen evidence ids and an explanation support it;
- **refuted** — exact frozen evidence ids and an explanation disprove it;
- **unresolved** — the available snapshot cannot decide.

Only an explicit, validated resolution may mark an objective investigated
clean. A completed tool call, missing file, empty search, or model assertion is
not resolution. Uncaptured paths fail closed and are reported as unavailable.

### Findings

Project Direction is a review criterion, not source evidence. A direction-based
finding requires:

- the exact applicable direction item from the frozen projection;
- exact frozen changed-file evidence for investigation and causal explanation;
- an exact changed-line diff anchor for any publishable finding;
- a causal explanation and actionable correction;
- the existing severity and deterministic verification gates.

Intelligence alone cannot create, suppress, upgrade, or publish a finding.
Safety and correctness findings remain valid even if all intelligence is later
challenged.

### Review outcomes

The product must keep these cases distinct:

| Case | Review result |
|---|---|
| Direction is correct and implementation is complete | no blocking finding; approval remains the user's choice |
| Implementation violates applicable direction and code evidence proves it | changes requested or replan candidate |
| Direction is correct but implementation is incomplete | focused changes requested; stay in the fix loop |
| Direction itself appears wrong | challenge direction; do not force a code change from that item |
| Evidence is incomplete | unresolved question or coverage gap; never invent a clean result |
| Guidance conflicts with code but is not governing | investigate and explain; guidance cannot block |

The review engine produces findings and coverage state, not an automatic
approve/reject decision. The user chooses the GitHub verdict and explicitly
publishes.

### Correcting bad intelligence during review

When a user challenges an item:

1. new review rounds stop selecting it;
2. the current round remains immutable and auditable;
3. a normal Continue or Re-review command creates a fresh source and
   intelligence snapshot;
4. objectives derived only from the challenged item are recomputed;
5. independent code findings remain;
6. affected historical output is labeled with its frozen source, not deleted.

An Ignore for this round action changes presentation only. It cannot mutate the
frozen projection or erase a finding.

## Review Voice

Review Voice is a later, personal post-judgement phrasing layer.

Eligible signals are limited to:

- the user's own draft-to-published edits;
- comments the user authored and explicitly published;
- explicit user preferences such as concise, direct, or explanatory.

Project-wide comments written by other people are not copied as the user's
voice. Reviewer personas and substantive project lessons are separate.

The first implementation should use one resettable personal profile. A
per-project overlay is deferred until enough evidence demonstrates a real
difference.

Voice may change:

- phrasing;
- brevity;
- directness;
- explanation order;
- greeting or closing habits.

Voice may not change:

- file or line anchor;
- factual claim;
- evidence ids;
- severity;
- action requested;
- resolution state;
- publishability;
- verdict.

The first implementation is a constrained renderer over the structured finding
record. Protected claim, impact, requested action, anchor, evidence, severity,
and verdict fields are inserted unchanged; the profile selects only a bounded
template, brevity, directness, and ordering. It does not ask a model to prove
free-text semantic equivalence. A future free-form rewrite would need a
separately designed semantic verifier and remains deferred. The user can
preview, disable, or reset the profile, and every use records its profile
revision.

## Project Briefing

Project Briefing answers:

- what recently became true;
- which landed changes are important;
- why they matter;
- where the user should look next;
- how complete the evidence coverage is.

It reuses the merged-PR evidence pipeline. Extraction produces one bounded
structured brief alongside lessons so the app does not create a second crawler
or a model call on the read path.

Each per-PR brief records:

- factual title and repository;
- merge identity and time;
- affected areas;
- evidence-backed change statement;
- importance reason and confidence;
- source links;
- coverage and missing evidence;
- landed-versus-released state.

The default surface is a compact Today card with a short recent window and a
detail view for 7- and 30-day exploration. Factual fallback remains available
when model extraction fails.

Briefing prose is human-only. It never activates knowledge, settles direction,
enters an agent projection, or claims release without release evidence.

## User interface

No new top-level review page or workflow stage is required.

### Memory

Extend the existing workspace Memory surface with:

- Project Direction and Candidates sections;
- Governing, Candidate, Challenged, Conflict, Superseded, and Retired states;
- exact provenance and applicability;
- Approve direction;
- Edit and approve;
- Keep as guidance;
- Need evidence;
- Reject;
- Challenge or replace;
- affected Plan/review projection links.

The default view stays compact. Provenance and audit details expand on demand.

### Plan

Add one compact direction strip to the existing Plan card:

- Aligned;
- No governing direction;
- Direction concern;
- Human-approved exception.

It is not a fifth Stage. Opening the strip shows the frozen direction items,
digest, applicability, concern, and action to replan, challenge, or approve an
exception. An exception state also says that automatic Plan approval,
readiness, and merge are disabled.

Reuse the existing self-review and approval area for a CHANGES_REQUESTED
decision. Show every exact concern and offer Revise Plan or Adjudicate and
approve. The adjudication dialog requires an explanation per concern and makes
the alternatives explicit:

- Review reasoning is wrong — eligible for same-revision adjudication;
- Plan should change — start the normal revision path;
- Intelligence is wrong or not applicable — open Memory correction, then
  create a new Plan revision;
- Deliberate Direction deviation — start a revised Plan that explicitly records
  the bounded deviation, rationale, alternatives, risks, and compensating
  checks, then use the scoped exception path after APPROVED self-review.

After adjudication, show Human-approved after review correction with the actor,
time, and expandable reasons. Do not overload the Direction strip or imply that
the underlying Project Intelligence was changed.

### Review

Extend the existing review Plan card and agent column:

- label deterministic versus Project Intelligence objectives;
- show the exact item and provenance behind an intelligence objective;
- display Frozen at round start;
- show Supported, Refuted, or Unresolved resolution with evidence;
- provide Challenge for future rounds and Ignore in this view;
- preserve unrelated findings after a challenge.

The publish surface continues to require explicit user action and verdict.

### Today

Add a Project Briefing card to the existing Today surface:

- recent landed changes;
- two or three important items;
- area and evidence-coverage chips;
- View 7 days and View 30 days;
- clear Landed and Released wording.

### Settings

Learning controls expose real running, paused, failed, and complete state plus
pause, resume, and retry. Review Voice settings appear only when implemented
and include enable, preview, reset, signal count, profile revision, and bounded
preferences for brevity, directness, and explanation order.

## Security, privacy, and audit

- Project Intelligence is workspace-scoped.
- Review Voice is user-scoped and never inferred from another person's prose.
- Every governing decision and correction is attributable.
- Exact source links and digests are retained.
- Frozen projections are immutable audit records.
- Prompt text is never accepted as authority merely because it came from a
  repository file.
- No learned output is published to GitHub automatically.
- Deleting a workspace follows the existing workspace deletion and durable
  execution fences; Project Intelligence adds no bypass.

## Locked invariants

1. Project Intelligence introduces no new Stage or runtime owner.
2. CapacityManager remains the sole development-flow admission authority.
3. Exact Workspace identity precedes every workspace-scoped intelligence
   retrieval.
4. Quick review receives no workspace intelligence.
5. One post-cutover full ReviewRound uses one immutable source snapshot and
   one immutable intelligence projection.
6. One post-cutover Plan revision uses one immutable direction projection and
   exactly one mandatory self-review.
7. A live knowledge read cannot alter an admitted Plan review or ReviewRound.
8. Guidance never blocks development or review.
9. Only current, applicable, conflict-free, user-approved Direction may block
   automatic Plan approval.
10. Intelligence never counts as code evidence.
11. Intelligence never changes severity, verification, publishability, or the
    user verdict.
12. A completed investigation step is not a clean resolution.
13. Challenges affect future projections and preserve audit history.
14. Code Areas affect retrieval only.
15. Briefing prose never becomes agent guidance automatically.
16. Review Voice changes phrasing only and is reversible.
17. Closed-but-unmerged work never becomes project truth automatically.
18. Nothing publishes to GitHub without explicit user action.
19. Knowledge semantic edits create replacements; they never rewrite a
    governing or historically projected item.
20. Direction decisions advance one monotonic Workspace revision through
    compare-and-swap.
21. Plan approval requires the frozen and current applicable Direction digests
    to match. A scoped exception can cover only a deliberate deviation from an
    item inside that unchanged basis.
22. CHANGES_REQUESTED Plan self-review waits for human decision and never starts
    an automatic redraft.
23. Same-revision adjudication resolves every exact CONCERN and records HUMAN
    approval atomically; POLICY and AUTOMATION cannot use it.
24. BLOCKED, failed, no-verdict, partial, stale, and open-STEWARDSHIP cases are
    not adjudication-eligible.
25. Adjudication records review-quality feedback and never mutates Project
    Intelligence or Project Direction automatically.
26. A Direction conflict is typed STEWARDSHIP linked to the frozen item and
    statement digest; it cannot be adjudicated as PLAN_REASONING.
27. Plan revision retry identity belongs to its stable command or Turn, never
    to matching historical prose or intelligence content.

## Required acceptance scenarios

### Workspace and snapshot isolation

- Two workspaces watch the same repository. A full review uses only the
  workspace selected by its authorized capture; no item is borrowed from the
  other workspace.
- A full round starts, then an item is retired. Every seat and continuation in
  that round still sees the frozen projection; the next round does not.
- A full review's Workspace resolution is missing or ambiguous. Admission is
  rejected before round creation; the app never guesses.
- An exact full-review Workspace is authorized but intelligence retrieval
  fails. Base review proceeds with the canonical empty projection.
- Quick review is started for a learned repository. Its projection is empty
  and its tools remain diff-only.

### Development

- A Plan has no applicable Direction. Planning and self-review behave as today.
- Applicable guidance conflicts with a Plan. It is shown as a question and
  cannot block approval.
- Applicable approved Direction conflicts with a Plan. Automatic approval is
  held and a stewardship concern cites the frozen item and Plan evidence.
- Direction changes before approval. A new Plan revision and one new
  self-review are always required; the old revision is not rerun and cannot use
  an exception for an item it never froze.
- The user approves a scoped exception. The exact reason and direction item
  plus the matching typed STEWARDSHIP concern are recorded, development
  proceeds without changing global Direction, and automatic Plan approval,
  readiness, and merge remain disabled.
- A self-review returns CHANGES_REQUESTED with two concerns. The user identifies
  both as wrong review reasoning, supplies two explanations, and approves. Both
  concern resolutions and the HUMAN approval commit atomically against the same
  revision; its single self-review remains unchanged and development may open.
- Development receives those exact task-scoped resolutions with the approved
  Plan. Brain does not repeat the dismissed Plan concern without new evidence,
  but a new implementation-backed finding remains valid.
- The user adjudicates only one of two CHANGES_REQUESTED concerns, or a fence
  changes before commit. The command rejects without a partial approval or
  partial dismissal.
- A generic follow-up command tries to resolve or defer a CONCERN or
  STEWARDSHIP, or adjudication finds one such row already non-OPEN. It rejects;
  no generic or pre-resolved row can satisfy all-concern approval.
- A self-review returns BLOCKED, fails, or records no verdict. Human
  adjudication is unavailable and the existing blocker path remains.
- The user says a concern came from wrong or inapplicable intelligence. The app
  challenges, narrows, retires, or replaces the item only through Memory, then
  creates a new Plan revision and one self-review; adjudication itself changes
  no knowledge.
- A self-review returns CHANGES_REQUESTED with an open Direction stewardship
  concern. Human adjudication cannot convert it into a Direction exception.
- Plan revisions progress A/X, B/X, then A/X. All three remain distinct
  monotonic revisions; replay of a stable command creates no duplicate, while a
  fresh immediate prose-plus-basis no-op is rejected.
- A remote repair starts. Its launch input contains the exact approved Plan
  projection content and approval evidence, and it performs no live Direction
  lookup.

### Review

- Intelligence selects a likely architecture angle, but no code evidence
  supports it. The hypothesis is refuted or unresolved and no finding is
  published.
- A changed line violates frozen applicable Direction and passes all finding
  gates. The finding cites both the criterion and code evidence.
- A safety defect exists outside the intelligence objectives. Deterministic
  objectives still find and retain it.
- An agent completes a search step without evidence resolution. The objective
  is not marked clean.
- The user challenges a bad direction item and starts Re-review. Only the new
  round omits it; independent findings remain.
- Retrieval fails after exact Workspace authorization. Base objectives
  continue and review does not fail solely because Project Intelligence is
  unavailable.

### Human learning and voice

- Brief extraction fails. The Today card shows factual merged-change fallback
  and coverage state without a read-time model call.
- A landed change has no release evidence. The UI never labels it released.
- A Voice profile prefers softer wording. The constrained renderer still
  inserts the exact required action and severity-bearing impact unchanged.
- The user resets Review Voice. Future drafts stop using the prior profile;
  historical published comments remain unchanged.
- The user saves bounded Voice preferences. A new profile revision is visible
  in preview without changing any protected finding field.

### Code Areas and publication

- A suggested Code Area is not approved. Whole-repository retrieval remains
  the default.
- A Code Area is approved. It improves applicability but does not change cwd,
  worktree, sandbox, or captured source.
- A review completes with publishable findings. Nothing is sent to GitHub until
  the user chooses a verdict and publishes.

## Explicitly rejected designs

- A second knowledge database for Project Direction.
- Automatic governance from document paths or merge frequency.
- A fifth Stewardship or Intelligence Stage.
- A resident project-maintainer agent.
- A second scheduler or direct application-executor path tied to Task
  lifecycle.
- Live memory retrieval independently performed by each review seat.
- Repository-only workspace selection.
- Broad source, history, or caller tools after a C48 snapshot.
- Intelligence-driven review class, panel, budget, severity, or verdict.
- Re-running mandatory self-review on the same Plan revision.
- Learning the user's voice from all project reviewers.
- A briefing summary table that agents can silently consume as truth.
- Using Code Areas as filesystem or worktree boundaries.

# ByteQuay review discovery and validation

This is the implemented review contract. The discovery instructions and durable
reporting rules are described below. The controlled quality evaluation remains
pending; implementation tests do not establish improved review recall or precision.

## Purpose

Improve the concerns ByteQuay discovers before strengthening its filtering.
Organize investigation around changed responsibilities, give reviewers
complementary questions, and record source-grounded hypotheses before expensive
verification. Then validate, classify, deduplicate, and select the useful results.

The target is more distinct, supported, useful comments at a controlled cost.
Candidate count and comment count alone are not success criteria. A correct
review with no findings remains a valid result.

The workflow uses the existing review task, frozen revisions, evidence store,
source and IDE tools, memory delivery, provider subagents, and evidence follow-ups.
The parent adapts read-only delegation to the saved responsibility map: two
children by default, fewer for small scopes, and at most four concurrently when
independent unanswered responsibilities, provider capacity, and remaining turn
budget justify it. There is no separate review engine, persistent code graph,
model router, or external review-service dependency.

## What the workflow records

Investigation, validation, counterevidence, frozen source references, focused
verification, and optional delegation now preserve four distinct kinds of work:

- Investigation records coverage of distinct responsibilities and review
  perspectives. Agent count and files read do not establish that coverage.
- Checkpoints retain surfaced candidates before filtering so discovery can be
  distinguished from rejection during validation or selection.
- Design and test improvements belong to normal investigation, including when
  the review also finds defects.
- Final selection limits the presented results without classifying a supported
  concern as rejected merely because it arrived later.

Measure these changes against the existing workflow on frozen PR snapshots.
Recorded candidates describe surfaced hypotheses, not private model reasoning.
More investigation time, test execution, or comments alone does not establish
better coverage or precision.

## Workflow

```mermaid
flowchart TD
    A[Freeze scope and map changed responsibilities] --> M[Verify and checkpoint the neutral map]
    M --> B[Complementary discovery]
    B --> C[Checkpoint surfaced hypotheses and coverage]
    C -->|Independent unanswered responsibilities within budget| B
    C -->|Coverage ready or remaining gaps recorded| D[Seal investigation]
    A --> S[Second reader lists leads]
    S -->|Appended as unverified candidates| D
    D --> E[Validate every candidate]
    E -->|Specific missing evidence, at most two follow-ups| F[Collect evidence and seal updated report]
    F --> E
    E -->|Settled and covered| G[Classify, deduplicate, rank and present]
    E -->|Unresolved or omitted scope| H[Retain partial work and report incomplete]
```

The durable step kinds are `INVESTIGATE`, `VALIDATE`, `COLLECT_EVIDENCE`, and
the user-requested `EXPLAIN` refresh. Preparation, complementary discovery, and coverage reconciliation
happen inside `INVESTIGATE`; they are not additional mandatory provider turns.
There is no dedicated nit-check step or zero-supported-findings transition.
A complete zero-finding validation finishes immediately.

The change explanation is first produced alongside mapping in the initial
investigation pass. It records the PR intention and each scoped commit's
implementation. The earliest run with a complete explanation owns it, even if
that run's findings later fail to complete. Its original head, commit links and
discussion targets remain attached to that run. If an attempt never saves a
complete explanation, a later initial investigation can supply it.

`review_save_summary` admits writes only in unsealed investigation pass 1 when
no earlier run owns a complete explanation, or in an explicit `EXPLAIN` assignment. Validation, evidence collection,
subsequent reviews and discussions cannot rewrite it. Exact retries may return
an existing receipt without changing the saved explanation. Discussions clarify
the original through replies. `assignment.changeExplanationRequired` tells the
agent whether initial writing is needed; summary context identifies its owner.

The refresh icon beside Change intention starts a separate `EXPLAIN` assignment.
Its request binds the displayed explanation's run and revision, rejects stale
or busy requests, and freezes the latest committed source using the same working
copy guards as review updates. Replays keep their existing frozen head. The
assignment reads code and saves intention plus every commit explanation together;
it cannot write discovery, findings, validation, or coverage. Existing task turns,
stop, runtime cleanup and bounded repair handle execution. A refresh is accepted
only after its complete summary is saved and its provider finishes successfully.
The newest accepted refresh owns the visible explanation; an active, failed or
incomplete refresh keeps the previous explanation visible. Every explanation
retains its own head, commit links and discussion targets. No finding, accepted
review baseline or publication eligibility is changed by refreshing explanations.

Every later findings review starts with fresh investigation and evidence, including after
new commits or rewritten history, unless the new head carries the same change as
described below. It updates the source-backed map and findings
without another explanation. Completion requires a complete initial explanation
somewhere at or before that run, plus the current run's own coverage and validated
report. Incremental scope follows the latest accepted coverage, independently of
which run owns the explanation. The UI retains the saved explanation while
showing the latest review's status, findings, warnings and review details.

## Second reader

One turn that both looks for concerns and proves them spends nearly all of its
budget proving a few. Each findings run therefore also starts a second reader:
one disposable read-only provider session on the review's working copy, on the
review task's provider and model with the provider's default effort. It has no
review tools and cannot edit. [ReviewSweep](ReviewSweep.java) starts it beside
the investigation turn and gives it everything in one request:

- its assignment, `agent-skills/bytequay-review-sweep.md`: one read of the whole
  change per lens (words, mechanisms, siblings, tests, rules, facts), and the
  four kinds a lead may be;
- where to read the frozen change from Git objects, because the working files
  may hold the investigation's temporary experiments;
- the pull request title and description, except for a local change or a replay;
- measurements from [ReviewFacts](ReviewFacts.java): each commit's subject
  length, body size and fixup, work-in-progress or merge markers, tests a commit
  adds for code an earlier commit of the change added, the options the
  description marks under a template section, added tests that are switched
  off, and added JVM-language strings that hold several lines as `\n` escapes.
  A fact is a measurement, never a judgement;
- the text of the repository memories that match the changed paths, without
  recipes. A reader has no memory tool, so a title alone would reach nothing.

It answers with at most thirty leads, one for each topic, the surest and most
concrete first and questions last. A lead is a claim nobody has checked, so
it never becomes a finding directly. When the investigation is sealed, ByteQuay
waits for the reader to end, appends its leads to the sealed report as candidates
attributed to `second reader` with no evidence refs, and only then starts
validation, which must settle every numbered candidate. Leads beyond the run's
candidate capacity are left out. Appending keeps the investigation's own
candidates, numbers and evidence unchanged.

Thirty bounds what validation spends on leads. Each lead that holds costs a verdict
and usually a saved draft, and one measured validation of 25 candidates took 21
minutes.

The assignment names three habits of reviewers so the reader does not have to
think of them: tests arrive in the commit that adds their code, a multi-line
string is written as a multi-line literal, and a helper reused from another
feature's package moves to a shared place. The first two also arrive as measured
facts.

The read is not durable state. A failed, timed-out, unparsable or cancelled read
contributes nothing and the run continues; after a restart no read is running
and validation starts without leads. Stopping or ending the review cancels it.
Its provider usage is not added to the review task's totals. Every run starts a
fresh reader with no conversation history, so a later round gets a new read of
the whole change even though the review conversation carries earlier rounds.

## Carrying a review to a head with the same change

An update first asks whether the new head carries the change the last accepted,
complete review read ([ReviewHeadCheck](ReviewHeadCheck.java)): the same base
branch, the same patch over each head's own merge base with blob ids and hunk
positions left out, and the same messages on its non-merge commits. A rebase, a
clean merge of the base branch, and a base branch that only moved all pass. A base
that changed inside a hunk's context does not, and neither does a range Git cannot
read.

When it does, and every open finding stands exactly as that review left it, no
agent turn runs. A finding stands that way when it is validated at the reviewed
head, supported there at its current revision, and its lines map onto the new
diff. The working copy moves to the new head as for any update. ByteQuay stores a
run for the new head that is already accepted and holds the reviewed run's report,
coverage and evidence, and it moves each open finding to the new head and its
mapped lines without changing its revision. The run's outcome names the head whose
review it carries, and no second reader starts for it.

Anything else starts a normal findings review: a different change, an open finding
revised or added in a discussion since acceptance, a finding awaiting reassessment,
or lines that no longer map. Resolved and withdrawn findings stay as they are. A
carried run records that the same change was already read. It is not a new
reading, and the base branch's own new commits were not reviewed.

## Test check

A change that touches tests and other code owes a test check before its
investigation seals: do the added and changed tests fail once the change is
undone? `assignment.testCheckRequired` says so, judged from the names of the
paths this run must review ([ReviewFacts](ReviewFacts.java)), and
`review_save_investigation` refuses the seal until the run holds a `testCheck`.

The agent runs the check in its own sandbox: the tests on the unmodified head,
the same command with the behavior undone in the working copy, then a restore.
The rules give `git apply` for both the undo and the restore, because a review
sandbox cannot write Git's index and `git checkout` or `git restore` would fail.
It reports one of four outcomes (`core.review.ReviewTestCheck`):

| Outcome | Meaning | Must carry |
| --- | --- | --- |
| `PROTECTS` | Every selected test fails with the change undone. | Both captured runs |
| `DOES_NOT_PROTECT` | At least one still passes. | Both captured runs and the number of a recorded candidate |
| `NOT_APPLICABLE` | The question does not fit, such as a refactoring that promises unchanged behavior. | The reason |
| `BLOCKED` | A run could not be made, including a head run that fails. | The command and its error |

The candidate is what turns a test that guards nothing into a claim validation
must settle. The rules ask for a defect in the tests with severity, not a nit.

ByteQuay already records the working copy each Maven command of the agent
started on ([LocalRunService](LocalRunService.java)). The seal compares the two
cited runs with those records. It refuses a head run that started at another
commit or with local changes to the change's non-test files, and an undone run
that started on the reviewed head with no local change to any file of the change.
A command with no such record, such as one that is not Maven, cannot be compared
and is accepted.

Nothing checks that the files were restored afterwards, and a run sealed before
the check existed is not asked for one. `PROTECTS` shows that these tests notice
this undo, not that the change is correct. The outcome appears on the tool row
that sealed the investigation; Review details does not show it.

## Private Maven repository

`assignment.mavenRepository` names a local repository for the reviewed head under
the temporary directory ([ReviewMavenRepository](ReviewMavenRepository.java)),
and the user's shared `~/.m2/repository` as its tail. The rules ask for both
flags, `-Dmaven.repo.local` and `-Dmaven.repo.local.tail`, on every Maven
command, so builds and installs write only the private directory while cached
third-party artifacts are still read from the shared one. Installing the head's
modules there lets a later run test one module without rebuilding the modules it
depends on. The directory's name carries the head, so a new head never reads an
earlier head's modules.

This is an instruction, not a sandbox rule. Both providers may still write the
shared repository, and a project module that a run neither builds nor finds in
the private repository still resolves from the shared one. The tail needs Maven
3.9. Ending the review deletes every head's directory; the operating system's
temporary-file cleanup covers a review that never ends.

## Follow-up on the user's own threads

A later round owes the user an answer on every comment they already made. For a
review of a pull request, `review_get_context` kind `own_threads` lists the
inline review threads whose first comment is the user's, read from the stored
pull-request activity ([ReviewOwnThreads](ReviewOwnThreads.java)): place, opening
words, resolved and outdated marks, who spoke last, and whether the author is
waiting for the user. Unresolved threads come first, and at most 40 are listed.

`review_finish` takes `threadVerdicts`, and a complete report needs one for every
listed thread:

| Verdict | Meaning |
| --- | --- |
| `ADDRESSED` | The change or the author's answer settles the request. |
| `PARTLY_ADDRESSED` | Part of it remains. |
| `NOT_ADDRESSED` | Nothing settles it. A resolved mark is not evidence. |
| `AWAITING_REPLY` | The author asked or pushed back, and the next word is the user's. |

Each verdict cites evidence read in the run and is stored on the validation with
the thread's place and opening words (`core.review.ReviewThreadVerdict`), so it
still reads after the discussion moves on. Review details lists them, and the
tool row that finishes the review counts them. The rules also tell the reviewer
not to save a finding that repeats one of these threads.

The login comes from the cached GitHub credential, never from a request made for
this purpose. A replay, a local review, an unknown login or a pull request that
cannot be read has no threads to answer, and the report is accepted without
verdicts. Nothing is posted: replying on a thread and resolving it stay the
user's actions on the pull-request page. Review bodies and top-level comments
are not threads and are not listed.

## Discovery responsibilities

### Prepare a small map of the change

The managed policy uses the existing `changeMap` text field with a concise format:

```text
R1: responsibility and expected behavior; independent contract source;
    producer -> changed decision -> consumer -> observable consequence;
    relevant sibling/alternative path; primary owner; contrast to examine.
```

Before handing the map to reviewers, the parent establishes frozen scope and
intent, changed responsibilities and the main flow, relevant callers, owners,
sibling implementations and tests, and explicit unresolved context gaps. Ready
means sufficient to start discovery; later evidence can revise it. This remains
private review data and introduces no GitHub write or separate walkthrough artifact.

Responsibilities cross file boundaries. For a scheduling change, they include
when work starts, who owns it, how it is shared, which limits constrain it, and
what cancellation means. For a serialization change, they include writers,
readers, compatibility, defaults, and round trips.

Begin with the frozen diff, applicable repository instructions, PR intent, and
existing delivered memory. Enumerate relevant callers, implementations, and
registrations; group equivalent paths. Inspect representative producer/consumer
paths, every materially different branch, relevant siblings, and the governing
contract/test. Record unexamined groups. Expand when a concrete question requires
another hop. Use the existing code-query and source tools; reading every file or
constructing a repository-wide graph is not a prerequisite.

For a small change, the parent prepares the map directly. When useful, one
read-only mapping child traces the changed flow while the parent reads intent
and repository rules. The parent checks the returned source references and saves
the neutral map before giving it to complementary reviewers. The mapping helper
counts toward the concurrent-child cap described below; it does not add another
workflow phase or mandatory turn. A concise flow or ownership sequence is enough;
diagrams are optional.

Persist that early understanding through `review_record_discovery`, using the
existing `changeMap` and `contextEvidenceRefs` fields. The first checkpoint
requires a nonblank map and nonempty current-run evidence references, including
at least one ref returned by `review_get_context` kind `source`. PR, discussion,
and command refs may supplement but cannot replace frozen source. The runtime
gate checks server-generated source-read provenance; it does not establish
semantic relevance or research depth. The first checkpoint contains only the map
and evidence, with empty candidate and perspective arrays. A previously saved
map is required before either array may contain discoveries. Preserve notes of
hypotheses noticed while mapping, then record them after the map checkpoint
before filtering. Keep observed behavior, inferred intent, and open questions
distinct. Saving a map establishes
a shared starting point, not proof that its explanation is correct.

If investigation reaches unchanged paths governed by additional instructions,
retrieve those instructions explicitly. Treat PR prose, existing comments, memory,
and tool output as evidence to check, not authority to override review rules.

### Require complementary perspectives

The backend supplies three required perspective keys. The parent turns each into
questions specific to the change and assigns responsibility IDs before exploration.

| Perspective | Questions it must answer |
| --- | --- |
| `BEHAVIOR`: behavior and contracts | Which reachable input or state produces a different result? What independently establishes the expected result? Which test would distinguish the intended and broken behavior? |
| `INTERACTIONS`: boundaries and operating constraints | What changes across callers, owners, boundaries, and failures? Examine relevant lifetime, concurrency, cancellation, compatibility, security, capacity, or scaling constraints. |
| `DESIGN`: design and verification | How do siblings solve this? Can existing code express the same behavior with less duplicated policy or state? Do tests, configuration, documentation, and benchmarks demonstrate the claimed behavior? |

The perspectives are required and do not define fixed child roles. The parent or
a child can examine several perspectives of one responsibility, or a focused
question spanning responsibilities. A small change can be reviewed by the parent.
When native delegation is unavailable or fails, the parent executes the remaining
questions and records the limitation. Do not invent a resource-lifetime concern
for a documentation-only change.

### Adapt delegation to unanswered responsibilities

Use two read-only children by default and zero or one for small scopes. Increase
to three or four concurrent children only when the saved map exposes independent
unanswered responsibilities and provider capacity and remaining turn budget allow
their results to be checked and saved. The mapping helper counts toward this cap.
The parent continues investigating and owns every checkpoint and decision.

After each batch, the parent records qualifying hypotheses before filtering,
reassesses responsibility coverage and conflicting evidence, and identifies the
next concrete unanswered questions. Reuse a suitable child when the provider
supports continuing it, or start a fresh child with a targeted assignment. A
reused child's earlier conclusions are context, not independent confirmation.
There is no total child quota; further batches must still fit the current turn's
budget and leave time for reconciliation and sealing.

For example, a map may expose separate questions about cancellation ownership,
configuration routing, compatibility callers, and test discrimination. Four
children can investigate those questions concurrently when the capacity and
budget conditions hold. A later batch may ask one suitable child to resolve a
specific conflict while the parent finishes the remaining coverage. The three
perspectives still apply across this work without fixing one agent to each label.

These are delegation instructions. The backend neither dispatches the children
automatically nor adds a runtime concurrency counter; native provider limits may
further restrict available capacity. The source-backed map checkpoint remains
required before candidate or perspective writes, and the parent must save that
map before starting complementary reviewer assignments. Adaptive batches do not
change the maximum of two evidence-collection follow-up turns.

Complementary children receive the frozen scope, verified saved neutral
responsibility map, relevant contract facts, and source entry points. Avoid
supplying the parent's suspected findings
before their initial results. The parent records its own initial hypotheses before
reconciling their reports. Existing inherited context may prevent a blind review;
do not claim independent reasoning merely because different agent IDs appear.

Each investigator returns examined responsibilities, concrete hypotheses with
evidence seeds, obvious counterevidence, and gaps. The parent checks every child
claim against source. First checkpoint child hypotheses meeting the admission
criteria below, then attach the parent's disproof or support; do not lose their
origin through silent parent filtering. Combine distinct concerns without voting:
agreement is not proof, and a concern found by only one investigator may be the
most useful one.

Children remain read-only and cannot run tests, edit experiments, or save reports.
Do not mutate their shared checkout while they inspect it. Finish their reads
first, or bind every read to an immutable commit.

### Generate contrasts, not generic checklists

Derive concrete questions from the mechanism being changed:

| Mechanism | Concrete contrast to investigate |
| --- | --- |
| Starting asynchronous work earlier | Start before versus after each pruning boundary: what unnecessary work is avoided, and what I/O overlap is lost? |
| Adding a concurrency wrapper | Compare its bound and ownership scope with the underlying shared pool. Check the dependency's queueing/submission semantics before suggesting removal. Which workload actually receives protection? |
| Adding a policy to one implementation | Follow the same input through sibling implementations and compare resource accounting, configuration, and execution choices. |
| Sharing asynchronous state | Compare the creator's lifetime with that of the last consumer. Who owns outstanding resources after the creator finishes? |
| Adding parallel implementations | Try expressing both paths with an existing component and an explicit policy; identify the behavior that would be lost or preserved. |
| Adding tests or timing code | Determine what runs in normal CI and which observable failure the assertion detects. |

After saving the neutral map and before expensive proof, inventory how added or
materially changed mechanisms, tests and benchmarks are used and exercised. Group
equivalent additions. Trace real callers, registrations, effective configuration,
consumers and observable effects, including dynamic routes and no-op bodies. For
test artifacts, identify discovery and execution gates, the fixture path reaching
the change, assertions versus timing/log-only output, and automatic or manual use.
Save this account with source references in the existing map or DESIGN assessment.

New APIs and methods need a concrete consumer in the final PR tree or an evidenced
external/framework contract. Future-use claims and tests that merely call otherwise
unused production code do not justify new API surface. Suggest removing or deferring
an unused addition, or investigate missing wiring when the PR promises integration.
Check the combined PR so consumers added by later commits are included.

Every identified dormant, unwired or no-op artifact needs a disposition: a
checkpointed candidate with a concrete consequence or removable cost and next
check, or an evidence-backed retention reason naming its execution route and
benefit. A broad `CHECKED` receipt or an intentional manual/disabled label is not
enough. Successful compilation neither exercises disabled behavior nor establishes
continuing value. Absence of a runtime defect does not disprove a concrete
maintenance suggestion; its basis is removable code, confusion or cost. A
deliberately disabled timing harness may justify a placement or
maintenance suggestion while active tests already cover the feature. Conversely,
manual benchmarks, registered benchmark runners, reflective services, test helpers
and interface no-op contracts can have legitimate roles; missing textual callers
alone do not prove dead code. Disabled code alone does not require a finding or
benchmark migration, and stylistic preferences are insufficient.

Validation challenges claimed activation and coverage against this inventory and
checks the disposition of dormant artifacts. Missed concrete hypotheses use the
existing `newCandidates` and `NEEDS_EVIDENCE` path. Settled entries are reused;
evidence turns investigate specific gaps without restarting a broad audit. These
are review and reporting requirements in existing fields, not a new schema, phase
or semantic runtime gate. The backend cannot prove that the inventory is complete
or that a stated retention benefit is correct.

These are investigation leads, not automatically valid findings. For example,
claiming that one start point is faster requires evidence about both avoided work
and lost overlap. A wrapper with the same bound as its executor can still change
queueing behavior. Check the actual implementation and contract before turning a
contrast into a finding. The holdout evaluation must use previously unseen PRs.

### Capture before expensive settlement

Record a candidate once it has a source-grounded trigger, plausible mechanism,
observable consequence or concrete improvement, and a discriminating next check.
It does not yet need a completed reproduction. Preserve candidates subsequently
disproved, including why they failed.

Cheap source checks can eliminate obvious misunderstandings. Expensive builds,
experimental patches, and broad test runs should not consume investigation before
the material responsibilities have been explored. Reuse applicable verification
evidence and existing output deduplication. Passing a broad suite does not settle
a specific hypothesis.

Keep the existing per-turn timeout and adaptive concurrency cap. Reserve time to collect
reports and record gaps. Checkpoint before the first expensive experimental build
and collect current hypotheses by roughly 70% of the investigation turn's budget.
Use the remainder for targeted gaps and sealing. This is a tunable scheduling
instruction, not a guarantee of completeness. No minimum candidate quota, repeated
generic full review, or automatic increase in model effort is part of this change.

## Contracts and persistence

### Structured candidates and perspective assessments

`ReviewInvestigation.candidates` contains structured `ReviewCandidate` records
in `core.review`:

| Field | Purpose |
| --- | --- |
| `number` | Server-assigned, stable one-based identity within the run. |
| `perspective`, `responsibility` | Why this concern was investigated and which part of the change it covers. |
| `hypothesis` | Concise trigger, mechanism, and consequence or concrete cost. |
| `evidenceRefs` | Current-run source/evidence references supporting the initial suspicion. |
| `nextCheck` | Missing proposition and smallest useful discriminating observation. |
| `carriedFindingId` | Optional identity of an existing OPEN finding being reassessed. |
| `originStep`, `originTurnId` | Server-derived assignment and parent turn that first saved the candidate. |
| `attributedSource` | Optional reported child or discussion source, distinct from the observed saving identity. |

Keep reported investigator attribution distinct from provider-observed identity.
Store concise claims and evidence, not private reasoning transcripts.

`ReviewPerspectiveAssessment` records contain a perspective key,
concrete question, inspected responsibilities/paths, evidence references, and
`CHECKED`, `NOT_APPLICABLE`, or `BLOCKED` with an explanation. Absence means
unfinished. A not-applicable claim needs a scope-based justification. A checkbox
or a list of read files is not sufficient evidence of coverage.

Use the existing `changeMap` text for the responsibility map rather than adding a
graph schema. Receipts make omissions inspectable; they cannot mechanically prove
that the model searched thoroughly or found every issue.

### Checkpoint and seal

`review_record_discovery` is a parent-only batch tool in
[ReviewTools](ReviewTools.java). It saves the early map, appends candidates, and
records perspective assessments during investigation or evidence collection.
The first checkpoint must supply nonblank `changeMap` and nonempty
`contextEvidenceRefs` together. Later checkpoints omit both fields to preserve the
saved map and references, or supply both to revise them. Each supplied reference
list must retain at least one current-run frozen-source ref from
`review_get_context` kind `source`; other evidence kinds alone cannot satisfy the
gate. The first checkpoint must have empty candidate and perspective arrays;
supplying a map and discoveries in the same first call is rejected. Later map
revisions may accompany candidates or assessments, or use empty arrays. A call
without a map update must add a candidate or record an assessment.
Assignment identity comes
from the server. Operation keys and transactional replay ensure the same key and
payload return the same numbers; a changed payload conflicts.

This enforces ordering of saved tool results inside the existing investigation
turn. The backend does not dispatch native children or prove the depth of the
mapping research. Instructions require the parent to verify and save the map
before starting complementary reviewers, and to revise it when later research
changes the understanding. No additional workflow phase or immutable map state
is introduced.

Use the existing run and step storage:

- `run.investigation` holds the current draft/checkpoint.
- The current `step.investigation` stays empty while that assignment is collecting.
- `review_save_investigation` takes assessed files, evidence answers, the
  verification handoff and the test check, then seals the stored draft into the
  step. It rejects
  `changeMap`, `contextEvidenceRefs`, and replacement candidate lists; revise the
  map only through `review_record_discovery` before sealing.
- `ReviewRun.latestSealedInvestigation()` returns the step containing the latest
  sealed investigation. Validation, completion, accepted discussion, and evidence
  follow-ups use its number and report together.

The existing investigation and steps JSON columns store these records. The tool
schemas, HTTP models, TypeScript parsers, and SQLite serialization carry the same
structured candidates and perspective assessments; no separate report table is used.

Candidate numbers, hypotheses, initial provenance, and initial evidence remain
append-only after checkpointing. Corrections and counterevidence go into assessments
or evidence answers. Collection starts with all prior candidates; new hypotheses
append. Collection and assignment repair also retain the saved map and references;
an evidence-driven map revision uses the same paired checkpoint fields.
Exact retries preserve checkpoints. A stopped or
failed provider must not turn a draft into a sealed or complete review.

Require all three perspective assessments before sealing a normal investigation.
Allow a report to record a blocked perspective and omissions, but never accept it
as complete coverage. The existing incomplete-result path handles inaccessible
scope. A failed child alone does not make coverage incomplete if the parent
finishes its assignment.

A run holds at most 60 new candidates, room for the investigation's own
hypotheses and the second reader's thirty leads, plus an allowance for carried
findings. `review_get_context` kind `investigation` returns candidates and their
validations twenty to a page, with the rest of the report on the first page, so
no single tool result grows with the pool; `assignment.previousValidation` is
not paged. At capacity,
record unexamined responsibilities/overflow as omissions; do not quietly convert
unexamined concerns into rejections. This is a bound, not an output target.

## Validation and selection

Keep the existing `SUPPORTED`, `REJECTED`, `NEEDS_EVIDENCE`, and `UNRESOLVED`
outcomes. For every candidate, check reachability, independent contract basis,
counterevidence, downstream effect, and the smallest useful proof. A substantive
simplification needs a concrete alternative and preserved behavior; a design
question needs a specific unresolved tradeoff, not a speculative bug label.

Keep the existing classification vocabulary. Bugs, useful suggestions, and pure
nits have different evidence and presentation requirements. A useful test or design
improvement may coexist with a defect. Nits are not suppressed merely because
another finding exists; pure wording preferences remain lowest priority and need
an actionable benefit.

A question is the fourth kind and the last resort. Its title starts with
`Question:` and it has no severity. It carries an observation verified in current
source, the question it raises, and the two acceptable outcomes: a change, or the
reason stated in a comment. It is for a choice only the author can explain: the
observation holds, neither the code, a commit message nor the PR text explains
it, and the reviewer cannot rank the two outcomes. A shown consequence is stated
as a defect or a suggestion with the change wanted, because a question carries no
severity to rank it by and leaves the author to do the reviewer's deciding.
Behavior that got worse as a side effect of the change, a sibling that handles
the same case differently, and a dependency's documented behavior are each a
basis for a defect, so a candidate is not rejected only because no written
contract is violated. It is rejected, naming where, when the code, a commit
message or the PR text answers it. An unverified claim never becomes a question.

Those are rules for the agent. One limit is enforced: `review_finish` refuses a
selection that holds more than two questions (`ReviewTools.MAX_SELECTED_QUESTIONS`)
and names the count, so the reviewer revises a question that shows a consequence
into a defect or a suggestion, or leaves it unselected, where it stays supported in
the details. A question is known by its title alone. The limit covers one run's
selection; findings carried from earlier runs and findings saved in a discussion
are not counted.

Every supported result remains bound to current evidence and the exact saved
finding revision. Multiple candidates for the same root cause can reference the
same finding; the existing contract already permits this. Retain their origins
and count one unique result. Do not introduce a duplicate verdict or erase the
original hypotheses.

New hypotheses raised during validation remain `NEEDS_EVIDENCE` until a collection
assignment seals them. Keep the existing maximum of two evidence follow-ups.
Missing proof at that limit means unresolved/incomplete, not rejected. Reassess
each carried OPEN finding against current source; preserve user RESOLVED state.

### Apply the twelve-finding limit after ranking

Twelve is the final presentation limit for new automatic findings from a run
(`ReviewValidation.MAX_SELECTED`).
Saving a supported draft does not consume a presentation slot:

1. Save supported finding drafts with their evidence, bounded by the recorded
   candidate pool. A save alone does not make a draft a selected result.
2. `ReviewValidation.selectedFindingIds` contains the ranked selection, with a
   required `selectionExplanation`. At final validation, select at most twelve
   supported findings originating in this run, at most two of them questions.
   Existing carried findings retain their lifecycle and history.
3. Check that every selected ID belongs to this review/run and is backed by a
   current supported candidate and exact revision. Verify these conditions again
   at acceptance. A newly found stronger result can displace an earlier draft.
4. Retain supported but unselected findings in Review details, and the selection
   explanation with the validation. They are neither rejected nor automatically
   published.

Before acceptance, account for every saved, nonwithdrawn automatic finding in the
candidate assessments. An unfinished validation may retain provisional support,
but only final acceptance establishes the immutable selection. Show provisional
findings in details until that selection exists.

Preserve uncertainty separately from selection. No supported-but-unselected
outcome is needed: validation answers whether the concern holds, and the selected
ID set answers whether it becomes a finding card. Prefer demonstrated impact and
usefulness over arrival order; do not assign fixed quotas to perspectives.

### Check the comment's form when it is saved

`review_save_finding` refuses a body that would not read as a review comment
([ReviewCommentForm](ReviewCommentForm.java)). Measured on prose, with fenced
code left out and inline code counted only as words:

| Rule | Regular finding | `Nit:` |
| --- | --- | --- |
| Prose words | at most 120 | at most 35 |
| Sentences | at most 5 | at most 2 |
| The request | within the first 2 sentences | within the first 2 sentences |
| Hedged requests | refused | refused |

A sentence makes a request when it is a question, when a clause of it opens with
one of a fixed list of action verbs, or when it says what should or must change.
Hedged requests are a short list of phrases such as "consider" at the start of a
sentence, "might want to" and "perhaps". Both are wording rules, not
understanding: a request worded some other way is refused with the wordings that
pass, and a statement that happens to open with a listed verb is let through.
Each refusal names the measured figure or the phrase, so the agent rewrites the
comment instead of the rule being advice. The user's own edits to a saved
comment are never checked.

## UI and trust boundaries

Review details show only what the reader acts on: verdicts on the user's own
threads, omitted scope, and findings outside the selection. The change map,
perspectives, candidates, assessed files and per-pass validation are the agent's
handoff between passes; they stay in storage and in its context, not on screen,
and the Evidence tab lists what it read. The main result shows selected
automatic findings and separately labelled discussion follow-ups; internal
unselected saves remain accessible through details.

Keep every finding in domain storage and HTTP data so details, direct links,
discussion, and outgoing drafts retain their identities. Use the derived selection
rule in [ReviewSelection](ReviewSelection.java) and the frontend review model for
review cards, summary counts, IDE markers, and automatic-result exports;
do not filter the underlying finding collection. Distinguish supported counts from
selected counts, including when no finding was selected.

The accepted selection is immutable. Later agent revisions, resolve/reopen, and
withdrawal preserve its historical membership. A newer discussion revision must
not claim to have been validated by the older sealed assessment. Withdrawal hides
the actionable card without automatically filling its slot. Existing published
and user-resolved history remains visible.

Preserve user-requested discussion follow-ups as separately labelled findings,
outside the completed automatic review. They do not rewrite its report or count
as automatic discoveries in evaluation. Record automatic-versus-discussion origin
on finding creation from the server's assignment context and preserve it through
revisions. Do not infer origin from timestamps or absence from a supported set:
an automatic finding may have been withdrawn and later reopened.
Previously unselected supported findings remain in details across later reviews;
the user can explicitly choose one for publication, but merely discussing it does
not select it. Preserve outgoing draft text independently of later agent revisions.

Selection is a recommendation, not publication authorization. An explicit user
choice may promote an additional supported finding into a publication draft, still
subject to current evidence/head checks and the exact previewed revision. Raw
hypotheses cannot bypass those checks. This workflow grants no additional GitHub-write or
child-tool permissions.

Adding a finding to the user's review leaves it open. Confirmed publication, or
the user's confirmation that an uncertain send reached GitHub, resolves its source
finding locally and displays Accepted beside Resolved. Draft publication and source
resolution commit together; failed or uncertain sends leave the source unchanged.
Existing resolutions keep their reason and attribution, and later publications do
not undo a user's decision to reopen an accepted finding.

## Implementation boundaries

The implementation is concentrated in the current review service:

| Contract | Main surfaces |
| --- | --- |
| Append-only discovery and sealed-report identity | `core.review`, [ReviewTools](ReviewTools.java), existing SQLite JSON storage |
| Responsibility mapping, concrete contrasts and parent fallback | [ReviewPrompts](ReviewPrompts.java), `agent-skills/bytequay-review.md` |
| Investigation, validation and bounded evidence collection | [AgentReviewService](AgentReviewService.java), `ReviewStep` |
| Supported pool, final selection and discussion origin | `ReviewValidation`, `ReviewFinding`, [ReviewSelection](ReviewSelection.java), publication checks |
| Second reader, its facts and its leads | [ReviewSweep](ReviewSweep.java), [ReviewFacts](ReviewFacts.java), [ReviewLead](ReviewLead.java), `agent-skills/bytequay-review-sweep.md` |
| Carrying an accepted review to a head with the same change | [ReviewHeadCheck](ReviewHeadCheck.java), `followHead` in [AgentReviewService](AgentReviewService.java), `ReviewFinding.carried` |
| Test check gate, its outcomes and the comparison with recorded runs | `core.review.ReviewTestCheck`, [ReviewTools](ReviewTools.java), [ReviewFacts](ReviewFacts.java), `LocalRun` records |
| Private Maven repository per reviewed head | [ReviewMavenRepository](ReviewMavenRepository.java), `assignment` context, `end` in [AgentReviewService](AgentReviewService.java) |
| Verdicts on the user's own threads | [ReviewOwnThreads](ReviewOwnThreads.java), `own_threads` and `review_finish` in [ReviewTools](ReviewTools.java), `core.review.ReviewThreadVerdict` |
| Provisional coverage and selected/unselected results | Review HTTP models, frontend review contracts/model/details and IDE selection |
| Offline input freezing and bounded discovery-only calibration | [Developer runbook](../../../../../../../../../scripts/review-calibration/README.md) |

The superseded unshipped contracts are replaced directly. There is no legacy
review adapter or migration-history machinery. Development tests use isolated
data. Incompatible saved development data requires the explicit reset procedure
in `CONTRIBUTING.md`; never reset a user's runtime database automatically or resume
an old active assignment against a different report contract.

## Evaluation still to run

The existing [ReviewReplayService](ReviewReplayService.java) is useful plumbing,
but its memory-on/off experiment is not this benchmark. It requires merged PRs
with non-bot maintainer inline comments, can choose a later fallback revision, and
matches issues approximately by line location. It also reads current cached PR
prose. Evaluating open PRs or using a separately adjudicated reference set needs
explicitly frozen inputs and manual adjudication outside that replay experiment.
The current replay UI cannot evaluate them unchanged; its output does not
establish blind discovery recall.

The developer harness in `scripts/review-calibration/` freezes exact Git trees,
policy and supplied context, then invokes a bounded Codex discovery-only pass.
It records source hashes, CLI version, provider/model settings, transcript and
failure status. Its synthetic CSV fixture tests execution feasibility, not review
quality. The wrapper replaces reporting tools and disables delegation, so it does
not measure the production checkpoint/validation/selection workflow. It never
resets a live review database. Its scorer builds a case from a public pull
request's reviewer comments and has a model judge match each comment to the
run's hypotheses; a person still checks the judge against hand labels.

A controlled production comparison still needs an independently frozen corpus and
blind adjudication. No holdout quality or cost gate below has been achieved. Use
these constraints when preparing it:

- Freeze exact head, base, merge base, instructions, dependency versions, PR prose
  at the cutoff, model/provider settings, prompts, and tools. Use the full cutoff
  timestamp. If historical prose is unavailable, omit it from both arms.
- Disable memory in both arms initially. Existing memory cutoff filtering is not
  proof that every possible future source has been excluded.
- Exclude target comments, later fixes/discussion, and later external context
  during discovery. Supply version-matched references where needed. Mark exposure
  and exclude contaminated runs from blind discovery scoring.
- Use three calibration PRs and six untouched holdout PRs, with two runs per arm
  as an initial pilot. Include different change types and at least one clean PR.
  Previously inspected PRs belong only in the calibration set.
- Build a reference set from pooled outputs plus an independent manual sweep.
  Adjudicate semantic root causes without showing which arm produced them.
  Existing review comments are leads to verify, not automatic ground truth.

Run three separate comparisons:

1. **Discovery:** frozen baseline versus current investigation at the same total budget,
   using the same validator and presentation policy.
2. **Validation:** old versus revised settlement on the same shuffled candidate
   pool, keeping discovery fixed.
3. **Presentation:** old versus new selection on the same validated concerns.

| Metric | Question answered |
| --- | --- |
| Initial discovery coverage | Which adjudicated reference concerns appeared in the first investigation's surfaced candidates? |
| Cumulative discovery coverage | Which concerns surfaced anywhere within the equal total budget, including the baseline nit pass and validation/collection discoveries? |
| Validation retention and precision | Which valid discoveries survived, and which supported claims were actually valid? |
| Presentation retention | Which useful validated concerns reached the result? |
| End-to-end useful coverage | Which reference concerns became useful user-facing comments? |
| Cost and reliability | What were total parent/child/follow-up tokens, active wall time, failures, duplicate concerns, and user-wait time? |

Report defects, substantive suggestions, and polish separately. Candidate
provenance measures surfaced hypotheses, not access to hidden model reasoning.
Count distinct adjudicated concerns per PR and report both per-PR and pooled
coverage. Precision is undefined when no result is emitted; a clean PR is not an
automatic 100% precision score.

Proposed pilot gates, not achieved results: improve mean per-PR cumulative holdout
discovery coverage by at least ten percentage points among PRs with reference
concerns; introduce no unsupported Critical/High finding;
lose no more than five points of output precision; and keep median total tokens
and active time within 1.25 times baseline. Investigate each paired run over 1.5
times baseline. No valid result may disappear due to bookkeeping or truncation.
Publish raw denominators and per-PR outcomes; expand the sample before claiming
general superiority. If the budget cannot meet both quality and cost gates, report
the tradeoff rather than silently increasing compute.

## Implementation verification

Review tool/service, SQLite store, provider permission, prompt and frontend
contract/details tests cover draft recovery, operation replay, stable
candidate identity, an early map citing frozen source, supplemental evidence,
map-only first checkpoints, rejection of discoveries before a saved map, paired map
revisions, seal-time replacement rejection, missing/blocked perspectives, exact
sealed evidence, carried findings, shared-root-cause mapping, late discoveries, final selection, and
publication boundaries. The late-selection regression records twelve earlier drafts
before a stronger supported finding arrives. Related cases cover discussion revisions,
follow-up findings, unselected-result navigation, and independent outgoing drafts.

For material implementation changes, run `mvn verify` in `backend/`, then frontend
lint, TypeScript checking, tests, and packaging as required by `CONTRIBUTING.md`.
Those checks establish implementation behavior; the held-out evaluation establishes
whether the changed investigation improves review quality.

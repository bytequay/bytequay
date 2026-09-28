# Repository context: discover, learn, and use

Status: approved refactor. This is the final product contract, not a claim that
every part has shipped. Delivery and verification belong in
[the temporary development checkpoints](repository-context-impl-tmp.md#refactor-development-checkpoints).
This contract supersedes earlier automatic note selection, direct generated-note
activation, one-note-per-scope, and manual-first discovery flows in
[Repository Context](repository-context.md) and
[agent discovery](repository-context-agent-discovery.md). Their ownership,
source retention, exact-version receipts, and task safety contracts still apply.

## 1. One saved object: a context note

A context note is a reusable, source-linked explanation. **Knowledge** explains
concepts, contracts, architecture, and intended behavior. A **lesson** records a
supported, reusable conclusion from actual work. The existing internal `MEMORY`
kind represents a lesson; there is no third memory system or model training.

An area can have several focused notes. An area/path is an association for
finding candidates, not proof that every note applies to every change there.
Notes retain stable identity, kind, title, summary, body, source references,
applicable area/path associations, origin, enabled state, and version.

Agent-generated new notes and updates are drafts. Pending drafts are separate
from active entries and excluded from ordinary agent lookup. An update retains
the active version until the user approves the replacement. Approval checks the
observed notebook revision and original entry version. Concurrent user edits,
disabling, deletion, or stale evidence ownership cannot silently be overwritten.
Users may edit, approve, or dismiss drafts. Explicit manual Save already grants
approval and needs no second confirmation. Failed or stopped work preserves
existing active notes and completed drafts.

## 2. Who does what

The agent discovers project areas, connects implementation with documentation,
chooses useful learning questions, judges note relevance and applicability,
and proposes new notes or changes. It must support conclusions with sources and
distinguish documented rules, observations, inferences, and unanswered questions.

ByteQuay provides repository/document tools, literal note lookup, persistence,
source and version checks, run limits, task execution, progress, and approval.
It does not decide semantic relevance, rank notes by inferred usefulness, or
infer correctness from a merged PR or an accepted comment.

Reuse the workspace notebook, source readers, agent task/worktree service,
provider bridge, usage receipts, and SQLite settings. No new task engine,
semantic index, vector database, crawler, or background learning scheduler.

## 3. Initial experience

1. Opening a workspace or Repository context loads existing state. When no
   discovery exists, show **Start discovery** with the configured model and
   small default run limits. Page reads, startup, and repository synchronization
   never launch a model.
2. The user's Start action launches one bounded discovery task against a
   recorded local repository revision. No manually registered scope is required.
3. Repository-wide filename/text search, paginated directory listing, and file
   reads let the agent inspect README/build files, structure, and selected docs.
   This is language-neutral: Maven is an example, not a requirement. Deeply
   nested manifests remain searchable. The agent does not read all code.
4. Save a project outline, suggested areas/topics, supporting links, and proposed
   sources. Associations need evidence, such as matching connector identity and
   documentation links; filenames alone are not a formal module/document binding.
5. The user selects topics and clicks **Learn selected**, accepting source
   boundaries and run limits. Defaults avoid manual path/rule entry. Existing
   notes remain available. Existing workspaces follow the same missing-discovery
   rule; reopening shows saved results rather than rerunning.

Discovery can itself be partial. Save the remaining inventory position and show
what was not surveyed. Never claim a partial list is the complete project map.

## 4. Bounded, incremental learning

Learning answers small questions and saves useful notes. For example, Iceberg
table concepts, Trino's read path, commit behavior, and delete semantics are
separate units rather than one request to read an entire connector.

The accepted plan fixes repository revisions/document versions and allowed
folders or website roots. Suggested web defaults are two link hops from the
approved starting page and ten documentation pages per batch, with separate
read/byte/tool limits for code. Continuation does not reset link depth or
implicitly authorize another site, repository, or version. Repository file
inventories use paths, not a directory-depth cutoff.

Readers return source text and usable links. Out-of-scope links become suggestions;
following them for learning requires expansion approval. An approved docs set
gets a concrete bounded inventory of processed, pending, failed, and excluded
material. Reading an index does not mean reading its linked pages. Coverage
describes sources processed, not complete understanding.

After each small unit, retain the plan, source identities/read evidence,
completed drafts, unfinished draft/question, and next source or question.
Continue within the approved run while work and authorized capacity remain.
At a limit, stop starting more work and show **Review drafts** and **Continue
learning**. Continue authorizes another bounded run from the checkpoint; note
approval never grants more spending.

Resume with relevant saved notes and progress, not the complete old conversation
or every note. Recheck changed sources as necessary. Do not promise zero
rereading, full expertise, or hard token/dollar ceilings where a provider only
reports usage after a request. Source/tool/turn bounds must be enforced by
ByteQuay, not merely written in a prompt. Save progress before exhausting them.

Approved completed notes are usable immediately while other topics remain
unfinished. A stopped run may have useful drafts without finishing a topic.

## 5. Use during development and review

Keep the existing development and investigation/validation workflows. Enabled
tasks receive concise lookup guidance, not all notes or an application-ranked
shortlist. The agent inspects the request/diff/code, then uses:

- **List:** stable, paginated active note identities, versions, titles, summaries,
  and associated paths; optionally restrict by path.
- **Search:** optional path filtering and case-insensitive literal text matching
  over documented fields, with the same pagination and no relevance score.
- **Get:** the requested exact active note version plus supporting sources.

The existing tool may call exact get `READ`; reuse it rather than adding aliases.
Pagination binds the notebook revision and rejects stale continuation. Results
retain task/run ownership and delivery receipts. A path match is a candidate,
never an assertion of applicability. The agent can broaden search or list pages.

Missing notes never block development/review. The agent researches the specific
question needed using code, tests, and permitted sources. Fresh evidence is
usable in that task immediately; approval controls publishing reusable notes.
If required work exceeds allowed sources or run limits, request expansion;
otherwise continue inside the existing task without a separate learning run.
Unestablished rules remain explicit uncertainty, not invented knowledge.

Useful supported work can produce a draft/update. Check existing notes before
proposing duplicates. No matching search hit does not prove a new note is needed;
no task is required to produce a lesson. Larger research opportunities become
suggested topics, not automatic jobs.

## 6. Explore and merged work

**Explore & prepare context** reuses the workspace question/development task.
Starting it authorizes an answer and useful draft preparation within shown limits.
Present the answer and optional drafts for approval, editing, or dismissal.
An existing equivalent note may need an update or no change.

A confirmed merged PR associated with a development task can become a candidate
automatically without a model call. Its bundle uses known links to the final
PR/diff, issue or ticket, task, review, tests, and verified development summary.
Do not crawl unrelated history, infer links, or equate completion/closure with
merge. Repeated observations must not duplicate the candidate. Accepted comments
and merge status record a decision, not proof that every claim is true.

The user starts bounded learning from selected candidates. The agent proposes
a new note, an update, or no reusable conclusion. There is no default background
model job after merge and no automatic activation of lessons.

## 7. Visible controls and verification

The main progression is **Start discovery → Learn selected → Review drafts /
Continue learning**. Existing editing, context-use switches, source inspection,
and usage details remain reachable without mandatory configuration steps.
Show completed work, pending questions, limitations, and source versions.
Found/opened/cited observations do not claim understanding or correctness.

Verify draft isolation/conflicts, multiple notes per area, literal pagination,
exact reads, empty-notebook task startup, deep/non-Maven discovery, bounded
external sources, restart/continuation, partial results, and no hidden model
launches. Real-provider quality/cost checks are separate, explicit acceptance
work; do not run them against watched repositories during implementation tests.

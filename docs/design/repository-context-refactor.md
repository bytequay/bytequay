# Repository context: discover, learn, and use

Status: implemented and verified by automated tests. Live-provider quality and
cost acceptance remain separate. Delivery and verification belong in
[the temporary development checkpoints](repository-context-impl-tmp.md#refactor-development-checkpoints);
the [source check at read](#source-check-at-read) has
[its own checklist](repository-context-impl-tmp.md#check-note-sources-at-read).
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
Suggest separate, evidenced topics for independently useful components. Shared
architecture or comparison topics supplement those topics: including Delta Lake
and Hive paths in an Iceberg-led topic does not cover their own learning questions.
Continuation revisits missing component questions even in surveyed directories;
unresolved components, paths and questions stay in the report's remaining work.

## 4. Bounded, incremental learning

Learning answers small questions and saves useful notes. For example, Iceberg
table concepts, Trino's read path, commit behavior, and delete semantics are
separate units rather than one request to read an entire connector.

Every topic declares two separate boundaries: `paths` lists directories available
to read, while `notePaths` lists where its saved notes apply. All questions in a
topic share that note target. Discovery splits questions into separate topics
when their applicability differs; it must not combine an Iceberg-only question
and a cross-connector comparison under one shared target. An Iceberg question can
read Delta Lake or Hive evidence for comparison while saving only Iceberg notes.
Reading another connector does not establish that its behavior applies to Iceberg.
Off-target findings become open questions or gaps for later explicit discovery,
not automatic notes or learning runs. Shared targets require shared or comparison
questions and evidence for the stated relationship.

Accepting a topic reuses a scope only when its complete `notePaths` set matches,
regardless of order, and it has no additional term rules. Otherwise it creates a
scope identified by that matching rule. The existing note's scope owns retrieval;
read paths and evidence never automatically broaden it. Draft approval remains
required and does not prove semantic correctness.

The page shows **Notes apply to** separately from **Code available to read**.
Explicit empty note paths mean repository-wide; missing note paths mean unknown.
Old suggestions and plans remain inspectable with unknown applicability, but
cannot start or use a learning task until the user rediscovers and reselects topics.
No targets are guessed from the former mixed path list. A saved target that differs
from its scope is rejected before source reads or note preparation. Existing
notes, drafts, scope rules and progress are retained; discarding a plan removes
only that plan. Existing broad notes are not automatically moved or reclassified.

The accepted plan fixes repository revisions/document versions and allowed
folders or website roots. Suggested web defaults are two link hops from the
approved starting page, with bounded source responses and retained data.
Continuation does not reset link depth or
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
reports usage after a request. Source-access, response-size and turn bounds must be enforced by
ByteQuay, not merely written in a prompt. Save progress before exhausting them.

Normal learning must fit within these bounds; limits prevent runaway work rather
than requiring repeated runs to overcome an internal bookkeeping failure. Each
uses the configured agent turn timeout (30 minutes by default), without a
source-operation or documentation-open count quota. Reads, searches and opens
remain recorded as activity, including failures and retries. Reading chunks and
two link hops remain bounded. Input identity capture supports 4,096 distinct source
paths and ancestor directories, then expands the directories into exact manifest
lookups in batches of at most 512, within the existing ten-second metadata deadline.
A scope allows 256 source paths and 1,024 ancestor directories; 2,048 fingerprint
inputs fit within 128 KiB. Candidate build-file names do not each consume a logical
path slot. Expanding ancestor manifest names must not prevent saving an evidenced draft.
Incomplete identity errors include their actual reason.

Stored capacity is separate from what an agent receives or may submit at once:
discovery retains 200 topics, 200 sources and 400 evidence records under 4 MiB;
the notebook retains 500 note identities, 256 scopes and 200 registered sources,
with 4,000 evidence records, 8 MiB of prose/evidence and 16 MiB serialized storage.
Retained fingerprints have a separate 2 MiB notebook allowance. A run can retain
2 MiB of evidence and the 200 most recent activity lines; read receipts and attempt markers have
no cumulative item-count quota. Build
and run-history records each have a 4 MiB storage bound. Per-note 8 KiB bodies,
one-question runs, 20-topic learning batches, bounded lookup pages and 1 MiB
tool arguments remain unchanged. READY reports select supporting read receipts;
EMPTY reports must account for every captured source. Receipt lists have no
separate count quota, while tool and stored-result byte limits still apply.
Larger stored catalogs never trigger automatic
learning or automatic delivery of the whole notebook to an agent.

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

### Source check at read

The monitored branch keeps moving, so neither a baseline nor a head SHA tells
whether a note still describes the code. A note is out of date only when the
lines it cites change. Each exact read therefore compares the note's
repository-file sources with one commit that belongs to the reader:

| Reader | Compared commit |
| --- | --- |
| Development task | The task worktree's committed HEAD; uncommitted edits are not compared |
| Review | The run's frozen reviewed head |
| Learning run | The run's frozen baseline |

A source cites a line range of one file, and ByteQuay saved those lines when
it read them. The source is **UNCHANGED** when its path at that commit is a
regular file with the recorded blob, or when the cited lines still appear in
that file as consecutive lines in the same order, wherever they now are.
It is **CHANGED** when the file exists but the cited lines do not, and
**ABSENT** when the path is missing or not a regular file. Matching is exact,
so reformatting counts as a change; indentation carries meaning in files such as
YAML and Python. Documents, history, agent-reported excerpts, files from another
repository, sources without a recorded blob and failed lookups are
**UNCHECKED**, as is an excerpt cut short by the capture limit when its file's
blob differs. The note is `MATCHED` when it has sources and all are UNCHANGED,
`VERSION_MISMATCH` when any is CHANGED or ABSENT, and `UNVERIFIED` otherwise.
The read returns the compared commit and each source's path, cited lines,
recorded commit and status. When the cited lines moved and occur once, the
read also returns their current lines. The note text marks claims that cite a
CHANGED or ABSENT source. The result is retained with the read's existing
receipt, so an exact retry returns what the agent first saw.

The check is one bounded local `git ls-tree` over the note's cited paths, at most
eight, plus one bounded blob read for each file whose blob differs, within the
existing local Git time bound and 2 MiB file bound. It never fetches, stores no
freshness state and starts no background work. A failed check leaves the note
`UNVERIFIED` with the reason as a limitation; it never fails the read. List and
search stay literal and unchecked, so their hits remain `UNVERIFIED`. The Notes
page does not run this check.

`MATCHED` means the cited lines are unchanged, not that a claim is proved; a
claim can depend on lines it did not cite. Renamed files report ABSENT. The
check reports that cited lines differ, not which revision is newer, so a note
learned from newer code than the reader's commit is also `VERSION_MISMATCH`.
A mismatch neither disables the note nor blocks its use. The agent re-reads the
changed sources at its commit and trusts that code over the note. When that
code establishes the current behavior, or shows the note still holds, the agent
may propose an update of the exact version through the existing draft flow,
which cites the lines at its own commit. Which side is newer is the drafting
agent's judgment, which a development task can check with Git; the user
approves every update as usual.

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
Section 8 defines how these controls are presented.

Verify draft isolation/conflicts, multiple notes per area, literal pagination,
exact reads and their source checks, empty-notebook task startup, deep/non-Maven
discovery, bounded external sources, restart/continuation, partial results, and
no hidden model launches. Real-provider quality/cost checks are separate,
explicit acceptance work; do not run them against watched repositories during
implementation tests.

## 8. User interface

The user-supplied Claude Design prototype is the authoritative UI:
[`Repository Context.dc.html`](https://claude.ai/design/p/707db2cd-ebf1-414d-b09b-5d16df81c7f0?file=Repository+Context.dc.html)
with its twelve journey screenshots. It supersedes every UI layout
recommendation in this and earlier Repository Context documents; the sections
above still define behavior. Where the prototype omits a state, the
implementation extends its existing patterns rather than the old settings page.

- **Navigation.** Repository context is a workspace sidebar destination below
  Commits, not a Workspace settings page. An amber count shows pending drafts
  and source proposals. The page has **Overview** and **Notes** tabs; detail
  opens in one right-side drawer (note, learning plan, discovery, usage or
  advanced configuration) over the still-visible list. Esc closes it.
- **Overview.** One status card states what is happening and the next action,
  followed by Needs review, Learning plan, recently updated notes, Learning
  opportunities (merged work and suggested topics not yet in the plan) and,
  before the first plan, the suggested topics with a sticky selection bar.
- **Notes.** Approved notes grouped by area, with an area rail, a Knowledge /
  Lessons filter and a literal filter over title, summary and paths. An area is
  the note's scope; several notes may share one.
- **Run marking.** A lightning mark and a run line naming the run and model sit
  on every action that starts an agent: Start discovery, Discover remaining
  areas, Learn selected, Continue learning, Learn from this PR and an Explore
  send. Approve, edit, dismiss and source approval never carry it and say that
  no agent runs.
- **Counts.** Questions answered, pages opened and notes approved stay separate;
  there is no completion percentage. Run limits appear in the selection bar and
  the learning drawer.
- **Learning tasks.** Discovery and learning runs appear as a distinct Learning
  type in Tasks. Selecting one opens its retained activity in Repository context.
  These are read-only workflow views with Stop for the current run; they never
  link to the interactive task screen or offer messages, queues, or steering.
  Generic interactive input endpoints reject workflow-owned tasks.

The prototype's own "proposed" list is implemented as follows:

| Prototype proposal | Implementation |
| --- | --- |
| Sidebar count | Pending drafts plus pending source proposals from local notebook and plan reads |
| Home Needs attention row | A Repository context card in the workspace Home list; it opens the review drawer |
| Next question preview | The plan response names the question the next run will take |
| Discover remaining areas | A continuation discovery run; at an unchanged baseline its report keeps the earlier topics and evidence and adds new ones |
| Live activity line | Recorded source operations retain their completed, failed or limit status; a separate run indicator shows the last activity's age without claiming a finished read is still running |
| Grouping notes by area | By the note's scope and title; the first path prefix is a compact hint, not its matching identity |
| Stopping discovery | Saves nothing; a submitted partial report is shown with the partial banner |
| Explore & prepare context | A Home composer mode; the sent first message carries the explore instruction, and the transcript shows it as sent. Tasks have no explore flag, so the task header shows no mode chip |
| Task draft card | Pending drafts whose recorded proposing task is this task, shown after its transcript; duplicate checks are not reported |

The prototype has no remove, reset, scope-editing or per-note activity
controls, so those are retired: a note is disabled instead of removed, nothing
suppresses a note's regeneration, areas come only from learning, and usage
reports each note's current-version finds and opens for every note at once.

Supporting data the prototype assumes and the backend now records:

- A suggested topic lists up to six learning questions; each learning run takes
  one question. Plan questions are not started, unfinished (progress saved) or
  answered. Learn selected can add topics to the current plan when they come
  from the plan's discovery report.
- A bounded run history keeps each finished run's outcome (answered, no reusable
  note, source limit reached, stopped, failed or unfinished), operation counts
  and proposed note IDs, so earlier runs stay inspectable after the next start.
- Suggested external links keep the page and run that found them. Approval
  chooses the boundary explicitly: this page only, or the documentation root
  when the URL is a root. Declining removes the suggestion; neither starts a run.
- Discovery records module-directory coverage computed by ByteQuay from the
  paths the run listed or read, and lists what it did not survey.
- Suggestions stay learnable at their recorded discovery revision after the
  monitored branch moves; the plan and its runs read that revision.
- Note versions and draft ages come from recorded approval and proposal times.
  Notes approved before this change show no date.

# Repository context: discover, learn, and use

Status: implemented and verified by automated tests, except two parts of
[Investigate](#grades-and-reports): investigating a note the user wrote, and
saving what a narrowed note leaves out as separate notes. Both are approved and
not yet implemented; see
[notes without approval](repository-context-impl-tmp.md#save-notes-without-approval).
Live-provider quality and cost acceptance remain separate. Delivery and verification belong in
[the temporary development checkpoints](repository-context-impl-tmp.md#refactor-development-checkpoints);
the [source check at read](#source-check-at-read) has
[its own checklist](repository-context-impl-tmp.md#check-note-sources-at-read).
This contract supersedes earlier automatic note selection, generated-note
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
applicable area/path associations, origin, enabled state, version, and the text
their last change replaced.
Source references record each file's commit and blob. Titles, summaries, bodies
and claims describe the code, not the run that produced them: they omit commit
SHAs, baseline names, run numbers and dates unless the subject is itself a
specific version, so a note does not read as dated when the branch moves.
Existing note text is not rewritten; users can edit it. Each claim cites the
lines that support it: after exploring with broad reads, the agent re-reads the
supporting lines with an explicit range and cites that read, so the
[source check at read](#source-check-at-read) does not flag edits the claim
does not depend on.

Agents save notes without user approval. A user cannot judge prose about code
they do not know, so an approval step would catch no errors; wrong notes surface
when agents use them, and [grades and reports](#grades-and-reports) catch them
there. A new note, or an update of a note an agent wrote, becomes the active
version when the agent saves it. The save checks the observed notebook revision
and the updated entry's version, scope, kind and enabled state, so concurrent
user edits, disabling, deletion, or stale evidence ownership are never silently
overwritten; a failed check rejects the save with its reason, and the agent
re-reads before trying again.

Saved notes, updates, and agents' Wrong reports and Holds answers cite code at
the saver's **base**: a learning run's baseline, a development task's
merge-base with the monitored branch, or a review's captured monitored
baseline. ByteQuay captures cited repository lines at the saver's commit and
rejects them unless they also appear, as consecutive lines in the same order,
in the same file at that base. A task therefore cannot save knowledge about its
own unmerged changes or call a note wrong because of them; that knowledge
arrives through [merged work](#6-explore-and-merged-work) once it merges.
ByteQuay also rejects an update, Wrong report or Holds answer when a repository
source of the target note was recorded at a commit that descends from the
saver's base, because the note describes newer code.

Agents never change a note the user wrote or edited, or a disabled one. A
disabled note does not stop an agent from saving a similar new note. Every
change to a note's text keeps the text it replaced, one level deep;
**Restore** makes that text current again as a new version. Failed or stopped
work keeps every note it saved.

## 2. Who does what

The agent discovers project areas, connects implementation with documentation,
chooses useful learning questions, judges note relevance and applicability,
and proposes new notes or changes. It must support conclusions with sources and
distinguish documented rules, observations, inferences, and unanswered questions.

ByteQuay provides repository/document tools, literal note lookup, persistence,
source and version checks, run limits, task execution, progress, source
approval, and grade records.
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
   boundaries and run limits, which starts a batch over the plan's open
   questions. Defaults avoid manual path/rule entry. Existing notes remain
   available. Existing workspaces follow the same missing-discovery rule;
   reopening shows saved results rather than rerunning.

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
read paths and evidence never automatically broaden it. A saved note does not
prove semantic correctness.

The page shows **Notes apply to** separately from **Code available to read**.
Explicit empty note paths mean repository-wide; missing note paths mean unknown.
Old suggestions and plans remain inspectable with unknown applicability, but
cannot start or use a learning task until the user rediscovers and reselects topics.
No targets are guessed from the former mixed path list. A saved target that differs
from its scope is rejected before source reads or note preparation. Existing
notes, scope rules and progress are retained; discarding a plan removes
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

A learning run may also read one public page by its address, a question's lead
or a page the run found, without approval: ByteQuay fetches it over HTTPS,
refuses private and local addresses, and follows none of its links. A note that
cites such a page registers it as a page source, which the user sees among the
registered sources.

After each small unit, retain the plan, source identities/read evidence,
saved notes, the unfinished question, and next source or question.
Continue within the approved run while work and authorized capacity remain.
At a limit, stop starting more work and show the notes the run saved and
**Continue learning**. Continue authorizes a batch from the checkpoint: one
bounded run per open question, counted before the click. No other action grants
more spending.

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
path slot. Expanding ancestor manifest names must not prevent saving an evidenced note.
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

Saved notes are usable immediately while other topics remain unfinished. A
stopped run may have saved useful notes without finishing a topic.

### Questions and batches

Discovery writes each topic's starting questions from its survey: one to six,
more for a larger component. They orient a reader: the contracts and invariants
the code relies on, the risky paths, and the tests that guard them. A plan
topic holds up to 30 questions, and its list grows as learning finds more.

Each question records where it came from (discovery, a learning run, a
development task, a review or the user) and its state: open, unfinished
(progress saved), answered, corrected or dropped. A topic is done when none of
its questions is open or unfinished; there is no fixed count.

A learning run first checks its question's assumption against the code. It then
answers and saves notes, saves progress for a later run, or corrects the
question: the assumption does not hold, or the question is too broad. A
correction cites the lines that show why and replaces the question with one to
three questions, which also covers splitting it. A run may add up to three
follow-up questions to the same topic, each with the lines that raised it. It
never saves notes outside its topic's note target.

The user can add a question to a topic, edit an open one or drop one; a
question a run is working on changes only after that run ends. **Go deeper**
adds a question asking what the topic should cover next and starts it, so the
run answers with follow-up questions and any notes its reading supports.
Questions that runs, tasks or reviews add appear in the plan at once, marked
with their origin; the user drops the ones not worth learning. None of them
starts a run on its own.

**Learn selected** and **Continue learning** start a batch. One click runs each
question that is open or unfinished at that moment, one run per question in the
plan's order, at most 30 per batch; the run line shows how many runs the click
starts. The next run starts when the previous one ends and its worktree is
cleaned up. A run that reaches a limit or saves progress leaves its question
unfinished for a later batch, and the batch moves on. Stop ends the current run
and the rest of the batch; a failed run ends it too, and so does another run
the user starts between batch runs. Questions added during a batch wait for the
next click.

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

Agents search notes for an area before researching it, and go to primary
sources, the code, tests, documentation or the web, when notes do not answer
their question or a note's sources changed. Missing notes never block
development/review. The agent researches the specific question needed using
code, tests, and permitted sources. Fresh evidence is
usable in that task immediately; saving it as a reusable note is optional.
If required work exceeds allowed sources or run limits, request expansion;
otherwise continue inside the existing task without a separate learning run.
Unestablished rules remain explicit uncertainty, not invented knowledge.

Useful supported work can save a new note or an update. Check existing notes
before saving duplicates. No matching search hit does not prove a new note is
needed; no task is required to produce a lesson. Larger research opportunities
become suggested topics, not automatic jobs.

Work that had to look something up is where notes help most. A development task
or review that needed a web page or a long exploration to learn something
reusable files it with **Ask**: the question, the paths it concerns and an
optional lead, the page address or lines that answered it. The question joins
the plan topic whose note target covers those paths; without a matching topic
it becomes a plan gap for the next discovery, and without a plan it is refused.
The next learning batch reads the lead, checks it against the code and saves
the note with the passage it rests on, so later agents read the passage instead
of searching the web again. Task transcripts show the page each web step opened
or searched, not only its query.

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
YAML and Python. A cited web page is **CHANGED** when a learning run captured
different text from its address after the note's source was saved; learning
runs keep the newest capture of each page they read, for the 200 most recently
read pages. Other documents, history, agent-reported excerpts, files from
another repository, sources without a recorded blob and failed lookups are
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
existing local Git time bound and 2 MiB file bound, plus the saved page
captures when a source is a web page. It never fetches and starts no background
work. A failed check leaves the note `UNVERIFIED` with the reason as a
limitation; it never fails the read. List and search stay literal and
unchecked, so their hits remain `UNVERIFIED`. The Notes page does not run this
check, but a note's sources show a cited page that changed and when that was
seen.

`MATCHED` means the cited lines are unchanged, not that a claim is proved; a
claim can depend on lines it did not cite. Renamed files report ABSENT. The
check reports that cited lines differ, not which revision is newer, so a note
learned from newer code than the reader's commit is also `VERSION_MISMATCH`.
A mismatch neither disables the note nor blocks its use. The agent re-reads the
changed sources at its commit and trusts that code over the note. When that
code establishes the current behavior, or shows the note still holds, the agent
may save an update of the exact version that cites the lines at its own commit.
ByteQuay rejects an update or Wrong report from a base older than the note's
sources under the [save rules](#1-one-saved-object-a-context-note); where Git
cannot order the two, which side is newer is the agent's judgment.

### Grades and reports

A development task or review grades each note version it received in full or
opened, after checking it against its code: **Helped** when the note informed
the work, **Not relevant** when it did not apply, or **Wrong** when the code at
its base contradicts the note. Every grade carries a reason of at most 300
UTF-8 bytes. Wrong also quotes the contradicted statement, at most 500 bytes,
and cites one to three line ranges under the
[save rules](#1-one-saved-object-a-context-note); ByteQuay rejects a quote that
is not in that version's text. A task's latest grade for a version counts.
Grading is requested, not enforced: a note the task did not grade counts as
ungraded, never as helped. Learning runs do not grade the notes they read.
Grades, reports and answers are saved with the task's context receipts and
counted with its usage facts, so deleting the task removes them.

Helped and Not relevant are counts, never alerts. Not relevant describes one
task, not the note, so another task finding the same note helpful is no
conflict. Helped concerns the note as a whole and never answers a Wrong, which
concerns one quoted statement; grades are not tallied against each other.

A development task that grades an agent-written note Wrong saves the correction
in the same call, which may simply drop the statement, and the correction
becomes the active version. Any other Wrong is an open report on that version:
one from a review, which cannot save notes; one on a note the user wrote; or
one the user makes with **Wrong**, with an optional reason, wherever a task or
review lists the notes it received or opened. Tasks and reviews receive no note
bodies automatically, so while a report is open, an exact read of that version
returns up to three open reports with their reason, quote, cited lines and
reporter; list and search still find the note. A task or review that opens a
reported note re-checks the quoted statement at its base, then saves the
correction where it may change the note, or answers **Holds** with one to three
cited line ranges that support the statement. A report on a note the user wrote
also goes to **Needs you** at once: agents cannot change it, and its author can
judge it. Any new version closes the open reports on the version it replaces.

Each correction and agent settlement records the statement it settled: the
correction's new text or a settling save's rewritten statement, for as long as
the note's text still holds it. The user's Keep or Restore settles the whole
version they chose while it stays current. Agents disagree, and the note
becomes **contested**, when a task answers an open report with Holds, or when
another task's correction quotes a settled statement of the current version
while none of that version's cited repository lines changed at the reporter's
base. When they changed, or cannot be compared, the code may have moved on and
the correction is an ordinary fix. A contested note stays active, and an exact
read shows its current and previous text with each side's quote, reason and
cited lines. It stays contested through further agent corrections until a
settling save or the user's **Keep** (the current text), **Restore** (the
previous text), **Edit** or **Disable**. Needs you lists it, and leaving it
there is safe.

**Investigate** starts one learning run whose single question is the dispute.
Its input is both texts, every report and answer with its evidence, and the
root cause of any earlier settlement of the note. It reads the repository at
the monitored branch's current head within the usual run limits and ends in
exactly one outcome:

- rewrite the statement with the conditions under which each side holds,
  citing the lines that decide between them;
- keep the side that read the code correctly and say what the other missed;
- narrow the note to what holds in its scope and save the rest as separate
  notes;
- turn the statement into an open question when code and documents cannot
  settle it.

That outcome is a settling save: it records a root cause of at most 300 bytes
with the version it produces, and the note shows it. A development task that
needs a contested agent-written note for its own work may settle it without a
separate run: its correction of the note carries the root cause. For a note the
user wrote, an investigation changes nothing and returns its root cause and
suggested text for the user to apply with Edit. Keep and Restore record the
user's choice the same way.

A later correction of a settled statement on unchanged cited lines makes the
note contested again; Needs you then shows the earlier root cause or choice,
and nothing reruns. Investigating again passes that root cause to the new run.
Runs start only from the user's click, so disputes never loop on their own:
the resting state is a contested note that agents read with a warning.

Recently updated notes show agent corrections with Restore. Keep saves the
same text and owner again. ByteQuay stores and counts grades but never edits,
disables or ranks a note because of them, and does not decide which side of a
dispute is right.

Each note shows its grades beside its reach for the usage window, for example
*opened in 12 tasks · helped 7 · not relevant 3 · wrong 1 · last opened 2 days
ago*, or *not opened* when no retained task opened it since tracking began.
Helped is the agent's own judgment and the weakest signal. Mostly Not relevant
suggests a title or summary that attracts the wrong searches. Root causes stay
visible on their notes, so a kind that keeps recurring, such as notes scoped
wider than they hold, points to the learning instructions rather than to one
note. Whether repository context improves results overall remains a question
for the controlled On/Off comparison.

## 6. Explore and merged work

**Explore & prepare context** reuses the workspace question/development task.
Starting it authorizes an answer and useful note saving within shown limits.
Present the answer and the notes it saved. An existing equivalent note may need
an update or no change.

A confirmed merged PR associated with a development task can become a candidate
automatically without a model call. Its bundle uses known links to the final
PR/diff, issue or ticket, task, review, tests, and verified development summary.
Do not crawl unrelated history, infer links, or equate completion/closure with
merge. Repeated observations must not duplicate the candidate. Accepted comments
and merge status record a decision, not proof that every claim is true.

The user starts bounded learning from selected candidates. The agent saves a
new note, an update, or no reusable conclusion. There is no default background
model job after merge.

## 7. Visible controls and verification

The main progression is **Start discovery → Learn selected → Continue
learning**. Existing editing, context-use switches, source inspection,
and usage details remain reachable without mandatory configuration steps.
Show completed work, pending questions, limitations, and source versions.
Found/opened/cited observations do not claim understanding or correctness.
Section 8 defines how these controls are presented.

Verify save and base checks, Restore, grades, open reports, contested notes and
investigations, multiple notes per area, literal pagination, exact reads and
their source checks, empty-notebook task startup, deep/non-Maven discovery,
bounded external sources, restart/continuation, partial results, and no hidden
model launches, batches, question corrections and follow-ups, filed questions
and single-page reads. Real-provider quality/cost checks are separate, explicit
acceptance work; do not run them against watched repositories during
implementation tests.

## 8. User interface

The user-supplied Claude Design prototype is the authoritative UI:
[`Repository Context.dc.html`](https://claude.ai/design/p/707db2cd-ebf1-414d-b09b-5d16df81c7f0?file=Repository+Context.dc.html)
with its twelve journey screenshots. It supersedes every UI layout
recommendation in this and earlier Repository Context documents; the sections
above still define behavior. Where the prototype omits a state, the
implementation extends its existing patterns rather than the old settings page.

- **Navigation.** Repository context is a workspace sidebar destination below
  Commits, not a Workspace settings page. An amber count shows what needs the
  user: pending source proposals, contested notes, and open Wrong reports on
  notes the user wrote. The page has **Overview** and **Notes** tabs; detail
  opens in one right-side drawer (note, learning plan, discovery, usage or
  advanced configuration) over the still-visible list. Esc closes it.
- **Overview.** One status card states what is happening and the next action,
  followed by Needs you, Learning plan, recently updated notes (agent changes
  carry **Restore**), Learning opportunities (merged work and suggested topics
  not yet in the plan) and, before the first plan, the suggested topics with a
  sticky selection bar. A finished run's card names the notes it saved and
  offers **Disable notes from this run**, which disables the notes it added
  that are still at the version it saved.
- **Notes.** Notes grouped by area, with an area rail, a Knowledge / Lessons
  filter and a literal filter over title, summary and paths. An area is the
  note's scope; several notes may share one. Each row shows the note's grade
  line and marks a contested note and a note that cites a page a learning run
  later found changed.
- **Run marking.** A lightning mark and a run line naming the run and model sit
  on every action that starts an agent: Start discovery, Discover remaining
  areas, Learn selected, Continue learning, Learn from this PR, Investigate, Go
  deeper and an Explore send; a batch's run line says how many runs it starts.
  Edit, restore, keep, disable, question edits and source approval never carry
  it and say that no agent runs.
- **Learning plan.** Each question shows its state and, when it did not come
  from discovery, its origin; a corrected question shows why and a filed one its
  lead. A topic offers adding a question, editing or dropping an open one, and
  Go deeper. A running batch shows which of its questions it is on.
- **Counts.** Questions answered, pages opened and notes saved stay separate;
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
| Sidebar count | Pending source proposals from plan reads, plus contested notes and open Wrong reports on notes the user wrote |
| Home Needs attention row | A Repository context card in the workspace Home list; it opens the first item that needs the user |
| Next question preview | The plan response names the question the next run will take |
| Discover remaining areas | A continuation discovery run; at an unchanged baseline its report keeps the earlier topics and evidence and adds new ones |
| Live activity line | Recorded source operations retain their completed, failed or limit status; a separate run indicator shows the last activity's age without claiming a finished read is still running |
| Grouping notes by area | By the note's scope and title; the first path prefix is a compact hint, not its matching identity |
| Stopping discovery | Saves nothing; a submitted partial report is shown with the partial banner |
| Explore & prepare context | A Home composer mode; the sent first message carries the explore instruction, and the transcript shows it as sent. Tasks have no explore flag, so the task header shows no mode chip |
| Task note card | Notes this task saved, shown after its transcript; its context activity lists each note it received or opened with the grade and a Wrong action; duplicate checks are not reported |

The prototype has no remove, reset, scope-editing or per-note activity
controls, so those are retired: a note is disabled instead of removed, nothing
suppresses a note's regeneration, areas come only from learning, and usage
reports each note's current-version finds and opens for every note at once.

Supporting data the prototype assumes and the backend now records:

- A suggested topic lists up to six starting questions and a plan topic holds up
  to 30; each learning run takes one question. Plan questions are open,
  unfinished (progress saved), answered, corrected or dropped, and keep their
  origin. Learn selected can add topics to the current plan when they come from
  the plan's discovery report.
- A bounded run history keeps each finished run's outcome (answered, no reusable
  note, source limit reached, stopped, failed or unfinished), operation counts
  and saved note IDs and versions, so earlier runs stay inspectable after the
  next start.
- Suggested external links keep the page and run that found them. Approval
  chooses the boundary explicitly: this page only, or the documentation root
  when the URL is a root. Declining removes the suggestion; neither starts a run.
- Discovery records module-directory coverage computed by ByteQuay from the
  paths the run listed or read, and lists what it did not survey.
- Suggestions stay learnable at their recorded discovery revision after the
  monitored branch moves; the plan and its runs read that revision.
- Note ages come from recorded save times; notes saved before those times
  were recorded show no date.

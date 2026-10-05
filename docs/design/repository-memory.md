# Repository memory

Status: approved; phases 0 to 4 are implemented: the store with the memory tool,
the Memory page, turn-start delivery with receipts, harvest runs, finding
citations, and holdout replays. Development answers are asked to cite memories
but are not counted yet. It replaces the knowledge-learning design of Repository context
and the documents it built on, which phase 2 deleted, and revises the
[spec check](spec-check.md) to start from a directory instead of a plan topic. The user chose the direction: remove the learning system, its
note code and the note data built so far in one step, save memory without
approval and show it, give agents matching memory at turn start plus a lookup
tool, and learn first from the user's own and reviewed pull requests and from
any merged pull request the user picks.

## 1. What changes and why

Memory is what an agent cannot read from the repository at its commit: how to
build and test where the docs are silent or wrong, why code is the way it is,
the rules maintainers enforce, corrections of an agent's thinking, how the user
wants work done, and which specification governs which code. Everything else an
agent rebuilds each turn with semantic queries and source reading.

- **What the notes did.** The notebook held 216 notes: 215 knowledge notes and
  one lesson. One note reached one development task. Ten reviews opened 18
  notes, used them as maps to the right code paths and re-read the code every
  time; one was wrong because the code had moved on. Semantic queries, when available on a worktree task, and the review history reads (`commit_prs`, `history`) cover
  the same ground.
- **What CodeRabbit keeps.** For each review it rebuilds a code graph and runs
  linters and shell checks in a sandbox. Between reviews it keeps only the
  team's guideline files, learnings taken from people's replies in review
  threads (with author, pull request, file and lines), and past pull requests
  and issues for intent. Its comments show receipts for each claim: the commands
  it ran, the guideline that applied, the learnings it used.
- **Why learning runs cannot produce memory.** A run that reads code to write
  notes can only restate the code. A note that derives "intended behavior" from
  code records actual behavior, bugs included, as a contract.

## 2. Memory

A memory is one short rule with a title, a scope and its sources.

| Field | Content |
|---|---|
| `kind` | RECIPE: how to build, test or run something here, with the command. RULE: a convention, decision or correction, meaning what to do or avoid, when, and why. SPEC: a specification that governs code, with its address, the paths that implement it and deviations kept on purpose |
| `title` | At most 80 characters on one line, naming the situation the memory covers, such as "Adding connector table properties", so a reader can tell whether it applies before reading the text. Agents write it with the text, and the user edits it. A memory saved without a model takes its finding's title or "Specification at" its address, cut at a word. A memory saved before titles existed shows its text's opening words until it is edited |
| `text` | At most 600 bytes, one paragraph, a condition and an instruction: "When adding a connector table property, document it on the connector's page; maintainers ask for it on every such PR." Recipes put the command in backticks. No SHAs, run numbers or dates; sources carry those |
| `scope` | Up to 8 path prefixes or globs, the repository, or the user everywhere (personal preferences across workspaces) |
| `sources` | 1 to 8 references, each with a quote of at most 300 bytes: PR (number, comment or review, author, whether the author is a maintainer, merged or closed), ISSUE (number, comment, author), USER (task or review, message or reason, the user's words), COMMAND (task, turn, command, directory, exit code 0, commit), PAGE (address, section, cited text), FILE (path and lines at a commit) |
| `origin` | AGENT, with the task, review or harvest run that saved it, or USER when the user typed or edited it. Agents never change a USER memory |
| `id`, `version`, `previous` | Stable identity, a version for optimistic edits, and the title, text and scope it replaced, one level deep, for Undo and Restore |
| counts | Delivered and cited: how often it reached an agent and how often a finding or answer cited it |

A workspace holds at most 300 memories and the user at most 100 personal ones.

**Checks.** Each source kind brings its own check, computed when the memory is
read and never stored as state:

- PR and ISSUE sources show their age. A newer pull request that overrides a rule
  replaces it, keeping both sources.
- A COMMAND source is flagged when a later run of the same command in this
  repository failed. At the end of each turn ByteQuay compares the turn's
  finished commands with recipe commands, by text and directory, without a
  model. Task logs end with their retention window, so this is the one check
  stored: a report, "Its command failed with exit N in a later run", once per
  task and exit code, which Keep or Edit clears like any other.
- A PAGE source is flagged when its cited text is no longer on the page, using
  the existing page captures.
- A FILE source is flagged when its cited lines are gone at the reader's commit,
  using the existing source check.
- A scope is flagged when none of its paths exists at the monitored head.
- USER sources have no check; the user keeps or deletes the memory.
- A wrong report from an agent or the user flags the memory.

A flag never edits or hides a memory. It lists the memory under Needs you and is
delivered with the memory.

## 3. Where memory comes from

Memory is captured where the information appears. No run reads code to describe
code.

### 3.1 Pull request history

**Triggers.** Each click starts one harvest run.

- **Learn from my PRs** on the Memory page reads the pull requests in this
  repository the user authored or reviewed, merged or closed, newest first,
  after the last one harvested, at most 30 per click. The cursor is the newest
  pull request a finished run read; with more than 30 newer, the older ones are
  left to Learn from this PR. A click that finds no new maintainer thread starts
  no run and moves the cursor past what it read.
- **Learn from this PR** on any merged or closed pull request's page reads that
  one, when it belongs to the repository the workspace monitors. It never moves
  the cursor.
- **Learn from one closed pull request** on the Memory page starts the same run
  from a search. The user types or pastes a number or the pull request's GitHub
  link, which reads that pull request, or title words, which search the
  monitored repository's closed pull requests on GitHub, at most 10, most
  recently updated first. A link to another repository is refused with the
  reason. The search waits for typing to pause, keeps the repository and state
  fixed whatever is typed, and starts nothing; each result's Learn does.

**Fetch, without a model.** For each pull request ByteQuay reads its review
threads with their resolved and outdated flags, review bodies, issue comments,
and how it ended: the merge, or the closing actor and their comment. Comments
carry their author's association, which the GraphQL query adds. GitHub search
lists the user's pull requests with `author:` and `reviewed-by:`. Everything is
a read, under the shared rate budget.

**Filter, without a model.** Bots are dropped. A thread is kept when a maintainer
(association OWNER, MEMBER or COLLABORATOR) commented in it, and marked applied
when it was resolved or the code under it changed. A closed, unmerged pull
request is kept only when a maintainer explained the close. Threads read by an
earlier harvest are skipped.

**The harvest run** is a restricted, non-interactive task, the profile learning
runs use today: no children, commits, pushes or credentials, on the review model
setting.

- It reads the filtered threads as untrusted reference data.
- It may read the repository at the monitored head with permitted code queries and file reads
  to scope a rule and confirm it still applies.
- It saves through one tool:
  - SAVE adds a new rule with its title.
  - ADD_SOURCE adds a source when a rule is seen again, so a recurring
    convention shows "3 PRs".
  - REPLACE records a newer pull request that overrides an older rule, with a
    new title, text and scope.
- Every save cites comment IDs from the run's own threads, and ByteQuay rejects
  any other source.
- **Its instructions:** save what a maintainer asked for that would apply to
  other changes; skip one-off nits, typos and requests about one line; prefer
  applied threads; a rule from a closed pull request says what was rejected and
  why; never save how code works.

**What is kept:** the memories with their quotes and links, the harvest cursor,
the IDs of threads already read, at most 5,000, and the last run's counts. The
fetched threads live only in the run's binding until the next run replaces it.
A run that does not finish leaves the cursor and the read threads as they were.
A harvest and a spec check share the workspace's one run slot.

### 3.2 Corrections and preferences in tasks and reviews

Development tasks and reviews get REMEMBER in the memory tool. The instruction:
when the user corrects you or says how they want work done, and it would apply
beyond this change, remember it in your own words, titled with the situation it
covers, with the user's words as the source.

- **Source binding.** ByteQuay binds the source to the user message the turn is
  answering, or to the review discussion message. The agent may quote the user's
  words, and ByteQuay checks the quote against the messages the user sent in that
  turn, steers included; it cannot cite anything else. A review step, a generated
  follow-up and a local review dispatch carry ByteQuay's words, not the user's,
  so they remember no rule. Only a USER source can have the user-everywhere scope.
- **Saving.** A memory is saved at once. The transcript shows "Remembered:" and
  its title, with Undo, which deletes it, or restores the previous version of an
  update.
- **Remember on Resolve and Dismiss.** Resolve on a review finding and Dismiss on
  a spec finding gain **Remember for \<path\>** beside their reason. The reason
  is saved as written: a RULE with a USER source, titled with the finding's title
  and scoped to the finding's file, with no model. Dismiss picks its reason from a list, so its Remember asks for
  the rule in the user's words. A spec finding's file is the first code it cites
  under the checked directory; a finding with no file offers no Remember.
- **Save lesson** and its lesson drafts are removed; these paths replace them.

### 3.3 Recipes

**Docs come first.** Guideline files reach every conversation (section 4), so a recipe
records only what the docs don't say or got wrong.

**Proof.** REMEMBER with kind RECIPE must cite a command that finished in this
task with exit code 0. ByteQuay finds it in the task log, as the spec check
does, and stores the command, its directory and its commit. The instruction:
remember a command you had to work out (flags, module, setup), not one the docs
already give.

**Claude exit codes.** Claude command records carry an exit code: 0 when the
Bash result is not an error, and the number from Claude's `Exit code N` when it
is, so recipes and spec checks work on both lanes.

**Timing.** Capture happens during the turn. Nothing scans logs later; they are
deleted after the task's retention window.

### 3.4 Spec links and deviations

- **The check starts from a module.** The user picks one of the repository's
  modules, those whose own code cites one page in the most sections first, or
  types a directory, instead of a plan topic. The check lists the pages the code
  links, may propose a specification the code doesn't link, decides which are
  specifications and reproduces gaps.
- **Spec memories.** Each specification a check used becomes, or updates, a SPEC
  memory titled "Specification at" its address, with the paths that link it and
  the sections read. A
  development task or review that read a spec page may REMEMBER a SPEC with a
  PAGE source, and the user may Remember a Goal's knowledge note that cites a
  specification page as a SPEC of their own, with the pages it cites as sources.
- **Deviations.** A deviation kept on purpose is a RULE. It comes from a spec
  finding dismissed as "not a real gap" with Remember, or from a pull request
  thread.

### 3.5 Not stored

- **How code works,** call paths and module layout: semantic queries and source reading.
- **Why a line is the way it is:** looked up on demand from blame, commit and
  pull request, with `commit_prs` and `history` in reviews.
- **What the docs say:** it arrives as guidelines.

### Related workspace reads

Development conversations may SEARCH/READ the memory of a directly related
workspace using the existing tool's optional `workspaceId`. Both workspaces'
development-memory switches apply, and every call rechecks membership. Related
memory is read on demand; it is not copied into local memory or automatic turn
receipts. Results identify workspace, memory version and sources; existing
pagination exposes the complete saved collection. Memory is current advice, not
a release snapshot: the agent checks its sources against the selected code commit
and states missing or mismatched evidence. Related guidelines apply to their own
code. REPORT and REMEMBER stay local.
See [workspace relationships](workspace-backports.md).

## 4. Guidelines

**What and when.** At every turn of development tasks, reviews, harvest runs and
spec checks, ByteQuay adds the repository's guideline files as reference data,
as CodeRabbit lists "Code guidelines".

- **Files:** `CLAUDE.md`, `AGENTS.md`, `CONTRIBUTING.md` and `DEVELOPMENT.md` at
  the root and under `.github/`.
- **Reviews also get** any `CLAUDE.md` or `AGENTS.md` in a directory above a
  changed path. The Memory page can add paths.
- **Symlinks:** a symlinked file is skipped; it usually points at one already
  read.
- **Size:** up to 32 KiB, each file up to 16 KiB, with truncation stated.

**Read at the base.** A review reads its pull request's base branch tip, never
the head under review, so a pull request cannot rewrite the agent's guidance; a
backport reads its target branch's rules. Every other task reads the commit its
worktree started from. A read that fails leaves the turn's input unchanged.

**Both lanes get the same text.**
- Codex's own `AGENTS.md` loading is turned off (`project_doc_max_bytes=0`), so
  the file is read once and from the base.
- The Claude lane keeps `--setting-sources=`, which also keeps the
  repository's Claude settings and hooks out.

**Trust.** Guidelines are reference data under ByteQuay's managed instructions:
follow them where they don't conflict.

**Receipt.** Each turn records the files it carried, and its footer shows
"Guidelines 2".

Turns reuse guidelines already acknowledged by the provider in that
conversation and frozen scope until the conversation is compacted. A new
conversation or review run, or a compaction, reads them again; an absent or
unacknowledged receipt cannot suppress delivery. Memory is looked up afresh on
every turn, and a block identical to the latest acknowledged one, with no
compaction since, is left out; generated development turns continue without
another Memory block.

## 5. Delivery and receipts

**At turn start.** After the guidelines, a Memory block lists the titles of
matching memories, one line each: `[m12] <title> (PR #31201 @alice, 3 PRs)`. As
with a skill's description, the title tells the agent whether a memory applies;
when one covers what it is changing or reviewing, the agent reads the memory's
text and sources with READ before acting, and cites it.

- **Reviews:** memories scoped to a changed path, then repository-wide ones, then
  the user's.
- **Development tasks:** repository-wide and the user's. From the second turn on,
  also those scoped to paths the task has changed.
- **Limits and order:** at most 40 lines and 12 KiB. Path matches come first,
  longest prefix first, then by number of sources, newest first. A flagged memory
  is delivered with its flag. When the list is cut, the block states how many
  matches it shows and tells the agent to SEARCH by path and page through titles
  or narrow its query to find other relevant memories.

This deliberately reverses the current rule against automatic note injection:
lookup alone reached one development task, because an agent could not see what
was saved. Titles show it what exists without sending every rule's text, so the
block stays short as memories accumulate.

**The memory tool**, `repository_memory`, for development tasks and reviews with
memory on, even before anything is remembered, replaces
`repository_context_lookup` and the note kinds of `review_get_context`:

| Action | What it does |
|---|---|
| SEARCH | Literal title/text and path matches, paged; returns titles, scopes, source labels and flags without the full text |
| READ | A memory with its sources and quotes |
| REMEMBER | Saves a memory (section 3.2 and 3.3) |
| REPORT | Flags a memory as wrong, with a reason and a quote |

SEARCH is an index, like a skill listing. When many memories match a path, the
agent compares their descriptive titles with its task, browses further pages or
narrows its query, and READs only relevant memories before using them. Literal
search still checks the full text even though the listing does not return it.

**Citations.** Review findings get an optional `memoryIds`. Each revision keeps
the cited rules as the reviewer received them, and a memory counts once per
finding. A development task's final answer cites `[m12]` when a memory changed
what it did; ByteQuay does not count those yet, since a turn keeps no answer
text.

**Receipts.**
- A finding shows "Memory used", folded, with each rule and its source.
- Transcripts show "Remembered" rows.
- The turn receipt lists the titles delivered.

**On and off.** The Memory page brings back the workspace switches for reviews
and development; the old ones lived in the notebook phase 2 deleted. A turn reads
the switch when it starts, and a session when its tools are registered; a turn
whose per-turn flag is off gets no memory either. Off delivers no memory and
offers no tool; guidelines stay on.

## 6. User interface

**Sidebar.** Memory replaces Repository context. Its badge counts flagged
memories.

**Memory page:**
- **Top tabs:** Memories (the default), Learn from PRs, Needs you, Spec checks,
  Replays and Guidelines use the Tasks page's tab style. Only the selected
  section is shown; the header and tabs stay visible while its content scrolls.
  Filters survive switching tabs. Opening a spec check selects Spec checks.
- **Header:** chips with an icon and a count, the sentence on hover, for recipes,
  rules and specs, and for memories from you, from pull requests and from tasks.
- **Actions:** Learn from my PRs, showing how far the cursor is ("Learned
  through #31201"), with the last run's counts and the titles of the rules it
  saved, each with Delete; below it, the search for one closed pull request by
  number, link or title words, each result with merged or closed and Learn; and Check
  against spec, with a directory picker. Opening the page reads no GitHub data,
  so the count of newer pull requests comes with the run, and only typing in the
  search reads GitHub.
- **Needs you:** flagged memories by title, with the reason.
- **List:**
  - **Grouping and filters:** grouped by kind, with source and path filters and a
    literal filter over titles, text, scope and sources.
  - **Row:** the title, the rule beneath it, muted and cut to two lines, scope
    chips, a source chip ("PR #31201 @alice · 3 PRs", "You", "Task", "Page") and
    the cited count, with no bold text. A title the text already opens with, such
    as one cut from the text, is not repeated.
  - **Drawer:** a row opens the memory drawer with its title and text, sources
    with quotes and links, the previous version, Edit for the title and text, and
    Delete with an Undo toast.
- **Guidelines:** the files that reach agents, with size and an open link.
- **Specs:** spec memories with each one's latest check report, reusing the
  current report UI. Each check's confirmed findings appear directly on the
  Spec checks tab; its drawer holds coverage, other outcomes and rerun controls.
- **Replays:** each replay with what each side raised, the sum over finished
  replays, and a drawer that sets the maintainers' comments beside both sides'
  findings, file by file, where the user changes a call and opens either review.

**Elsewhere:**

| Surface | Change |
|---|---|
| Pull request page | Learn from this PR, on merged or closed pull requests; Replay review, on merged ones |
| Task and review transcripts | "Remembered:" rows with the memory's title and Undo, shown while their activity is folded; a Codex review whose root tools run outside MCP reports no line for them, so it shows none. The turn receipt folds "Memory: 6 delivered" and "Guidelines: 3 files" |
| Review findings | "Memory used", folded. The Resolve dialog gains Remember for \<path\> |
| Tasks list | Harvest runs appear as learning runs do today: read-only activity with Stop |
| Home | Needs attention keeps a row while memories are flagged |

## 7. What is removed

Phase 2 removes the learning system.

**Backend.** From `app/context` and `core/context`:
- **Discovery and learning:** discovery with its report, tools and controller;
  learning plans, topics, questions and batches; the LEARN and DISCOVER modes;
  Learn this topic and Go deeper; the ASK and PROPOSE tool actions.
- **Notes and preparation:** Knowledge notes and the notebook with its claims,
  scopes and areas; review preparation (`repository_context_prepare`,
  preparation records, input fingerprints, the review context snapshot and
  packet); merged-PR candidates.
- **Grading and usage:** grades, contests, disputes and Investigate; the usage
  page with its web-lookup counts; development receipts and their activity panel.
- **Storage:** the `context_grade`, `context_usage_fact` and
  `development_context_receipt` tables, and the `workspace.repositoryContext*`
  settings keys.

**Kept, and moved where the new design uses it:**
- The restricted run machinery (`ContextBuildService`, its store and
  coordinator), cut to spec checks and harvest runs.
- Safe page reads (`DocumentationHttpReader`, `SelectedDocumentationReader`),
  the spec check and its link scan. Page captures and the cited-lines source
  check went too: nothing reads them without notes.
- History reads (`ContextHistoryReader`, `SelectedCommitPullRequestReader`) for
  reviews.
- The `ContextLimits` and `ContextText` helpers that code outside the feature
  uses.

**Frontend:**
- `src/repository-context`, except the spec check parts, which make a slim Spec
  checks page until the Memory page takes them.
- Home's context row and its Explore & prepare context mode, the task note cards,
  the development activity panel and the Tasks list's Learning items.
- The review context details, preparation activity and Save lesson.
- The 41 IPC channels shrink to the spec check set; phase 3 adds the memory set.

**Docs:**
- Deleted: `repository-context.md`, `repository-context-refactor.md`,
  `repository-context-agent-discovery.md`, `repository-context-acceptance.md`,
  `repository-context-impl-tmp.md`, the `docs/evaluation` captures and the two
  `scripts/*repository-context-cases.py` fixture tools.
- Rewritten: the context paragraphs in `architecture.md`, `agent-service.md`,
  `agent-task-ui.md` and `agent-skills.md`; the spec check design moved to
  `spec-check.md`.

**Old local data** is deleted in the same step as the code. Nothing is
migrated, and the one saved lesson is not carried over. The deletion covers:
- every `workspace.repositoryContext*` settings key: notes, plans, discovery
  reports, runs, pages, disputes, merged candidates, web lookups and spec checks;
- `workspace.contextUsageTrackingSince.*`;
- the three tables above;
- the `agent_review_run.context_json` column;
- the renderer's saved view state for the old page.

One idempotent cleanup at startup performed the deletion and was removed once
it had run. The per-turn `use_repository_context` flags stay as
the memory switch. Spec checks keep their findings under new settings keys;
checks saved before phase 2 start over.

## 8. Boundaries kept

- **No GitHub write.** Harvests only read. Spec findings are still filed by the
  user.
- **A model runs only on a click.** Each click starts at most one harvest run.
  Agents save memory only inside tasks and reviews the user started. Flags and
  checks need no model.
- **Source text is untrusted.** Pull request threads, pages, guideline files and
  memory text reach agents as reference data under the managed instructions.
  Provider native memory stays off.
- **Sources are bound by the server.** ByteQuay attaches message IDs, command
  records and comment IDs; a model cannot name a source it was not given.
- **User content wins.** Agents never change a USER memory, and a user edit
  makes a memory USER.
- **Private text stays local.** Memories from private review discussions are
  never posted.
- **Missing memory never blocks work,** and a flag never hides a memory.

## 9. Holdout replay

**Replay review.** On a merged pull request, Replay review runs Agent Review
twice at the pull request's first reviewed commit, its head when the first
maintainer review arrived: once with memory and once without.

- **The commit.** ByteQuay reads the pull request's reviews and the head each was
  given on. A head GitHub no longer has gives way to the next maintainer-reviewed
  head, then the merged head, and the replay says so. The base is where the
  pull request branched from the base branch just before the merge commit.
- **No leakage.** The run with memory gets no memory with a source from this
  pull request or written after the first maintainer review. A replay's
  deliveries and citations are not counted, and it cannot report a memory. Both
  runs are told to stay on the frozen head and leave alone the pull request's
  discussion, later commits, and anything written after that review. Their
  review tools refuse the reads that show today's GitHub data: the pull request,
  its timeline, threads and checks, issues, and history. The local clone still
  holds the merged result, so that part rests on the instructions.
- **Comparison.** The maintainers' comments on lines, the first comment of each
  thread they started with its lines at the commit it was written on, sit next to
  each run's findings, file by file. A comment counts as raised by a run when one
  of its findings is on the same file and side within 3 lines, and the user can
  correct each call. A comment written on a later head is marked, since the
  replay never saw that code.
- **Use.** Five to ten replays show whether memory makes reviews raise what
  maintainers raise.
- **Limits.** Replays start only on a click, use the review model, are labeled as
  replays, are never posted, and end once their run does. A replay has no pull
  request number, so the pull request's own review never finds it. A workspace
  keeps its last 20 replays.

## 10. Delivery plan

Each phase lands as commits that build and pass, under the repository's commit
rules, and ends with the full gates: `mvn verify`, then lint, `tsc`, tests and
packaging in the frontend.

0. **Spec check fixes.** Claude command records carry exit codes, and workspace
   deletion removes the workspace's spec checks.
1. **Guidelines to every turn.** Read at the base, the same text on both lanes,
   Codex's native loading off, and the receipt.
2. **Removal.** The note code and the note data built so far, in one step:
   everything in section 7. The spec check starts from a directory, and the run
   machinery is cut to spec checks.
3. **Memory.**
   - The store and the memory tool with SEARCH, READ and REPORT.
   - Turn-start delivery, the Memory page and receipts.
   - Harvest runs: Learn from my PRs and Learn from this PR.
4. **Holdout replay.**
5. **Capture in tasks and reviews.**
   - REMEMBER from user messages, and recipes from passing commands.
   - Remember on Resolve and Dismiss.
   - Recipe failure flags and Undo.

## 11. Open questions

- **Maintainers.** The author association covers organization members and
  collaborators. A repository whose maintainers are outside both may need a
  per-workspace list.
- **First reviewed commit.** A force-pushed pull request may have lost it. The
  replay then uses the oldest commit GitHub still has, and says so.
- **Personal memory.** Memory scoped to the user everywhere lives outside any
  workspace, and only the user deletes it.

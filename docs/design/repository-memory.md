# Repository memory

Status: approved. It replaces the knowledge-learning design of
[Repository context](repository-context-refactor.md) and the documents it builds
on, which phase 2 deletes, and revises the
[spec check](repository-context-spec-check.md) to start from a directory instead
of a plan topic. The user chose the direction: remove the learning system, its
note code and the note data built so far in one step, save memory without
approval and show it, give agents matching memory at turn start plus a lookup
tool, and learn first from the user's own and reviewed pull requests and from
any merged pull request the user picks.

## 1. What changes and why

Memory is what an agent cannot read from the repository at its commit: how to
build and test where the docs are silent or wrong, why code is the way it is,
the rules maintainers enforce, corrections of an agent's thinking, how the user
wants work done, and which specification governs which code. Everything else an
agent rebuilds each turn with CodeGraph and reading.

- **What the notes did.** The notebook held 216 notes: 215 knowledge notes and
  one lesson. One note reached one development task. Ten reviews opened 18
  notes, used them as maps to the right code paths and re-read the code every
  time; one was wrong because the code had moved on. CodeGraph, on every
  worktree task, and the review history reads (`commit_prs`, `history`) cover
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

A memory is one short rule with a scope and its sources.

| Field | Content |
|---|---|
| `kind` | RECIPE: how to build, test or run something here, with the command. RULE: a convention, decision or correction, meaning what to do or avoid, when, and why. SPEC: a specification that governs code, with its address, the paths that implement it and deviations kept on purpose |
| `text` | At most 600 bytes, one paragraph, a condition and an instruction: "When adding a connector table property, document it on the connector's page; maintainers ask for it on every such PR." Recipes put the command in backticks. No SHAs, run numbers or dates; sources carry those |
| `scope` | Up to 8 path prefixes or globs, the repository, or the user everywhere (personal preferences across workspaces) |
| `sources` | 1 to 8 references, each with a quote of at most 300 bytes: PR (number, comment or review, author, whether the author is a maintainer, merged or closed), ISSUE (number, comment, author), USER (task or review, message or reason, the user's words), COMMAND (task, turn, command, directory, exit code 0, commit), PAGE (address, section, cited text), FILE (path and lines at a commit) |
| `origin` | AGENT, with the task, review or harvest run that saved it, or USER when the user typed or edited it. Agents never change a USER memory |
| `id`, `version`, `previous` | Stable identity, a version for optimistic edits, and the text and scope it replaced, one level deep, for Undo and Restore |
| counts | Delivered and cited: how often it reached an agent and how often a finding or answer cited it |

A workspace holds at most 300 memories and the user at most 100 personal ones.

**Checks.** Each source kind brings its own check, computed when the memory is
read and never stored as state:

- PR and ISSUE sources show their age. A newer pull request that overrides a rule
  replaces it, keeping both sources.
- A COMMAND source is flagged when a later run of the same command in this
  repository failed. At the end of each turn ByteQuay compares the turn's
  finished commands with recipe commands, without a model.
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
  after the last one harvested, at most 30 per click.
- **Learn from this PR** on any merged or closed pull request's page reads that
  one.

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
- It may read the repository at the monitored head with CodeGraph and file reads
  to scope a rule and confirm it still applies.
- It saves through one tool:
  - SAVE adds a new rule.
  - ADD_SOURCE adds a source when a rule is seen again, so a recurring
    convention shows "3 PRs".
  - REPLACE records a newer pull request that overrides an older rule.
- Every save cites comment IDs from the run's own threads, and ByteQuay rejects
  any other source.
- **Its instructions:** save what a maintainer asked for that would apply to
  other changes; skip one-off nits, typos and requests about one line; prefer
  applied threads; a rule from a closed pull request says what was rejected and
  why; never save how code works.

**What is kept:** the memories with their quotes and links, the harvest cursor,
and the IDs of threads already read. The fetched threads are not stored.

### 3.2 Corrections and preferences in tasks and reviews

Development tasks and reviews get REMEMBER in the memory tool. The instruction:
when the user corrects you or says how they want work done, and it would apply
beyond this change, remember it in your own words with the user's words as the
source.

- **Source binding.** ByteQuay binds the source to the user message the turn is
  answering, or to the review discussion message. The agent cannot cite anything
  else. Only a USER source can have the user-everywhere scope.
- **Saving.** A memory is saved at once. The transcript shows "Remembered: …"
  with Undo, which deletes it, or restores the previous version of an update.
- **Remember on Resolve and Dismiss.** Resolve on a review finding and Dismiss on
  a spec finding gain **Remember for \<path\>** beside their reason. The reason
  is saved as written: a RULE with a USER source, scoped to the finding's file,
  with no model.
- **Save lesson** and its lesson drafts are removed; these paths replace them.

### 3.3 Recipes

**Docs come first.** Guideline files reach every turn (section 4), so a recipe
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

- **The check starts from a directory.** The user picks a directory instead of a
  plan topic. The rest is unchanged: the check lists the pages the code links,
  decides which are specifications and reproduces gaps.
- **Spec memories.** Each specification a check used becomes, or updates, a SPEC
  memory with its address, the paths that link it and the sections read. A
  development task or review that read a spec page may REMEMBER a SPEC with a
  PAGE source.
- **Deviations.** A deviation kept on purpose is a RULE. It comes from a spec
  finding dismissed as "not a real gap" with Remember, or from a pull request
  thread.

### 3.5 Not stored

- **How code works,** call paths and module layout: CodeGraph and reading.
- **Why a line is the way it is:** looked up on demand from blame, commit and
  pull request, with `commit_prs` and `history` in reviews.
- **What the docs say:** it arrives as guidelines.

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

## 5. Delivery and receipts

**At turn start.** After the guidelines, a Memory block lists matching memories,
one line each: `[m12] <text> (PR #31201 @alice, 3 PRs)`.

- **Reviews:** memories scoped to a changed path, then repository-wide ones, then
  the user's.
- **Development tasks:** repository-wide and the user's. From the second turn on,
  also those scoped to paths the task has changed.
- **Limits and order:** at most 40 lines and 12 KiB. Path matches come first,
  longest prefix first, then by number of sources, newest first. A flagged memory
  is delivered with its flag.

This deliberately reverses the current rule against automatic note injection:
memories are short, and lookup alone reached one development task.

**The memory tool**, for development tasks and reviews, replaces
`repository_context_lookup` and the note kinds of `review_get_context`:

| Action | What it does |
|---|---|
| SEARCH | Literal text and path matches, paged |
| READ | A memory with its sources and quotes |
| REMEMBER | Saves a memory (section 3.2 and 3.3) |
| REPORT | Flags a memory as wrong, with a reason and a quote |

**Citations.** Review findings get an optional `memoryIds`. A development task's
final answer cites `[m12]` when a memory changed what it did. ByteQuay counts
citations from both.

**Receipts.**
- A finding shows "Memory used", folded, with each rule and its source.
- Transcripts show "Remembered" rows.
- The turn receipt lists the memories delivered.

**On and off.** The workspace switches for reviews and development stay and are
recorded per turn as today. Off delivers no memory and offers no tool;
guidelines stay on.

## 6. User interface

**Sidebar.** Memory replaces Repository context. Its badge counts flagged
memories.

**Memory page:**
- **Header:** chips with an icon and a count, the sentence on hover, for recipes,
  rules and specs, and for memories from you, from pull requests and from tasks.
- **Actions:** Learn from my PRs, showing how far the cursor is ("through #31201 ·
  12 newer"), and Check against spec, with a directory picker.
- **Needs you:** flagged memories with the reason.
- **List:**
  - **Grouping and filters:** grouped by kind, with source and path filters and a
    literal filter.
  - **Row:** the rule, scope chips, a source chip ("PR #31201 @alice · 3 PRs",
    "You", "Task", "Page") and the cited count, with no bold text.
  - **Drawer:** a row opens the memory drawer with its text, sources with quotes
    and links, the previous version, Edit, and Delete with an Undo toast.
- **Guidelines:** the files that reach agents, with size and an open link.
- **Specs:** spec memories with each one's latest check report, reusing the
  current report UI.

**Elsewhere:**

| Surface | Change |
|---|---|
| Pull request page | Learn from this PR, on merged or closed pull requests |
| Task and review transcripts | "Remembered: …" rows with Undo. The turn receipt folds "Memory: 6 delivered" and "Guidelines: 3 files" |
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
  page captures, the cited-lines source check, the spec check and its link scan.
- History reads (`ContextHistoryReader`, `SelectedCommitPullRequestReader`) for
  reviews.
- The `ContextLimits` and `ContextText` helpers that code outside the feature
  uses.

**Frontend:**
- `src/repository-context`, except the spec check parts.
- Home's context row, the task note cards and the development activity panel.
- The review context details, preparation activity and Save lesson.
- The 41 IPC channels shrink to the memory set.

**Docs:**
- Deleted: `repository-context.md`, `repository-context-refactor.md`,
  `repository-context-agent-discovery.md`, `repository-context-acceptance.md`,
  `repository-context-impl-tmp.md` and the `docs/evaluation/repository-context-*`
  captures.
- Rewritten: the context paragraphs in `architecture.md` and `agent-service.md`.

**Old local data** is deleted in the same step as the code. Nothing is
migrated, and the one saved lesson is not carried over. The deletion covers:
- every `workspace.repositoryContext*` settings key: notes, plans, discovery
  reports, runs, pages, disputes, merged candidates, web lookups and spec checks;
- `workspace.contextUsageTrackingSince.*`;
- the three tables above;
- the `agent_review_run.context_json` column;
- the renderer's saved view state for the old page.

One idempotent cleanup at startup performs the deletion, and the next phase
removes it once it has run. The per-turn `use_repository_context` flags stay as
the memory switch.

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

- **No leakage.** The run with memory excludes memories sourced from this pull
  request or from pull requests merged after that commit.
- **Comparison.** The result lays the maintainers' actual review comments next
  to each run's findings, file by file. It counts the comments each run raised
  on the same file within a few lines, and the user can correct a match.
- **Use.** Five to ten replays show whether memory makes reviews raise what
  maintainers raise.
- **Limits.** Replays start only on a click, use the review model, are labeled as
  replays and are never posted.

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

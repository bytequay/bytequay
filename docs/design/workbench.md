# WorkBench

WorkBench is the task's shared inspection and explanation surface. It groups
existing code, diff, summary, discussion, and review components; it is not a new
execution engine, task lifecycle, or worktree owner.

## Navigation

The task and pull-request side panels have two primary destinations: **PR** and
**WorkBench**. PR shows the overview with the existing
GitHub checks, publication, and merge actions. WorkBench contains **Summary**,
**Changes**, and **Code & Tests**. In a task panel, Discussions and Subagents
join PR and WorkBench as top-level tabs only once the conversation opens them:
a subagent chip, a discussion footer or passage mark, or a newly asked
discussion. While one shows, WorkBench's views are hidden. Each closes with its
×, which returns to the view it covered, and the conversation reopens it.
Existing styling, editors, cards, and the diff viewer are reused.
When a PR is linked, its refresh, review submission, and merge actions remain
available in the shared header throughout WorkBench.
Changes is the single diff destination. Its source picker selects the published
PR, the task working copy, or a recorded reviewer revision when available.
Opening Changes defaults to the published diff when a PR exists, so inline
review comments remain immediately available. Without a PR it opens local
changes. Explicit source choices and commit/finding navigation retain their
selected source.
The published source retains GitHub comment and review actions. Working-copy
commit views expose those same actions when the commit and its parent belong to
the published PR. A complete branch comparison also supports them when its
clean snapshot matches the published diff. Comments retain the displayed commit
and line anchors. Unpublished or changed local snapshots explain why GitHub
comments are unavailable and offer **View published PR**.

WorkBench is available before a PR exists. A task with an accessible managed
worktree can read Changes and Code & Tests regardless of its development or
review origin. Reads remain available during execution. Saving, running tests,
terminal access, cleanup, and publication retain their ownership and execution
guards. Switching views preserves open files and unsaved editor drafts.

## Code and changes

The task owns the default working copy. Code requests identify that task and
workspace, without requiring a PR identity. A review surface identifies its
reviewer's working copy explicitly; it never silently replaces the developer's
working copy when a linked review starts.

Changes offers the branch comparison, uncommitted changes, and selectable
commits. Uncommitted work includes staged, unstaged, and untracked files. The
comparison displays its base and head. Existing PR-based tasks use their PR
comparison base; a new development branch uses its recorded starting commit.
Selecting a commit displays its message, changed files, and first-parent diff
without checking out another revision. Historical review links retain the
reviewed base/head and never substitute current working files.
The source, scope/commit selector, revision, file-list toggle, and diff actions
share the compact header toolbar. Branch names and full commit messages live
in a closed-by-default details disclosure so the diff starts immediately below
navigation. The changed-file sidebar defaults open in zoom/fullscreen and
closed in the side panel; an explicit user toggle is retained for that mode.

## Summary and review

Summary replaces Review & Learning. Development retains its automatic change
intention and individual commit explanations after successful code-changing
work. These explanations and private questions do not start review, create
findings, or publish to GitHub. Summary identifies its source and revision.

**Review changes** explicitly starts the existing review service for the
development branch's committed snapshot, including unpushed commits. The
development task is the source; a PR is optional. The UI states that
uncommitted work is excluded. Review uses the configured Review provider and
model in a separate reviewer task, provider conversation, and managed worktree.
The developer keeps its original conversation and worktree. Review experiments
cannot alter the developer's unfinished files.

Local development snapshots and GitHub PR snapshots feed the same bounded
investigation, validation, reporting, and private discussion process. Existing
review-specific permissions, runtime ownership, reporting tools, and recovery
remain in force. Repeated starts reuse the source's open review; **Update
review** captures newer committed changes in the existing reviewer conversation.
Opening a view and ordinary refresh never start or update a review. New
development work leaves the prior assessment readable at its recorded revision.

For a remote PR review, observing a newer head automatically aligns a clean
reviewer worktree with that revision, including force-pushed history. If a review
or other task work is running, catch-up waits until the whole run and its
processes finish and the task is idle. It does not start another review or change
the existing report, findings, or outgoing comment anchors. **Update review**
remains the explicit action that assesses the new revision.

Saved edits, staged or untracked files, local commits, saved experiments, or an
open Git operation block automatic catch-up. Show that the branch is stale and
offer the existing **Update review** action without altering those files.
Automatic catch-up never stashes, discards, or resolves local changes. Unsaved
Code & Tests drafts remain in the editor across refreshes, including remote
file deletion; saving still checks their original content hash. Development
worktrees and reviews sourced from development tasks do not follow remote heads
through this mechanism.

Questions on findings and reviewer explanations go to the reviewer. Explicit
**Request fix** sends the selected finding, source revision, and user instruction
to the original development task. It does not convert a reviewer question into
a development edit. The existing development authorization and delivery choices
govern that accepted request. Review findings remain private until an explicit
publication action passes existing account, PR, and revision checks. A local
review without an associated PR has no GitHub publication action.

Questions on the developer's saved intention and commit explanations go to that
developer with local-only delivery. They do not require a PR or enter the
private experiment session. The clarification prompt requests explanation and
investigation using existing code and evidence, without code edits or publication.

## Shared implementation boundary

Review records retain the optional source development task and optional PR
identity. Source capture resolves a repository, comparison base, and exact head;
the existing review coordinator and task service own everything after capture.
The UI shares reports, commit explanations, findings, private editors, and
review status. Development delivery remains in its existing service. Hibernate
owns the schema; no compatibility layer or migration-history machinery is added.
Existing development databases with a non-null `agent_review.number` column
require the documented `./dev.sh --reset-data` reset before starting reviews
without a PR. That command deletes local application data; it is a deliberate
developer action, never performed automatically by this feature.

A separate optional worktree head records the last revision aligned by the
review service. It lets repeated catch-ups distinguish an earlier automatic
move from user-created local commits. Run heads remain immutable review facts.
This additive field uses Hibernate schema updates and requires no data reset.

Verification covers worktree access without PR creation, local commit and dirty
file inspection without checkout mutation, distinct reviewer ownership/model,
stable source revisions, reviewer questions, explicit development fix routing,
and reuse after additional commits.

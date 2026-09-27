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
Changes is the single diff destination. When a PR is linked, it always shows
that published PR, including its selectable published commits and existing
GitHub comment and review actions. There is no source picker. Local edits,
unpushed commits, and reviewed snapshots never replace the published diff.
Summary commit links select the matching published commit; an unpublished or
removed commit shows an explicit notice. Finding links navigate only when the
displayed revision matches their anchors; otherwise the finding stays readable
in Summary. Without a PR, Changes shows the task's local changes and commits.
Code & Tests continues to read the bound worktree independently of the PR.

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

Before a PR exists, Changes offers the branch comparison, uncommitted changes,
and selectable local commits. Uncommitted work includes staged, unstaged, and
untracked files. A development branch uses its recorded starting commit as the
comparison base. Local review links retain the reviewed base/head. Once a PR is
linked, Changes uses only its published comparison and commit history.
Selecting a commit displays its changed files without checking it out.

The file-list toggle and Collapse all start a dedicated toolbar row below the
Summary, Changes, and Code & Tests tabs, followed by the commit selector and
diff totals. Local-only comparisons also show folded revision details in this
row. The changed-file sidebar defaults open in zoom/fullscreen and closed in
the side panel; an explicit user toggle is retained for that mode.

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
open Git operation block automatic catch-up. Code & Tests marks a stale local
branch with an amber toolbar icon. Clicking it offers a confirmed **Discard and
reset** to fetch the latest PR head and discard local commits, tracked and
untracked changes, and unsaved editor drafts without starting review. This
explicit action requires an idle, open remote review and a verified working
copy with no open Git operation. Automatic catch-up never stashes, discards, or
resolves local changes. Unsaved Code & Tests drafts remain in the editor across
ordinary refreshes, including remote file deletion; saving still checks their
original content hash. Reset clears drafts only on success; cancellation and
failure preserve them. A current checkout has no stale icon even if Summary's
review results still cover an older head. Development
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

# Workflows

Feature workflows compose the existing application and agent services. They do
not create another general task engine. [Product design](product.md) describes
the surfaces; [security](security.md) defines their authority.

## Read, refresh, and review

Git supplies local commits and patches. SQLite supplies cached GitHub details,
enrichment, and refresh facts. Display uses local snapshots; explicit and
scheduled refresh paths fetch external state. Opening an uncached exact remote
item may fetch that item. Visible PR views may refresh stale state while keeping
the saved content readable.

Refreshes share in-flight work, honor rate limits, and preserve usable snapshots
on failure. Missing pages never justify treating a partial scan as complete.
Repository synchronization must not reset or discard working files. Metadata
freshness and availability of local code are independent.

The user inspects the change before commenting, approving, editing, or merging.
Private reviews and discussions remain separate from GitHub publication. A
published review uses the exact previewed body, destination, source revision,
and location; later agent changes cannot silently alter that accepted snapshot.

## Development and delivery

A development task starts at a verified repository revision in a separate
managed worktree. The user selects its provider and delivery choices when
submitting work. Tasks remain open for subsequent turns.

Accepted commit, draft-PR, and CI-fix choices authorize bounded follow-up work.
The application waits for explicit readiness and unresolved user questions,
verifies actual Git and GitHub state, coordinates existing CI services, and
promotes a draft only when complete supported check evidence is green.

Delivery uses one in-process coordinator. A backend restart fails an unfinished
continuation instead of replaying uncertain writes. Private reviewer experiments
have no publication authority.

## CI repair

CI Autofix is a user-authorized loop for the user's PR and its verified head.
It watches supported GitHub Actions checks, collects evidence, repairs in a
managed worktree, and verifies results before committing or publishing.

Authority has explicit time, repair, and rerun limits. Reruns require evidence
of a transient or flaky failure. Remote operations are recorded and checked
against the expected head. Unsupported or incomplete checks never count as
green; uncertainty pauses work. The service does not comment, approve, merge,
or perform an unrestricted force push. Minimal PR-description repairs stay
within the accepted workflow's scope.

## Cherry-pick and rebase

Cherry-pick preview fixes the ordered source range, target revision, proven
prior presence, and user-selected skips. A moved target invalidates that plan.
Confirmation authorizes local Git mutations; publication needs separate
authority. Git facts determine the result, not the agent's description.

Conflict handling intentionally preserves the source conflict snapshot and an
adjacent agent fixup commit. Only their combined final tree is verified; the
snapshot alone may contain conflict markers or fail to build. The source-marked
commit retains attribution while execution advances to the fixup. The range
flow does not automatically rebase or infer a repository's validation command.

Rebase is a distinct program-owned operation in the caller's worktree. It retains
the original tip, asks agents only to resolve and stage conflicts, and controls
continue, skip, and abort itself. Fetch and push remain the caller's actions.

## Related repositories and backports

Direct Fork and Dependency relationships provide bounded context without
transitive inheritance or silently changing repository routing. A Dependency
relationship does not authorize commit transfer.

Work in another repository starts a fresh conversation at an exact revision;
it does not copy parent conversation history or inherit publication authority.
Backport chains reuse cherry-pick, delivery, CI, and rebase services. They consume
each predecessor PR's proven landed commits and wait for its merge. They never
merge automatically, and ambiguous histories require explicit selection.

## Code, tests, and terminals

Code & Tests uses an existing task worktree; opening it never provisions one.
Published PR changes show the published revision, while this tab reads the bound
working copy. Edits require verified ownership, an idle writer, and unchanged
source content. Reviewer edits are restricted to tests; development editing
has a broader scope.

Test runs use a supported validation profile and validated selectors. The first
profile is the Maven wrapper. Runner results, rather than model prose or symbol
matches, provide test evidence. User-started terminals and tests execute host
code with the user's environment; they are not the agent sandbox.

## Jira and source-backed work

Jira account and site identity bind ticket access. Direct API-token connections
and optional hosted OAuth reuse the same application services and local cache;
the hosted OAuth component owns its secrets and rotating grants separately
from the desktop app. Source refresh respects the existing quiet-hours policy.

Issue and Jira context attaches to the existing development flow. Reading a
planning view does not run a model or change source state. Accepted outcome,
task, and source associations stay consistent. Current Jira transitions require
direct user actions; automatic Done transitions remain deferred.

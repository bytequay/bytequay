/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.bytequay.app.service.localpr;

import com.bytequay.app.domain.HandledAction;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PR.PRSyncSnapshot;
import com.bytequay.app.domain.PRCheck;
import com.bytequay.app.domain.PRComment;
import com.bytequay.app.domain.PRCommit;
import com.bytequay.app.domain.PRDashboardEntry;
import com.bytequay.app.domain.PRTimelineEntry;
import com.bytequay.app.domain.PRTriageState;
import com.bytequay.app.domain.PullRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The local-PR state machine + child-row writers. Pure state — no git / GitHub
 * I/O (the push and merge transitions are driven by their user-gated callers,
 * which perform the I/O and then call {@link #transition} to record the flip).
 * Every status flip goes through {@link #transition}, which validates the edge
 * against {@link PR#ALLOWED_TRANSITIONS} and writes a {@code status}
 * timeline event so the timeline tells the whole story (design #45, non-negotiable
 * "PR status flips are timeline events").
 */
public interface PRService
{
    // ── reads ────────────────────────────────────────────────────────────
    Optional<PR> findByTask(String taskId);

    Optional<PR> findById(String prId);

    /** The external PR already synced in for this (repo, remote PR number). */
    Optional<PR> findByRepoAndNumber(String repo, int remotePrNumber);

    /** The task-origin PR that has been pushed to this (repo, remote PR
     *  number), if any — a task's own PR once it's opened on GitHub. The
     *  dashboard sync consults this so it reuses the task row instead of
     *  minting a separate external twin for the same GitHub PR. */
    Optional<PR> findTaskByRepoAndNumber(String repo, int remotePrNumber);

    /**
     * Fold the dashboard-synced external twin for this task PR's (repo,
     * number) into the task row and delete it, so one GitHub PR maps to one
     * aggregate row. No-op when the PR has no remote identity or no twin
     * exists. See docs/mockups/pr-record-unification-design.md.
     */
    PR foldExternalTwinIntoTask(String taskPrId);

    List<PRCommit> commits(String prId);

    List<PRTimelineEntry> timeline(String prId);

    List<PRCheck> checks(String prId);

    List<PRComment> comments(String prId);

    // ── writes ───────────────────────────────────────────────────────────
    /** Create the task's local PR at {@code local-drafted}, or return the
     *  existing one — idempotent, so Dev can call it on every early commit. */
    PR createForTask(String taskId, String branchName, String baseBranch, String title, String description);

    /** Create an external PR discovered via the dashboard sync, or return the
     *  existing one for this (repo, remote PR number) — idempotent, so a
     *  repeat resolver call never duplicates the row. */
    PR createExternal(
            String repo, int remotePrNumber, String remotePrUrl, String author,
            String branchName, String baseBranch, String title, String description,
            String status, Instant createdAt, Instant mergedAt, Instant closedAt);

    /** Stamp a successful GitHub sync (the sync chip's "synced Xs ago"). */
    PR markSynced(String prId, Instant when);

    // ── dashboard ────────────────────────────────────────────────────────
    /** Every PR the dashboard sync currently watches, paired with its
     *  triage state. */
    List<PRDashboardEntry> dashboardEntries();

    /** A single PR's triage state — empty (never touched) if it has none. */
    PRTriageState triage(String prId);

    /** Flip the dashboard watch flag ({@code syncList}'s "why is this PR on
     *  my dashboard" tag) — {@code null} un-watches it. */
    PR setWatchReason(String prId, PullRequest.Origin watchReason);

    /** Overwrite the dashboard sync-derived fields ({@code syncList}'s list
     *  and detail passes both call this). */
    PR updateSyncSnapshot(String prId, PRSyncSnapshot snapshot);

    /** Records that the user opened this PR — idempotent (only the first
     *  view sticks). */
    void markViewed(String prId);

    /** Marks a PR handled with the given action, without any GitHub call —
     *  the dashboard's hover "Handled" affordance. */
    void markHandled(String prId, HandledAction action);

    /** Clears the local reviewed timestamp so the PR returns to the Inbox. */
    void reopen(String prId);

    /** Park a PR until {@code until} (must be in the future). */
    void snooze(String prId, Instant until);

    /** User-initiated wake — no wake-reason banner. */
    void unsnooze(String prId);

    /** Auto-wake: unsnooze and stamp why, so the UI can show the banner. */
    void autoWake(String prId, String wakeReason);

    /** Drop the wake-reason flag once the user has seen the "PR woke up" banner. */
    void clearSnoozeWakeReason(String prId);

    /** Edit title / description (a null argument leaves that field unchanged). */
    PR updateDetails(String prId, String title, String description);

    /** Correct the head/base branch names once a detail fetch resolves the
     *  real ones (a null argument leaves that field unchanged). */
    PR updateBranches(String prId, String branchName, String baseBranch);

    /** Backfill the GitHub login that owns the remote PR once known. */
    PR updateAuthor(String prId, String author);

    /** Append a commit + a {@code commit} timeline event. */
    PRCommit recordCommit(
            String prId, String sha, String message, int additions, int deletions, String actor);

    /** Append a check; writes a {@code ci} timeline event once the check
     *  reaches a terminal status (passed / failed / neutral). */
    PRCheck recordCheck(String prId, String kind, String name, String status, Long durationMs);

    /** Append a commit synced from GitHub for an external-origin PR, using
     *  its real authored timestamp instead of "now" (unlike {@link
     *  #recordCommit}, which assumes a freshly-made local commit). GitHub's
     *  PR-commits list endpoint has no per-commit diff stats, so this always
     *  records zero additions/deletions — the header sums the PR-level
     *  total from {@link PRSyncSnapshot} instead. */
    PRCommit recordSyncedCommit(String prId, String sha, String message, Instant authoredAt, String actor);

    /** Upsert a remote check run synced from GitHub for an external-origin
     *  PR, deduped by GitHub's check-run id — unlike {@link #recordCheck},
     *  which always appends a fresh row for a local test run with no
     *  external id to dedupe against. Writes a {@code ci} timeline event
     *  only the first time this run reaches a terminal status. */
    PRCheck recordSyncedCheck(
            String prId, String runId, String name, String status, Instant startedAt, Instant finishedAt);

    /** Prunes remote check rows that are absent from GitHub's latest snapshot. */
    void retainSyncedChecks(String prId, Set<String> runIds);

    /** Flip {@code local-drafted → local-open} (dev auto-declares "ready"). */
    PR requestUserReview(String prId, String actor);

    /** Records one brain adversarial-review pass as a `review` timeline event
     *  (author=brain, local-only — plan-rail-runs.md R24). A no-op when the
     *  task has no local PR yet (the plan self-review, R20, predates it — its
     *  event is backfilled onto the timeline once {@link #createForTask}
     *  first creates the row). */
    void recordBrainReview(
            String taskId, String scope, String verdict, int iteration, String roundId, String body);

    /** Records the system-owned start of one adversarial code-review pass.
     *  Unlike the verdict tool, this does not depend on the agent remembering
     *  to call anything, so an interrupted review still leaves an honest
     *  timeline trail. */
    void recordBrainReviewStarted(String taskId, String scope, int iteration, String roundId);

    /** Records that the dev agent has begun addressing the findings from one
     *  adversarial-review pass. */
    void recordBrainReviewAddressing(String taskId, String scope, int iteration, String roundId);

    /** Append an auditable publish-gate decision to the PR timeline. */
    void recordGateApproval(String prId, String gate, String reason);

    /** Records the user's plan approval as a {@code plan-finalized} timeline
     *  event carrying {@code planStageId} (so the row can link back to the
     *  Plan node). A no-op when the task has no local PR yet — the usual
     *  case, since approval precedes dev's first commit; that event is
     *  backfilled onto the timeline once {@link #createForTask} first
     *  creates the row, same as {@link #recordBrainReview}'s plan scope. */
    void recordPlanApproved(String taskId, String planStageId);

    /**
     * Validated status flip. Throws {@link IllegalArgumentException} on an
     * unknown PR or an illegal edge. Writes the {@code status} timeline event.
     * The push / merge callers pass the remote identity separately via
     * {@link #recordPushed} before flipping.
     */
    PR transition(String prId, String newStatus, String actor);

    /** Record the remote PR identity assigned on push (before the status flip). */
    PR recordPushed(String prId, String repo, int remotePrNumber, String remotePrUrl);

    /**
     * Complete a push: strip every not-yet-stripped local-only timeline event
     * and local-origin comment (they never migrate to GitHub — design #47),
     * record the remote PR identity, and flip {@code local-open → remote-drafted}
     * (writing the status timeline event). Called by the push orchestrator
     * after the git push + draft-PR create succeed.
     */
    PR recordPush(String prId, String repo, int remotePrNumber, String remotePrUrl);

    /** Flip {@code remote-open → merged} after a user-gated GitHub merge. */
    PR recordMerged(String prId);

    /** Stamp {@code branchDeletedAt} after a user-gated GitHub branch
     *  deletion — hides the merge-box's "Delete branch" affordance. */
    PR recordBranchDeleted(String prId);

    /** How many local-only events + local comments a push would strip — the
     *  count the push dialog surfaces before the user confirms. */
    int pendingStripCount(String prId);

    /** Add a comment (PR-level or inline; local or remote origin). {@code side}
     *  is {@code LEFT}/{@code RIGHT} (defaults to RIGHT); {@code startLine}/
     *  {@code startSide} are non-null only for a multi-line range. */
    PRComment addComment(
            String prId,
            String origin,
            String scope,
            String filePath,
            Integer lineNumber,
            String side,
            Integer startLine,
            String startSide,
            String author,
            String body,
            String parentCommentId);

    /** Delete an unpublished local draft comment. */
    void deleteDraftComment(String commentId);

    /** Resolve a comment (marks {@code resolvedAt}) — the agent addressed it. */
    PRComment resolveComment(String commentId);

    /** Reopen a previously resolved/dismissed comment thread. */
    PRComment reopenComment(String commentId);

    /** Dismiss a comment (marks {@code dismissedAt}) — closed without action,
     *  the other terminal state alongside {@code resolveComment}. */
    PRComment dismissComment(String commentId);

    /** Mark a draft comment published — {@code publish-review} batched it into
     *  one GitHub review (external PRs only; task-origin drafts are stripped
     *  on push instead and never reach this state). */
    PRComment markPublished(String commentId, Instant when);

    /** Link an ordinary local draft to its persisted investigation finding. */
    PRComment attachFinding(String commentId, String findingId);

    /** Edit a pending local draft without changing its anchor or provenance. */
    PRComment editCommentBody(String commentId, String body);

    /** Advance the local-addressing marker to {@code through} — comments
     *  created at or before this instant are considered already accounted
     *  for by the addressing loop (see {@link PR#withLocalAddressedThrough}). */
    PR markLocalAddressed(String prId, Instant through);

    // ── remote-timeline sync ─────────────────────────────────────────────
    /** Whether a remote comment/review with this GitHub id has already been
     *  mirrored onto the timeline — lets {@code PRSyncService} stay
     *  idempotent across repeated PR-bundle fetches. */
    boolean hasRemoteEvent(String prId, long remoteEventId);

    /** Mirror a remote GitHub PR-level (issue) comment: a comment row
     *  ({@code origin=remote}) plus its {@code comment} timeline event,
     *  tagged with the comment's GitHub id for {@link #hasRemoteEvent}. */
    PRComment addRemoteComment(String prId, String author, String body, Instant createdAt, long remoteCommentId);

    /** Mirror a remote GitHub PR review (approved / changes requested /
     *  commented), plus its written summary if any, as a {@code review}
     *  timeline event, tagged with the review's GitHub id for
     *  {@link #hasRemoteEvent}. */
    void recordRemoteReview(
            String prId, String reviewer, String verdict, String body, Instant when, long remoteReviewId);

    /** Append a local investigation-review event to the unified PR timeline. */
    void recordReviewEvent(String prId, String actor, String payloadJson);
}

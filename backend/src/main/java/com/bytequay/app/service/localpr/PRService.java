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

    /** Append a commit + a {@code commit} timeline event. */
    PRCommit recordCommit(
            String prId, String sha, String message, int additions, int deletions, String actor);

    /** Append a check; writes a {@code ci} timeline event once the check
     *  reaches a terminal status (passed / failed / neutral). */
    PRCheck recordCheck(String prId, String kind, String name, String status, Long durationMs);

    /** Flip {@code local-drafted → local-open} (dev auto-declares "ready"). */
    PR requestUserReview(String prId, String actor);

    /** Records one brain adversarial-review pass as a `review` timeline event
     *  (author=brain, local-only — plan-rail-runs.md R24). A no-op when the
     *  task has no local PR yet (the plan self-review, R20, predates it — its
     *  event is backfilled onto the timeline once {@link #createForTask}
     *  first creates the row). */
    void recordBrainReview(String taskId, String scope, String verdict, int iteration);

    /**
     * Validated status flip. Throws {@link IllegalArgumentException} on an
     * unknown PR or an illegal edge. Writes the {@code status} timeline event.
     * The push / merge callers pass the remote identity separately via
     * {@link #recordPushed} before flipping.
     */
    PR transition(String prId, String newStatus, String actor);

    /** Record the remote PR identity assigned on push (before the status flip). */
    PR recordPushed(String prId, int remotePrNumber, String remotePrUrl);

    /**
     * Complete a push: strip every not-yet-stripped local-only timeline event
     * and local-origin comment (they never migrate to GitHub — design #47),
     * record the remote PR identity, and flip {@code local-open → remote-drafted}
     * (writing the status timeline event). Called by the push orchestrator
     * after the git push + draft-PR create succeed.
     */
    PR recordPush(String prId, int remotePrNumber, String remotePrUrl);

    /** Flip {@code remote-open → merged} after a user-gated GitHub merge. */
    PR recordMerged(String prId);

    /** How many local-only events + local comments a push would strip — the
     *  count the push dialog surfaces before the user confirms. */
    int pendingStripCount(String prId);

    /** Add a comment (PR-level or inline; local or remote origin). */
    PRComment addComment(
            String prId,
            String origin,
            String scope,
            String filePath,
            Integer lineNumber,
            String author,
            String body,
            String parentCommentId);

    /** Resolve a comment (marks {@code resolvedAt}) — the agent addressed it. */
    PRComment resolveComment(String commentId);

    /** Dismiss a comment (marks {@code dismissedAt}) — closed without action,
     *  the other terminal state alongside {@code resolveComment}. */
    PRComment dismissComment(String commentId);

    /** Mark a draft comment published — {@code publish-review} batched it into
     *  one GitHub review (external PRs only; task-origin drafts are stripped
     *  on push instead and never reach this state). */
    PRComment markPublished(String commentId, Instant when);

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
}

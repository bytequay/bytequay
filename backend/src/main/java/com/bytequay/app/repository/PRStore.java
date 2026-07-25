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
package com.bytequay.app.repository;

import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRCheck;
import com.bytequay.app.domain.PRComment;
import com.bytequay.app.domain.PRCommit;
import com.bytequay.app.domain.PRDashboardEntry;
import com.bytequay.app.domain.PRTimelineEntry;
import com.bytequay.app.domain.PRTriageState;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Persistence boundary for the {@link PR} aggregate — the PR row plus
 * its commits, timeline events, checks, and comments. One task has at most
 * one local PR ({@link #findByTaskId}).
 */
public interface PRStore
{
    // ── pr ─────────────────────────────────────────────────────────
    /** Insert or update the PR row; returns the persisted value. */
    PR save(PR pr);

    Optional<PR> findById(String id);

    /** Atomically bump the task-local review epoch and return the new
     *  value. Entity-managed — {@link #save} never resets it. Every
     *  submitted local review advances it; validation claims bound to
     *  an older epoch are superseded. */
    default long incrementLocalReviewEpoch(String prId)
    {
        throw new UnsupportedOperationException("incrementLocalReviewEpoch");
    }

    default long localReviewEpoch(String prId)
    {
        throw new UnsupportedOperationException("localReviewEpoch");
    }

    Optional<PR> findByTaskId(String taskId);

    /** The external PR already synced in for this (repo, remote PR number),
     *  if any — the dashboard/details-page resolver's idempotency check. */
    Optional<PR> findByRepoAndRemotePrNumber(String repo, int remotePrNumber);

    /** The task-origin PR pushed to this (repo, remote PR number), if any —
     *  lets the sync reuse a task's own PR row instead of minting a twin. */
    Optional<PR> findTaskByRepoAndRemotePrNumber(String repo, int remotePrNumber);

    /** True while an AgentReview is starting or a round is still writing against this PR id. */
    boolean hasRunningAgentReview(String prId);

    /**
     * Fold one PR aggregate into another: move {@code fromPrId}'s child rows
     * (commits, checks, comments, AgentReview history, non-redundant remote and
     * review timeline events, triage) onto {@code toPrId}, dropping rows that
     * would duplicate one the survivor already has. The caller deletes the
     * emptied {@code fromPrId} row afterwards ({@link #deletePr}); its remaining
     * redundant children cascade away.
     */
    void reparentChildren(String fromPrId, String toPrId);

    /** Delete a PR row; its child rows cascade (FK {@code ON DELETE CASCADE}). */
    void deletePr(String prId);

    /** Pushed task rows that carry a remote URL + number but a null {@code
     *  repo} — legacy "half-pushed" rows the reconcile sweep repairs by
     *  parsing the repo out of the URL. */
    List<PR> findPushedTaskPrsMissingRepo();

    /** Ids of task rows that share a (repo, remote PR number) with a separate
     *  external row — the duplicate pairs the reconcile sweep folds. */
    List<String> findTaskPrIdsWithExternalTwin();

    /** Set a PR row's {@code repo} column (half-pushed-row repair). */
    void setRepo(String prId, String repo);

    // ── dashboard ──────────────────────────────────────────────────
    /** Every PR currently watched by the dashboard sync (non-null {@code
     *  watch_reason}), each paired with its triage state (empty if the
     *  user has never touched it). */
    List<PRDashboardEntry> findDashboardEntries();

    Optional<PRTriageState> findTriage(String prId);

    /** Insert or update the triage row; returns the persisted value. */
    PRTriageState saveTriage(PRTriageState triage);

    // ── pr_commit ──────────────────────────────────────────────────
    PRCommit addCommit(PRCommit commit);

    List<PRCommit> commitsFor(String prId);

    // ── pr_timeline_event ──────────────────────────────────────────
    PRTimelineEntry addEvent(PRTimelineEntry event);

    List<PRTimelineEntry> timelineFor(String prId);

    /** Local-only events not yet stripped — the push transition stamps these. */
    List<PRTimelineEntry> unstrippedLocalOnlyEvents(String prId);

    /** Whether a remote-synced event with this GitHub id has already been
     *  mirrored onto the timeline — keeps a repeated remote-timeline sync
     *  idempotent. */
    boolean timelineEventExistsByRemoteId(String prId, long remoteEventId);

    // ── pr_check ───────────────────────────────────────────────────
    PRCheck addCheck(PRCheck check);

    List<PRCheck> checksFor(String prId);

    /** Keep only the listed external runs for one check kind. Used after a
     *  GitHub snapshot sync so reruns and checks from an older head do not
     *  accumulate in the current PR view. */
    void retainChecks(String prId, String kind, Set<String> runIds);

    // ── pr_comment ─────────────────────────────────────────────────
    /** Insert or update a comment (add, resolve, edit, or stamp stripped). */
    PRComment saveComment(PRComment comment);

    /** Delete a draft comment and any direct replies. */
    void deleteComment(String id);

    Optional<PRComment> findCommentById(String id);

    List<PRComment> commentsFor(String prId);

    /** Local-origin comments not yet stripped — the push transition stamps these. */
    List<PRComment> unstrippedLocalComments(String prId);
}

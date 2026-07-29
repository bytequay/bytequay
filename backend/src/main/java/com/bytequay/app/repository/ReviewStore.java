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

import com.bytequay.app.domain.ReviewFinding;
import com.bytequay.app.domain.ReviewMessage;
import com.bytequay.app.domain.ReviewParticipant;
import com.bytequay.app.domain.ReviewPass;
import com.bytequay.app.domain.ReviewPassHostKind;
import com.bytequay.app.domain.ReviewPassKind;
import com.bytequay.app.domain.ReviewPhase;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary for the review-panel surface. One store
 * covers all four tables (passes / participants / messages /
 * findings) because they share a transactional lifecycle — a pass is
 * created with its participants in one shot, messages stream into
 * the pass, findings come out of consensus extraction. Mirrors the
 * shape of {@link WorkspaceStore} which similarly fronts a related
 * pair of tables.
 */
public interface ReviewStore
{
    // ── passes ────────────────────────────────────────────────────────

    void savePass(ReviewPass pass);

    /** Stamp a pass's host (THREAD vs TASK_PHASE) + kind. Written once at
     *  creation, outside {@code savePass}, so a later full-row save can't
     *  clobber it. */
    void setPassHost(String passId, ReviewPassHostKind hostKind, String hostId, ReviewPassKind kind);

    /** Link a pass to the REVIEW_STAGE row it was spawned for. Written once
     *  at creation, outside {@code savePass}, so a later full-row save can't
     *  clobber it — same discipline as {@code setPassHost}. */
    void setPassTaskStage(String passId, String taskStageId);

    /** Freeze the remote PR coordinates read during seating. Publication is
     *  later authorized from these database facts and never re-discovers its
     *  subject inside the user command transaction. */
    default void setPassRemoteSubject(
            String passId,
            String baseRepositoryId,
            String headRepositoryId,
            String headRef)
    {
    }

    Optional<ReviewPass> findPassById(String id);

    /** All passes for a thread, oldest first. A {@code flow='review'}
     *  thread typically owns one active pass plus a small tail of
     *  historical re-runs. */
    List<ReviewPass> listPassesByThread(String threadId);

    /** Cross-thread lookup by PR — useful for "this PR already has N
     *  reviews" surfaces on the PR detail page. Newest first. */
    List<ReviewPass> listPassesForPr(String repoFullName, int prNumber);

    /** Durable backstop for task-hosted review stages whose terminal event
     * was committed but whose after-commit close callback was lost. */
    default List<ReviewPass> listTaskStagePassesByPhases(List<ReviewPhase> phases)
    {
        return List.of();
    }

    /** The active (non-PUBLISHED) THREAD-hosted review pass for a PR, if
     *  one is open — drives the dashboard / PR-detail "review in progress"
     *  affordance. Empty default for test stores. */
    default Optional<ReviewPass> findActivePrReview(String repoFullName, int prNumber)
    {
        return Optional.empty();
    }

    /** Total review spend (milli-USD) across all passes created at or
     *  after {@code since} — the scheduler's rolling daily cost cap. */
    long sumPassCostSince(Instant since);

    void deletePass(String id);

    // ── participants ─────────────────────────────────────────────────

    void saveParticipant(ReviewParticipant participant);

    Optional<ReviewParticipant> findParticipantById(String id);

    List<ReviewParticipant> listParticipantsForPass(String reviewPassId);

    // ── messages ─────────────────────────────────────────────────────

    void saveMessage(ReviewMessage message);

    Optional<ReviewMessage> findMessageById(String id);

    /** All messages on one pass, oldest first. Drives the transcript
     *  view; the moderator also re-reads this to assemble the
     *  referenced-by-id context for downstream model calls. */
    List<ReviewMessage> listMessagesForPass(String reviewPassId);

    // ── findings ─────────────────────────────────────────────────────

    void saveFinding(ReviewFinding finding);

    Optional<ReviewFinding> findFindingById(String id);

    List<ReviewFinding> listFindingsForPass(String reviewPassId);
}

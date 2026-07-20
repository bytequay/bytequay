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

import com.bytequay.app.domain.AiReviewDraft;
import com.bytequay.app.domain.ReviewOutput;

import java.util.List;
import java.util.Optional;

public interface AiReviewDraftStore
{
    /** Inserts a new draft + its comments atomically, returning the domain view.
     *  {@code repo} and {@code number} are persisted on the row so the publish
     *  path doesn't need a second lookup against pull_requests. */
    AiReviewDraft save(long prId, String repo, int number, String headSha, ReviewOutput output);

    /** Stores a quick review against the unified UUID-style PR aggregate. */
    AiReviewDraft saveForUnifiedPr(String prId, String repo, int number, String headSha, ReviewOutput output);

    /** Returns the most recent draft for a PR, or empty if none. */
    Optional<AiReviewDraft> latestForPr(long prId);

    /** Most recent quick-review draft for the unified PR, if any. */
    Optional<AiReviewDraft> latestForUnifiedPr(String prId);

    /** Moves quick-review drafts when an external PR is folded into its task-owned survivor. */
    void reparentUnifiedPr(String fromPrId, String toPrId);

    /** All drafts for a PR, newest first. */
    List<AiReviewDraft> historyForPr(long prId);

    /** Lookup by primary key. */
    Optional<AiReviewDraft> byId(long draftId);

    /** Flips a draft's status to PUBLISHED after a successful GitHub publish. */
    AiReviewDraft markPublished(long draftId);

    /**
     * Replaces a single comment's edited_body. Pass null to clear the edit
     * and revert to the AI's original. Returns the parent draft (refreshed)
     * so callers can re-render without a separate fetch.
     */
    AiReviewDraft updateCommentBody(long draftId, long commentId, String editedBody);

    /** Deletes a single comment from a draft. No-op if not found. */
    AiReviewDraft deleteComment(long draftId, long commentId);

    /**
     * Toggles the dismissed flag on a single comment. Dismissed comments
     * are skipped on publish but stay on the row so the user can restore.
     */
    AiReviewDraft setCommentDismissed(long draftId, long commentId, boolean dismissed);

    /** Deletes a specific draft (and its comments via FK cascade). */
    void delete(long draftId);

    /**
     * Returns the active (non-PUBLISHED) draft for a PR, creating an empty
     * one if none exists. Used by the unified-stage flow so human-staged
     * comments and AI runs both accumulate into the same draft.
     */
    AiReviewDraft findOrCreateActive(long prId, String repo, int number, String headSha);

    /**
     * Appends a single human-authored comment to a draft. Body, side, and
     * line are required; startLine/startSide carry the multi-line range
     * when present. Returns the refreshed draft.
     */
    AiReviewDraft stageHumanComment(
            long draftId,
            String filePath,
            int lineNumber,
            String side,
            Integer startLine,
            String startSide,
            String body);
}

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
package com.bytequay.app.domain;

import java.time.Instant;

/**
 * Structured artifact of a review pass — the line the publish step
 * actually turns into a PR comment. A finding flows
 * {@code AGREED|DISPUTED} → ({@code RESOLVED|ARBITRATED|DROPPED}) →
 * {@code POSTED} once the human confirms a verdict.
 *
 * @param path             file the finding anchors to; null for
 *                         whole-PR notes.
 * @param line             line number in the file; null for whole-
 *                         file findings or whole-PR notes.
 * @param resolution       how a disputed finding was settled (Phase
 *                         3+); null until the finding leaves
 *                         {@link ReviewFindingStatus#DISPUTED}.
 * @param postedCommentId  GitHub comment id once the publish step
 *                         lands the finding on the PR.
 * @param debateStatus     outcome of the bounded DEBATE phase for a
 *                         DISPUTED finding: null / "not_eligible",
 *                         "converged" (panel reaffirmed → AGREED), or
 *                         "stalled_rounds" / "stalled_cost".
 * @param debateRounds     round-robin rounds the finding's debate ran.
 */
public record ReviewFinding(
        String id,
        String reviewPassId,
        String path,
        Integer line,
        ReviewFindingSeverity severity,
        ReviewFindingStatus status,
        String body,
        String resolution,
        String postedCommentId,
        Instant createdAt,
        String debateStatus,
        int debateRounds)
{
    /** New finding straight out of consensus — not yet debated, so the
     *  debate fields default. Keeps the existing call sites unchanged
     *  while the canonical constructor grows the debate columns. */
    public ReviewFinding(
            String id,
            String reviewPassId,
            String path,
            Integer line,
            ReviewFindingSeverity severity,
            ReviewFindingStatus status,
            String body,
            String resolution,
            String postedCommentId,
            Instant createdAt)
    {
        this(id, reviewPassId, path, line, severity, status, body, resolution,
                postedCommentId, createdAt, null, 0);
    }
}

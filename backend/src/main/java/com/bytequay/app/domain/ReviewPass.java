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
 * One run of the review flow over a referenced PR. A
 * {@code flow='review'} thread owns at most one active pass at a
 * time; a new pass starts when the human re-triggers (e.g. after the
 * PR head moves) or schedules a fresh review.
 *
 * <p>Phase 1 ships a single-reviewer pass — kickoff fetches the diff,
 * INDEPENDENT runs the one reviewer through the existing logic-loop
 * pathway, the pass terminates straight to AWAITING_REVIEW with the
 * findings list ready for the publish gate. Cross-review / consensus
 * / debate / arbitrate fields exist on the record so later phases
 * don't need a schema change.
 *
 * @param headSha   commit reviewed; null while kickoff fetch is in
 *                  flight. Lets a later run detect "the PR moved
 *                  since this review".
 * @param verdict   suggested verdict for the publish step; null
 *                  while the panel is still deciding.
 */
public record ReviewPass(
        String id,
        String threadId,
        String repoFullName,
        int prNumber,
        String headSha,
        ReviewPhase phase,
        int round,
        int roundCap,
        long costCapMilli,
        long costUsdMilli,
        ReviewVerdict verdict,
        Instant createdAt,
        Instant endedAt)
{
}

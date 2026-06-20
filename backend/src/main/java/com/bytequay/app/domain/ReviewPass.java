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
 * @param spawnedBuildThreadId  the build thread this pass spawned to
 *                  apply its AGREED findings ("→ Spawn build thread"),
 *                  or null. Set once, after the pass is TERMINATE.
 * @param agendaJson  the Lead's agenda as a JSON array of
 *                  {@code {id, title, status}} objects (see
 *                  {@link AgendaPhase}); null until the Lead's
 *                  {@code set_agenda} call, and forever on passes
 *                  that predate the Lead orchestrator.
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
        Instant endedAt,
        String spawnedBuildThreadId,
        String agendaJson,
        /** What hosts this pass (V108). Defaulted to THREAD by the
         *  convenience constructors; the row mapper threads the persisted
         *  value, and the host is written once via {@code setPassHost} —
         *  {@code savePass} never maps it, so a full-row update can't
         *  clobber it. */
        ReviewPassHostKind hostKind,
        /** The review thread id (THREAD) or the task id (TASK_PHASE). */
        String hostId,
        /** FRESH first review vs RE_REVIEW (Loop D). */
        ReviewPassKind kind,
        /** The REVIEW_STAGE task_stage row this pass was spawned for, when
         *  hosted from an internal-review context (V123); null otherwise.
         *  Written once via {@code setPassTaskStage}, never by {@code savePass}
         *  — same discipline as the host fields. */
        String taskStageId)
{
    /** Convenience constructor for the pre-host (V108) 15-field shape:
     *  defaults to a THREAD-hosted FRESH pass with {@code hostId =
     *  threadId}, which is correct for every standalone-review call site
     *  and every existing row. TASK_PHASE hosting is stamped via {@code
     *  setPassHost} at creation. */
    public ReviewPass(
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
            Instant endedAt,
            String spawnedBuildThreadId,
            String agendaJson)
    {
        this(id, threadId, repoFullName, prNumber, headSha, phase, round, roundCap,
                costCapMilli, costUsdMilli, verdict, createdAt, endedAt,
                spawnedBuildThreadId, agendaJson,
                ReviewPassHostKind.THREAD, threadId, ReviewPassKind.FRESH, null);
    }

    /** Pass with no spawned build thread yet — every site that builds
     *  or rebuilds a pass during its run uses this; only the spawn
     *  handoff (after TERMINATE) sets the link. */
    public ReviewPass(
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
        this(id, threadId, repoFullName, prNumber, headSha, phase, round, roundCap,
                costCapMilli, costUsdMilli, verdict, createdAt, endedAt, null, null);
    }

    /** Pass with no agenda yet — pre-Lead call sites. */
    public ReviewPass(
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
            Instant endedAt,
            String spawnedBuildThreadId)
    {
        this(id, threadId, repoFullName, prNumber, headSha, phase, round, roundCap,
                costCapMilli, costUsdMilli, verdict, createdAt, endedAt,
                spawnedBuildThreadId, null);
    }

    /** Copy with a different agenda payload — preserves host + stage link. */
    public ReviewPass withAgendaJson(String newAgendaJson)
    {
        return new ReviewPass(id, threadId, repoFullName, prNumber, headSha, phase,
                round, roundCap, costCapMilli, costUsdMilli, verdict, createdAt,
                endedAt, spawnedBuildThreadId, newAgendaJson, hostKind, hostId, kind, taskStageId);
    }
}

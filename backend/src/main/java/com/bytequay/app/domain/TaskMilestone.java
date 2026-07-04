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

import java.util.List;

/**
 * Coarse lifecycle <em>milestone</em> over {@link TaskPhase} — the six
 * buckets the collapsed flow stepper shows, plus the off-stepper specials
 * ({@link #PLAN} and {@link #QUEUED} before the sequence starts,
 * {@link #PARKED} for a NEEDS_ATTENTION escape).
 *
 * <p>Several phases collapse into the same milestone (e.g. IMPLEMENTING and
 * ADDRESSING_COMMENTS both live under {@link #IMPLEMENT}), which is what
 * makes a loop show up as a single bucket carrying a {@code ×N} visit badge
 * in the collapsed view.
 *
 * <p>Distinct from {@link TaskPhaseGroup}, which answers "who is waiting"
 * (the trunk card's four buckets); this answers "what lifecycle stage".
 */
public enum TaskMilestone
{
    PLAN("Plan"),
    QUEUED("Queued"),
    IMPLEMENT("Implement"),
    VALIDATE("Validate"),
    REVIEW("Review"),
    PUSH("Push"),
    WAIT_ON_PR("Wait on PR"),
    MERGE("Merge"),
    PARKED("Parked");

    /** The six canonical buckets shown in the collapsed milestone view,
     *  in fixed left-to-right order. {@link #QUEUED} (pre-sequence) and
     *  {@link #PARKED} (a NEEDS_ATTENTION escape) are not canonical
     *  buckets — they never appear in the 6-bucket overview. */
    public static final List<TaskMilestone> CANONICAL =
            List.of(IMPLEMENT, VALIDATE, REVIEW, PUSH, WAIT_ON_PR, MERGE);

    private final String label;

    TaskMilestone(String label)
    {
        this.label = label;
    }

    public String label()
    {
        return label;
    }

    public static TaskMilestone of(TaskPhase phase)
    {
        return switch (phase) {
            case PLANNING -> PLAN;
            case QUEUED -> QUEUED;
            case IMPLEMENTING, ADDRESSING_COMMENTS -> IMPLEMENT;
            case VALIDATING -> VALIDATE;
            case INTERNAL_REVIEW, AGENT_RE_REVIEW -> REVIEW;
            case AWAITING_PUSH, AWAITING_UPDATE_PUSH, ADDRESSING_LOCAL_COMMENTS -> PUSH;
            case PUSHED_AWAITING_CI, AWAITING_READY, AWAITING_REMOTE_REVIEW -> WAIT_ON_PR;
            case COMPLETED -> MERGE;
            case NEEDS_ATTENTION -> PARKED;
        };
    }
}

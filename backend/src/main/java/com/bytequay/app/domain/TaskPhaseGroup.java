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

/**
 * Coarse grouping over {@link TaskPhase} for the trunk task-card
 * surface — the user sees four buckets, not the 12 phases:
 * <ul>
 *   <li>{@code IN_PROGRESS} — the agent or CI is actively working.</li>
 *   <li>{@code AWAITING_YOU} — blocked on a human decision (a push to
 *       approve, a PR to mark ready, or a parked NEEDS_ATTENTION).</li>
 *   <li>{@code IDLE} — waiting on someone else (remote reviewers); no
 *       action for the agent or the user right now.</li>
 *   <li>{@code DONE} — merged or closed.</li>
 * </ul>
 */
public enum TaskPhaseGroup
{
    IN_PROGRESS,
    AWAITING_YOU,
    IDLE,
    DONE;

    public static TaskPhaseGroup of(TaskPhase phase)
    {
        return switch (phase) {
            case IMPLEMENTING, VALIDATING, INTERNAL_REVIEW,
                 PUSHED_AWAITING_CI, CI_FIXING,
                 ADDRESSING_COMMENTS, AGENT_RE_REVIEW, ADDRESSING_LOCAL_COMMENTS -> IN_PROGRESS;
            // PLANNING holds for the user to approve the plan before any dev
            // work starts — it's an action the user owns.
            case PLANNING, AWAITING_PUSH, AWAITING_READY, AWAITING_UPDATE_PUSH,
                 NEEDS_ATTENTION -> AWAITING_YOU;
            // QUEUED waits on the scheduler to free a slot; the remote-
            // review wait is on external humans. Neither needs the user.
            case QUEUED, AWAITING_REMOTE_REVIEW -> IDLE;
            case COMPLETED -> DONE;
        };
    }
}

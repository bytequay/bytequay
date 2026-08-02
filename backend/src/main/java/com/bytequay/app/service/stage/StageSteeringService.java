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
package com.bytequay.app.service.stage;

import java.util.List;
import java.util.UUID;

/**
 * Lets a user steer a stage's dev agent from the stage detail page. A
 * steering message is just a user turn enqueued against the Task's dev
 * thread — the agent's existing echo is the conversation row, so there is
 * no separate write. The message is attributed to the stage by time window
 * at read time (it falls inside the stage's open span).
 */
public interface StageSteeringService
{
    enum Mode
    {
        APPEND,
        CANCEL_AND_REPLACE
    }

    /** The handle a steering request returns: the enqueued dev-agent turn. */
    record SteerResult(String turnId) {}

    /**
     * Enqueue a steering turn carrying {@code text} on the stage's dev
     * thread. The stage must be OPEN or ACTIVE; a closed/paused stage is
     * rejected. When the stage is a monitor stage, opens a
     * {@code user_steering} iteration for the turn.
     *
     * @param stageId the stage being steered
     * @param text    the user's steering message (non-blank)
     * @param images  pasted-screenshot data URLs, saved and folded into the
     *                turn the same way trunk/task-brain sends do; may be null/empty
     * @return the enqueued turn's id
     */
    default SteerResult steer(UUID stageId, String text, List<String> images)
    {
        return steer(stageId, text, images, Mode.APPEND, null);
    }

    default SteerResult steer(
            UUID stageId, String text, List<String> images, Mode mode)
    {
        return steer(stageId, text, images, mode, null);
    }

    SteerResult steer(
            UUID stageId, String text, List<String> images, Mode mode,
            String expectedPredecessorStageTurnId);
}

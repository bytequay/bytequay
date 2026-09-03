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
package com.bytequay.agent.core;

import java.util.Optional;

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static java.util.Objects.requireNonNull;

/**
 * What stopping a task's provider process would interrupt.
 *
 * @param taskId the task
 * @param generation names the runtime the caller looked at; a stop must echo it, so a turn that
 * started after the caller confirmed cannot be stopped by that confirmation
 * @param activeTurnId the turn the stop would interrupt, when one is active
 * @param liveProcess whether this backend currently runs a provider process for the task
 */
public record AgentRuntimeStopImpact(
        TaskId taskId,
        String generation,
        Optional<TurnId> activeTurnId,
        boolean liveProcess)
{
    public AgentRuntimeStopImpact
    {
        requireNonNull(taskId, "taskId is null");
        checkNotBlank(generation, "generation");
        requireNonNull(activeTurnId, "activeTurnId is null");
    }
}

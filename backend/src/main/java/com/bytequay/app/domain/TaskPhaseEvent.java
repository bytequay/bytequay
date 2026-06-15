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
 * One row of a task's phase-transition audit log
 * ({@code task_phase_event}). Powers the lifecycle stepper and the
 * agenda render history.
 *
 * @param fromPhase null on the task's first transition
 */
public record TaskPhaseEvent(
        long id,
        String taskId,
        TaskPhase fromPhase,
        TaskPhase toPhase,
        Instant transitionedAt,
        String reason,
        Actor actor)
{
}

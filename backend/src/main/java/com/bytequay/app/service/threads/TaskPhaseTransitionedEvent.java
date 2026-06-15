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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.TaskPhase;

/**
 * Fired by {@link TaskPhaseMachine} after a task's phase changes and the
 * audit row is written. Listeners (UI refresh, downstream automation)
 * react to it; the machine itself never listens to its own event.
 */
public record TaskPhaseTransitionedEvent(
        String taskId,
        TaskPhase from,
        TaskPhase to,
        String reason)
{
}

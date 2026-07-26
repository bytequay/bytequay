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

import com.bytequay.app.domain.Actor;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TestTaskSchedulerConflictBridge
{
    @Test
    void parksTheOwningTaskForRecovery()
    {
        TaskPhaseMachine tasks = mock(TaskPhaseMachine.class);
        TaskSchedulerConflictBridge bridge = new TaskSchedulerConflictBridge(tasks);

        bridge.onConflict(new TaskSchedulerConflictEvent("task-1", "turn-1", "stale completion"));

        verify(tasks).parkOperational(
                "task-1", Actor.SCHEDULER, "scheduler_turn_conflict");
    }
}

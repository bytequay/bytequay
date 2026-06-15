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
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.checks.ValidationPassResult;
import com.bytequay.app.service.checks.ValidationPassService;
import com.bytequay.app.service.local.GitRunner;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestTaskPrePushDriver
{
    private final TaskStore taskStore = mock(TaskStore.class);
    private final ValidationPassService validation = mock(ValidationPassService.class);
    private final GitRunner git = mock(GitRunner.class);
    private final TaskPhaseMachine phaseMachine = mock(TaskPhaseMachine.class);
    private final TaskPrePushDriver driver =
            new TaskPrePushDriver(taskStore, validation, git, phaseMachine);

    @Test
    void cleanValidationWalksToAwaitingPush()
    {
        when(validation.run("t1.k1")).thenReturn(new ValidationPassResult(true, 0, List.of()));

        driver.runPrePush(task());

        verify(phaseMachine).transition("t1.k1", TaskPhase.VALIDATING, "pre_push_checks", Actor.SCHEDULER);
        verify(validation).run("t1.k1");
        // Validation drives VALIDATING -> INTERNAL_REVIEW via its event;
        // the driver then advances to the push gate.
        verify(phaseMachine).transition(
                eq("t1.k1"), eq(TaskPhase.AWAITING_PUSH), eq("internal_review_clean"), eq(Actor.SCHEDULER));
    }

    @Test
    void failedValidationStopsBeforeAwaitingPush()
    {
        when(validation.run("t1.k1")).thenReturn(new ValidationPassResult(false, 3, List.of()));

        driver.runPrePush(task());

        verify(phaseMachine).transition("t1.k1", TaskPhase.VALIDATING, "pre_push_checks", Actor.SCHEDULER);
        // Validation failing parks the task at NEEDS_ATTENTION (its own
        // event); the driver must NOT push it to AWAITING_PUSH.
        verify(phaseMachine, never()).transition(
                eq("t1.k1"), eq(TaskPhase.AWAITING_PUSH), eq("internal_review_clean"), eq(Actor.SCHEDULER));
    }

    private static Task task()
    {
        Instant now = Instant.ofEpochMilli(1_700_000_000_000L);
        return new Task("t1.k1", "t1", 1L, TaskStatus.IDLE, "dev/t1.k1", "/wt/t1.k1", "main", "/clone",
                null, null, null, null, null, "DEVELOP", null, null, 0L, 0L, 0L, null,
                now, null, null, null, null, null, null, TaskPhase.IMPLEMENTING, null, 0, null, null);
    }
}

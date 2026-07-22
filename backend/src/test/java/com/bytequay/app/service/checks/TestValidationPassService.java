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
package com.bytequay.app.service.checks;

import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ValidationPassStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestValidationPassService
{
    private final TaskStore taskStore = mock(TaskStore.class);
    private final ValidationPassStore validationStore = mock(ValidationPassStore.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void passesOnCleanStateWithNoChecks()
    {
        ValidationPassService service = service(List.of());
        stubTask();

        ValidationPassResult result = service.run("t1");

        assertThat(result.passed()).isTrue();
        assertThat(result.fixRounds()).isZero();
        verify(validationStore).finishPass(anyLong(), any(), eq(true), eq(0), eq("[]"));
    }

    @Test
    void aFailureRunsOnceAndPublishesTheTerminalResult()
    {
        int[] calls = {0};
        ValidationPassService service = service(List.of(
                (taskId, worktree) -> {
                    calls[0]++;
                    return List.of(new ValidationFailure("test", "still red"));
                }));
        stubTask();

        ValidationPassResult result = service.run("t1");

        assertThat(result.passed()).isFalse();
        assertThat(result.fixRounds()).isZero();
        assertThat(calls[0]).isEqualTo(1);
        verify(validationStore).finishPass(anyLong(), any(), eq(false), eq(0), anyString());
        verify(events).publishEvent(any(ValidationPassFinishedEvent.class));
    }

    private ValidationPassService service(List<ValidationCheck> checks)
    {
        return new ValidationPassService(
                checks, taskStore, validationStore, events, mapper);
    }

    private void stubTask()
    {
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(task()));
        when(validationStore.startPass(eq("t1"), any())).thenReturn(7L);
    }

    private static Task task()
    {
        Instant now = Instant.parse("2026-06-15T12:00:00Z");
        return new Task(
                "t1", "thread-1", 1L, TaskStatus.RUNNING,
                "dev/x", "/tmp/wt", "main", "/tmp/repo",
                null, null, null, null, null, "DEVELOP",
                null, null, 0L, 0L, 0L, null,
                now, null, null, null, null, null, null, TaskPhase.VALIDATING, null, 0, null);
    }
}

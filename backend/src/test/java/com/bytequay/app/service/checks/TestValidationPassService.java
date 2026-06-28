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
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ValidationPassStore;
import com.bytequay.app.service.threads.AgentScheduler;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestValidationPassService
{
    private final TaskStore taskStore = mock(TaskStore.class);
    private final ThreadStore threadStore = mock(ThreadStore.class);
    private final AgentScheduler scheduler = mock(AgentScheduler.class);
    private final ValidationPassStore validationStore = mock(ValidationPassStore.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void passesOnCleanStateWithNoChecks()
    {
        ValidationPassService service = service(List.of());
        stubTaskAndThread();

        ValidationPassResult result = service.run("t1");

        assertThat(result.passed()).isTrue();
        assertThat(result.fixRounds()).isZero();
        verify(validationStore).finishPass(anyLong(), any(), eq(true), eq(0), eq("[]"));
        verify(scheduler, never()).enqueueTaskTurn(any(), anyString(), anyString());
    }

    @Test
    void capHitsWithFailuresAfterThreeFixRounds()
    {
        ValidationPassService service = service(List.of(
                (taskId, worktree) -> List.of(new ValidationFailure("test", "still red"))));
        stubTaskAndThread();

        ValidationPassResult result = service.run("t1");

        assertThat(result.passed()).isFalse();
        assertThat(result.fixRounds()).isEqualTo(ValidationPassService.CAP_FIX_ROUNDS);
        // One auto-fix turn per failing round.
        verify(scheduler, times(ValidationPassService.CAP_FIX_ROUNDS))
                .enqueueTaskTurn(any(), anyString(), anyString());
        verify(validationStore).finishPass(anyLong(), any(), eq(false),
                eq(ValidationPassService.CAP_FIX_ROUNDS), anyString());
    }

    @Test
    void convergesWhenTheFixTurnsClearTheFailures()
    {
        // Fails twice, then the (simulated) fix turns make it pass.
        int[] calls = {0};
        ValidationPassService service = service(List.of((taskId, worktree) ->
                calls[0]++ < 2 ? List.of(new ValidationFailure("checkstyle", "x")) : List.of()));
        stubTaskAndThread();

        ValidationPassResult result = service.run("t1");

        assertThat(result.passed()).isTrue();
        assertThat(result.fixRounds()).isEqualTo(2);
        verify(scheduler, times(2)).enqueueTaskTurn(any(), anyString(), anyString());
    }

    private ValidationPassService service(List<ValidationCheck> checks)
    {
        return new ValidationPassService(
                checks, taskStore, threadStore, scheduler, validationStore, events, mapper);
    }

    private void stubTaskAndThread()
    {
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(task()));
        when(threadStore.findThreadById("thread-1")).thenReturn(Optional.of(thread()));
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

    private static Thread thread()
    {
        Instant now = Instant.parse("2026-06-15T12:00:00Z");
        return new Thread(
                "thread-1", ThreadKind.CLI_AGENT, "claude-code", null,
                "Test thread", ThreadStatus.RUNNING, "claude-sonnet-4.6",
                0L, 0L, 0L, now, now, null, null,
                ThreadFlow.BUILD, "ws-default", null, null);
    }
}

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

import com.bytequay.app.domain.Task;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.checks.ValidationFailure;
import com.bytequay.app.service.runs.AgentRunService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class TestLocalCiFixExecutor
{
    @Test
    void allLegacyCiFixEntryPointsFailClosed()
    {
        ThreadStore threads = mock(ThreadStore.class);
        StageStore stages = mock(StageStore.class);
        AgentRunService runs = mock(AgentRunService.class);
        ThreadTurnScheduler scheduler = mock(ThreadTurnScheduler.class);
        WorktreeLeaseService leases = mock(WorktreeLeaseService.class);
        TaskStore tasks = mock(TaskStore.class);
        LocalCiFixExecutor executor = new LocalCiFixExecutor(
                threads, stages, runs, scheduler, leases, tasks);
        Task task = mock(Task.class);
        List<ValidationFailure> failures = List.of(
                new ValidationFailure("test", "failed"));

        assertRetired(() -> executor.tryFix(task, failures));
        assertRetired(() -> executor.tryFixInCommand(task, failures));
        assertRetired(() -> executor.closeIfGreen("task-legacy"));
        assertRetired(() -> executor.closeIfGreenInCommand("task-legacy"));

        verifyNoInteractions(threads, stages, runs, scheduler, leases, tasks, task);
    }

    private static void assertRetired(Runnable action)
    {
        assertThatThrownBy(action::run)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("retired")
                .hasMessageContaining("typed V2 validation owner");
    }
}

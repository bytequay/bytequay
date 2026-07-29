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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.Task;
import com.bytequay.app.repository.ReviewRoundStore;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestLegacyReviewRoundRetirement
{
    private final TaskStore tasks = mock(TaskStore.class);
    private final StageStore stages = mock(StageStore.class);
    private final ReviewRoundStore rounds = mock(ReviewRoundStore.class);
    private final BrainReviewService brain = mock(BrainReviewService.class);
    private final PRService prs = mock(PRService.class);
    private final TaskCommandExecutor commands = mock(TaskCommandExecutor.class);
    private final ReviewRoundStateMachine machine = mock(ReviewRoundStateMachine.class);
    private final RoundGateSaga gate = mock(RoundGateSaga.class);
    private final ReviewRoundServiceImpl service = new ReviewRoundServiceImpl(
            tasks, stages, rounds, brain, prs, commands, machine, gate,
            Clock.systemUTC());

    @Test
    void legacyMutationsRejectBeforeCommandsStoresOrGate()
    {
        Task task = mock(Task.class);
        when(task.id()).thenReturn("task-1");

        assertRetired(() -> service.reconcile(task));
        assertRetired(() -> service.closeOpenRounds("task-1", "done"));
        assertRetired(() -> service.closeOpenRoundsInCommand("task-1", "done"));
        assertRetired(() -> service.approve("round-1"));
        assertRetired(() -> service.recomputeStats("round-1"));

        verifyNoInteractions(stages, rounds, brain, prs, commands, machine, gate);
    }

    @Test
    void v2ReconcileRemainsOwnedByTheTypedRuntime()
    {
        Task task = mock(Task.class);
        when(task.id()).thenReturn("task-1");
        when(tasks.isV2Task("task-1")).thenReturn(true);

        service.reconcile(task);

        verifyNoInteractions(stages, rounds, brain, prs, commands, machine, gate);
    }

    private static void assertRetired(Runnable action)
    {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(409));
    }
}

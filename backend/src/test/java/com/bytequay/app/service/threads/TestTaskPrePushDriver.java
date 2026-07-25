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
import com.bytequay.app.service.localpr.PRSyncService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestTaskPrePushDriver
{
    private final TaskStore taskStore = mock(TaskStore.class);
    private final ValidationPassService validation = mock(ValidationPassService.class);
    private final GitRunner git = mock(GitRunner.class);
    private final PRSyncService prSync = mock(PRSyncService.class);
    private final TaskPhaseMachine phaseMachine = mock(TaskPhaseMachine.class);
    private final TaskPrePushDriver driver =
            new TaskPrePushDriver(taskStore, validation, git, prSync, phaseMachine);

    @Test
    void cleanValidationStartsTheLocalPrBrainReviewWithoutOpeningAPushGate()
    {
        when(validation.run("t1.k1")).thenReturn(new ValidationPassResult(true, 0, List.of()));

        driver.runPrePush(task());

        verify(validation).run("t1.k1");
        // Validation drives VALIDATING -> INTERNAL_REVIEW via its event. The
        // local PR / Brain review owns the later Local Review handoff.
        verify(prSync).syncFromTask("t1.k1");
    }

    @Test
    void failedValidationStopsBeforeAwaitingPush()
    {
        when(validation.run("t1.k1")).thenReturn(new ValidationPassResult(false, 3, List.of()));

        driver.runPrePush(task());

        // Validation failing parks the task at NEEDS_ATTENTION (its own
        // event); the driver must NOT push it to AWAITING_PUSH.
        verify(prSync, never()).syncFromTask(anyString());
    }

    @Test
    void checkpointsLocalFixesBeforeRerunningValidation()
            throws Exception
    {
        Path worktree = Path.of("/wt/t1.k1");
        when(git.hasUncommittedChanges(worktree)).thenReturn(true);
        when(git.commit(worktree, "Fix local validation failures"))
                .thenReturn(Optional.of("abc123"));
        when(validation.run("t1.k1")).thenReturn(new ValidationPassResult(true, 0, List.of()));

        driver.runPrePush(task());

        verify(git).stageAll(worktree, List.of(".bytequay-hooks"));
        verify(git).commit(worktree, "Fix local validation failures");
        verify(validation).run("t1.k1");
    }

    @Test
    void checkpointFailureParksWithTheGitErrorInsteadOfLeavingValidationStuck()
            throws Exception
    {
        Task task = task();
        Path worktree = Path.of("/wt/t1.k1");
        when(git.hasUncommittedChanges(worktree)).thenReturn(true);
        doThrow(new IOException("index denied"))
                .when(git).stageAll(worktree, List.of(".bytequay-hooks"));

        driver.runPrePush(task);

        verify(validation, never()).run(anyString());
        verify(taskStore).saveTask(argThat(saved -> saved.errorMessage() != null
                && saved.errorMessage().contains("index denied")));
        verify(phaseMachine).transition(
                "t1.k1", TaskPhase.NEEDS_ATTENTION,
                "local_validation_checkpoint_failed", Actor.AGENT);
    }

    @Test
    void reconcileDoesNotInferADevelopmentHandoffFromCommitsAlone()
            throws Exception
    {
        when(taskStore.listByStatus(TaskStatus.IDLE, 200)).thenReturn(List.of(task(TaskPhase.IMPLEMENTING)));
        when(git.commitCountUniqueTo(any(), anyString(), anyString())).thenReturn(1);

        driver.reconcile();

        verify(validation, never()).run(anyString());
    }

    @Test
    void reconcileRunsValidationAfterTheExplicitHandoff()
            throws Exception
    {
        when(taskStore.listByStatus(TaskStatus.IDLE, 200)).thenReturn(List.of(task(TaskPhase.VALIDATING)));
        when(git.commitCountUniqueTo(any(), anyString(), anyString())).thenReturn(1);
        when(validation.run("t1.k1")).thenReturn(new ValidationPassResult(true, 0, List.of()));

        driver.reconcile();

        verify(validation).run("t1.k1");
        verify(prSync).syncFromTask("t1.k1");
    }

    private static Task task()
    {
        return task(TaskPhase.VALIDATING);
    }

    private static Task task(TaskPhase phase)
    {
        Instant now = Instant.ofEpochMilli(1_700_000_000_000L);
        return new Task("t1.k1", "t1", 1L, TaskStatus.IDLE, "dev/t1.k1", "/wt/t1.k1", "main", "/clone",
                null, null, null, null, null, "DEVELOP", null, null, 0L, 0L, 0L, null,
                now, null, null, null, null, null, null, phase, null, 0, null, null);
    }
}

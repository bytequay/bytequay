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

import com.bytequay.app.domain.StageEvent;
import com.bytequay.app.domain.StageEventType;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.workspaces.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Coverage for the auto-merge toggle's eligibility gate (R19-style
 *  risk=low/effort=small check against the task's latest recorded plan) and
 *  its "implies auto_approve" wiring. */
class TestTaskService
{
    private static final String THREAD = "thread-1";
    private static final String TASK = "task1";
    private static final Instant NOW = Instant.parse("2026-07-08T00:00:00Z");

    private final ThreadStore threadStore = mock(ThreadStore.class);
    private final TaskStore taskStore = mock(TaskStore.class);
    private final StageStore stageStore = mock(StageStore.class);
    private final WatchedRepoStore watchedRepoStore = mock(WatchedRepoStore.class);
    private final WorktreeService worktreeService = mock(WorktreeService.class);
    private final GitRunner git = mock(GitRunner.class);
    private final PullRequestRepository pullRequestRepository = mock(PullRequestRepository.class);
    private final PatResolver patResolver = mock(PatResolver.class);
    private final ThreadRegistry registry = mock(ThreadRegistry.class);
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final TaskPhaseMachine taskPhaseMachine = mock(TaskPhaseMachine.class);
    private final TaskTerminalSealer sealer = mock(TaskTerminalSealer.class);
    private final TaskService service = new TaskService(
            threadStore, taskStore, stageStore, watchedRepoStore, worktreeService, git,
            pullRequestRepository, patResolver, registry, workspaceService, notificationService,
            new ObjectMapper(), eventPublisher, taskPhaseMachine, sealer);

    private static Task task()
    {
        return new Task(
                TASK, THREAD, 1L, TaskStatus.RUNNING,
                "feature/x", "/tmp/wt/x", "main", "/tmp/repo",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, NOW, null, null, null, null, null);
    }

    private static StageInstance planStage()
    {
        return new StageInstance(
                UUID.fromString("00000000-0000-0000-0000-000000000001"), TASK,
                StageType.PLAN_STAGE, StageState.OPEN, NOW, null, null, null);
    }

    private static StageEvent planRecorded(String riskLevel, String estimatedComplexity)
    {
        String payload = "{\"signals\":{\"riskLevel\":\"" + riskLevel
                + "\",\"estimatedComplexity\":\"" + estimatedComplexity + "\"}}";
        return new StageEvent(UUID.randomUUID(), planStage().id(), TASK,
                StageEventType.PLAN_RECORDED, NOW, payload);
    }

    @Test
    void setAutoMergeRejectsATaskWithNoPlanRecordedYet()
    {
        when(taskStore.findTaskById(TASK)).thenReturn(Optional.of(task()));
        when(stageStore.findStagesByTask(TASK)).thenReturn(List.of());

        assertThatThrownBy(() -> service.setAutoMerge(THREAD, TASK, true))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not low-risk/small-effort");
        verify(taskStore, never()).setAutoMerge(any(), anyBoolean());
    }

    @Test
    void setAutoMergeRejectsAPlanThatIsNotLowRiskSmallEffort()
    {
        when(taskStore.findTaskById(TASK)).thenReturn(Optional.of(task()));
        when(stageStore.findStagesByTask(TASK)).thenReturn(List.of(planStage()));
        when(stageStore.findEventsByStage(planStage().id()))
                .thenReturn(List.of(planRecorded("medium", "small")));

        assertThatThrownBy(() -> service.setAutoMerge(THREAD, TASK, true))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not low-risk/small-effort");
        verify(taskStore, never()).setAutoMerge(any(), anyBoolean());
    }

    @Test
    void setAutoMergeEnablesAndImpliesAutoApproveWhenThePlanQualifies()
    {
        when(taskStore.findTaskById(TASK)).thenReturn(Optional.of(task()));
        when(stageStore.findStagesByTask(TASK)).thenReturn(List.of(planStage()));
        when(stageStore.findEventsByStage(planStage().id()))
                .thenReturn(List.of(planRecorded("low", "small")));
        when(taskStore.isAutoMerge(TASK)).thenReturn(true);

        boolean result = service.setAutoMerge(THREAD, TASK, true);

        assertThat(result).isTrue();
        verify(taskStore).setAutoMerge(TASK, true);
        verify(taskStore).setAutoApprove(TASK, true);
        verify(eventPublisher).publishEvent(new AutoApproveEnabledEvent(THREAD, TASK));
    }

    @Test
    void setAutoMergeAcceptsEffortWrittenAsLowNotJustSmall()
    {
        // The brain writes estimatedComplexity as free text and sometimes
        // drifts onto risk's low/medium/high vocabulary instead of small/
        // medium/large — "low" must qualify the same as "small".
        when(taskStore.findTaskById(TASK)).thenReturn(Optional.of(task()));
        when(stageStore.findStagesByTask(TASK)).thenReturn(List.of(planStage()));
        when(stageStore.findEventsByStage(planStage().id()))
                .thenReturn(List.of(planRecorded("low", "low")));
        when(taskStore.isAutoMerge(TASK)).thenReturn(true);

        boolean result = service.setAutoMerge(THREAD, TASK, true);

        assertThat(result).isTrue();
        verify(taskStore).setAutoMerge(TASK, true);
    }

    @Test
    void setAutoMergeDisablingSkipsTheEligibilityCheckAndLeavesAutoApproveAlone()
    {
        when(taskStore.findTaskById(TASK)).thenReturn(Optional.of(task()));

        boolean result = service.setAutoMerge(THREAD, TASK, false);

        assertThat(result).isFalse();
        verify(taskStore).setAutoMerge(TASK, false);
        verify(taskStore, never()).setAutoApprove(any(), anyBoolean());
        verify(eventPublisher, never()).publishEvent(any());
        verify(stageStore, never()).findStagesByTask(any());
    }
}

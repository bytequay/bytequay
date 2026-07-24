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
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.review.BrainReviewService;
import com.bytequay.app.service.workspaces.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Coverage for the auto-merge toggle's "implies auto_approve" wiring —
 *  enabling it is always the user's call, not gated on the task's plan. */
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
    private final PRService prService = mock(PRService.class);
    private final BrainReviewService brainReview = mock(BrainReviewService.class);
    private final ThreadTurnScheduler scheduler = mock(ThreadTurnScheduler.class);
    private final TaskService service = new TaskService(
            threadStore, taskStore, stageStore, watchedRepoStore, worktreeService, git,
            pullRequestRepository, patResolver, registry, workspaceService, notificationService,
            new ObjectMapper(), eventPublisher, taskPhaseMachine, sealer, prService, brainReview,
            scheduler);

    private static Task task()
    {
        return new Task(
                TASK, THREAD, 1L, TaskStatus.RUNNING,
                "feature/x", "/tmp/wt/x", "main", "/tmp/repo",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, NOW, null, null, null, null, null);
    }

    @Test
    void setAutoMergeEnablesAndImpliesAutoApprove()
    {
        when(taskStore.findTaskById(TASK)).thenReturn(Optional.of(task()));
        when(taskStore.isAutoMerge(TASK)).thenReturn(true);

        boolean result = service.setAutoMerge(THREAD, TASK, true);

        assertThat(result).isTrue();
        verify(taskStore).setAutoMerge(TASK, true);
        verify(taskStore).setAutoApprove(TASK, true);
        verify(eventPublisher).publishEvent(new AutoApproveEnabledEvent(THREAD, TASK));
    }

    @Test
    void setAutoMergeDisablingLeavesAutoApproveAlone()
    {
        when(taskStore.findTaskById(TASK)).thenReturn(Optional.of(task()));

        boolean result = service.setAutoMerge(THREAD, TASK, false);

        assertThat(result).isFalse();
        verify(taskStore).setAutoMerge(TASK, false);
        verify(taskStore, never()).setAutoApprove(any(), anyBoolean());
        verify(eventPublisher, never()).publishEvent(any());
    }
}

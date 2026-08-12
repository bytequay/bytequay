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
package com.bytequay.app.service.workspaces;

import com.bytequay.app.beans.workspace.WorkspaceOnboardingDto;
import com.bytequay.app.beans.workspace.WorkspaceSummaryDto;
import com.bytequay.app.developmentflow.compatibility.V2DevelopmentFlowProjection;
import com.bytequay.app.developmentflow.compatibility.V2TrunkRuntimeProjection;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.WorkspaceCardDto;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.repository.sqlite.RepoMetaStore;
import com.bytequay.app.repository.sqlite.SqliteBacklogStore;
import com.bytequay.app.service.runs.SessionProjectionService;
import com.bytequay.app.service.threads.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestWorkspaceOverviewRuntimeProjection
{
    @Test
    void landingCardUsesProjectedV2RuntimeStateAndPreservesLegacyTasks()
    {
        WorkspaceService workspaces = mock(WorkspaceService.class);
        WorkspaceCreationService creations =
                mock(WorkspaceCreationService.class);
        WorkspaceConfigurationService configuration =
                mock(WorkspaceConfigurationService.class);
        ThreadStore threads = mock(ThreadStore.class);
        V2TrunkRuntimeProjection runtime =
                mock(V2TrunkRuntimeProjection.class);
        TaskStore tasks = mock(TaskStore.class);
        V2DevelopmentFlowProjection taskRuntime =
                mock(V2DevelopmentFlowProjection.class);
        WorkspaceCardDto card = new WorkspaceCardDto(
                "workspace-1", "Workspace", "#000000", false, List.of(),
                99, 99, 99, 99,
                new WorkspaceCardDto.MemorySummary(0, 0, 0, 4_000), 99L);
        Thread stored = thread(ThreadStatus.ERRORED, Instant.EPOCH);
        Thread projected = thread(
                ThreadStatus.NEEDS_ATTENTION, Instant.ofEpochMilli(50));
        Task storedV2 = task("v2", TaskStatus.COMPLETED, 1);
        Task projectedV2 = task("v2", TaskStatus.NEEDS_ATTENTION, 41);
        Task legacy = task("legacy", TaskStatus.IDLE, 7);
        when(workspaces.listWithStats()).thenReturn(List.of(card));
        when(workspaces.listRepos("workspace-1")).thenReturn(List.of());
        when(creations.visible("workspace-1")).thenReturn(true);
        when(configuration.onboarding("workspace-1"))
                .thenReturn(onboarding());
        when(threads.listThreadsByWorkspace("workspace-1"))
                .thenReturn(List.of(stored));
        when(runtime.projectAll(List.of(stored)))
                .thenReturn(List.of(projected));
        when(tasks.listTasksByThread("trunk-1"))
                .thenReturn(List.of(storedV2, legacy));
        when(taskRuntime.isV2Task("v2")).thenReturn(true);
        when(taskRuntime.project(storedV2)).thenReturn(projectedV2);
        WorkspaceOverviewService service = new WorkspaceOverviewService(
                workspaces, creations, configuration, threads, runtime,
                tasks, taskRuntime,
                mock(SessionProjectionService.class), mock(SqliteBacklogStore.class),
                mock(NotificationService.class), mock(WatchedRepoStore.class),
                mock(RepoMetaStore.class),
                mock(JdbcTemplate.class));

        WorkspaceSummaryDto summary = service.overview("workspace-1")
                .workspace();
        WorkspaceSummaryDto.ActivityDto activity =
                summary.recentActivity().getFirst();

        assertThat(activity.status()).isEqualTo("NEEDS_ATTENTION");
        assertThat(activity.occurredAt()).isEqualTo(50L);
        assertThat(summary.activeThreadCount()).isEqualTo(1);
        assertThat(summary.tasksInFlight()).isEqualTo(2);
        assertThat(summary.needsAttentionCount()).isEqualTo(1);
        assertThat(summary.spendTodayMilliUsd()).isEqualTo(48);
        assertThat(summary.lastActivityMs()).isEqualTo(50L);
    }

    private static WorkspaceOnboardingDto onboarding()
    {
        return new WorkspaceOnboardingDto(
                "workspace-1", true, "ready", 1, 1, true, true, false,
                null, null, 0, 0, 0, 0, null, 1L);
    }

    private static Thread thread(ThreadStatus status, Instant updatedAt)
    {
        return new Thread(
                "trunk-1", ThreadKind.CLI_AGENT, "codex", null, "Trunk",
                status, "model", 0, 0, 0, Instant.EPOCH, updatedAt, null,
                null, ThreadFlow.BUILD, "workspace-1", null);
    }

    private static Task task(String id, TaskStatus status, long cost)
    {
        return new Task(
                id, "trunk-1", 1, status, "branch", "/worktree", "main",
                "/repo", null, null, null, null, null, "BUILD", null, null,
                cost, 0, 0, null, Instant.now(), null, null, id, null, null);
    }
}

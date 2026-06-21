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
package com.bytequay.app.service;

import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.github.GitHubRateLimitMonitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestWorkspaceInsightsService
{
    private static final Instant NOW = Instant.now();

    private TaskStore taskStore;
    private WorkspaceInsightsService service;

    @BeforeEach
    void setUp()
    {
        ThreadStore threadStore = mock(ThreadStore.class);
        taskStore = mock(TaskStore.class);
        when(threadStore.listThreadsUpdatedSince(any())).thenReturn(List.of());
        service = new WorkspaceInsightsService(
                threadStore, taskStore, new GitHubRateLimitMonitor());
    }

    @Test
    void tasksByRepoSplitsShippedAndOpenByLinkRef()
    {
        when(taskStore.listWithLinkedPr(anyInt())).thenReturn(List.of(
                task("acme/widget#1", TaskPhase.COMPLETED, TaskStatus.COMPLETED, NOW.minusSeconds(3600)),
                task("acme/widget#2", TaskPhase.CI_FIXING, TaskStatus.RUNNING, NOW.minusSeconds(60)),
                task("acme/other#9", TaskPhase.COMPLETED, TaskStatus.COMPLETED, NOW.minusSeconds(120)),
                // No link ref → no repo signal → omitted.
                task(null, TaskPhase.IMPLEMENTING, TaskStatus.RUNNING, NOW)));

        List<WorkspaceInsightsService.RepoTaskBreakdown> byRepo =
                service.get("7d").tasksByRepo();

        assertThat(byRepo).extracting(WorkspaceInsightsService.RepoTaskBreakdown::repoFullName)
                .containsExactlyInAnyOrder("acme/widget", "acme/other");
        WorkspaceInsightsService.RepoTaskBreakdown widget = byRepo.stream()
                .filter(r -> r.repoFullName().equals("acme/widget")).findFirst().orElseThrow();
        assertThat(widget.tasksShipped()).isEqualTo(1);
        assertThat(widget.tasksOpen()).isEqualTo(1);
    }

    private static Task task(String linkedPrRef, TaskPhase phase, TaskStatus status, Instant createdAt)
    {
        return new Task(
                "task-" + System.identityHashCode(linkedPrRef + phase), "thread-1", 1L, status,
                "feature", null, "main", "/tmp", null, null, 1, null, null, "DEVELOP", 1, null,
                0L, 0L, 0L, null, createdAt, null, null, null, null, null, null, phase, null, 0, linkedPrRef);
    }
}

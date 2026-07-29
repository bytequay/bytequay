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

import com.bytequay.app.developmentflow.compatibility.V2TrunkRuntimeProjection;
import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.github.GitHubRateLimitMonitor;
import com.bytequay.app.repository.sqlite.InvestigationReviewStore;
import com.bytequay.app.service.runs.AgentRunService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestWorkspaceInsightsService
{
    private static final Instant NOW = Instant.now();

    private ThreadStore threadStore;
    private V2TrunkRuntimeProjection trunkRuntime;
    private TaskStore taskStore;
    private InvestigationReviewStore reviewStore;
    private AgentRunService runs;
    private WorkspaceInsightsService service;

    @BeforeEach
    void setUp()
    {
        threadStore = mock(ThreadStore.class);
        trunkRuntime = mock(V2TrunkRuntimeProjection.class);
        taskStore = mock(TaskStore.class);
        reviewStore = mock(InvestigationReviewStore.class);
        runs = mock(AgentRunService.class);
        when(threadStore.listThreadsUpdatedSince(any())).thenReturn(List.of());
        when(reviewStore.taskReviewSpendSince(any())).thenReturn(List.of());
        when(reviewStore.reviewSpendSince(any())).thenReturn(List.of());
        when(reviewStore.reviewSpendSince(eq("ws-1"), any())).thenReturn(List.of());
        when(trunkRuntime.projectAll(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        service = new WorkspaceInsightsService(
                threadStore,
                trunkRuntime,
                taskStore,
                reviewStore,
                new GitHubRateLimitMonitor(),
                runs);
    }

    @Test
    void includesTypedActivityThatLegacyTimestampQueryMisses()
    {
        Thread stored = thread("v2", 0L);
        Thread projected = thread("v2", 275L);
        when(trunkRuntime.listIdsUpdatedSince(isNull(), any()))
                .thenReturn(List.of("v2"));
        when(threadStore.listTasksByIds(List.of("v2")))
                .thenReturn(List.of(stored));
        when(trunkRuntime.projectAll(List.of(stored)))
                .thenReturn(List.of(projected));

        WorkspaceInsightsService.Insights insights = service.get("7d");

        assertThat(insights.spendInWindowMilli()).isEqualTo(275L);
    }

    @Test
    void tasksByRepoSplitsShippedAndOpenByLinkRef()
    {
        when(taskStore.listWithLinkedPr(anyInt())).thenReturn(List.of(
                task("acme/widget#1", TaskPhase.COMPLETED, TaskStatus.COMPLETED, NOW.minusSeconds(3600)),
                task("acme/widget#2", TaskPhase.PUSHED_AWAITING_CI, TaskStatus.RUNNING, NOW.minusSeconds(60)),
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

    @Test
    void includesTaskOwnedAgentReviewSpend()
    {
        when(reviewStore.reviewSpendSince(any())).thenReturn(List.of(
                new InvestigationReviewStore.TaskReviewSpend(1_160L, NOW.minusSeconds(60))));

        WorkspaceInsightsService.Insights insights = service.get("7d");

        assertThat(insights.spendInWindowMilli()).isEqualTo(1_160L);
        assertThat(insights.spendByDay()).extracting(WorkspaceInsightsService.DayPoint::costUsdMilli)
                .contains(1_160L);
    }

    @Test
    void workspaceInsightsUsePrOwnedReviewSpendAndIgnoreLegacyReviewThreads()
    {
        Thread own = thread("thread-own", 120L);
        Thread legacyReview = thread("legacy-review", 9_999L, ThreadFlow.REVIEW);
        when(threadStore.listThreadsByWorkspaceUpdatedSince(
                eq("ws-1"), any()))
                .thenReturn(List.of(own, legacyReview));
        when(threadStore.listThreadsByWorkspace("ws-1"))
                .thenReturn(List.of(own, legacyReview));
        when(taskStore.hasActiveTask(own.id())).thenReturn(true);
        when(taskStore.listWithLinkedPr(anyInt())).thenReturn(List.of(
                task(
                        "acme/widget#1",
                        TaskPhase.COMPLETED,
                        TaskStatus.COMPLETED,
                        NOW.minusSeconds(60),
                        own.id()),
                task(
                        "other/repo#9",
                        TaskPhase.COMPLETED,
                        TaskStatus.COMPLETED,
                        NOW.minusSeconds(60),
                        "thread-other")));
        when(reviewStore.reviewSpendSince(eq("ws-1"), any())).thenReturn(List.of(
                new InvestigationReviewStore.TaskReviewSpend(310L, NOW.minusSeconds(30))));
        when(runs.findByWorkspace("ws-1")).thenReturn(List.of(
                run("run-own", AgentRun.KIND_DEV, "claude-code", 240L)));

        WorkspaceInsightsService.Insights insights =
                service.get("ws-1", "7d");

        assertThat(insights.spendInWindowMilli()).isEqualTo(430L);
        assertThat(insights.tasksShippedInWindow()).isEqualTo(1);
        assertThat(insights.tasksByRepo())
                .extracting(
                        WorkspaceInsightsService.RepoTaskBreakdown
                                ::repoFullName)
                .containsExactly("acme/widget");
        assertThat(insights.usageByProvider())
                .extracting(
                        WorkspaceInsightsService.UsageBreakdown::key,
                        WorkspaceInsightsService.UsageBreakdown::costUsdMilli)
                .containsExactly(
                        tuple("claude-code", 240L));
        assertThat(insights.usageByKind())
                .extracting(
                        WorkspaceInsightsService.UsageBreakdown::key,
                        WorkspaceInsightsService.UsageBreakdown::tokensIn,
                        WorkspaceInsightsService.UsageBreakdown::tokensOut)
                .containsExactly(
                        tuple("dev", 1_200L, 300L));
    }

    private static Task task(String linkedPrRef, TaskPhase phase, TaskStatus status, Instant createdAt)
    {
        return task(linkedPrRef, phase, status, createdAt, "thread-1");
    }

    private static Task task(
            String linkedPrRef,
            TaskPhase phase,
            TaskStatus status,
            Instant createdAt,
            String threadId)
    {
        return new Task(
                "task-" + System.identityHashCode(linkedPrRef + phase), threadId, 1L, status,
                "feature", null, "main", "/tmp", null, null, 1, null, null, "DEVELOP", 1, null,
                0L, 0L, 0L, null, createdAt, null, null, null, null, null, null, phase, null, 0, linkedPrRef);
    }

    private static Thread thread(String id, long costUsdMilli)
    {
        return thread(id, costUsdMilli, ThreadFlow.BUILD);
    }

    private static Thread thread(String id, long costUsdMilli, ThreadFlow flow)
    {
        return new Thread(
                id,
                ThreadKind.CLI_AGENT,
                "claude-code",
                null,
                "Workspace trunk",
                ThreadStatus.IDLE,
                "sonnet",
                costUsdMilli,
                0,
                0,
                NOW.minusSeconds(120),
                NOW.minusSeconds(30),
                null,
                null,
                flow,
                "ws-1",
                null);
    }

    private static AgentRun run(
            String id,
            String kind,
            String provider,
            long costUsdMilli)
    {
        return new AgentRun(
                id,
                null,
                kind,
                AgentRun.SOURCE_SCHEDULED,
                null,
                null,
                null,
                AgentRun.STATUS_SUCCEEDED,
                0,
                null,
                null,
                null,
                NOW.minusSeconds(60),
                NOW.minusSeconds(30),
                "ws-1",
                "thread-own",
                provider,
                "sonnet",
                costUsdMilli,
                1_200L,
                300L,
                1,
                "Implement",
                null,
                "completed");
    }
}

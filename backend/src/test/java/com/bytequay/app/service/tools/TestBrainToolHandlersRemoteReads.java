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
package com.bytequay.app.service.tools;

import com.bytequay.app.domain.PrCiSnapshot;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.local.LocalRepoService;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.tools.BrainToolHandlers.PrStatusArgs;
import com.bytequay.app.service.tools.BrainToolHandlers.ReadCiLogArgs;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestBrainToolHandlersRemoteReads
{
    private static final String TASK_ID = "task-1";
    private static final ToolCall CALL = new ToolCall(ThreadScope.TASK,
            "thread-1", null, AgentRole.TASK, TASK_ID, null);

    @Mock
    private TaskStore taskStore;
    @Mock
    private StageStore stageStore;
    @Mock
    private PullRequestService pullRequests;
    @Mock
    private LocalRepoService localRepos;
    @Mock
    private PRService prService;

    private BrainToolHandlers tools;

    @BeforeEach
    void setUp()
    {
        tools = new BrainToolHandlers(
                taskStore, stageStore, pullRequests, localRepos, prService, new ObjectMapper());
    }

    @Test
    void remotePrStatusRefreshesInsteadOfReadingTheCachedDetail()
    {
        linkTask();
        PullRequestDetail detail = mock(PullRequestDetail.class);
        PullRequestDetail.CheckRun check = check(99L, "build", "failure");
        when(detail.state()).thenReturn("open");
        when(detail.draft()).thenReturn(false);
        when(detail.merged()).thenReturn(false);
        when(detail.approvalCount()).thenReturn(1);
        when(detail.changesRequestedCount()).thenReturn(0);
        when(detail.mergeable()).thenReturn(true);
        when(detail.mergeableState()).thenReturn("blocked");
        when(pullRequests.refreshPullRequestDetail("owner/repo", 7)).thenReturn(detail);
        when(pullRequests.getPullRequestCiSnapshot("owner/repo", 7)).thenReturn(
                new PrCiSnapshot(PullRequestDetail.CiStatus.FAILING, List.of(check), true));

        ToolOutcome.Completed result = completed(
                tools.readRemotePrStatus(new PrStatusArgs(TASK_ID), CALL));

        assertThat(result.isError()).isFalse();
        assertThat(result.text())
                .contains("\"ciStatus\":\"FAILING\"")
                .contains("\"name\":\"build\"");
        verify(pullRequests).refreshPullRequestDetail("owner/repo", 7);
        verify(pullRequests).getPullRequestCiSnapshot("owner/repo", 7);
        verify(pullRequests, never()).getPullRequestDetail("owner/repo", 7);
    }

    @Test
    void remotePrStatusRejectsAnotherTaskId()
    {
        ToolOutcome.Completed result = completed(tools.readRemotePrStatus(
                new PrStatusArgs("task-2"), CALL));

        assertThat(result.isError()).isTrue();
        assertThat(result.text()).contains("current task scope");
        verify(pullRequests, never()).refreshPullRequestDetail("owner/repo", 7);
    }

    @Test
    void ciLogRefreshesTheCheckListAndLogOnEveryCall()
    {
        linkTask();
        PullRequestDetail.CheckRun check = check(99L, "build", "failure");
        when(pullRequests.getPullRequestCiSnapshot("owner/repo", 7))
                .thenReturn(new PrCiSnapshot(
                        PullRequestDetail.CiStatus.FAILING, List.of(check), true));
        when(pullRequests.getCheckRunLog("owner/repo", 99L))
                .thenReturn("first log", "second log");

        ToolOutcome.Completed first = completed(tools.readCiLog(new ReadCiLogArgs(null), CALL));
        ToolOutcome.Completed second = completed(tools.readCiLog(new ReadCiLogArgs(null), CALL));

        assertThat(first.text()).contains("first log");
        assertThat(second.text()).contains("second log");
        verify(pullRequests, times(2)).getPullRequestCiSnapshot("owner/repo", 7);
        verify(pullRequests, times(2)).getCheckRunLog("owner/repo", 99L);
    }

    @Test
    void ciLogRequiresTheRuntimeTaskScope()
    {
        ToolOutcome.Completed result = completed(tools.readCiLog(
                new ReadCiLogArgs("build"),
                new ToolCall(ThreadScope.TRUNK, "thread-1", null, AgentRole.TASK)));

        assertThat(result.isError()).isTrue();
        assertThat(result.text()).contains("task-scoped turn");
    }

    private void linkTask()
    {
        Task task = mock(Task.class);
        when(task.linkedPrRef()).thenReturn("owner/repo#7");
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
    }

    private static PullRequestDetail.CheckRun check(long githubId, String name, String conclusion)
    {
        return new PullRequestDetail.CheckRun(
                githubId, name, "completed", conclusion, "https://example.test/check", null, null);
    }

    private static ToolOutcome.Completed completed(ToolOutcome outcome)
    {
        return (ToolOutcome.Completed) outcome;
    }
}

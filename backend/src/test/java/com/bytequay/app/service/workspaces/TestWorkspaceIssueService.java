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

import com.bytequay.app.domain.IssueDetail;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.service.RepoService;
import com.bytequay.app.service.threads.ThreadService;
import com.bytequay.app.service.workmodel.SessionAudience;
import com.bytequay.app.service.workmodel.WorkModelResolver;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestWorkspaceIssueService
{
    @Test
    void startLeavesTrunkDispatchOutsideTheLinkTransaction()
            throws Exception
    {
        Transactional boundary = WorkspaceIssueService.class
                .getMethod("start", String.class, int.class, String.class)
                .getAnnotation(Transactional.class);
        assertThat(boundary).isNotNull();
        assertThat(boundary.propagation()).isEqualTo(Propagation.NOT_SUPPORTED);
    }

    @Test
    void startSchedulesOnlyTheExactIssueReferencePrompt()
    {
        WorkspaceRepositoryResolver resolver =
                mock(WorkspaceRepositoryResolver.class);
        RepoService repos = mock(RepoService.class);
        ThreadService threads = mock(ThreadService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        WorkspaceIssueService service = new WorkspaceIssueService(
                resolver, repos, threads, jdbc, mock(WorkModelResolver.class));
        Thread trunk = trunk();
        when(threads.find(trunk.id())).thenReturn(Optional.of(trunk));
        when(threads.sendTrunk(trunk.id(), "Work on issue #482"))
                .thenReturn("turn-1");

        WorkspaceIssueService.StartIssueResult result =
                service.start("ws-1", 482, trunk.id());

        assertThat(result.trunkId()).isEqualTo(trunk.id());
        assertThat(result.turnId()).isEqualTo("turn-1");
        verify(threads).sendTrunk(trunk.id(), "Work on issue #482");
    }

    @Test
    void newIssueTrunkUsesTheWorkspaceApiRuntime()
    {
        WorkspaceRepositoryResolver resolver = mock(WorkspaceRepositoryResolver.class);
        RepoService repos = mock(RepoService.class);
        ThreadService threads = mock(ThreadService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        WorkModelResolver workModels = mock(WorkModelResolver.class);
        WorkspaceIssueService service = new WorkspaceIssueService(
                resolver, repos, threads, jdbc, workModels);
        when(resolver.resolve("ws-1")).thenReturn(
                new WorkspaceRepositoryResolver.RepositoryIdentity(
                        "acme", "widget", "acme/widget", "main"));
        IssueDetail detail = mock(IssueDetail.class);
        when(detail.title()).thenReturn("Fix query fan-out");
        when(repos.getIssueDetail("acme", "widget", 12)).thenReturn(detail);
        WorkModel api = new WorkModel(WorkModelKind.API, "openai", "gpt-5", "work");
        Instant now = Instant.now();
        when(workModels.resolveForWorkspace("ws-1", SessionAudience.PLAN))
                .thenReturn(new WorkModelResolver.Resolved(api,
                        new WorkModelResolver.Provenance(
                                WorkModelResolver.Source.WORKSPACE, "ws-1", "workspace Widget")));
        Thread apiTrunk = new Thread(
                "api-trunk", ThreadKind.LOGIC_LOOP, "openai", null,
                "Fix query fan-out", ThreadStatus.IDLE, "gpt-5",
                0, 0, 0, now, now, null, null, ThreadFlow.BUILD,
                "ws-1", api);
        when(threads.create(any())).thenReturn(apiTrunk);

        service.linkToTrunk("ws-1", 12, null);

        ArgumentCaptor<ThreadService.NewTaskRequest> request =
                ArgumentCaptor.forClass(ThreadService.NewTaskRequest.class);
        verify(threads).create(request.capture());
        assertThat(request.getValue().kind()).isEqualTo(ThreadKind.LOGIC_LOOP);
        assertThat(request.getValue().provider()).isEqualTo("openai");
        // The engine lives on the workspace — the thread row carries no
        // override of its own.
        assertThat(request.getValue().workModel()).isNull();
        verify(jdbc).update(any(String.class), eq("ws-1"), eq(12), eq("api-trunk"), any());
    }

    private static Thread trunk()
    {
        Instant now = Instant.parse("2026-07-17T00:00:00Z");
        return new Thread(
                "trunk-issue",
                ThreadKind.CLI_AGENT,
                "claude-code",
                null,
                "Issue work",
                ThreadStatus.IDLE,
                null,
                0,
                0,
                0,
                now,
                now,
                null,
                null,
                ThreadFlow.BUILD,
                "ws-1",
                null);
    }
}

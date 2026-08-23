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
package com.bytequay.app.scheduler;

import com.bytequay.app.git.CommitHistoryRef;
import com.bytequay.app.git.CommitHistoryRef.Source;
import com.bytequay.app.git.CommitHistoryService;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.app.workspace.WorkspaceSummary;
import com.bytequay.core.domain.CommitRefreshStatus;
import com.bytequay.core.domain.Workspace.CodeState;
import com.bytequay.core.domain.Workspace.MetadataState;
import com.bytequay.core.domain.Workspace.Topology;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static com.bytequay.app.git.CommitRefreshCause.SCHEDULED;
import static com.bytequay.app.git.CommitRefreshCause.STARTUP;
import static com.bytequay.core.domain.CommitRefreshStatus.State.IDLE;
import static com.bytequay.core.domain.CommitRefreshStatus.State.RUNNING;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestCommitHistorySyncJob
{
    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");

    private WorkspaceRepositoryService workspaces;
    private CommitHistoryService commits;
    private CommitHistorySyncJob job;

    @BeforeEach
    void setUp()
    {
        workspaces = mock(WorkspaceRepositoryService.class);
        commits = mock(CommitHistoryService.class);
        job = new CommitHistorySyncJob(
                workspaces,
                commits,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void scheduledTickQueuesOnlyStaleReadyWorkspacesAndExplicitUpstreams()
    {
        WorkspaceSummary linked = workspace("linked", CodeState.READY, true, 200);
        WorkspaceSummary direct = workspace("direct", CodeState.READY, false, 300);
        WorkspaceSummary fresh = workspace("fresh", CodeState.READY, false, 400);
        WorkspaceSummary running = workspace("running", CodeState.READY, false, 500);
        WorkspaceSummary pending = workspace("pending", CodeState.PENDING, false, 600);
        when(workspaces.listWorkspaces()).thenReturn(List.of(linked, direct, fresh, running, pending));
        when(commits.getRefreshStatus("linked")).thenReturn(CommitRefreshStatus.idle("linked"));
        when(commits.getRefreshStatus("direct")).thenReturn(failedAttempt("direct", NOW.minusSeconds(301)));
        when(commits.getRefreshStatus("fresh")).thenReturn(success("fresh", NOW.minusSeconds(60)));
        when(commits.getRefreshStatus("running")).thenReturn(new CommitRefreshStatus(
                "running", RUNNING, Optional.of(NOW.minusSeconds(600)), Optional.empty(), Optional.empty()));

        job.tick();

        verify(commits).requestRefresh("linked", List.of(
                new CommitHistoryRef(Source.WORKSPACE, "main"),
                new CommitHistoryRef(Source.UPSTREAM, "upstream-main")), SCHEDULED);
        verify(commits).requestRefresh(
                "direct", List.of(new CommitHistoryRef(Source.WORKSPACE, "main")), SCHEDULED);
        verify(commits, never()).requestRefresh(
                "fresh", List.of(new CommitHistoryRef(Source.WORKSPACE, "main")), SCHEDULED);
        verify(commits, never()).requestRefresh(
                "running", List.of(new CommitHistoryRef(Source.WORKSPACE, "main")), SCHEDULED);
        verify(commits, never()).getRefreshStatus("pending");
    }

    @Test
    void startupUsesTheSameDueWorkPathAndRecentFailureIsThrottled()
    {
        WorkspaceSummary due = workspace("due", CodeState.READY, false, 700);
        WorkspaceSummary recentFailure = workspace("recent-failure", CodeState.READY, false, 800);
        when(workspaces.listWorkspaces()).thenReturn(List.of(due, recentFailure));
        when(commits.getRefreshStatus("due")).thenReturn(CommitRefreshStatus.idle("due"));
        when(commits.getRefreshStatus("recent-failure"))
                .thenReturn(failedAttempt("recent-failure", NOW.minusSeconds(60)));

        job.requestStartupRefreshes();

        verify(commits).requestRefresh(
                "due", List.of(new CommitHistoryRef(Source.WORKSPACE, "main")), STARTUP);
        verify(commits, never()).requestRefresh(
                "recent-failure", List.of(new CommitHistoryRef(Source.WORKSPACE, "main")), STARTUP);
    }

    private static CommitRefreshStatus success(String workspaceId, Instant succeededAt)
    {
        return new CommitRefreshStatus(
                workspaceId, IDLE, Optional.of(succeededAt), Optional.of(succeededAt), Optional.empty());
    }

    private static CommitRefreshStatus failedAttempt(String workspaceId, Instant attemptedAt)
    {
        return new CommitRefreshStatus(
                workspaceId,
                CommitRefreshStatus.State.FAILED,
                Optional.of(attemptedAt),
                Optional.empty(),
                Optional.of("Refresh failed. Reference: example-reference"));
    }

    private static WorkspaceSummary workspace(
            String id,
            CodeState codeState,
            boolean linked,
            long repositoryId)
    {
        return new WorkspaceSummary(
                id,
                "Example " + id,
                repositoryId,
                "example-owner",
                "example-repository",
                linked ? 100L : null,
                linked ? "example-upstream" : null,
                linked ? "example-source" : null,
                linked ? 100L : repositoryId,
                linked ? "example-upstream" : "example-owner",
                linked ? "example-source" : "example-repository",
                linked ? Topology.FORK : Topology.DIRECT,
                "main",
                linked ? "upstream-main" : null,
                "/managed/" + id,
                codeState,
                MetadataState.READY,
                null,
                null,
                null,
                null,
                0);
    }
}

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
import com.bytequay.app.service.settings.AppSettingsService;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.app.workspace.WorkspaceSummary;
import com.bytequay.core.domain.CommitRefreshStatus;
import com.bytequay.core.domain.Workspace.Topology;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static com.bytequay.core.domain.CommitRefreshStatus.State.IDLE;
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
                mock(AppSettingsService.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void scheduledTickRefreshesStaleAndUnsyncedWorkspacesAndExplicitUpstreams()
    {
        WorkspaceSummary linked = workspace("linked", true, true, 200);
        WorkspaceSummary direct = workspace("direct", true, false, 300);
        WorkspaceSummary fresh = workspace("fresh", true, false, 400);
        WorkspaceSummary recentFailure = workspace("recent-failure", true, false, 500);
        WorkspaceSummary unsynced = workspace("unsynced", false, false, 600);
        when(workspaces.listWorkspaces()).thenReturn(ImmutableList.of(linked, direct, fresh, recentFailure, unsynced));
        when(commits.getRefreshStatus("linked")).thenReturn(CommitRefreshStatus.idle("linked"));
        when(commits.getRefreshStatus("direct")).thenReturn(failedAttempt("direct", NOW.minusSeconds(301)));
        when(commits.getRefreshStatus("fresh")).thenReturn(success("fresh", NOW.minusSeconds(60)));
        when(commits.getRefreshStatus("recent-failure"))
                .thenReturn(failedAttempt("recent-failure", NOW.minusSeconds(60)));
        when(commits.getRefreshStatus("unsynced")).thenReturn(CommitRefreshStatus.idle("unsynced"));

        job.tick();

        verify(commits).refresh("linked", ImmutableList.of(
                new CommitHistoryRef(Source.WORKSPACE, "main"),
                new CommitHistoryRef(Source.UPSTREAM, "upstream-main")));
        verify(commits).refresh(
                "direct", ImmutableList.of(new CommitHistoryRef(Source.WORKSPACE, "main")));
        verify(commits, never()).refresh(
                "fresh", ImmutableList.of(new CommitHistoryRef(Source.WORKSPACE, "main")));
        verify(commits, never()).refresh(
                "recent-failure", ImmutableList.of(new CommitHistoryRef(Source.WORKSPACE, "main")));
        verify(commits).refresh(
                "unsynced", ImmutableList.of(new CommitHistoryRef(Source.WORKSPACE, "main")));
    }

    @Test
    void dueRefreshUsesTheSameDirectServicePath()
    {
        WorkspaceSummary due = workspace("due", true, false, 700);
        WorkspaceSummary recentFailure = workspace("recent-failure", true, false, 800);
        when(workspaces.listWorkspaces()).thenReturn(ImmutableList.of(due, recentFailure));
        when(commits.getRefreshStatus("due")).thenReturn(CommitRefreshStatus.idle("due"));
        when(commits.getRefreshStatus("recent-failure"))
                .thenReturn(failedAttempt("recent-failure", NOW.minusSeconds(60)));

        job.refreshDue();

        verify(commits).refresh(
                "due", ImmutableList.of(new CommitHistoryRef(Source.WORKSPACE, "main")));
        verify(commits, never()).refresh(
                "recent-failure", ImmutableList.of(new CommitHistoryRef(Source.WORKSPACE, "main")));
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
            boolean codeAvailable,
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
                codeAvailable ? NOW : null,
                NOW,
                0);
    }
}

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

import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.workspace.WorkspaceIssueService;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.domain.Workspace.CodeState;
import com.bytequay.core.domain.Workspace.MetadataState;
import com.bytequay.core.domain.Workspace.Topology;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestIssueSyncJob
{
    @Test
    void scheduledSyncRefreshesEachMonitoredRepositoryOnce()
    {
        WorkspaceIssueService issues = mock(WorkspaceIssueService.class);
        WorkspaceStore workspaces = mock(WorkspaceStore.class);
        CredentialService credentials = mock(CredentialService.class);
        when(credentials.isGitHubAccountConfigured()).thenReturn(true);
        when(workspaces.findAll()).thenReturn(ImmutableList.of(
                workspace("first", 1),
                workspace("second", 1),
                workspace("third", 2)));

        new IssueSyncJob(issues, workspaces, credentials).tick();

        verify(issues).synchronize("first");
        verify(issues, never()).synchronize("second");
        verify(issues).synchronize("third");
        verify(issues, times(2)).synchronize(org.mockito.ArgumentMatchers.anyString());
    }

    private static Workspace workspace(String id, long repositoryId)
    {
        RepoRef repository = RepoRef.of("owner", "repo-" + repositoryId);
        Instant now = Instant.parse("2026-08-22T00:00:00Z");
        return new Workspace(
                id,
                id,
                repositoryId,
                repository,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                repositoryId,
                repository,
                Topology.DIRECT,
                "main",
                "https://github.com/" + repository.fullName() + ".git",
                Optional.empty(),
                Optional.empty(),
                "/tmp/bytequay-issue-sync/" + id,
                CodeState.READY,
                MetadataState.READY,
                Optional.of(now),
                Optional.of(now),
                Optional.empty(),
                Optional.empty(),
                0,
                now,
                now);
    }
}

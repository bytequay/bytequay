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
import com.bytequay.app.service.pr.PullRequestRefreshService;
import com.bytequay.app.service.settings.AppSettingsService;
import com.bytequay.app.workspace.WorkspaceMetadataService;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.domain.Workspace.Topology;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestPullRequestSyncJob
{
    @Test
    void refreshesDashboardAndDistinctWorkspaceRepositoriesOnSchedule()
    {
        PullRequestRefreshService refreshService = mock(PullRequestRefreshService.class);
        WorkspaceMetadataService metadata = mock(WorkspaceMetadataService.class);
        WorkspaceStore workspaces = mock(WorkspaceStore.class);
        CredentialService credentials = configuredCredentials();
        RepoRef shared = RepoRef.of("upstream", "shared");
        RepoRef other = RepoRef.of("owner", "other");
        Workspace first = workspace("first", 1, shared);
        Workspace second = workspace("second", 1, shared);
        Workspace third = workspace("third", 3, other);
        when(workspaces.findAll()).thenReturn(ImmutableList.of(first, second, third));
        PullRequestSyncJob job = new PullRequestSyncJob(
                refreshService, metadata, workspaces, credentials, mock(AppSettingsService.class));

        job.tick();

        var order = inOrder(refreshService);
        order.verify(refreshService).refreshDashboard();
        order.verify(refreshService).refreshRepository(shared);
        order.verify(refreshService).refreshRepository(other);
        verify(metadata).synchronize(first.id());
        verify(metadata, never()).synchronize(second.id());
        verify(metadata).synchronize(third.id());
    }

    @Test
    void skipsRefreshDuringQuietHours()
    {
        PullRequestRefreshService refreshService = mock(PullRequestRefreshService.class);
        AppSettingsService appSettings = mock(AppSettingsService.class);
        when(appSettings.inQuietHours()).thenReturn(true);
        PullRequestSyncJob job = new PullRequestSyncJob(
                refreshService, mock(WorkspaceMetadataService.class),
                mock(WorkspaceStore.class), configuredCredentials(), appSettings);

        job.tick();

        verify(refreshService, never()).refreshDashboard();
    }

    @Test
    void skipsRefreshWithoutGitHubCredentials()
    {
        PullRequestRefreshService refreshService = mock(PullRequestRefreshService.class);
        PullRequestSyncJob job = new PullRequestSyncJob(
                refreshService,
                mock(WorkspaceMetadataService.class),
                mock(WorkspaceStore.class),
                mock(CredentialService.class),
                mock(AppSettingsService.class));

        job.tick();

        verify(refreshService, never()).refreshDashboard();
    }

    private static CredentialService configuredCredentials()
    {
        CredentialService credentials = mock(CredentialService.class);
        when(credentials.isGitHubAccountConfigured()).thenReturn(true);
        return credentials;
    }

    private static Workspace workspace(String id, long repositoryId, RepoRef repository)
    {
        Instant now = Instant.parse("2026-08-22T00:00:00Z");
        return new Workspace(
                id,
                id,
                repositoryId,
                repository,
                Optional.empty(),
                Optional.empty(),
                repositoryId,
                repository,
                Topology.DIRECT,
                "main",
                "https://github.com/" + repository.fullName() + ".git",
                Optional.empty(),
                Optional.empty(),
                "/tmp/bytequay-pr-refresh/" + id,
                Optional.of(now),
                Optional.of(now),
                0,
                now,
                now);
    }
}

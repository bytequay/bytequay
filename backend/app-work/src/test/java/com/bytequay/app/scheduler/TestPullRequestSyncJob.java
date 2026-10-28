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
import com.bytequay.app.service.settings.AppPreferences;
import com.bytequay.app.service.settings.AppSettingsService;
import com.bytequay.app.workspace.WorkspaceMetadataService;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.domain.Workspace.Topology;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestPullRequestSyncJob
{
    @Test
    void refreshesEveryWorkspaceWhileDeduplicatingRepositoryMetadata()
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
        PullRequestSyncJob job = new PullRequestSyncJob(refreshService, metadata, workspaces, credentials, idleSettings(), Clock.systemUTC());

        job.tick();

        var order = inOrder(refreshService);
        order.verify(refreshService).refreshScheduledDashboard();
        order.verify(refreshService).refreshWorkspace(first.id());
        order.verify(refreshService).refreshWorkspace(second.id());
        order.verify(refreshService).refreshWorkspace(third.id());
        verify(metadata).synchronize(first.id());
        verify(metadata, never()).synchronize(second.id());
        verify(metadata).synchronize(third.id());
    }

    @Test
    void skipsRefreshDuringQuietHours()
    {
        PullRequestRefreshService refreshService = mock(PullRequestRefreshService.class);
        AppSettingsService appSettings = mock(AppSettingsService.class);
        when(appSettings.preferences()).thenReturn(AppPreferences.DEFAULT);
        when(appSettings.inQuietHours()).thenReturn(true);
        PullRequestSyncJob job = new PullRequestSyncJob(
                refreshService, mock(WorkspaceMetadataService.class),
                mock(WorkspaceStore.class), configuredCredentials(), appSettings, Clock.systemUTC());

        job.tick();

        verifyNoInteractions(refreshService);
    }

    @Test
    void warmsEveryTenMinutesAndReconcilesEveryThirtyMinutes()
    {
        PullRequestRefreshService refreshService = mock(PullRequestRefreshService.class);
        AppSettingsService appSettings = mock(AppSettingsService.class);
        when(appSettings.preferences()).thenReturn(AppPreferences.DEFAULT);
        MutableClock clock = new MutableClock(Instant.parse("2026-09-10T08:00:00Z"));
        PullRequestSyncJob job = new PullRequestSyncJob(
                refreshService, mock(WorkspaceMetadataService.class),
                mock(WorkspaceStore.class), configuredCredentials(), appSettings, clock);

        job.tick();
        clock.advance(Duration.ofMinutes(10));
        job.tick();
        verify(refreshService).refreshScheduledDashboard();
        verify(refreshService, org.mockito.Mockito.times(2)).warmRecentDetails(30);

        clock.advance(Duration.ofMinutes(21));
        job.tick();
        verify(refreshService, org.mockito.Mockito.times(2)).refreshScheduledDashboard();
        verify(refreshService, org.mockito.Mockito.times(3)).warmRecentDetails(30);
    }

    @Test
    void refreshesBoundWorkspacesWithoutGlobalGitHubCredentials()
    {
        PullRequestRefreshService refreshService = mock(PullRequestRefreshService.class);
        WorkspaceMetadataService metadata = mock(WorkspaceMetadataService.class);
        WorkspaceStore workspaces = mock(WorkspaceStore.class);
        when(workspaces.findAll()).thenReturn(ImmutableList.of(workspace("bound", 1, RepoRef.of("owner", "repo"))));
        CredentialService credentials = mock(CredentialService.class);
        MutableClock clock = new MutableClock(Instant.parse("2026-09-10T08:00:00Z"));
        PullRequestSyncJob job = new PullRequestSyncJob(refreshService, metadata, workspaces, credentials, idleSettings(), clock);

        job.tick();

        verify(refreshService).refreshWorkspace("bound");
        verify(refreshService, never()).refreshScheduledDashboard();
        verify(refreshService, never()).warmRecentDetails(30);
        verifyNoInteractions(metadata);

        when(credentials.isGitHubAccountConfigured()).thenReturn(true);
        clock.advance(Duration.ofMinutes(1));
        job.tick();

        verify(refreshService).refreshScheduledDashboard();
        verify(refreshService).warmRecentDetails(30);
        verify(refreshService).refreshWorkspace("bound");
    }

    /** Settings whose refresh interval has already elapsed, so the tick under test is the one being asserted. */
    private static AppSettingsService idleSettings()
    {
        AppSettingsService settings = mock(AppSettingsService.class);
        when(settings.preferences()).thenReturn(AppPreferences.DEFAULT);
        return settings;
    }

    private static CredentialService configuredCredentials()
    {
        CredentialService credentials = mock(CredentialService.class);
        when(credentials.isGitHubAccountConfigured()).thenReturn(true);
        return credentials;
    }

    private static final class MutableClock
            extends Clock
    {
        private Instant now;

        private MutableClock(Instant now)
        {
            this.now = now;
        }

        void advance(Duration amount)
        {
            now = now.plus(amount);
        }

        @Override
        public java.time.ZoneId getZone()
        {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone)
        {
            return this;
        }

        @Override
        public Instant instant()
        {
            return now;
        }
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

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
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.WorkspaceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.bytequay.app.repository.github.GitHubRateLimitException.refreshQuietly;
import static java.util.Objects.requireNonNull;

/** Periodically refreshes pull requests and checks repository metadata freshness outside quiet hours. */
@Component
public class PullRequestSyncJob
{
    private static final Logger log = LoggerFactory.getLogger(PullRequestSyncJob.class);
    /** Fast discovery has its own job; this wake checks only reconciliation and detail warming. */
    private static final long TICK_MILLIS = 60_000L;

    private final PullRequestRefreshService refreshService;
    private final WorkspaceMetadataService metadata;
    private final WorkspaceStore workspaces;
    private final CredentialService credentials;
    private final AppSettingsService appSettings;
    private final Clock clock;
    private volatile Instant lastRefreshedAt = Instant.EPOCH;
    private volatile Instant lastWarmedAt = Instant.EPOCH;

    public PullRequestSyncJob(
            PullRequestRefreshService refreshService,
            WorkspaceMetadataService metadata,
            WorkspaceStore workspaces,
            CredentialService credentials,
            AppSettingsService appSettings,
            Clock clock)
    {
        this.refreshService = requireNonNull(refreshService, "refreshService is null");
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.appSettings = requireNonNull(appSettings, "appSettings is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Scheduled(fixedDelay = TICK_MILLIS, scheduler = "repositorySyncScheduler")
    public void tick()
    {
        if (!credentials.isGitHubAccountConfigured()) {
            log.debug("Sync skipped: no GitHub account configured");
            return;
        }
        if (appSettings.inQuietHours()) {
            log.debug("Sync skipped: GitHub quiet hours");
            return;
        }
        Instant now = Instant.now(clock);
        if (!now.isBefore(lastRefreshedAt.plus(Duration.ofMinutes(30)))) {
            lastRefreshedAt = now;
            refreshQuietly(log, "Pull request refresh for global dashboard", refreshService::refreshScheduledDashboard);
            List<Workspace> configuredWorkspaces = workspaces.findAll();
            Set<Long> metadataRepositories = new HashSet<>();
            configuredWorkspaces.stream()
                    .filter(workspace -> metadataRepositories.add(workspace.monitoredRepositoryId()))
                    .forEach(workspace -> metadata.synchronize(workspace.id()));
            Set<Long> pullRequestRepositories = new HashSet<>();
            configuredWorkspaces.stream()
                    .filter(workspace -> pullRequestRepositories.add(workspace.monitoredRepositoryId()))
                    .forEach(workspace -> refreshQuietly(log, "Pull request refresh for " + workspace.monitoredRepository().fullName(),
                            () -> refreshService.refreshWorkspace(workspace.id())));
        }
        if (!now.isBefore(lastWarmedAt.plus(Duration.ofMinutes(10)))) {
            lastWarmedAt = now;
            refreshQuietly(log, "Pull request refresh for warm PR details", () -> refreshService.warmRecentDetails(appSettings.preferences().prWarmCount()));
        }
    }
}

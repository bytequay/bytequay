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
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.WorkspaceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import static java.util.Objects.requireNonNull;

/** Periodically refreshes pull-request projections outside GitHub quiet hours. */
@Component
public class PullRequestSyncJob
{
    private static final Logger log = LoggerFactory.getLogger(PullRequestSyncJob.class);

    private final PullRequestRefreshService refreshService;
    private final WorkspaceStore workspaces;
    private final CredentialService credentials;
    private final AppSettingsService appSettings;

    public PullRequestSyncJob(
            PullRequestRefreshService refreshService,
            WorkspaceStore workspaces,
            CredentialService credentials,
            AppSettingsService appSettings)
    {
        this.refreshService = requireNonNull(refreshService, "refreshService is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.appSettings = requireNonNull(appSettings, "appSettings is null");
    }

    @Scheduled(fixedDelay = 60_000)
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
        refresh("global dashboard", refreshService::refreshDashboard);
        workspaces.findAll().stream()
                .map(Workspace::monitoredRepository)
                .distinct()
                .forEach(repository -> refresh(repository.fullName(), () -> refreshService.refreshRepository(repository)));
    }

    private void refresh(String scope, Runnable action)
    {
        try {
            action.run();
        }
        catch (RuntimeException failure) {
            log.warn("Pull request refresh failed for {}: {}", scope, failure.getMessage());
        }
    }
}

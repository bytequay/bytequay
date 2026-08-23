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
import com.bytequay.app.service.settings.AppSettingsService;
import com.bytequay.app.workspace.WorkspaceIssueRefreshService;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.WorkspaceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/** Periodically refreshes the database-backed issue projection for monitored repositories. */
@Component
public class IssueSyncJob
{
    private static final Logger log = LoggerFactory.getLogger(IssueSyncJob.class);

    private final WorkspaceIssueRefreshService issues;
    private final WorkspaceStore workspaces;
    private final CredentialService credentials;
    private final AppSettingsService appSettings;

    public IssueSyncJob(
            WorkspaceIssueRefreshService issues,
            WorkspaceStore workspaces,
            CredentialService credentials,
            AppSettingsService appSettings)
    {
        this.issues = requireNonNull(issues, "issues is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.appSettings = requireNonNull(appSettings, "appSettings is null");
    }

    @Scheduled(
            initialDelayString = "${bytequay.issues.sync-initial-delay:PT1M}",
            fixedDelayString = "${bytequay.issues.sync-interval:PT15M}")
    public void tick()
    {
        if (!credentials.isGitHubAccountConfigured() || appSettings.inQuietHours()) {
            return;
        }
        Set<Long> synchronizedRepositories = new HashSet<>();
        for (Workspace workspace : workspaces.findAll()) {
            if (!synchronizedRepositories.add(workspace.monitoredRepositoryId())) {
                continue;
            }
            try {
                issues.refreshIssues(workspace.id());
            }
            catch (RuntimeException failure) {
                log.warn("Issue sync failed for {}: {}", workspace.monitoredRepository(), failure.getMessage());
            }
        }
    }
}

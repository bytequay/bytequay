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

import com.bytequay.app.service.settings.AppSettingsService;
import com.bytequay.app.workspace.WorkspaceIssueService;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.WorkspaceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

import static com.bytequay.app.repository.github.GitHubRateLimitException.refreshQuietly;
import static java.util.Objects.requireNonNull;

/** Periodically refreshes the database-backed issue projection for monitored repositories. */
@Component
public class IssueSyncJob
{
    private static final Logger log = LoggerFactory.getLogger(IssueSyncJob.class);
    // A full sync reads every open issue, about 30 pages for a large repository; the one-minute recent refresh keeps Home
    // current in between, and opening a workspace's Issues tab syncs it sooner.
    private static final Duration FULL_SYNC_AGE = Duration.ofHours(4);

    private final WorkspaceIssueService issues;
    private final WorkspaceStore workspaces;
    private final AppSettingsService appSettings;

    public IssueSyncJob(WorkspaceIssueService issues, WorkspaceStore workspaces, AppSettingsService appSettings)
    {
        this.issues = requireNonNull(issues, "issues is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.appSettings = requireNonNull(appSettings, "appSettings is null");
    }

    @Scheduled(
            initialDelayString = "PT1M",
            fixedDelayString = "PT30M",
            scheduler = "repositorySyncScheduler")
    public void tick()
    {
        if (appSettings.inQuietHours()) {
            return;
        }
        for (Workspace workspace : workspaces.findAll()) {
            refreshQuietly(log, "Issue sync for " + workspace.monitoredRepository(), () -> issues.refreshIssuesOlderThan(workspace.id(), FULL_SYNC_AGE));
        }
    }
}

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
import com.bytequay.core.domain.Workspace.CodeState;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.requireNonNull;

/** Refreshes due commit snapshots through the same service used by manual refresh. */
@Component
public class CommitHistorySyncJob
{
    private static final Logger log = LoggerFactory.getLogger(CommitHistorySyncJob.class);
    private static final Duration FRESHNESS = Duration.ofMinutes(5);

    private final WorkspaceRepositoryService workspaces;
    private final CommitHistoryService commits;
    private final AppSettingsService appSettings;
    private final Clock clock;

    public CommitHistorySyncJob(
            WorkspaceRepositoryService workspaces,
            CommitHistoryService commits,
            AppSettingsService appSettings,
            Clock clock)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.commits = requireNonNull(commits, "commits is null");
        this.appSettings = requireNonNull(appSettings, "appSettings is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Scheduled(fixedDelay = 60_000)
    public void tick()
    {
        // Startup and manual refreshes are never paused; only the periodic tick is.
        if (appSettings.inQuietHours()) {
            return;
        }
        refreshDue();
    }

    void refreshDue()
    {
        Instant staleBefore = clock.instant().minus(FRESHNESS);
        for (WorkspaceSummary workspace : workspaces.listWorkspaces()) {
            if (workspace.codeState() != CodeState.READY) {
                continue;
            }
            CommitRefreshStatus status = commits.getRefreshStatus(workspace.id());
            if (!due(status, staleBefore)) {
                continue;
            }
            try {
                commits.refresh(workspace.id(), refs(workspace));
            }
            catch (RuntimeException failure) {
                log.warn("Could not refresh commits for workspace {}: {}",
                        workspace.id(), failure.getMessage());
            }
        }
    }

    private static boolean due(CommitRefreshStatus status, Instant staleBefore)
    {
        boolean successStale = status.lastSucceededAt().map(value -> value.isBefore(staleBefore)).orElse(true);
        boolean attemptStale = status.lastAttemptAt().map(value -> value.isBefore(staleBefore)).orElse(true);
        return successStale && attemptStale;
    }

    private static List<CommitHistoryRef> refs(WorkspaceSummary workspace)
    {
        List<CommitHistoryRef> refs = new ArrayList<>();
        refs.add(new CommitHistoryRef(Source.WORKSPACE, workspace.defaultBranch()));
        if (workspace.upstreamRepositoryId() != null) {
            refs.add(new CommitHistoryRef(Source.UPSTREAM, requireNonNull(
                    workspace.upstreamDefaultBranch(), "linked upstream default branch is null")));
        }
        return ImmutableList.copyOf(refs);
    }
}

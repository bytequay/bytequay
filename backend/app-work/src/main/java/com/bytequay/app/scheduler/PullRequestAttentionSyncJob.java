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
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.settings.AppSettingsService;
import com.bytequay.app.workspace.WorkspaceIssueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import static com.bytequay.app.repository.github.GitHubRateLimitException.isRateLimit;
import static com.bytequay.app.repository.github.GitHubRateLimitException.refreshQuietly;
import static java.util.Objects.requireNonNull;

/** Reads recent card summaries without waiting for the slower detail and repository scans. */
@Component
public class PullRequestAttentionSyncJob
{
    private static final Logger log = LoggerFactory.getLogger(PullRequestAttentionSyncJob.class);

    private final PullRequestService pullRequests;
    private final CredentialService credentials;
    private final AppSettingsService settings;
    private final WorkspaceIssueService issues;

    public PullRequestAttentionSyncJob(PullRequestService pullRequests, CredentialService credentials, AppSettingsService settings, WorkspaceIssueService issues)
    {
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.settings = requireNonNull(settings, "settings is null");
        this.issues = requireNonNull(issues, "issues is null");
    }

    @Scheduled(fixedDelay = 60_000, scheduler = "attentionScheduler")
    public void tick()
    {
        if (!credentials.isGitHubAccountConfigured() || settings.inQuietHours()) {
            return;
        }
        try {
            pullRequests.syncAttentionSummaries();
        }
        catch (RuntimeException failure) {
            if (isRateLimit(failure)) {
                return;
            }
            log.warn("Pull request summary refresh failed: {}", failure.getMessage());
        }
        refreshQuietly(log, "Recent issue refresh", issues::refreshRecentIssues);
    }
}

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
import com.bytequay.app.team.TeamActivityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import static com.bytequay.app.repository.github.GitHubRateLimitException.isRateLimit;
import static java.util.Objects.requireNonNull;

@Component
public class TeamActivitySyncJob
{
    private static final Logger log = LoggerFactory.getLogger(TeamActivitySyncJob.class);
    private final TeamActivityService activity;
    private final CredentialService credentials;
    private final AppSettingsService settings;

    public TeamActivitySyncJob(TeamActivityService activity, CredentialService credentials, AppSettingsService settings)
    {
        this.activity = requireNonNull(activity, "activity is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.settings = requireNonNull(settings, "settings is null");
    }

    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT15M")
    public void tick()
    {
        if (!credentials.isGitHubAccountConfigured() || settings.inQuietHours()) {
            return;
        }
        try {
            activity.collect();
        }
        catch (RuntimeException failure) {
            if (!isRateLimit(failure)) {
                log.warn("Team activity collection failed: {}", failure.getMessage());
            }
        }
    }
}

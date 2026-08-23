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

import com.bytequay.app.service.pr.PullRequestRefreshService;
import com.bytequay.app.service.settings.AppSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import static java.util.Objects.requireNonNull;

/** Periodically requests a pull-request refresh outside GitHub quiet hours. */
@Component
public class PullRequestSyncJob
{
    private static final Logger log = LoggerFactory.getLogger(PullRequestSyncJob.class);

    private final PullRequestRefreshService refreshService;
    private final AppSettingsService appSettings;

    public PullRequestSyncJob(
            PullRequestRefreshService refreshService,
            AppSettingsService appSettings)
    {
        this.refreshService = requireNonNull(refreshService, "refreshService is null");
        this.appSettings = requireNonNull(appSettings, "appSettings is null");
    }

    @Scheduled(fixedDelay = 60_000)
    public void tick()
    {
        // Manual sync paths call doSync directly and are never paused.
        if (appSettings.inQuietHours()) {
            log.debug("Sync skipped: GitHub quiet hours");
            return;
        }
        refreshService.requestRefresh();
    }
}

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
package com.bytequay.app.web;

import com.bytequay.app.domain.SyncSettings;
import com.bytequay.app.scheduler.PullRequestSyncJob;
import com.bytequay.app.service.SyncSettingsService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import static java.util.Objects.requireNonNull;

@RestController
@RequestMapping("/api/settings")
public class SettingsController
{
    private final SyncSettingsService syncSettingsService;
    private final PullRequestSyncJob syncJob;

    public SettingsController(SyncSettingsService syncSettingsService, PullRequestSyncJob syncJob)
    {
        this.syncSettingsService = requireNonNull(syncSettingsService, "syncSettingsService is null");
        this.syncJob = requireNonNull(syncJob, "syncJob is null");
    }

    /**
     * Returns the current sync configuration.
     * GET /api/settings/sync
     */
    @GetMapping("/sync")
    public SyncSettings getSyncSettings()
    {
        return syncSettingsService.getSettings();
    }

    /**
     * Updates the sync interval. Change takes effect within the next scheduler tick (≤ 10 s).
     * PUT /api/settings/sync
     */
    @PutMapping("/sync")
    public SyncSettings updateSyncSettings(@RequestBody SyncSettings settings)
    {
        return syncSettingsService.updateSettings(settings);
    }

    /**
     * Triggers an immediate sync on the next scheduler tick (within 10 s).
     * POST /api/settings/sync/trigger
     */
    @PostMapping("/sync/trigger")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void triggerSync()
    {
        syncJob.requestImmediateSync();
    }
}

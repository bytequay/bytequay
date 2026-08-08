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
import com.bytequay.app.service.WorkspaceBehaviorService;
import com.bytequay.app.service.WorkspaceBehaviorService.Settings;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import static com.bytequay.app.web.RequestValidation.requireBody;
import static java.util.Objects.requireNonNull;

@RestController
@RequestMapping("/api/settings")
public class SettingsController
{
    private final SyncSettingsService syncSettings;
    private final PullRequestSyncJob syncJob;
    private final WorkspaceBehaviorService workspaceBehavior;

    public SettingsController(
            SyncSettingsService syncSettings,
            PullRequestSyncJob syncJob,
            WorkspaceBehaviorService workspaceBehavior)
    {
        this.syncSettings = requireNonNull(syncSettings, "syncSettings is null");
        this.syncJob = requireNonNull(syncJob, "syncJob is null");
        this.workspaceBehavior = requireNonNull(workspaceBehavior, "workspaceBehavior is null");
    }

    @GetMapping("/workspace-behavior")
    public Settings getWorkspaceBehavior()
    {
        return workspaceBehavior.get();
    }

    @PutMapping("/workspace-behavior")
    public Settings updateWorkspaceBehavior(@RequestBody Settings body)
    {
        body = requireBody(body);
        return workspaceBehavior.update(body);
    }

    @GetMapping("/sync")
    public SyncSettings getSyncSettings()
    {
        return syncSettings.getSettings();
    }

    @PutMapping("/sync")
    public SyncSettings updateSyncSettings(@RequestBody SyncSettings settings)
    {
        settings = requireBody(settings);
        return syncSettings.updateSettings(settings);
    }

    @PostMapping("/sync/trigger")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void triggerSync()
    {
        syncJob.requestImmediateSync();
    }
}

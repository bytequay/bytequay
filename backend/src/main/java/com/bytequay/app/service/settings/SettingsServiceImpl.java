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
package com.bytequay.app.service.settings;

import com.bytequay.app.domain.SyncSettings;
import com.bytequay.app.scheduler.PullRequestSyncJob;
import com.bytequay.app.service.SyncSettingsService;
import com.bytequay.app.service.WorkspaceBehaviorService;
import com.bytequay.app.service.WorkspaceBehaviorService.Settings;
import com.bytequay.app.service.settings.AiDefaultsService.AiDefaults;
import org.springframework.stereotype.Service;

import static java.util.Objects.requireNonNull;

@Service
public class SettingsServiceImpl
        implements SettingsService
{
    private final SyncSettingsService syncSettingsService;
    private final PullRequestSyncJob syncJob;
    private final WorkspaceBehaviorService workspaceBehavior;
    private final AiDefaultsService aiDefaults;

    public SettingsServiceImpl(
            SyncSettingsService syncSettingsService,
            PullRequestSyncJob syncJob,
            WorkspaceBehaviorService workspaceBehavior,
            AiDefaultsService aiDefaults)
    {
        this.syncSettingsService = requireNonNull(syncSettingsService, "syncSettingsService is null");
        this.syncJob = requireNonNull(syncJob, "syncJob is null");
        this.workspaceBehavior = requireNonNull(workspaceBehavior, "workspaceBehavior is null");
        this.aiDefaults = requireNonNull(aiDefaults, "aiDefaults is null");
    }

    @Override
    public Settings getWorkspaceBehavior()
    {
        return workspaceBehavior.get();
    }

    @Override
    public Settings updateWorkspaceBehavior(Settings body)
    {
        return workspaceBehavior.update(body);
    }

    @Override
    public SyncSettings getSyncSettings()
    {
        return syncSettingsService.getSettings();
    }

    @Override
    public SyncSettings updateSyncSettings(SyncSettings settings)
    {
        return syncSettingsService.updateSettings(settings);
    }

    @Override
    public void triggerSync()
    {
        syncJob.requestImmediateSync();
    }

    @Override
    public AiDefaults getAiDefaults()
    {
        return aiDefaults.get();
    }

    @Override
    public AiDefaults updateAiDefaults(AiDefaults defaults)
    {
        return aiDefaults.update(defaults);
    }
}

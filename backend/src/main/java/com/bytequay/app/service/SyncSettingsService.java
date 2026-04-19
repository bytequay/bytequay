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
package com.bytequay.app.service;

import com.bytequay.app.domain.SyncSettings;
import com.bytequay.app.repository.AppSettingsStore;
import org.springframework.stereotype.Service;

import static java.util.Objects.requireNonNull;

@Service
public class SyncSettingsService
{
    private static final int DEFAULT_SYNC_INTERVAL_SECONDS = 60;

    private final AppSettingsStore settings;

    public SyncSettingsService(AppSettingsStore settings)
    {
        this.settings = requireNonNull(settings, "settings is null");
    }

    public SyncSettings getSettings()
    {
        return new SyncSettings(settings.get(AppSettingsStore.Key.SYNC_INTERVAL_SECONDS)
                .map(SyncSettingsService::parseIntervalSeconds)
                .orElse(DEFAULT_SYNC_INTERVAL_SECONDS));
    }

    public SyncSettings updateSettings(SyncSettings syncSettings)
    {
        settings.set(AppSettingsStore.Key.SYNC_INTERVAL_SECONDS, String.valueOf(syncSettings.intervalSeconds()));
        return syncSettings;
    }

    private static int parseIntervalSeconds(String value)
    {
        try {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException e) {
            return DEFAULT_SYNC_INTERVAL_SECONDS;
        }
    }
}

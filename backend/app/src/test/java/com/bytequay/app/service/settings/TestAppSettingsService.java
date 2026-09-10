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

import com.bytequay.core.store.AppSettingsStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestAppSettingsService
{
    private final Map<String, String> rows = new HashMap<>();
    private final AppSettingsService settings = new AppSettingsService(new AppSettingsStore()
    {
        @Override
        public Optional<String> get(String key)
        {
            return Optional.ofNullable(rows.get(key));
        }

        @Override
        public void put(String key, String value)
        {
            rows.put(key, value);
        }

        @Override
        public void delete(String key)
        {
            rows.remove(key);
        }
    }, new ObjectMapper(), Clock.systemUTC());

    @Test
    void codeGraphModeDefaultsToOnAndRoundTrips()
    {
        assertThat(settings.codeGraphMode()).isEqualTo(CodeGraphMode.ON);

        settings.saveCodeGraph(new CodeGraphSetting(CodeGraphMode.OFF));

        assertThat(settings.codeGraphMode()).isEqualTo(CodeGraphMode.OFF);
        assertThat(rows).containsEntry("codegraph.mode", "OFF");
    }

    @Test
    void preferencesDefaultAndRoundTripAsOneRow()
    {
        assertThat(settings.preferences()).isEqualTo(AppPreferences.DEFAULT);
        assertThat(settings.taskRuntimeRetention()).isEqualTo(Duration.ofDays(7));
        assertThat(settings.recentRetentionDays()).isEqualTo(14);
        assertThat(settings.refreshInterval()).isEqualTo(Duration.ofMinutes(6));

        settings.savePreferences(new AppPreferences(30, 21, 15, AppTheme.LIGHT));

        assertThat(settings.taskRuntimeRetention()).isEqualTo(Duration.ofDays(30));
        assertThat(settings.recentRetentionDays()).isEqualTo(21);
        assertThat(settings.refreshInterval()).isEqualTo(Duration.ofMinutes(15));
        assertThat(rows).containsKey("app.preferences");
    }

    @Test
    void anUnreadableOrOutOfRangePreferenceRowFallsBackToTheDefault()
    {
        rows.put("app.preferences", "{\"taskRetentionDays\":0,\"recentRetentionDays\":14,\"refreshIntervalMinutes\":6,\"theme\":\"SYSTEM\"}");

        assertThat(settings.preferences()).isEqualTo(AppPreferences.DEFAULT);

        rows.put("app.preferences", "not json");

        assertThat(settings.preferences()).isEqualTo(AppPreferences.DEFAULT);
    }

    @Test
    void readsTheBooleanSettingWrittenBeforeTheModeExisted()
    {
        rows.put("codegraph.enabled", "false");

        assertThat(settings.codeGraphMode()).isEqualTo(CodeGraphMode.OFF);
    }
}

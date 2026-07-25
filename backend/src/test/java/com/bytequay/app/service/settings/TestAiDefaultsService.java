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

import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.service.settings.AiDefaultsService.AiDefaults;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestAiDefaultsService
{
    @Test
    void unsetKeysFallBackToTheShippedEngines()
    {
        AiDefaults defaults = new AiDefaultsService(new InMemoryStore()).get();

        assertThat(defaults.plan()).isEqualTo("cli:claude-code");
        assertThat(defaults.dev()).isEqualTo("cli:claude-code");
        assertThat(defaults.review()).isEqualTo("cli:claude-code");
        assertThat(defaults.triage()).isEqualTo("cli:claude-code");
        assertThat(defaults.perf()).isEqualTo("cli:claude-code");
        // Red-build loops start on the cheap lane, not the planning engine.
        assertThat(defaults.ciFix()).isEqualTo("cli:codex");
    }

    @Test
    void updateRoundTripsEveryKind()
    {
        AiDefaultsService service = new AiDefaultsService(new InMemoryStore());

        service.update(new AiDefaults(
                "api:anthropic", "cli:codex", "cli:claude-code", "local", "api:deepseek", "cli:codex"));

        assertThat(service.get()).isEqualTo(new AiDefaults(
                "api:anthropic", "cli:codex", "cli:claude-code", "local", "api:deepseek", "cli:codex"));
    }

    @Test
    void blankNullAndOverlongValuesFallBackInsteadOfPersistingJunk()
    {
        AiDefaultsService service = new AiDefaultsService(new InMemoryStore());

        service.update(new AiDefaults("  ", null, "x".repeat(121), "cli:codex", " api:deepseek ", "local"));

        AiDefaults stored = service.get();
        assertThat(stored.plan()).isEqualTo("cli:claude-code");
        assertThat(stored.dev()).isEqualTo("cli:claude-code");
        assertThat(stored.review()).isEqualTo("cli:claude-code");
        assertThat(stored.ciFix()).isEqualTo("cli:codex");
        // A usable id still round-trips, trimmed.
        assertThat(stored.triage()).isEqualTo("api:deepseek");
        assertThat(stored.perf()).isEqualTo("local");
    }

    private static final class InMemoryStore
            implements AppSettingsStore
    {
        private final Map<String, String> values = new HashMap<>();

        @Override
        public Optional<String> get(String key)
        {
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public void set(String key, String value)
        {
            values.put(key, value);
        }
    }
}

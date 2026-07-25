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
import com.bytequay.app.repository.AppSettingsStore.Key;
import org.springframework.stereotype.Service;

import static java.util.Objects.requireNonNull;

/**
 * Account-level defaults for which engine runs each kind of agent work.
 *
 * <p>A workspace's own settings are the authority for a run; these are
 * what a workspace starts from before anyone overrides it, and what the
 * account-wide roles (issue triage, performance investigation) use since
 * they have no workspace to inherit from.
 *
 * <p>Values are the opaque choice ids the picker already speaks —
 * {@code cli:codex}, {@code cli:claude-code}, {@code api:<provider>},
 * {@code local}. This service does not resolve them; it validates only
 * that a value is non-blank and short enough to be an id, so a renamed
 * or newly-installed engine needs no change here.
 */
@Service
public class AiDefaultsService
{
    /** CLI agents ship as the safe baseline: nothing bills an API key
     *  until the user picks a provider explicitly. */
    static final String DEFAULT_ENGINE = "cli:claude-code";

    /** Red-build loops are the cheap lane, so they start on the other CLI. */
    static final String DEFAULT_CI_FIX_ENGINE = "cli:codex";

    /** An id long enough for any real choice and short enough that a
     *  malformed body can't bloat the settings table. */
    private static final int MAX_ENGINE_ID_LENGTH = 120;

    private final AppSettingsStore settings;

    public AiDefaultsService(AppSettingsStore settings)
    {
        this.settings = requireNonNull(settings, "settings is null");
    }

    public AiDefaults get()
    {
        return new AiDefaults(
                read(Key.AI_DEFAULT_PLAN, DEFAULT_ENGINE),
                read(Key.AI_DEFAULT_DEV, DEFAULT_ENGINE),
                read(Key.AI_DEFAULT_REVIEW, DEFAULT_ENGINE),
                read(Key.AI_DEFAULT_CI_FIX, DEFAULT_CI_FIX_ENGINE),
                read(Key.AI_DEFAULT_TRIAGE, DEFAULT_ENGINE),
                read(Key.AI_DEFAULT_PERF, DEFAULT_ENGINE));
    }

    public AiDefaults update(AiDefaults next)
    {
        requireNonNull(next, "defaults is null");
        settings.set(Key.AI_DEFAULT_PLAN, clean(next.plan(), DEFAULT_ENGINE));
        settings.set(Key.AI_DEFAULT_DEV, clean(next.dev(), DEFAULT_ENGINE));
        settings.set(Key.AI_DEFAULT_REVIEW, clean(next.review(), DEFAULT_ENGINE));
        settings.set(Key.AI_DEFAULT_CI_FIX, clean(next.ciFix(), DEFAULT_CI_FIX_ENGINE));
        settings.set(Key.AI_DEFAULT_TRIAGE, clean(next.triage(), DEFAULT_ENGINE));
        settings.set(Key.AI_DEFAULT_PERF, clean(next.perf(), DEFAULT_ENGINE));
        return get();
    }

    private String read(String key, String fallback)
    {
        return settings.get(key)
                .filter(value -> !value.isBlank())
                .orElse(fallback);
    }

    private static String clean(String value, String fallback)
    {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_ENGINE_ID_LENGTH) {
            return fallback;
        }
        return trimmed;
    }

    /**
     * One engine choice per kind of agent work. {@code plan} / {@code dev} /
     * {@code review} / {@code ciFix} mirror the four workspace session kinds;
     * {@code triage} and {@code perf} are account-wide roles with no
     * workspace equivalent.
     */
    public record AiDefaults(
            String plan,
            String dev,
            String review,
            String ciFix,
            String triage,
            String perf) {}
}

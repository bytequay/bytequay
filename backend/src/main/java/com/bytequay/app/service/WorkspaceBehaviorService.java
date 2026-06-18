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

import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.repository.AppSettingsStore.Key;
import org.springframework.stereotype.Service;

import static java.util.Objects.requireNonNull;

/**
 * Reads and writes the Workspace Settings → Behavior toggles.
 *
 * <p>Single-workspace mode today: all keys live in {@link AppSettingsStore}
 * with the {@code behavior.*} prefix. When multi-workspace creation
 * lands, this service is the natural choke point to swap in a
 * workspace-scoped settings store. The shape of {@link Settings} is
 * the same record the frontend reads/writes, so the controller is a
 * thin pass-through.
 *
 * <p>Persistence only — this service doesn't enforce the toggles.
 * Each consumer (the auto-archive sweeper, the propose-task hook,
 * etc.) reads its own key when it makes a decision so this service
 * stays a single source of truth without coupling the enforcement
 * paths together.
 */
@Service
public class WorkspaceBehaviorService
{
    /** Allowed values for {@code archive_idle_after}. The frontend
     *  toggles render these as a pill group; the values are stable
     *  strings so the column doesn't migrate when copy changes. */
    static final String ARCHIVE_AFTER_1H = "1h";
    static final String ARCHIVE_AFTER_1D = "1d";
    static final String ARCHIVE_AFTER_1W = "1w";
    static final String ARCHIVE_AFTER_NEVER = "never";

    /** Default cadence — auto-archive a thread after a week idle. */
    static final String DEFAULT_ARCHIVE_AFTER = ARCHIVE_AFTER_1W;

    private final AppSettingsStore settings;

    public WorkspaceBehaviorService(AppSettingsStore settings)
    {
        this.settings = requireNonNull(settings, "settings is null");
    }

    public Settings get()
    {
        return new Settings(
                readArchiveAfter(),
                readBool(Key.BEHAVIOR_AUTO_PROPOSE_TASK, true),
                readBool(Key.BEHAVIOR_AUTO_PROMOTE_DECISIONS, false),
                readBool(Key.BEHAVIOR_NEW_TOPIC_NUDGE, true));
    }

    public Settings update(Settings next)
    {
        requireNonNull(next, "settings is null");
        // archive_idle_after is constrained to the four legal values;
        // anything else falls back to the default rather than ending
        // up as junk in the DB.
        String archive = switch (next.archiveIdleAfter()) {
            case ARCHIVE_AFTER_1H, ARCHIVE_AFTER_1D,
                 ARCHIVE_AFTER_1W, ARCHIVE_AFTER_NEVER -> next.archiveIdleAfter();
            default -> DEFAULT_ARCHIVE_AFTER;
        };
        settings.set(Key.BEHAVIOR_ARCHIVE_IDLE_AFTER, archive);
        settings.set(Key.BEHAVIOR_AUTO_PROPOSE_TASK, Boolean.toString(next.autoProposeTask()));
        settings.set(Key.BEHAVIOR_AUTO_PROMOTE_DECISIONS,
                Boolean.toString(next.autoPromoteDecisions()));
        settings.set(Key.BEHAVIOR_NEW_TOPIC_NUDGE, Boolean.toString(next.newTopicNudge()));
        return get();
    }

    private String readArchiveAfter()
    {
        return settings.get(Key.BEHAVIOR_ARCHIVE_IDLE_AFTER).orElse(DEFAULT_ARCHIVE_AFTER);
    }

    private boolean readBool(String key, boolean defaultValue)
    {
        return settings.get(key).map(Boolean::parseBoolean).orElse(defaultValue);
    }

    /** Workspace Behavior section payload. {@code archiveIdleAfter}
     *  is one of {@code 1h / 1d / 1w / never}; the booleans drive
     *  the three feature toggles directly. */
    public record Settings(
            String archiveIdleAfter,
            boolean autoProposeTask,
            boolean autoPromoteDecisions,
            boolean newTopicNudge) {}
}

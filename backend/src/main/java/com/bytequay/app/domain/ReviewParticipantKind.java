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
package com.bytequay.app.domain;

import java.util.Locale;

/**
 * Role a {@link ReviewParticipant} plays on the panel.
 *
 * <ul>
 *   <li>{@link #MODERATOR} — the deterministic spine; no credential.
 *       Posts system-voice messages announcing phase transitions and
 *       summarising consensus.</li>
 *   <li>{@link #REVIEWER} — a credential tagged {@code review} in the
 *       AI settings. Phase 1 ships with exactly one; Phase 2 onwards
 *       admits 2+ for cross-review and debate.</li>
 *   <li>{@link #HUMAN} — the orchestrator + final arbiter. Right-
 *       aligned bubble; used for the user's posts in the panel UI.</li>
 * </ul>
 */
public enum ReviewParticipantKind
{
    MODERATOR("moderator"),
    REVIEWER("reviewer"),
    HUMAN("human");

    private final String dbValue;

    ReviewParticipantKind(String dbValue)
    {
        this.dbValue = dbValue;
    }

    public String dbValue()
    {
        return dbValue;
    }

    public static ReviewParticipantKind fromDbValue(String raw)
    {
        if (raw == null || raw.isBlank()) {
            return REVIEWER;
        }
        String normalised = raw.toLowerCase(Locale.ROOT);
        for (ReviewParticipantKind value : values()) {
            if (value.dbValue.equals(normalised)) {
                return value;
            }
        }
        return REVIEWER;
    }
}

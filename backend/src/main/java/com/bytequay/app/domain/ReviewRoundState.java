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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/** Durable lifecycle state of a task-owned response round. */
public enum ReviewRoundState
{
    TRIAGING("triaging"),
    ADDRESSING("addressing"),
    AWAITING_GATE("awaiting_gate"),
    POSTED("posted"),
    CLOSED("closed"),
    PAUSED("paused");

    private final String dbValue;

    ReviewRoundState(String dbValue)
    {
        this.dbValue = dbValue;
    }

    /** Lower-case value shared by SQLite and the frontend JSON contract. */
    @JsonValue
    public String dbValue()
    {
        return dbValue;
    }

    /** Parse the historical lower-case storage representation. Persisted
     *  corruption is rejected rather than silently reopening work. */
    @JsonCreator
    public static ReviewRoundState fromDbValue(String raw)
    {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("review round state is blank");
        }
        String normalized = raw.toLowerCase(Locale.ROOT);
        for (ReviewRoundState value : values()) {
            if (value.dbValue.equals(normalized)) {
                return value;
            }
        }
        throw new IllegalArgumentException("unknown review round state: " + raw);
    }

    /** True while the coordinator has driveable work for this round. */
    public boolean isLive()
    {
        return this == TRIAGING || this == ADDRESSING
                || this == AWAITING_GATE;
    }
}

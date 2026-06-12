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
 * Status of one {@link AgendaPhase}. The JSON storage value (the
 * {@code agenda_json} column) is the lowercase form; the wire value
 * to the frontend is the enum name, matching every other review enum.
 */
public enum AgendaPhaseStatus
{
    OPEN("open"),
    IN_PROGRESS("in_progress"),
    DONE("done");

    private final String jsonValue;

    AgendaPhaseStatus(String jsonValue)
    {
        this.jsonValue = jsonValue;
    }

    public String jsonValue()
    {
        return jsonValue;
    }

    public static AgendaPhaseStatus fromJsonValue(String raw)
    {
        if (raw == null || raw.isBlank()) {
            return OPEN;
        }
        String normalised = raw.toLowerCase(Locale.ROOT);
        for (AgendaPhaseStatus value : values()) {
            if (value.jsonValue.equals(normalised)) {
                return value;
            }
        }
        return OPEN;
    }
}

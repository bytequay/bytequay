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
 * How important a {@link ReviewFinding} is. Drives the severity
 * chip colour in the panel UI and lets the publish step batch the
 * blockers / majors separately from the nits.
 */
public enum ReviewFindingSeverity
{
    BLOCKER("blocker"),
    MAJOR("major"),
    NIT("nit"),
    QUESTION("question");

    private final String dbValue;

    ReviewFindingSeverity(String dbValue)
    {
        this.dbValue = dbValue;
    }

    public String dbValue()
    {
        return dbValue;
    }

    public static ReviewFindingSeverity fromDbValue(String raw)
    {
        if (raw == null || raw.isBlank()) {
            return MAJOR;
        }
        String normalised = raw.toLowerCase(Locale.ROOT);
        for (ReviewFindingSeverity value : values()) {
            if (value.dbValue.equals(normalised)) {
                return value;
            }
        }
        return MAJOR;
    }
}

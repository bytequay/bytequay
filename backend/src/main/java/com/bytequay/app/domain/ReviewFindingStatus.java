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
 * Lifecycle of a {@link ReviewFinding}. A finding flows
 * {@code AGREED|DISPUTED} → ({@code RESOLVED|ARBITRATED|DROPPED}) →
 * {@code POSTED} once the user publishes it as a PR comment.
 *
 * <p>Phase 1 only writes {@code AGREED} (single reviewer means no
 * disputes) and {@code POSTED}; the rest of the lifecycle is for the
 * multi-reviewer + arbitration flows that ship in later commits.
 */
public enum ReviewFindingStatus
{
    /** Raised by a reviewer seat ({@code report_finding}) and not yet
     *  classified by the lead's consensus pass. Counts as "open" for
     *  the arbitration gate: a pass with REPORTED findings left parks
     *  at ARBITRATE the same way DISPUTED ones do. */
    REPORTED("reported"),
    AGREED("agreed"),
    DISPUTED("disputed"),
    RESOLVED("resolved"),
    ARBITRATED("arbitrated"),
    DROPPED("dropped"),
    POSTED("posted");

    private final String dbValue;

    ReviewFindingStatus(String dbValue)
    {
        this.dbValue = dbValue;
    }

    public String dbValue()
    {
        return dbValue;
    }

    public static ReviewFindingStatus fromDbValue(String raw)
    {
        if (raw == null || raw.isBlank()) {
            return AGREED;
        }
        String normalised = raw.toLowerCase(Locale.ROOT);
        for (ReviewFindingStatus value : values()) {
            if (value.dbValue.equals(normalised)) {
                return value;
            }
        }
        return AGREED;
    }
}

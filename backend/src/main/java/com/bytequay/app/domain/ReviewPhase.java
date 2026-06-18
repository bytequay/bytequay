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
 * Phase machine for a {@link ReviewPass}. The deterministic
 * ReviewModerator drives the transitions:
 *
 * <pre>
 * KICKOFF → INDEPENDENT → CROSS_REVIEW → CONSENSUS
 *            → DEBATE (loop, bounded) → TERMINATE → ARBITRATE → PUBLISHED
 * </pre>
 *
 * <p>Phase 1 of the review build only exercises KICKOFF → INDEPENDENT
 * → TERMINATE — the cross-review / consensus / debate / arbitrate
 * states light up when the multi-reviewer phases land. The enum is
 * defined wholesale so the schema doesn't need to grow as later
 * phases ship.
 */
public enum ReviewPhase
{
    KICKOFF("kickoff"),
    INDEPENDENT("independent"),
    CROSS_REVIEW("cross_review"),
    CONSENSUS("consensus"),
    DEBATE("debate"),
    TERMINATE("terminate"),
    ARBITRATE("arbitrate"),
    PUBLISHED("published"),
    /** Terminal state the human sets by hand via "Mark as completed" —
     *  the review is done without posting to GitHub. Distinct from
     *  PUBLISHED (posted) and from the automatic TERMINATE (wrap-up).
     *  Still resumable: {@code resumePass} re-runs the pipeline. */
    COMPLETED("completed");

    private final String dbValue;

    ReviewPhase(String dbValue)
    {
        this.dbValue = dbValue;
    }

    public String dbValue()
    {
        return dbValue;
    }

    /** Parse a TEXT column value back into the enum. Null / blank /
     *  unknown all map to {@link #KICKOFF} so a row predating any
     *  later additive value still reads cleanly. */
    public static ReviewPhase fromDbValue(String raw)
    {
        if (raw == null || raw.isBlank()) {
            return KICKOFF;
        }
        String normalised = raw.toLowerCase(Locale.ROOT);
        for (ReviewPhase value : values()) {
            if (value.dbValue.equals(normalised)) {
                return value;
            }
        }
        return KICKOFF;
    }
}

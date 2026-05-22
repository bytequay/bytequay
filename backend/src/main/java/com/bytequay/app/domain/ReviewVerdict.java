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
 * Suggested GitHub review verdict. {@link ReviewPass#verdict()} is
 * null while the panel is still deciding; once filled in, the publish
 * step proposes it to the user who confirms before anything posts
 * (per the design row "publish is gated").
 *
 * <p>{@link #COMMENT} maps to a plain "comment" review on GitHub —
 * no approval, no change-request — for the case where the panel
 * surfaced findings but didn't reach a stronger verdict.
 */
public enum ReviewVerdict
{
    APPROVE("approve"),
    REQUEST_CHANGES("request_changes"),
    COMMENT("comment");

    private final String dbValue;

    ReviewVerdict(String dbValue)
    {
        this.dbValue = dbValue;
    }

    public String dbValue()
    {
        return dbValue;
    }

    /** {@code null} when the column is null or blank — that's the
     *  "not yet decided" state and shouldn't surface as a default
     *  verdict. */
    public static ReviewVerdict fromDbValue(String raw)
    {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalised = raw.toLowerCase(Locale.ROOT);
        for (ReviewVerdict value : values()) {
            if (value.dbValue.equals(normalised)) {
                return value;
            }
        }
        return null;
    }
}

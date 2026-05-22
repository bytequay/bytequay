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

/**
 * Structural discriminator on a {@link Thread}: a build thread runs
 * an agent that's writing code; a review thread runs the multi-agent
 * review panel against a PR. Set at creation, never silently
 * flipped — see V74's {@code threads.flow} column and the
 * workspace/thread/task design doc.
 *
 * <p>The DB column stores lowercase string literals ({@code "build"}
 * / {@code "review"}) to match the migration default; the enum
 * carries the mapping so callers can switch on a typed value rather
 * than string-compare.
 */
public enum ThreadFlow
{
    /** The default. Agent writes code, opens PRs, hands off to
     *  ship-and-continue. Every thread created before review semantics
     *  ship is BUILD. */
    BUILD("build"),

    /** Multi-agent review panel against a target PR. Lands with
     *  Phase 8's review-thread design; structurally allowed today so
     *  the column doesn't sit dark. */
    REVIEW("review");

    private final String dbValue;

    ThreadFlow(String dbValue)
    {
        this.dbValue = dbValue;
    }

    /** Lowercase token the migration default uses. */
    public String dbValue()
    {
        return dbValue;
    }

    /** Inverse of {@link #dbValue}. Null / blank falls back to
     *  {@link #BUILD} so a row predating V74 reads as the migration
     *  default. */
    public static ThreadFlow fromDbValue(String v)
    {
        if (v == null || v.isBlank()) {
            return BUILD;
        }
        return switch (v) {
            case "build" -> BUILD;
            case "review" -> REVIEW;
            default -> throw new IllegalArgumentException("unknown thread flow: " + v);
        };
    }
}

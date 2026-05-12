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

import java.time.Instant;

/**
 * A single review submission: who reviewed, what state they left (APPROVED,
 * CHANGES_REQUESTED, etc.), and when they submitted it.
 *
 * <p>{@code submittedAt} may be null for cached reviews captured before
 * V53 added the column — older rows simply don't carry a timestamp.
 * Callers that need to filter by time window must treat null as
 * "unknown" and skip rather than include.
 */
public record PrReviewState(String login, String state, Instant submittedAt)
{
    /** Convenience for call sites (mostly tests) that don't care about
     *  the submission timestamp. Equivalent to passing null. */
    public PrReviewState(String login, String state)
    {
        this(login, state, null);
    }
}

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
 * Pure local dashboard-triage state for a {@link PR} — viewed / handled /
 * snoozed. Deliberately its own row rather than fields on {@code PR}: a
 * bulk {@code syncList} sweep overwrites {@link PR.PRSyncSnapshot} wholesale,
 * and must never be able to touch this table even by accident. Absent
 * (no row) for a PR the user has never interacted with from the dashboard.
 */
public record PRTriageState(
        String prId,
        Instant viewedAt,
        Instant reviewedAt,
        HandledAction handledAction,
        Instant snoozedUntil,
        Instant snoozedAt,
        String snoozeWakeReason)
{
    /** The empty state for a PR that has never been touched — never
     *  persisted itself; a save call is what first creates the row. */
    public static PRTriageState empty(String prId)
    {
        return new PRTriageState(prId, null, null, null, null, null, null);
    }
}

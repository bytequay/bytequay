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
 * Local state the app tracks per pull request — entirely client-side, never sent to GitHub.
 *
 * @param snoozedUntil set while the PR is snoozed; null means no time-based
 * snooze.
 * @param snoozedAt when the snooze was set.
 * @param snoozeWakeReason populated when an auto-wake fires; cleared after the
 * user acknowledges the alert.
 */
public record PrViewState(
        long prId,
        Instant viewedAt,
        Instant snoozedUntil,
        Instant snoozedAt,
        String snoozeWakeReason,
        Instant reviewedAt,
        HandledAction handledAction)
{
    public static PrViewState viewed(long prId, Instant viewedAt)
    {
        return new PrViewState(prId, viewedAt, null, null, null, null, null);
    }
}

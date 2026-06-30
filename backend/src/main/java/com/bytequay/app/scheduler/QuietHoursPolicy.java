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
package com.bytequay.app.scheduler;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalTime;

import static java.util.Objects.requireNonNull;

/**
 * Nightly window during which the app's scheduled GitHub pollers pause to
 * conserve the 5,000/hr REST rate limit while the user is asleep.
 *
 * <p>This gates only the {@code @Scheduled} background jobs (the per-minute
 * PR sync, the home-cache refresh, the optional review sweep). It does
 * <strong>not</strong> touch anything the user initiates — opening a PR,
 * posting a comment, a manual sync, or running a task all hit GitHub on
 * demand regardless of the hour.
 *
 * <p>The window is evaluated in the JVM's local time zone, which on this
 * locally-spawned sidecar is the user's own time zone.
 */
@Component
public class QuietHoursPolicy
{
    /** Inclusive start of the quiet window (local time). */
    private static final LocalTime QUIET_START = LocalTime.of(1, 0);
    /** Exclusive end of the quiet window (local time). */
    private static final LocalTime QUIET_END = LocalTime.of(7, 30);

    private final Clock clock;

    public QuietHoursPolicy()
    {
        this(Clock.systemDefaultZone());
    }

    QuietHoursPolicy(Clock clock)
    {
        this.clock = requireNonNull(clock, "clock is null");
    }

    /** True when the current local time falls inside the quiet window, so
     *  scheduled GitHub polling should skip this tick. */
    public boolean isQuietNow()
    {
        LocalTime now = LocalTime.now(clock);
        if (QUIET_START.isBefore(QUIET_END)) {
            // Same-day window (e.g. 01:00–07:30).
            return !now.isBefore(QUIET_START) && now.isBefore(QUIET_END);
        }
        // Window wraps past midnight (e.g. 23:00–06:00).
        return !now.isBefore(QUIET_START) || now.isBefore(QUIET_END);
    }
}

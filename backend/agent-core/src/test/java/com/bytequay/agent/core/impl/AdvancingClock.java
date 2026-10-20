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
package com.bytequay.agent.core.impl;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

final class AdvancingClock
        extends Clock
{
    private Instant instant;

    AdvancingClock(Instant instant)
    {
        this.instant = instant;
    }

    @Override
    public ZoneOffset getZone()
    {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone)
    {
        return this;
    }

    @Override
    public synchronized Instant instant()
    {
        Instant current = instant;
        instant = instant.plusMillis(1);
        return current;
    }

    synchronized void advance(Duration duration)
    {
        instant = instant.plus(duration);
    }
}

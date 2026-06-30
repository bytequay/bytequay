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

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class TestQuietHoursPolicy
{
    private static final ZoneId ZONE = ZoneId.of("UTC");

    private static QuietHoursPolicy at(LocalTime time)
    {
        Instant instant = time.atDate(LocalDate.of(2026, 6, 30)).atZone(ZONE).toInstant();
        return new QuietHoursPolicy(Clock.fixed(instant, ZONE));
    }

    @Test
    void quietInsideTheWindow()
    {
        assertThat(at(LocalTime.of(3, 0)).isQuietNow()).isTrue();
        assertThat(at(LocalTime.of(1, 0)).isQuietNow()).isTrue();   // inclusive start
        assertThat(at(LocalTime.of(7, 29)).isQuietNow()).isTrue();
    }

    @Test
    void awakeOutsideTheWindow()
    {
        assertThat(at(LocalTime.of(7, 30)).isQuietNow()).isFalse(); // exclusive end
        assertThat(at(LocalTime.of(0, 59)).isQuietNow()).isFalse();
        assertThat(at(LocalTime.of(12, 0)).isQuietNow()).isFalse();
        assertThat(at(LocalTime.of(23, 0)).isQuietNow()).isFalse();
    }
}

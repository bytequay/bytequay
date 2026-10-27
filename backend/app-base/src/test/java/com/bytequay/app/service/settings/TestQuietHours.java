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
package com.bytequay.app.service.settings;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestQuietHours
{
    @Test
    void disabledWindowIsNeverQuiet()
    {
        QuietHours hours = new QuietHours(false, 60, 450);
        assertThat(hours.quietAt(LocalTime.of(2, 0))).isFalse();
    }

    @Test
    void sameDayWindowIsHalfOpen()
    {
        QuietHours hours = new QuietHours(true, 60, 450);
        assertThat(hours.quietAt(LocalTime.of(0, 59))).isFalse();
        assertThat(hours.quietAt(LocalTime.of(1, 0))).isTrue();
        assertThat(hours.quietAt(LocalTime.of(7, 29))).isTrue();
        assertThat(hours.quietAt(LocalTime.of(7, 30))).isFalse();
    }

    @Test
    void windowMayWrapMidnight()
    {
        QuietHours hours = new QuietHours(true, 22 * 60, 6 * 60);
        assertThat(hours.quietAt(LocalTime.of(23, 0))).isTrue();
        assertThat(hours.quietAt(LocalTime.of(3, 0))).isTrue();
        assertThat(hours.quietAt(LocalTime.of(12, 0))).isFalse();
    }

    @Test
    void emptyWindowIsNeverQuiet()
    {
        assertThat(new QuietHours(true, 120, 120).quietAt(LocalTime.of(2, 0))).isFalse();
    }

    @Test
    void minutesAreRangeChecked()
    {
        assertThatThrownBy(() -> new QuietHours(true, 1440, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuietHours(true, 0, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

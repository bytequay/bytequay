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

import java.time.LocalTime;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * Daily window, in local minutes of day, during which scheduled GitHub
 * refresh jobs stay idle. The window may wrap midnight; an empty window
 * (equal start and end) never pauses anything.
 */
public record QuietHours(boolean enabled, int startMinute, int endMinute)
{
    public static final QuietHours DEFAULT = new QuietHours(false, 60, 450);

    public QuietHours
    {
        checkArgument(startMinute >= 0 && startMinute < 1440, "startMinute is out of range");
        checkArgument(endMinute >= 0 && endMinute < 1440, "endMinute is out of range");
    }

    public boolean quietAt(LocalTime time)
    {
        if (!enabled || startMinute == endMinute) {
            return false;
        }
        int minute = time.getHour() * 60 + time.getMinute();
        if (startMinute < endMinute) {
            return minute >= startMinute && minute < endMinute;
        }
        return minute >= startMinute || minute < endMinute;
    }
}

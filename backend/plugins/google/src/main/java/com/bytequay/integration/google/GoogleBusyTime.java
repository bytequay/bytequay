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
package com.bytequay.integration.google;

import static java.util.Objects.requireNonNull;

/**
 * When a calendar event holds the user's time, and nothing else about it.
 *
 * @param start an RFC 3339 time, or an ISO date for an all-day event
 * @param end the same, exclusive
 */
public record GoogleBusyTime(String start, String end, boolean allDay)
{
    public GoogleBusyTime
    {
        requireNonNull(start, "start is null");
        requireNonNull(end, "end is null");
    }
}

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
package com.bytequay.app.service.recent;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/**
 * Reads the local record of what the user worked on. Every method answers from
 * SQLite alone: a failed GitHub refresh degrades {@link #changes()} and never
 * blanks the stream.
 */
public interface RecentService
{
    /**
     * How long a detail must stay open before reading it counts. Empirical, and
     * deliberately the only place the number appears.
     */
    Duration DWELL_THRESHOLD = Duration.ofSeconds(20);

    /** How far back the record reaches. Rolling, local, and clearable. */
    int RETENTION_DAYS = 14;

    /**
     * One day of the stream.
     *
     * @param cursor the day to start at, or null for the newest day in the window.
     */
    RecentDayPage stream(RecentFilter filter, LocalDate cursor);

    /** Items mode: fuzzy over title, number, repository and the user's own text. */
    List<RecentRow> search(String query, int limit);

    /** Words mode: full-text over the user's own writing alone. */
    List<RecentWordHit> words(String query, int limit);

    /** What moved on recently-touched items since the user last looked. */
    List<RecentChange> changes();

    /** Records a touch only the renderer can observe. */
    void recordViewerTouch(ViewerTouch touch);

    /** Stores or clears the composer's unsent text for one item. */
    void recordDraft(RecentDraft draft);

    /** Clears the whole record, stream and Words index together. */
    void clear();

    /** Removes one row. Returns false when it was already gone. */
    boolean clear(String touchId);

    /** Drops everything outside the retention window. */
    int sweep();

    /** The oldest day the record still keeps, so the calendar can grey the rest. */
    LocalDate windowStart();
}

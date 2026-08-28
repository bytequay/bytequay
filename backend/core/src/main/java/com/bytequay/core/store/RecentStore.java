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
package com.bytequay.core.store;

import com.bytequay.core.domain.RecentText;
import com.bytequay.core.domain.RecentTouch;

import java.time.LocalDate;
import java.util.List;

/**
 * Local-only storage for the Recent stream. Nothing here contacts GitHub, so
 * every read answers with the network off.
 */
public interface RecentStore
{
    /**
     * Upserts one day's row for an item, applying the same-day dedupe rule.
     */
    void recordTouch(RecentTouch touch);

    /**
     * Rows touched on the given inclusive day range, newest touch first.
     */
    List<RecentTouch> findTouches(LocalDate from, LocalDate to);

    /**
     * Upserts one piece of text the user wrote, replacing any earlier body under the same id.
     */
    void saveText(RecentText text);

    /**
     * Everything the user wrote on one item, newest first.
     */
    List<RecentText> findTextByItemKey(String itemKey);

    /**
     * Removes one indexed body, for a draft the user emptied or posted.
     */
    void deleteText(String textId);

    /**
     * Bodies containing the given already-lowercased fragment, newest first.
     * Tokenising a query and ranking the hits is the service's job; this is the
     * one narrowing the database can do with an index.
     */
    List<RecentText> findTextContaining(String fragment, int limit);

    /**
     * Drops every touch and every indexed body on days before the cutoff.
     */
    int deleteBefore(LocalDate cutoffExclusive);

    /**
     * Clears the whole record, stream and Words index together.
     */
    void deleteAll();

    /**
     * Removes one row from the stream. Returns false when it was already gone.
     */
    boolean deleteTouch(String touchId);
}

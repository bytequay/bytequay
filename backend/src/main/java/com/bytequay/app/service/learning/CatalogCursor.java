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
package com.bytequay.app.service.learning;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Persisted cursor for historical merged-PR enumeration. GitHub search
 * returns at most 1,000 results per query, so the enumerator partitions the
 * complete merged history by merge-date window and records the page reached
 * within each window. A restart reloads this cursor and resumes the first
 * unfinished window at its saved page — it never restarts from page one.
 *
 * <p>Windows are inclusive day ranges ({@code YYYY-MM-DD}). A window whose
 * server-reported total still exceeds 1,000 is subdivided by its midpoint
 * date until each window fits under the cap.
 */
public record CatalogCursor(List<Partition> partitions)
{
    @JsonCreator
    public CatalogCursor(@JsonProperty("partitions") List<Partition> partitions)
    {
        this.partitions = partitions == null ? List.of() : List.copyOf(partitions);
    }

    /** A single merge-date window and the next page to fetch within it. */
    public record Partition(String from, String to, int nextPage, boolean exhausted)
    {
        @JsonCreator
        public Partition(
                @JsonProperty("from") String from,
                @JsonProperty("to") String to,
                @JsonProperty("nextPage") int nextPage,
                @JsonProperty("exhausted") boolean exhausted)
        {
            this.from = from;
            this.to = to;
            this.nextPage = nextPage;
            this.exhausted = exhausted;
        }

        public boolean singleDay()
        {
            return from.equals(to);
        }
    }

    /** Index of the first window still needing work, or -1 when complete. */
    public int firstPending()
    {
        for (int i = 0; i < partitions.size(); i++) {
            if (!partitions.get(i).exhausted()) {
                return i;
            }
        }
        return -1;
    }

    public boolean complete()
    {
        return firstPending() < 0;
    }

    /** Returns a copy with the window at {@code index} replaced. */
    public CatalogCursor replace(int index, Partition... replacements)
    {
        List<Partition> next = new ArrayList<>(partitions);
        next.remove(index);
        for (int i = 0; i < replacements.length; i++) {
            next.add(index + i, replacements[i]);
        }
        return new CatalogCursor(next);
    }
}

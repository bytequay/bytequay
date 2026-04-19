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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Backend mirror of the frontend's MyPrColumn type. The team kanban
 * categorizes PRs server-side now so we can paginate per column at the
 * API layer — see TeamPullCategorizer for the rules.
 *
 * <p>Slugs match the frontend's lowercase-snake-case literal type so the
 * JSON wire shape needs no remapping.
 */
public enum MyPrColumn
{
    DRAFTING("drafting"),
    WAITING_ON_REVIEW("waiting_on_review"),
    NEEDS_CHANGES("needs_changes"),
    READY_TO_MERGE("ready_to_merge"),
    RECENTLY_MERGED("recently_merged"),
    /** Catch-all for PRs the user marked handled (MERGED/DISMISSED/
     *  MANUAL via mark-handled). Sits to the right of the active
     *  columns so dismissed cards have somewhere to live and can be
     *  reopened from. Only the team kanban renders this column —
     *  inbox separates handled PRs into a top-level Handled tab. */
    HANDLED("handled");

    private final String slug;

    MyPrColumn(String slug)
    {
        this.slug = slug;
    }

    @JsonValue
    public String slug()
    {
        return slug;
    }

    @JsonCreator
    public static MyPrColumn fromSlug(String slug)
    {
        for (MyPrColumn c : values()) {
            if (c.slug.equals(slug)) {
                return c;
            }
        }
        throw new IllegalArgumentException("Unknown my-pr column: " + slug);
    }
}

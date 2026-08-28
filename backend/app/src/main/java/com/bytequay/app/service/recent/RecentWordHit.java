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

import com.bytequay.core.domain.RecentItemType;
import com.bytequay.core.domain.RecentTextKind;
import com.bytequay.core.domain.RecentWordMatch;
import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * One Words-mode hit. The sentence is the headline, so it ships with the
 * offsets that produced it rather than making the renderer re-find the term.
 *
 * @param body the whole passage, because the mode's primary key copies rather
 * than navigates.
 * @param neverPosted a draft the user wrote and never sent — the reply they
 * could not find again.
 */
public record RecentWordHit(
        String id,
        RecentItemType type,
        String itemKey,
        RecentTextKind kind,
        String kindLabel,
        boolean neverPosted,
        String sentence,
        List<RecentWordMatch.MatchRange> highlights,
        String body,
        Integer number,
        String repo,
        String title,
        String htmlUrl,
        Instant writtenAt)
{
    public RecentWordHit
    {
        requireNonNull(id, "id is null");
        highlights = ImmutableList.copyOf(requireNonNull(highlights, "highlights is null"));
    }
}

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
package com.bytequay.core.domain;

import com.google.common.collect.ImmutableList;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * One Words-mode hit: the sentence the term appears in, plus the offsets within
 * that sentence so the renderer can highlight without re-running the match.
 */
public record RecentWordMatch(RecentText source, String sentence, List<MatchRange> highlights)
{
    public RecentWordMatch
    {
        requireNonNull(source, "source is null");
        requireNonNull(sentence, "sentence is null");
        highlights = ImmutableList.copyOf(requireNonNull(highlights, "highlights is null"));
    }

    public record MatchRange(int start, int end) {}
}

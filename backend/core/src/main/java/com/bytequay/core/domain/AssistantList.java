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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * What a list in the assistant's conversation shows: the style the assistant picked first, and its refs in its order. It keeps no row
 * text; the panel reads each ref's row from ByteQuay's own data whenever it draws the day, so the list stays current and a title stays
 * data.
 */
public record AssistantList(Style style, List<String> refs)
{
    public static final int MAXIMUM_REFS = 50;

    public enum Style
    {
        ROWS, CARDS, TIMELINE
    }

    public AssistantList
    {
        requireNonNull(style, "style is null");
        refs = ImmutableList.copyOf(refs);
        checkArgument(!refs.isEmpty() && refs.size() <= MAXIMUM_REFS, "A list shows 1 to %s refs", MAXIMUM_REFS);
    }
}

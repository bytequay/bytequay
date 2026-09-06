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
package com.bytequay.agent.core;

import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.OptionalLong;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** A bounded ascending event window with adjacent byte cursors and its live sequence watermark. */
public record AgentHistoryPage(
        List<AgentEventEnvelope> events,
        OptionalLong beforeCursor,
        OptionalLong afterCursor,
        long latestSequence)
{
    public AgentHistoryPage
    {
        events = ImmutableList.copyOf(events);
        requireNonNull(beforeCursor, "beforeCursor is null");
        requireNonNull(afterCursor, "afterCursor is null");
        checkArgument(latestSequence >= 0, "latestSequence is negative");
        checkArgument(beforeCursor.isEmpty() || beforeCursor.orElseThrow() >= 0, "beforeCursor is negative");
        checkArgument(afterCursor.isEmpty() || afterCursor.orElseThrow() >= 0, "afterCursor is negative");
    }
}

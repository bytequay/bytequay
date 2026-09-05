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

import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.Math.addExact;

/**
 * What a turn did with tools, folded from its persisted events when it ended.
 *
 * <p>{@code toolCalls} counts every finished tool or command activity, {@code exploreActions} the
 * subset that read, listed, or searched source, and the {@code codegraph} counters the CodeGraph
 * tool calls with their summed duration, including the first call's index preparation, and how
 * many failed. Task totals are the sum over its turns.
 */
public record ToolCounters(long toolCalls, long exploreActions, long codegraphCalls, long codegraphMillis, long codegraphFailures)
{
    public static final ToolCounters ZERO = new ToolCounters(0, 0, 0, 0, 0);

    public ToolCounters
    {
        checkArgument(toolCalls >= 0 && exploreActions >= 0 && codegraphCalls >= 0 && codegraphMillis >= 0 && codegraphFailures >= 0, "counters must not be negative");
    }

    public ToolCounters plus(ToolCounters other)
    {
        return new ToolCounters(
                addExact(toolCalls, other.toolCalls),
                addExact(exploreActions, other.exploreActions),
                addExact(codegraphCalls, other.codegraphCalls),
                addExact(codegraphMillis, other.codegraphMillis),
                addExact(codegraphFailures, other.codegraphFailures));
    }
}

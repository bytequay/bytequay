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
package com.bytequay.agent.spi;

import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * The optional task-bound CodeGraph tool a provider session exposes to its root agent.
 *
 * <p>{@code explore} answers one bounded query for the task's own worktree and returns tool text
 * even when the graph is unavailable, so a provider adapter never sees a graph failure. The
 * instruction is ByteQuay's managed guidance for the provider's system or developer channel.
 */
public record CodeGraphBridge(Function<String, String> explore, String instruction)
{
    public static final String TOOL_NAME = "codegraph_explore";
    public static final int MAXIMUM_QUERY_LENGTH = 2000;

    public CodeGraphBridge
    {
        requireNonNull(explore, "explore is null");
        requireNonNull(instruction, "instruction is null");
        checkArgument(!instruction.isBlank(), "instruction is blank");
    }
}

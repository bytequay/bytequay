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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** A read-only code graph query result; the symbols are empty unless the status is {@link Status#READY}. */
public record CodeSymbolResult(Status status, List<CodeSymbol> symbols)
{
    public static final CodeSymbolResult BUSY = new CodeSymbolResult(Status.BUSY, ImmutableList.of());
    public static final CodeSymbolResult UPDATING = new CodeSymbolResult(Status.UPDATING, ImmutableList.of());
    public static final CodeSymbolResult UNAVAILABLE = new CodeSymbolResult(Status.UNAVAILABLE, ImmutableList.of());

    public enum Status
    {
        /** The index was current and the query ran. */
        READY,
        /** Another graph operation held the task's index. */
        BUSY,
        /** The index needs a rebuild, which was queued in the background. */
        UPDATING,
        /** No helper, no usable working copy, or the query failed. */
        UNAVAILABLE
    }

    public CodeSymbolResult
    {
        requireNonNull(status, "status is null");
        symbols = ImmutableList.copyOf(symbols);
        checkArgument(status == Status.READY || symbols.isEmpty(), "only a READY result carries symbols");
    }

    public static CodeSymbolResult ready(List<CodeSymbol> symbols)
    {
        return new CodeSymbolResult(Status.READY, symbols);
    }
}

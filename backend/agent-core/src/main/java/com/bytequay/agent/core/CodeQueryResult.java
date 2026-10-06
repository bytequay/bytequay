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
import java.util.Optional;
import java.util.OptionalInt;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** A bounded provider answer. READY means the provider answered, not that indexing or compilation is complete. */
public record CodeQueryResult(Status status, List<Item> items, boolean truncated, String message)
{
    public CodeQueryResult
    {
        requireNonNull(status, "status is null");
        items = ImmutableList.copyOf(items);
        requireNonNull(message, "message is null");
        checkArgument(items.size() <= 200 && message.length() <= 4000, "code query result is too large");
        checkArgument(status == Status.READY || items.isEmpty(), "unavailable query contains results");
    }

    public static CodeQueryResult unavailable(Status status, String message)
    {
        return new CodeQueryResult(status, ImmutableList.of(), false, message);
    }

    public enum Status
    {
        READY, UPDATING, UNSUPPORTED, UNAVAILABLE, CANCELLED, STALE, UNSAVED
    }

    public record Location(String path, int line, int column, int endLine, int endColumn)
    {
        public Location
        {
            CodeQuery.checkPath(path);
            checkArgument(line > 0 && column > 0 && endLine >= line && endColumn > 0 && (endLine != line || endColumn >= column), "invalid source range");
        }
    }

    public record Item(String path, int line, int column, int endLine, int endColumn, Optional<String> name, OptionalInt kind,
            Optional<String> message, OptionalInt severity, List<Location> calls)
    {
        public Item
        {
            new Location(path, line, column, endLine, endColumn);
            requireNonNull(name, "name is null");
            requireNonNull(kind, "kind is null");
            requireNonNull(message, "message is null");
            requireNonNull(severity, "severity is null");
            calls = ImmutableList.copyOf(calls);
            checkArgument(calls.size() <= 40, "too many call locations");
            name.ifPresent(value -> checkArgument(value.length() <= 2000, "symbol name is too long"));
            message.ifPresent(value -> checkArgument(value.length() <= 2000, "diagnostic is too long"));
        }
    }
}

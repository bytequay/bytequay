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

import com.google.common.base.Splitter;

import java.util.Optional;
import java.util.OptionalInt;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** A fixed semantic operation in the caller's task; positions are one-based UTF-16 columns. */
public record CodeQuery(Operation operation, String path, Optional<String> query, OptionalInt line, OptionalInt column, int limit)
{
    public CodeQuery
    {
        requireNonNull(operation, "operation is null");
        checkPath(path);
        requireNonNull(query, "query is null");
        requireNonNull(line, "line is null");
        requireNonNull(column, "column is null");
        checkArgument(limit >= 1 && limit <= 200, "limit must be between 1 and 200");
        query.ifPresent(value -> checkArgument(value.length() <= 200, "symbol query is too long"));
        line.ifPresent(value -> checkArgument(value > 0, "line must be positive"));
        column.ifPresent(value -> checkArgument(value > 0, "column must be positive"));
        if (operation == Operation.SYMBOLS) {
            checkArgument(query.filter(value -> !value.isBlank()).isPresent(), "a symbol query is required");
        }
        if (operation != Operation.SYMBOLS && operation != Operation.DOCUMENT_SYMBOLS && operation != Operation.DIAGNOSTICS) {
            checkArgument(line.isPresent() && column.isPresent(), "a source position is required");
        }
    }

    public static void checkPath(String path)
    {
        requireNonNull(path, "path is null");
        checkArgument(!path.isEmpty() && path.length() <= 4096 && path.chars().noneMatch(value -> value < 32 || value == '\\'), "invalid workspace path");
        for (String part : Splitter.on('/').split(path)) {
            checkArgument(!part.isEmpty() && !part.equals(".") && !part.equals("..") && !part.equalsIgnoreCase(".git"), "invalid workspace path");
        }
    }

    public enum Operation
    {
        SYMBOLS, DOCUMENT_SYMBOLS, DEFINITIONS, REFERENCES, IMPLEMENTATIONS, INCOMING_CALLS, OUTGOING_CALLS, DIAGNOSTICS
    }
}

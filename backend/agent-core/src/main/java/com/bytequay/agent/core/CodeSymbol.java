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

import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * One structural symbol from a task's code graph.
 *
 * @param path repository-relative path of the file that declares the symbol
 * @param startLine 1-based first line, including leading annotations
 * @param startColumn 0-based column on {@code startLine}
 * @param endLine 1-based last line
 */
public record CodeSymbol(String name, String kind, Optional<String> qualifiedName, String path, int startLine, int startColumn, int endLine, Optional<String> signature)
{
    public CodeSymbol
    {
        requireNonNull(name, "name is null");
        requireNonNull(kind, "kind is null");
        requireNonNull(qualifiedName, "qualifiedName is null");
        requireNonNull(path, "path is null");
        requireNonNull(signature, "signature is null");
        checkArgument(startLine >= 1 && endLine >= startLine, "invalid line range %s-%s", startLine, endLine);
        checkArgument(startColumn >= 0, "startColumn is negative");
    }
}

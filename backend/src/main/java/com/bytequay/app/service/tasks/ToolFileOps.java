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
package com.bytequay.app.service.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Pure converter from a Claude Code tool-call name + input JSON into
 * the file operations it would (or did) perform. The session uses
 * the result to upsert {@code task_files} rows so the Files tab can
 * surface what each task touched.
 *
 * <p>Only file-touching tools are recognized — {@code Read},
 * {@code Write}, {@code Edit}, {@code MultiEdit}, and
 * {@code NotebookEdit}. Anything else returns an empty list. We
 * derive line-add / line-remove counts from the tool input rather
 * than the result, which means the totals are an upper bound on
 * what actually happened (a denied or failed call still counts).
 * Good enough for the Files sidebar.
 */
public class ToolFileOps
{
    private final ObjectMapper mapper;

    public ToolFileOps(ObjectMapper mapper)
    {
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    public List<FileOp> parse(String toolName, String inputJson)
    {
        if (toolName == null || inputJson == null || inputJson.isEmpty()) {
            return ImmutableList.of();
        }
        JsonNode input;
        try {
            input = mapper.readTree(inputJson);
        }
        catch (Exception ignored) {
            return ImmutableList.of();
        }
        if (!input.isObject()) {
            return ImmutableList.of();
        }
        return switch (toolName) {
            case "Read" -> singleton(textOrNull(input, "file_path"), "read", 0, 0);
            case "Write" -> {
                String path = textOrNull(input, "file_path");
                int lines = countLines(textOrEmpty(input, "content"));
                yield singleton(path, "write", lines, 0);
            }
            case "Edit", "NotebookEdit" -> {
                String path = textOrNull(input, "file_path");
                String oldStr = textOrEmpty(input, "old_string");
                String newStr = textOrEmpty(input, "new_string");
                int added = Math.max(0, countLines(newStr) - countLines(oldStr));
                int removed = Math.max(0, countLines(oldStr) - countLines(newStr));
                yield singleton(path, "edit", added, removed);
            }
            case "MultiEdit" -> {
                String path = textOrNull(input, "file_path");
                if (path == null) {
                    yield ImmutableList.of();
                }
                JsonNode edits = input.path("edits");
                if (!edits.isArray()) {
                    yield ImmutableList.of();
                }
                int added = 0;
                int removed = 0;
                for (JsonNode edit : edits) {
                    int o = countLines(textOrEmpty(edit, "old_string"));
                    int n = countLines(textOrEmpty(edit, "new_string"));
                    added += Math.max(0, n - o);
                    removed += Math.max(0, o - n);
                }
                yield ImmutableList.of(new FileOp(path, "edit", added, removed));
            }
            default -> ImmutableList.of();
        };
    }

    private static List<FileOp> singleton(String path, String op, int added, int removed)
    {
        if (path == null || path.isEmpty()) {
            return ImmutableList.of();
        }
        return ImmutableList.of(new FileOp(path, op, added, removed));
    }

    private static String textOrNull(JsonNode node, String field)
    {
        JsonNode v = node.path(field);
        return v.isTextual() ? v.asText() : null;
    }

    private static String textOrEmpty(JsonNode node, String field)
    {
        JsonNode v = node.path(field);
        return v.isTextual() ? v.asText() : "";
    }

    private static int countLines(String s)
    {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        int count = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    /**
     * One file operation extracted from a tool call. {@code operation}
     * is {@code "read"}, {@code "write"}, or {@code "edit"} — the
     * granularity the {@code task_files} table stores.
     */
    public record FileOp(String path, String operation, int linesAdded, int linesRemoved)
    {
        public static Optional<FileOp> empty()
        {
            return Optional.empty();
        }
    }
}

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
package com.bytequay.app.service.threads;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Pure converter from a Claude Code tool-call name + input JSON into
 * the file operations it would (or did) perform. The session uses
 * the result to upsert {@code thread_files} rows so the Files tab can
 * surface what each thread touched.
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
        ToolInput input;
        try {
            input = mapper.readValue(inputJson, ToolInput.class);
        }
        catch (Exception ignored) {
            return ImmutableList.of();
        }
        // countLines is null-safe, so the optional string fields can be
        // passed straight through.
        return switch (toolName) {
            case "Read" -> singleton(input.filePath(), "read", 0, 0);
            case "Write" -> singleton(input.filePath(), "write", countLines(input.content()), 0);
            case "Edit", "NotebookEdit" -> {
                int added = Math.max(0, countLines(input.newString()) - countLines(input.oldString()));
                int removed = Math.max(0, countLines(input.oldString()) - countLines(input.newString()));
                yield singleton(input.filePath(), "edit", added, removed);
            }
            case "MultiEdit" -> {
                if (input.filePath() == null || input.edits() == null) {
                    yield ImmutableList.of();
                }
                int added = 0;
                int removed = 0;
                for (ToolInput.Edit edit : input.edits()) {
                    int o = countLines(edit.oldString());
                    int n = countLines(edit.newString());
                    added += Math.max(0, n - o);
                    removed += Math.max(0, o - n);
                }
                yield ImmutableList.of(new FileOp(input.filePath(), "edit", added, removed));
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

    /**
     * The union of the file-touching Claude Code tool inputs we read —
     * {@code file_path} plus the {@code Write}/{@code Edit} payload fields
     * and the {@code MultiEdit} {@code edits} array. Binding to this record
     * (unknown keys ignored) replaces poking at a raw JSON tree.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ToolInput(
            @JsonProperty("file_path") String filePath,
            @JsonProperty("content") String content,
            @JsonProperty("old_string") String oldString,
            @JsonProperty("new_string") String newString,
            @JsonProperty("edits") List<Edit> edits)
    {
        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Edit(
                @JsonProperty("old_string") String oldString,
                @JsonProperty("new_string") String newString) {}
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
     * granularity the {@code thread_files} table stores.
     */
    public record FileOp(String path, String operation, int linesAdded, int linesRemoved)
    {
        public static Optional<FileOp> empty()
        {
            return Optional.empty();
        }
    }
}

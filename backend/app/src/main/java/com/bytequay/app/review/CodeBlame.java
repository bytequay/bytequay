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
package com.bytequay.app.review;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkState;

/** Line spans refer to the exact saved contents identified by contentHash. */
public record CodeBlame(String path, String revision, String contentHash, List<Span> spans, boolean truncated, Optional<String> unavailableReason)
{
    public CodeBlame
    {
        spans = ImmutableList.copyOf(spans);
    }

    public record Span(int line, int count, int originalLine, String commit, String author, Instant authoredAt, String summary, String originalPath)
    {
        public boolean uncommitted()
        {
            return commit.chars().allMatch(value -> value == '0');
        }
    }

    /** Incremental blame groups lines and repeats commit metadata only on its first occurrence. */
    static List<Span> parse(String output)
    {
        Map<String, Map<String, String>> commits = new LinkedHashMap<>();
        ImmutableList.Builder<Span> spans = ImmutableList.builder();
        String commit = "";
        int original = 0;
        int line = 0;
        int count = 0;
        for (String entry : Splitter.on('\n').split(output)) {
            if (entry.matches("[a-f0-9]{40,64} [0-9]+ [0-9]+ [0-9]+")) {
                List<String> fields = Splitter.on(' ').splitToList(entry);
                commit = fields.get(0);
                original = Integer.parseInt(fields.get(1));
                line = Integer.parseInt(fields.get(2));
                count = Integer.parseInt(fields.get(3));
                commits.computeIfAbsent(commit, ignored -> new LinkedHashMap<>());
            }
            else if (!commit.isEmpty()) {
                int separator = entry.indexOf(' ');
                if (separator < 0) {
                    continue;
                }
                String key = entry.substring(0, separator);
                String value = entry.substring(separator + 1);
                Map<String, String> metadata = commits.get(commit);
                if (key.equals("filename")) {
                    checkState(metadata.containsKey("author") && metadata.containsKey("author-time") && metadata.containsKey("summary"), "Incomplete blame commit metadata");
                    String path = value.startsWith("\"") && value.endsWith("\"") ? ReviewCodeService.unquote(value) : value;
                    spans.add(new Span(line, count, original, commit, metadata.get("author"), Instant.ofEpochSecond(Long.parseLong(metadata.get("author-time"))),
                            metadata.get("summary"), path));
                    commit = "";
                }
                else if (key.equals("author") || key.equals("author-time") || key.equals("summary")) {
                    metadata.put(key, value);
                }
            }
        }
        return spans.build().stream().sorted(Comparator.comparingInt(Span::line)).collect(ImmutableList.toImmutableList());
    }
}

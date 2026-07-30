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
package com.bytequay.app.developmentflow.execution.remote;

import com.google.common.collect.ImmutableList;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Splices a GitHub review suggestion into a file, the same edit clicking
 * "Apply suggestion" on github.com produces: the comment's line range is
 * replaced verbatim by the body of its {@code ```suggestion} block.
 *
 * <p>Everything outside the replaced range is preserved byte-for-byte,
 * including the file's trailing-newline state, so a one-line suggestion
 * commits a one-line diff.
 *
 * <p>ponytail: lines are split on {@code \n} only — a CRLF file keeps its
 * {@code \r} on untouched lines but the suggested lines land LF-only,
 * matching what the reviewer actually typed. Normalise per-file line
 * endings only if a CRLF repo ever complains.
 */
final class SuggestionPatch
{
    private SuggestionPatch() {}

    /**
     * Replaces lines {@code [startLine, endLine]} (1-based, inclusive) of
     * {@code fileText} with {@code suggestion}. An empty suggestion deletes
     * the range.
     *
     * @throws IllegalArgumentException when the range doesn't sit inside
     *         the file — the comment anchors to a line that no longer
     *         exists, so applying it would corrupt unrelated code.
     */
    static String apply(String fileText, int startLine, int endLine, String suggestion)
    {
        requireNonNull(fileText, "fileText is null");
        List<String> lines = split(fileText);
        // split("\n", -1) leaves a trailing "" when the file ends with a
        // newline. That sentinel is not a line the user can comment on, but
        // it must survive into the output so the file keeps its final \n.
        boolean endsWithNewline = !lines.isEmpty()
                && lines.get(lines.size() - 1).isEmpty();
        int lineCount = endsWithNewline ? lines.size() - 1 : lines.size();
        if (startLine < 1 || endLine < startLine || endLine > lineCount) {
            throw new IllegalArgumentException(
                    "suggestion range " + startLine + "-" + endLine
                            + " is outside a file of " + lineCount + " lines");
        }
        ImmutableList.Builder<String> out = ImmutableList.builder();
        out.addAll(lines.subList(0, startLine - 1));
        out.addAll(suggestionLines(suggestion));
        out.addAll(lines.subList(endLine, lines.size()));
        return String.join("\n", out.build());
    }

    /**
     * True when {@code fileText} already carries {@code suggestion} at
     * {@code startLine} — the independent proof that a write landed, used
     * when a crash leaves the outcome unknown. An empty (delete-the-range)
     * suggestion has no content to match, so it never proves; the caller
     * falls back to reporting the attempt as indeterminate.
     */
    static boolean applied(String fileText, int startLine, String suggestion)
    {
        requireNonNull(fileText, "fileText is null");
        List<String> expected = suggestionLines(suggestion);
        if (expected.isEmpty() || startLine < 1) {
            return false;
        }
        List<String> lines = split(fileText);
        if (startLine - 1 + expected.size() > lines.size()) {
            return false;
        }
        return lines.subList(startLine - 1, startLine - 1 + expected.size())
                .equals(expected);
    }

    private static List<String> split(String text)
    {
        return ImmutableList.copyOf(text.split("\n", -1));
    }

    private static List<String> suggestionLines(String suggestion)
    {
        requireNonNull(suggestion, "suggestion is null");
        // A ```suggestion fence always ends with a newline before its
        // closing backticks; that newline is the fence's, not a blank
        // final line of the suggested code.
        String body = suggestion.endsWith("\n")
                ? suggestion.substring(0, suggestion.length() - 1)
                : suggestion;
        return body.isEmpty() ? ImmutableList.of() : split(body);
    }
}

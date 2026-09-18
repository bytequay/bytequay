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
package com.bytequay.app.ci;

import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/** Selects intact diagnostic blocks from a complete, credential-redacted log. */
public record CiLogExcerpt(String text, boolean compacted, boolean diagnosticsMatched)
{
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\\x1B(?:[@-Z\\\\-_]|\\[[0-?]*[ -/]*[@-~])");
    private static final Pattern TIMESTAMP_PREFIX = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z\\s?");
    private static final Pattern FAILED_TEST = Pattern.compile("<<<\\s*(?:FAILURE|ERROR)!");
    private static final Pattern COMPILER_ERROR = Pattern.compile("(?i)compilation (?:error|failed)|cannot find symbol");
    private static final Pattern ERROR = Pattern.compile(
            "(?i)(\\b(?:error|errors|failed|failure|fatal|panic|exception|traceback|assertion|assert|mismatch)\\b"
                    + "|\\b\\w*(?:Exception|Error)\\b|Caused by:|Suppressed:|timed out|segmentation fault|##\\[error\\]|^\\s*at\\s+\\S+\\([^\\n]*\\))");
    private static final Pattern PASSING_TEST = Pattern.compile("Tests run:.*\\bFailures: 0,\\s*Errors: 0\\b");
    private static final Pattern EXIT_STATUS = Pattern.compile("^##\\[error]Process completed with exit code \\d+\\.?$");
    private static final Pattern EXCEPTION = Pattern.compile("^(?:Caused by:|Suppressed:|[\\w.$]+(?:Exception|Error)(?::|$)|\\.\\.\\. \\d+ more)");
    private static final Pattern LEVEL_PREFIX = Pattern.compile("^\\[(?:INFO|WARN|ERROR|DEBUG|TRACE)] ?");
    private static final Pattern RECORD = Pattern.compile("^(?:\\[(?:INFO|WARN|ERROR|DEBUG|TRACE)]|##\\[)");
    private static final String OMISSION = "[... log lines omitted ...]\n";

    public CiLogExcerpt
    {
        requireNonNull(text, "text is null");
    }

    /** The target may be exceeded to retain a whole block; the hard maximum never is. */
    public static CiLogExcerpt from(String text, int targetBytes, int maximumBytes)
    {
        requireNonNull(text, "text is null");
        checkArgument(targetBytes > 0 && targetBytes <= maximumBytes, "targetBytes is outside the log limit");
        checkArgument(maximumBytes <= ReadCiLogQuery.MAXIMUM_BYTES, "maximumBytes is outside the log limit");
        // Keep source lines, including their terminators, unchanged. Normalization is only for matching.
        List<String> lines = ImmutableList.copyOf(text.split("(?<=\\n)"));
        List<String> normalized = lines.stream()
                .map(line -> TIMESTAMP_PREFIX.matcher(ANSI_ESCAPE.matcher(line).replaceAll("")).replaceFirst("").stripTrailing())
                .toList();
        List<Integer> diagnostics = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = normalized.get(index);
            if (!PASSING_TEST.matcher(line).find() && !EXIT_STATUS.matcher(line).matches() && ERROR.matcher(line).find()) {
                diagnostics.add(index);
            }
        }
        if (diagnostics.isEmpty()) {
            byte[] bytes = text.getBytes(UTF_8);
            int start = Math.max(0, bytes.length - Math.min(targetBytes, 32 * 1024));
            while (start < bytes.length && (bytes[start] & 0xC0) == 0x80) {
                start++;
            }
            return new CiLogExcerpt(new String(bytes, start, bytes.length - start, UTF_8), start > 0, false);
        }
        if (text.getBytes(UTF_8).length <= targetBytes) {
            return new CiLogExcerpt(text, false, true);
        }
        diagnostics.sort(Comparator.comparingInt(index -> priority(normalized.get(index))));
        Selection selection = new Selection(lines, maximumBytes);
        BitSet considered = new BitSet();
        for (int anchor : diagnostics) {
            if (selection.bytes >= targetBytes) {
                break;
            }
            if (considered.get(anchor) || selection.selected.get(anchor)) {
                continue;
            }
            int start = blockStart(normalized, anchor);
            int end = blockEnd(normalized, anchor);
            considered.set(start, end);
            if (selection.range(start, end)) {
                int before = blockStart(normalized, Math.max(0, start - 20));
                int after = Math.min(lines.size(), end + 20);
                while (after < lines.size() && continuation(normalized.get(after))) {
                    after++;
                }
                if (selection.bytes < targetBytes) {
                    selection.range(before, after);
                }
            }
        }
        // Keep the final command outcome, but never start halfway through its exception chain.
        int end = lines.size();
        int tailStart = Math.max(0, end - 30);
        while (end > tailStart && selection.bytes < targetBytes) {
            int start = blockStart(normalized, end - 1);
            selection.range(start, end);
            end = start;
        }
        if (selection.bytes < targetBytes && !lines.isEmpty()) {
            selection.range(0, blockEnd(normalized, 0));
        }
        String excerpt = selection.render();
        return new CiLogExcerpt(excerpt, !excerpt.equals(text), true);
    }

    private static int priority(String line)
    {
        if (FAILED_TEST.matcher(line).find() || COMPILER_ERROR.matcher(line).find()) {
            return 0;
        }
        return line.startsWith("[ERROR]") || line.startsWith("##[error]") ? 1 : 2;
    }

    private static int blockStart(List<String> lines, int index)
    {
        while (index > 0 && continuation(lines.get(index))) {
            index--;
        }
        return index;
    }

    private static int blockEnd(List<String> lines, int anchor)
    {
        int end = anchor + 1;
        boolean failedTest = FAILED_TEST.matcher(lines.get(anchor)).find();
        // ponytail: textual boundaries tolerate noise; add runner-specific boundaries only for demonstrated misses.
        while (end < lines.size() && (continuation(lines.get(end)) || (failedTest && !RECORD.matcher(lines.get(end)).find()))) {
            end++;
        }
        return end;
    }

    private static boolean continuation(String line)
    {
        line = LEVEL_PREFIX.matcher(line).replaceFirst("");
        return line.isBlank() || Character.isWhitespace(line.charAt(0)) || EXCEPTION.matcher(line).find()
                || line.startsWith("Expecting ") || line.startsWith("expected:") || line.startsWith("to be ");
    }

    private static final class Selection
    {
        private final List<String> lines;
        private final BitSet selected = new BitSet();
        private final int maximumBytes;
        private int bytes;

        private Selection(List<String> lines, int maximumBytes)
        {
            this.lines = lines;
            this.maximumBytes = maximumBytes;
        }

        private boolean range(int start, int end)
        {
            int additionalBytes = 0;
            for (int index = start; index < end; index++) {
                if (!selected.get(index)) {
                    additionalBytes += lines.get(index).getBytes(UTF_8).length;
                }
            }
            if (additionalBytes == 0) {
                return true;
            }
            BitSet combined = (BitSet) selected.clone();
            combined.set(start, end);
            int gaps = 0;
            int index = combined.nextClearBit(0);
            while (index < lines.size()) {
                gaps++;
                int next = combined.nextSetBit(index);
                index = next < 0 ? lines.size() : combined.nextClearBit(next);
            }
            if (bytes + additionalBytes + gaps * OMISSION.length() > maximumBytes) {
                return false;
            }
            selected.set(start, end);
            bytes += additionalBytes;
            return true;
        }

        private String render()
        {
            StringBuilder result = new StringBuilder();
            int previous = -1;
            for (int index = selected.nextSetBit(0); index >= 0; index = selected.nextSetBit(index + 1)) {
                if (index > previous + 1) {
                    result.append(OMISSION);
                }
                result.append(lines.get(index));
                previous = index;
            }
            if (previous < lines.size() - 1 && OMISSION.length() <= maximumBytes) {
                result.append(OMISSION);
            }
            return result.toString();
        }
    }
}

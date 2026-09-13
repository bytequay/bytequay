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

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/** Selects bounded diagnostic context from already redacted, bounded log text. */
public record CiLogExcerpt(String text, boolean compacted)
{
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\\x1B(?:[@-Z\\\\-_]|\\[[0-?]*[ -/]*[@-~])");
    private static final Pattern TIMESTAMP_PREFIX = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z\\s?");
    private static final Pattern ERROR = Pattern.compile(
            "(?i)(\\b(?:error|errors|failed|failure|fatal|panic|exception|traceback|assertion|assert|mismatch)\\b"
                    + "|\\b\\w*(?:Exception|Error)\\b|Caused by:|timed out|segmentation fault|##\\[error\\])");
    private static final String OMISSION = "[... log lines omitted ...]\n";
    private static final int MAXIMUM_LINE_BYTES = 2000;

    public CiLogExcerpt
    {
        requireNonNull(text, "text is null");
    }

    public static CiLogExcerpt from(String text, int maximumBytes)
    {
        requireNonNull(text, "text is null");
        checkArgument(maximumBytes > 0 && maximumBytes <= ReadCiLogQuery.MAXIMUM_BYTES, "maximumBytes is outside the log limit");
        List<String> lines = new ArrayList<>();
        int repetitions = 0;
        String previous = null;
        for (String line : text.lines().toList()) {
            String normalized = TIMESTAMP_PREFIX.matcher(ANSI_ESCAPE.matcher(line).replaceAll("")).replaceFirst("").stripTrailing();
            if (normalized.equals(previous)) {
                repetitions++;
                continue;
            }
            if (repetitions > 0) {
                lines.add("[previous line repeated " + repetitions + " more times]");
                repetitions = 0;
            }
            lines.add(normalized);
            previous = normalized;
        }
        if (repetitions > 0) {
            lines.add("[previous line repeated " + repetitions + " more times]");
        }
        String normalized = String.join("\n", lines);
        if (normalized.getBytes(UTF_8).length <= maximumBytes) {
            return new CiLogExcerpt(normalized, !normalized.equals(text));
        }
        if (maximumBytes < 256) {
            return new CiLogExcerpt(boundedLine(normalized, maximumBytes), true);
        }
        List<Integer> errors = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            if (ERROR.matcher(lines.get(index)).find()) {
                errors.add(index);
            }
            lines.set(index, boundedLine(lines.get(index), Math.min(MAXIMUM_LINE_BYTES, Math.max(1, maximumBytes / 4))));
        }
        Selection selection = new Selection(lines, maximumBytes);
        // The first diagnostic often names the cause; the final one records the failed command.
        if (!errors.isEmpty()) {
            selection.range(errors.getFirst(), errors.getFirst() + 1);
            selection.range(errors.getLast(), errors.getLast() + 1);
        }
        selection.range(Math.max(0, lines.size() - 1), lines.size());
        if (!errors.isEmpty()) {
            selection.around(errors.getFirst());
            selection.around(errors.getLast());
        }
        selection.range(Math.max(0, lines.size() - 30), lines.size());
        selection.range(0, Math.min(8, lines.size()));
        for (int index = errors.size() - 1; index >= 0 && selection.remaining > 0; index--) {
            selection.around(errors.get(index));
        }
        return new CiLogExcerpt(selection.render(), true);
    }

    private static String boundedLine(String value, int maximumBytes)
    {
        byte[] bytes = value.getBytes(UTF_8);
        if (bytes.length <= maximumBytes) {
            return value;
        }
        String marker = " [line shortened]";
        if (maximumBytes <= marker.length()) {
            marker = "";
        }
        int end = maximumBytes - marker.length();
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, UTF_8) + marker;
    }

    private static final class Selection
    {
        private final List<String> lines;
        private final BitSet selected = new BitSet();
        private int remaining;

        private Selection(List<String> lines, int maximumBytes)
        {
            this.lines = lines;
            remaining = maximumBytes - OMISSION.length();
        }

        private void around(int anchor)
        {
            range(anchor, anchor + 1);
            range(Math.max(0, anchor - 4), Math.min(lines.size(), anchor + 13));
        }

        private void range(int start, int end)
        {
            for (int index = start; index < end; index++) {
                if (selected.get(index)) {
                    continue;
                }
                // Reserving a gap per line bounds output even when selected contexts never overlap.
                int cost = lines.get(index).getBytes(UTF_8).length + 1 + OMISSION.length();
                if (cost <= remaining) {
                    selected.set(index);
                    remaining -= cost;
                }
            }
        }

        private String render()
        {
            StringBuilder result = new StringBuilder();
            int previous = -1;
            for (int index = selected.nextSetBit(0); index >= 0; index = selected.nextSetBit(index + 1)) {
                if (index > previous + 1) {
                    result.append(OMISSION);
                }
                result.append(lines.get(index)).append('\n');
                previous = index;
            }
            if (previous < lines.size() - 1 && remaining >= 0) {
                result.append(OMISSION);
            }
            return result.toString();
        }
    }
}

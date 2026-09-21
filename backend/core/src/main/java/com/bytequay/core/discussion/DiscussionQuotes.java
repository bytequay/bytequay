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
package com.bytequay.core.discussion;

import com.google.common.hash.Hashing;

import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/**
 * Compares a highlighted passage against the answer it came from.
 *
 * <p>The reader highlights <em>rendered</em> text while the backend holds <em>markdown source</em>,
 * so the two are different strings: a selection crossing emphasis, inline code, a link or a line
 * wrap does not occur verbatim in the stored answer. Both sides pass through {@link #normalize}
 * before they are compared or hashed.
 *
 * <p>Changing what {@link #normalize} strips changes every stored {@code quoteHash}, so it is a
 * change to durable data rather than a refactor.
 */
public final class DiscussionQuotes
{
    /** Below this a selection is a stray click rather than a passage. */
    public static final int MINIMUM_QUOTE_CHARACTERS = 3;

    private static final Pattern FENCE = Pattern.compile("^(`{3,}|~{3,})");
    private static final Pattern LEADING_MARKERS = Pattern.compile("^(?:(?:#{1,6}|[>*+-]|\\d+[.)])\\s+)+");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\([^)]*\\)");
    private static final Pattern INLINE_MARKS = Pattern.compile("[`*_~]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private DiscussionQuotes() {}

    /**
     * Reduces markdown source and rendered text to the same string: fence lines are dropped,
     * leading heading, list and quote markers are stripped, a link becomes its text, inline marks
     * are removed and every whitespace run collapses to one space.
     */
    public static String normalize(String text)
    {
        requireNonNull(text, "text is null");
        StringBuilder joined = new StringBuilder();
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (FENCE.matcher(line).find()) {
                continue;
            }
            line = LEADING_MARKERS.matcher(line).replaceFirst("");
            line = LINK.matcher(line).replaceAll("$1");
            line = INLINE_MARKS.matcher(line).replaceAll("");
            joined.append(line).append(' ');
        }
        return WHITESPACE.matcher(joined).replaceAll(" ").strip();
    }

    /**
     * The stable identity of a passage. The occurrence is inside the hash, so two discussions on
     * different appearances of the same text are different passages.
     */
    public static String hash(String quote, int occurrence)
    {
        checkArgument(occurrence >= 1, "Invalid quote occurrence");
        return Hashing.sha256().hashString(normalize(quote) + "#" + occurrence, UTF_8).toString();
    }

    /**
     * Whether the normalized quote occurs in the normalized answer. The occurrence index is not
     * checked: the client counts it over rendered text while this runs over source, and image
     * syntax and table pipes make the two counts divergible. A divergent index costs the footer's
     * placement, which the renderer already degrades, rather than an unactionable refusal.
     */
    public static boolean occursIn(String quote, String answer)
    {
        return normalize(answer).contains(normalize(quote));
    }
}

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

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static java.util.Objects.requireNonNull;

/** Normalizes the immediate fallback and the separately generated task title. */
public final class TaskTitles
{
    /** Codex and Claude Code keep generated and fallback session titles compact. */
    public static final int MAX_LENGTH = 36;

    private TaskTitles() {}

    /**
     * The title a task carries while its dedicated naming request is pending: the first message's
     * first plain-text line.
     *
     * @param message the first message, not blank
     * @return the cut first line
     */
    public static String provisional(String message)
    {
        checkNotBlank(message, "message");
        String line = firstLine(message);
        return cut(line.isEmpty() ? message.strip() : line, MAX_LENGTH, true);
    }

    /**
     * Normalizes the title returned by the isolated naming request. Markdown, surrounding quotes,
     * a common {@code Title:} preamble, and final punctuation are not UI title content.
     *
     * @param response naming request's final response
     * @return a compact plain-text title, or empty when the response contains no usable text
     */
    public static String generated(String response)
    {
        requireNonNull(response, "response is null");
        String title = stripOuterFormatting(stripOuterFormatting(firstLine(response)
                .replaceFirst("(?i)^title\\s*:\\s*", "")
                .replaceAll("^[\\\"']+|[\\\"']+$", ""))
                .replaceAll("[.!?:;]+$", "")
                .strip());
        return cut(title, MAX_LENGTH, false);
    }

    private static String firstLine(String text)
    {
        for (String raw : text.split("\\R")) {
            // A Markdown link is its text; the path or address it points at is not a name.
            String line = raw.strip();
            if (line.matches("`{3,}|~{3,}")) {
                continue;
            }
            line = line.replaceFirst("^(?:(?:#{1,6}|[>*+\\-])\\s+)+", "")
                    .replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "$1")
                    .replaceAll("\\s+", " ")
                    .strip();
            line = stripOuterFormatting(line);
            if (!line.isEmpty()) {
                return line;
            }
        }
        return "";
    }

    private static String cut(String text, int maximumLength, boolean ellipsis)
    {
        int codePoints = text.codePointCount(0, text.length());
        if (codePoints <= maximumLength) {
            return text;
        }
        int contentLength = maximumLength - (ellipsis ? 1 : 0);
        int characterLimit = text.offsetByCodePoints(0, contentLength);
        int space = text.lastIndexOf(' ', characterLimit);
        boolean useSpace = space >= 0 && text.codePointCount(0, space) > contentLength / 2;
        String cut = text.substring(0, useSpace ? space : characterLimit).stripTrailing();
        return ellipsis ? cut + "…" : cut;
    }

    private static String stripOuterFormatting(String text)
    {
        String stripped = text.strip();
        boolean changed;
        do {
            changed = false;
            // Backticks and double asterisks are unambiguous wrappers here. Underscores and
            // single asterisks can be part of identifiers such as __init__ or *pointer.
            for (String marker : new String[] {"**", "`"}) {
                if (stripped.startsWith(marker)
                        && stripped.endsWith(marker)
                        && stripped.length() > marker.length() * 2) {
                    stripped = stripped.substring(marker.length(), stripped.length() - marker.length()).strip();
                    changed = true;
                    break;
                }
            }
        }
        while (changed);
        return stripped;
    }
}

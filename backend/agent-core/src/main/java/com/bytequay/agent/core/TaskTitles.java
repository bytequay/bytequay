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

/**
 * Derives task titles from text the task already has, so naming costs no provider turn.
 */
public final class TaskTitles
{
    /** Longest title a header, list row, or window card shows before it is cut. */
    public static final int MAX_LENGTH = 72;
    /** A sentence shorter than this says too little to stand as a title on its own. */
    private static final int MIN_SENTENCE_LENGTH = 12;

    private TaskTitles() {}

    /**
     * The title a task carries until the agent has answered: its first message's first line.
     *
     * @param message the first message, not blank
     * @return the cut first line
     */
    public static String provisional(String message)
    {
        checkNotBlank(message, "message");
        String derived = derive(message);
        return derived.isEmpty() ? cut(message.strip()) : derived;
    }

    /**
     * The title the agent's first answer proposes: its opening sentence when that says enough,
     * otherwise its first line. Markdown marks are dropped because a title is plain text.
     *
     * @param answer the agent's final answer
     * @return the proposed title, or an empty string when the answer has no usable text
     */
    // ponytail: first sentence of the first answer; a dedicated naming turn if this reads badly in practice
    public static String proposed(String answer)
    {
        requireNonNull(answer, "answer is null");
        return derive(answer);
    }

    /** The opening sentence when it says enough, otherwise the first line, without its final stop. */
    private static String derive(String text)
    {
        String line = firstLine(text);
        int end = sentenceEnd(line);
        String chosen = end >= MIN_SENTENCE_LENGTH ? line.substring(0, end) : line;
        return cut(chosen.replaceAll("[.!?]+$", "").strip());
    }

    private static String firstLine(String text)
    {
        for (String raw : text.split("\\R")) {
            String line = raw.replaceAll("^[#>*\\-\\s]+", "")
                    .replaceAll("[*_`]+", "")
                    .replaceAll("\\s+", " ")
                    .strip();
            if (!line.isEmpty()) {
                return line;
            }
        }
        return "";
    }

    private static int sentenceEnd(String line)
    {
        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            boolean terminal = current == '.' || current == '!' || current == '?';
            if (terminal && (index == line.length() - 1 || line.charAt(index + 1) == ' ')) {
                return index;
            }
        }
        return -1;
    }

    private static String cut(String text)
    {
        if (text.length() <= MAX_LENGTH) {
            return text;
        }
        int space = text.lastIndexOf(' ', MAX_LENGTH - 1);
        return text.substring(0, space > MAX_LENGTH / 2 ? space : MAX_LENGTH - 1).stripTrailing() + "…";
    }
}

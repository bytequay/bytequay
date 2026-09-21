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
package com.bytequay.app.discussion;

import com.bytequay.core.discussion.DiscussionMessage;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Assembles the one prompt a discussion question sends.
 *
 * <p>The first question and the re-seed after a lost provider session come through here alike, so
 * the two paths cannot drift: a re-seed is the same envelope with the intervening messages
 * appended. Message bodies are carried verbatim as the markdown they already are, because the
 * agent wrote them as markdown and flattening would destroy code blocks. Only the envelope is
 * ByteQuay's.
 */
final class DiscussionPrompt
{
    static final String PREAMBLE = """
            You are answering a reader's question about one passage of an answer you
            already gave in this task. Explain only; do not implement, continue the task,
            or suggest unrelated work.

            You may read files and run read-only commands to check something. You cannot
            change anything: edits, commits and publishing are refused.

            The working tree may be mid-edit by the task's own agent. Say so rather than
            guess.

            Answer in plain words and short sentences. Aim for 40 to 80 words and normally
            stay under 100. Give one small example only when it helps. Give more detail
            only when the reader asks. State uncertainty rather than inventing an
            explanation.""";

    private DiscussionPrompt() {}

    /**
     * @param seed the rendered task history through the anchored turn, as section 5.1 lays it out
     * @param quote the highlighted passage, verbatim
     * @param earlier this discussion's prior messages, oldest first; empty on the first question
     * @param question the question being asked now
     */
    static String render(String seed, String quote, List<DiscussionMessage> earlier, String question)
    {
        requireNonNull(seed, "seed is null");
        requireNonNull(quote, "quote is null");
        requireNonNull(earlier, "earlier is null");
        requireNonNull(question, "question is null");
        StringBuilder prompt = new StringBuilder(PREAMBLE)
                .append("\n\n")
                .append(seed)
                .append("\n\n--- passage the reader highlighted ---\n\n")
                .append(quote.strip());
        if (!earlier.isEmpty()) {
            prompt.append("\n\n--- discussion so far ---\n");
            for (DiscussionMessage message : earlier) {
                prompt.append('\n')
                        .append(message.author() == DiscussionMessage.Author.USER ? "[reader]" : "[you]")
                        .append('\n')
                        .append(message.body().strip())
                        .append('\n');
            }
            prompt.setLength(prompt.length() - 1);
        }
        return prompt.append("\n\n--- question ---\n\n").append(question.strip()).toString();
    }
}

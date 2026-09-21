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
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestDiscussionPrompt
{
    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");
    private static final String SEED = """
            --- conversation so far ---

            [user]
            fix the split generation

            [agent]
            I changed the coordinator.""";

    @Test
    void laysOutTheEnvelopeInOrderAndOmitsAnEmptyDiscussion()
    {
        String prompt = DiscussionPrompt.render(SEED, "  limited per thread  ", List.of(), "why per thread?");

        assertThat(prompt).startsWith(DiscussionPrompt.PREAMBLE);
        assertThat(prompt).containsSubsequence(
                "--- conversation so far ---",
                "[user]",
                "fix the split generation",
                "[agent]",
                "I changed the coordinator.",
                "--- passage the reader highlighted ---",
                "limited per thread",
                "--- question ---",
                "why per thread?");
        // The section is absent entirely on a first question rather than present and empty.
        assertThat(prompt).doesNotContain("--- discussion so far ---");
        assertThat(prompt).endsWith("why per thread?");
    }

    @Test
    void aReseedIsTheSameEnvelopeWithTheInterveningMessagesAppended()
    {
        List<DiscussionMessage> earlier = ImmutableList.of(
                new DiscussionMessage("m1", DiscussionMessage.Author.USER, "why per thread?", NOW, Optional.empty()),
                new DiscussionMessage("m2", DiscussionMessage.Author.AGENT, "The pool is sized from CPU count.", NOW, Optional.empty()));

        String first = DiscussionPrompt.render(SEED, "limited per thread", List.of(), "why per thread?");
        String reseed = DiscussionPrompt.render(SEED, "limited per thread", earlier, "and on four cores?");

        // Everything before the discussion section is byte-identical between the two paths.
        String shared = first.substring(0, first.indexOf("--- question ---"));
        assertThat(reseed).startsWith(shared);
        assertThat(reseed).containsSubsequence(
                "--- discussion so far ---",
                "[reader]",
                "why per thread?",
                "[you]",
                "The pool is sized from CPU count.",
                "--- question ---",
                "and on four cores?");
    }

    @Test
    void carriesMarkdownBodiesVerbatimSoCodeBlocksSurvive()
    {
        String seed = "--- conversation so far ---\n\n[agent]\n```java\nint x = 1;\n```";
        assertThat(DiscussionPrompt.render(seed, "int x", List.of(), "what is x?"))
                .contains("```java\nint x = 1;\n```");
    }
}

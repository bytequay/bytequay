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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestTaskTitles
{
    @Test
    void namesATaskAfterTheFirstSentenceOfItsFirstMessage()
    {
        assertThat(TaskTitles.provisional(
                "Investigate the flaky cherry-pick preview test and fix the root cause.\nThen open a PR."))
                .isEqualTo("Investigate the flaky cherry-pick preview test and fix the root cause");
        // A sentence too short to stand alone gives way to the whole line.
        assertThat(TaskTitles.provisional("Why? Because the test flakes.")).isEqualTo("Why? Because the test flakes");
        assertThat(TaskTitles.provisional("### Heading\n\nbody")).isEqualTo("Heading");
        assertThat(TaskTitles.provisional("  Fix it  ")).isEqualTo("Fix it");
        // Nothing usable in the message still yields a non-blank name.
        assertThat(TaskTitles.provisional("...")).isEqualTo("...");
        assertThatThrownBy(() -> TaskTitles.provisional(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void proposesTheOpeningSentenceOfAnAnswerWithoutMarkdown()
    {
        assertThat(TaskTitles.proposed("I traced the failing test to the preview limit. The fix is small."))
                .isEqualTo("I traced the failing test to the preview limit");
        assertThat(TaskTitles.proposed("**Done.** Everything passes.")).isEqualTo("Done. Everything passes");
        assertThat(TaskTitles.proposed("")).isEmpty();
        assertThat(TaskTitles.proposed("\n\n")).isEmpty();
    }

    @Test
    void cutsALongTitleAtAWordBoundary()
    {
        String words = "word ".repeat(30).strip();
        String title = TaskTitles.proposed(words);
        assertThat(title).endsWith("\u2026");
        assertThat(title.length()).isLessThanOrEqualTo(TaskTitles.MAX_LENGTH);
        assertThat(title).doesNotContain(" \u2026");
    }
}

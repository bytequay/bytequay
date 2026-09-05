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
    void keepsACompactFallbackFromTheFirstMessage()
    {
        assertThat(TaskTitles.provisional(
                "Investigate the flaky cherry-pick preview test and fix the root cause.\nThen open a PR."))
                .isEqualTo("Investigate the flaky cherry-pick…");
        assertThat(TaskTitles.provisional("Why? Because the test flakes.")).isEqualTo("Why? Because the test flakes.");
        assertThat(TaskTitles.provisional("### Heading\n\nbody")).isEqualTo("Heading");
        assertThat(TaskTitles.provisional("#123 Fix char* with -Xmx and foo_bar"))
                .isEqualTo("#123 Fix char* with -Xmx and foo_bar");
        assertThat(TaskTitles.provisional("  Fix it  ")).isEqualTo("Fix it");
        // Nothing usable in the message still yields a non-blank name.
        assertThat(TaskTitles.provisional("...")).isEqualTo("...");
        assertThatThrownBy(() -> TaskTitles.provisional(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalizesTheDedicatedNamingResponse()
    {
        assertThat(TaskTitles.generated("Improve agent task naming")).isEqualTo("Improve agent task naming");
        assertThat(TaskTitles.generated("**Fix preview title.**")).isEqualTo("Fix preview title");
        assertThat(TaskTitles.generated("Title: `Locate foo_bar`. ")).isEqualTo("Locate foo_bar");
        assertThat(TaskTitles.generated("__init__")).isEqualTo("__init__");
        assertThat(TaskTitles.generated("_request_id_")).isEqualTo("_request_id_");
        assertThat(TaskTitles.generated("*pointer")).isEqualTo("*pointer");
        // A link keeps its text and drops the worktree path it pointed at.
        assertThat(TaskTitles.generated("Review [pom.xml](/Users/me/worktrees/x/pom.xml:30)"))
                .isEqualTo("Review pom.xml");
        assertThat(TaskTitles.generated("")).isEmpty();
        assertThat(TaskTitles.generated("\n\n")).isEmpty();
    }

    @Test
    void cutsALongTitleAtAWordBoundary()
    {
        String words = "word ".repeat(30).strip();
        String title = TaskTitles.generated(words);
        assertThat(title).doesNotEndWith("\u2026");
        assertThat(title.length()).isLessThanOrEqualTo(TaskTitles.MAX_LENGTH);
        assertThat(title).doesNotEndWith(" ");
    }

    @Test
    void cutsUnicodeOnlyBetweenCodePoints()
    {
        String title = TaskTitles.provisional("😀".repeat(40));

        assertThat(title.codePointCount(0, title.length())).isEqualTo(TaskTitles.MAX_LENGTH);
        assertThat(title).isEqualTo("😀".repeat(TaskTitles.MAX_LENGTH - 1) + "…");
    }
}

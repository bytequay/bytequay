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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSuggestionPatch
{
    private static final String FILE = "one\ntwo\nthree\nfour\n";

    @Test
    void replacesASingleLineAndKeepsTheTrailingNewline()
    {
        assertThat(SuggestionPatch.apply(FILE, 2, 2, "TWO"))
                .isEqualTo("one\nTWO\nthree\nfour\n");
    }

    @Test
    void replacesAMultiLineRangeWithADifferentLineCount()
    {
        assertThat(SuggestionPatch.apply(FILE, 2, 3, "a\nb\nc"))
                .isEqualTo("one\na\nb\nc\nfour\n");
    }

    @Test
    void anEmptySuggestionDeletesTheRange()
    {
        assertThat(SuggestionPatch.apply(FILE, 2, 3, ""))
                .isEqualTo("one\nfour\n");
    }

    @Test
    void ignoresTheFenceNewlineRatherThanInsertingABlankLine()
    {
        assertThat(SuggestionPatch.apply(FILE, 2, 2, "TWO\n"))
                .isEqualTo("one\nTWO\nthree\nfour\n");
    }

    @Test
    void keepsAFileThatDoesNotEndWithANewlineUnterminated()
    {
        assertThat(SuggestionPatch.apply("one\ntwo", 2, 2, "TWO"))
                .isEqualTo("one\nTWO");
    }

    @Test
    void appliesToTheLastLineOfANewlineTerminatedFile()
    {
        assertThat(SuggestionPatch.apply(FILE, 4, 4, "FOUR"))
                .isEqualTo("one\ntwo\nthree\nFOUR\n");
    }

    @Test
    void rejectsARangeThatRunsPastTheEndOfTheFile()
    {
        // The trailing "" left by a final newline is not a line a reviewer
        // can comment on — applying there would append past the content.
        assertThatThrownBy(() -> SuggestionPatch.apply(FILE, 5, 5, "five"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside a file of 4 lines");
    }

    @Test
    void rejectsANonPositiveOrInvertedRange()
    {
        assertThatThrownBy(() -> SuggestionPatch.apply(FILE, 0, 1, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SuggestionPatch.apply(FILE, 3, 2, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void provesAnAppliedSuggestionByReadingTheContentBack()
    {
        String patched = SuggestionPatch.apply(FILE, 2, 3, "a\nb");
        assertThat(SuggestionPatch.applied(patched, 2, "a\nb")).isTrue();
        assertThat(SuggestionPatch.applied(FILE, 2, "a\nb")).isFalse();
    }

    @Test
    void neverProvesADeletionBecauseThereIsNoContentToMatch()
    {
        assertThat(SuggestionPatch.applied(SuggestionPatch.apply(FILE, 2, 2, ""), 2, ""))
                .isFalse();
    }
}

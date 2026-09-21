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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestDiscussionQuotes
{
    @Test
    void matchesRenderedTextAgainstTheMarkdownSourceItCameFrom()
    {
        String source = "Splits are limited per **thread**,\nso small tables skip remote generation.";

        // What the reader highlights on screen crosses the emphasis and the line wrap, so it does
        // not occur verbatim in the source. Normalizing both sides is what makes the anchor work.
        assertThat(source).doesNotContain("limited per thread, so small tables");
        assertThat(DiscussionQuotes.occursIn("limited per thread, so small tables", source)).isTrue();

        assertThat(DiscussionQuotes.occursIn("a passage the answer never made", source)).isFalse();
    }

    @Test
    void reducesEveryMarkdownConstructTheTranscriptRenders()
    {
        assertThat(DiscussionQuotes.normalize("## A heading")).isEqualTo("A heading");
        assertThat(DiscussionQuotes.normalize("- an item\n- another")).isEqualTo("an item another");
        assertThat(DiscussionQuotes.normalize("1. first\n2. second")).isEqualTo("first second");
        assertThat(DiscussionQuotes.normalize("> quoted")).isEqualTo("quoted");
        assertThat(DiscussionQuotes.normalize("see [the docs](https://example.test/x)")).isEqualTo("see the docs");
        assertThat(DiscussionQuotes.normalize("`code` and *em* and _it_ and ~s~")).isEqualTo("code and em and it and s");
        assertThat(DiscussionQuotes.normalize("```java\ncode();\n```")).isEqualTo("code();");
        assertThat(DiscussionQuotes.normalize("  ragged   \n\n  spacing  ")).isEqualTo("ragged spacing");
    }

    @Test
    void hashesTheOccurrenceSoRepeatedTextIsTwoPassages()
    {
        String first = DiscussionQuotes.hash("INVALID_TABLE_PROPERTY", 1);
        String second = DiscussionQuotes.hash("INVALID_TABLE_PROPERTY", 2);
        assertThat(first).hasSize(64).isNotEqualTo(second);

        // A selection differing only in markup or spacing is the same passage.
        assertThat(DiscussionQuotes.hash("per **thread**, so", 1)).isEqualTo(DiscussionQuotes.hash("per thread,  so", 1));
    }
}

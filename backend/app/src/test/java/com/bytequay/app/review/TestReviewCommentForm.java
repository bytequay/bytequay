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
package com.bytequay.app.review;

import com.bytequay.core.review.ReviewInvalidRequestException;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestReviewCommentForm
{
    @Test
    void acceptsARequestWithinTheFirstTwoSentences()
    {
        for (String body : ImmutableList.of(
                "Reset the buffer before it is reused. A second call returns the first call's rows.",
                "Can the second lookup go? `find()` already returns the entry.",
                "The guard should move above the loop, because the first iteration reads `rows[0]` unchecked.",
                "A second call returns the first call's rows. Reset the buffer before it is reused.",
                "Please rename `tmp` to `pending`.",
                "Instead, use the existing `Splitter`.\n\n```suggestion\nreturn Splitter.on(',').split(value);\n```",
                "Commit message of abc1234: say what changes for the caller. Proposed subject: Reject null widget input")) {
            ReviewCommentForm.check("Unchecked input", body);
        }
        ReviewCommentForm.check("Nit: shorter name", "Rename `tmp` to `pending`.");
        ReviewCommentForm.check("Question: why two lookups", "`find()` runs twice for each key. Why is the second lookup needed? Either drop it or say why in a comment.");
    }

    @Test
    void refusesABodyThatNeverMakesItsRequestOrMakesItLate()
    {
        refused("Unchecked input", "The added line is used without any validation.", "lead with the request");
        refused("Unchecked input", "The parser reads `rows[0]` first. That read is unchecked. Add a guard above the loop.", "lead with the request");
        refused("Unchecked input", "```suggestion\nreturn value;\n```", "lead with the request");
        // A verb inside code, or one that is not in its base form, opens no request.
        refused("Unchecked input", "`remove()` is called twice here. The second call is redundant.", "lead with the request");
        refused("Unchecked input", "Returns null for an empty input. Callers do not expect that.", "lead with the request");
    }

    @Test
    void refusesHedgedRequests()
    {
        refused("Unchecked input", "You might want to validate the added line. A null reaches the parser.", "\"might want to\" hedges the request");
        refused("Unchecked input", "Consider validating the added line. A null reaches the parser.", "\"Consider\" hedges the request");
        refused("Unchecked input", "A null reaches the parser. Perhaps validate the added line?", "\"Perhaps\" hedges the request");
        refused("Unchecked input", "Validate the added line, but feel free to skip it.", "\"feel free to\" hedges the request");
        // The word alone, used for what code does, is not a hedge.
        ReviewCommentForm.check("Unchecked input", "Validate the added line. The planner does not consider `null` a valid split.");
    }

    @Test
    void limitsSentencesAndWordsAndNeverMeasuresCode()
    {
        refused("Unchecked input", "Add a guard. One. Two. Three. Four. Five.", "6 sentences exceeds 5");
        refused("Nit: shorter name", "Rename it. It is long. It is also vague.", "3 sentences exceeds 2");
        refused("Unchecked input", "Add a guard. " + "word ".repeat(120), "123 prose words exceeds 120");
        refused("Nit: shorter name", "Rename it. " + "word ".repeat(34), "36 prose words exceeds 35");
        refused("Unchecked input", "Add a guard.\n```java\nint a = 1;", "close the code fence");
        // Dots in code, abbreviations and decimals end no sentence, and fenced code is left out.
        ReviewCommentForm.check("Unchecked input", "Use `rows.get(0).value()` here, e.g. for the 1.5 factor. It avoids the copy. That holds for v2.1 too. "
                + "The same applies below. Nothing else changes.\n```java\nOne. Two. Three. Four. Five. Six.\n```");
    }

    private static void refused(String title, String body, String message)
    {
        assertThatThrownBy(() -> ReviewCommentForm.check(title, body)).isInstanceOf(ReviewInvalidRequestException.class).hasMessageContaining(message);
    }
}

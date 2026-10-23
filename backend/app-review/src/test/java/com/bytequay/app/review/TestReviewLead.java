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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestReviewLead
{
    @Test
    void readsLeadsFromAFencedAnswerAndLeavesOutMalformedOnes()
    {
        List<ReviewLead> leads = ReviewLead.parse("""
                Here are the leads.
                ```json
                {"leads":[
                  {"path":"src/App.java","line":12,"lens":"words","kind":"nit","claim":"The comment is stale.","ask":"Reword it."},
                  {"path":"","line":3,"lens":"FACTS","kind":"Question","claim":"A release note is requested.","ask":"Propose one."},
                  {"path":"src/App.java","lens":"style","kind":"nit","claim":"An unknown lens.","ask":"Drop it."},
                  {"path":"src/App.java","lens":"tests","kind":"nit","claim":" ","ask":"A blank claim."},
                  "not an object"
                ]}
                ```
                """);

        assertThat(leads).containsExactly(
                new ReviewLead("src/App.java", OptionalInt.of(12), ReviewLead.Lens.WORDS, ReviewLead.Kind.NIT, "The comment is stale.", "Reword it."),
                new ReviewLead("", OptionalInt.empty(), ReviewLead.Lens.FACTS, ReviewLead.Kind.QUESTION, "A release note is requested.", "Propose one."));
        assertThat(leads.getFirst().location()).isEqualTo("src/App.java:12");
        assertThat(leads.getLast().location()).isEqualTo("the commits or the description");
    }

    @Test
    void keepsOnlyTheFirstLeadsOfALongAnswer()
    {
        String answer = IntStream.rangeClosed(1, ReviewLead.MAX_LEADS + 5)
                .mapToObj(number -> "{\"path\":\"src/App.java\",\"lens\":\"tests\",\"kind\":\"question\",\"claim\":\"Claim " + number + "\",\"ask\":\"Ask\"}")
                .collect(Collectors.joining(",", "Leads: {\"leads\":[", "]} That is all."));

        List<ReviewLead> leads = ReviewLead.parse(answer);

        assertThat(leads).hasSize(ReviewLead.MAX_LEADS);
        assertThat(leads.getFirst().claim()).isEqualTo("Claim 1");
        assertThat(leads.getLast().claim()).isEqualTo("Claim " + ReviewLead.MAX_LEADS);
        assertThat(leads.getFirst().location()).isEqualTo("src/App.java");
    }

    @Test
    void refusesAnAnswerThatIsNotTheRequestedObject()
    {
        assertThatThrownBy(() -> ReviewLead.parse("No leads found.")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not one JSON object");
        assertThatThrownBy(() -> ReviewLead.parse("[]")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("one object holding leads");
        assertThat(ReviewLead.parse("{\"leads\":[]}")).isEmpty();
        assertThat(ReviewLead.parse(ReviewLead.SHAPE)).hasSize(1);
    }
}

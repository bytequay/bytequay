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
package com.bytequay.app.service.goal;

import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestGoalPrompt
{
    @Test
    void promptCollectionsAreSnapshots()
    {
        var sources = new ArrayList<>(ImmutableList.of("https://example.com/spec"));
        var note = new GoalPrompt.CitedNote("Topic", "Body", sources);
        sources.clear();
        assertThat(note.sources()).containsExactly("https://example.com/spec");
        assertThatThrownBy(() -> note.sources().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new GoalPrompt.CitedNote("Topic", "Body", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void digestProjectionCannotRemoveBodiesFromThePrompt()
    {
        var notes = new ArrayList<>(ImmutableList.of(new GoalPrompt.Knowledge("Topic", Optional.of(true), Optional.of("Body"))));
        var facts = new GoalPrompt.Facts(Optional.empty(), ImmutableList.of(),
                new GoalPrompt.Objective("Goal", "", Optional.empty(), Optional.empty(), ImmutableList.of()),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), notes);
        notes.clear();
        assertThat(facts.withoutNoteBodies().knowledgeNotes()).containsExactly(new GoalPrompt.Knowledge("Topic", Optional.of(true), Optional.empty()));
        assertThat(facts.knowledgeNotes()).containsExactly(new GoalPrompt.Knowledge("Topic", Optional.of(true), Optional.of("Body")));
    }

    @Test
    void anExchangeCannotContainBothAUserMessageAndAnEvent()
    {
        assertThatThrownBy(() -> new GoalPrompt.Exchange(Optional.empty(), Optional.of("event"), Optional.of("message"), Optional.empty(), GoalPrompt.Unanswered.INSTANCE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GoalPrompt.Exchange(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), GoalPrompt.Unanswered.INSTANCE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

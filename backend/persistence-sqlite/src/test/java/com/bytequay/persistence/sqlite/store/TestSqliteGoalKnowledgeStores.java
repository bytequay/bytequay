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
package com.bytequay.persistence.sqlite.store;

import com.bytequay.core.domain.GoalKnowledgeNote;
import com.bytequay.core.domain.GoalRecommendation;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TestSqliteGoalKnowledgeStores
        extends AbstractTestSqliteStore
{
    private static final String DATABASE_PATH = Path.of(System.getProperty("java.io.tmpdir"), "bytequay-goal-knowledge-" + UUID.randomUUID() + ".db").toString();
    private static final Instant NOW = Instant.parse("2026-10-03T10:00:00.123Z");

    @Autowired
    private SqliteGoalRecommendationStore cards;

    @Autowired
    private SqliteGoalKnowledgeNoteStore notes;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry)
    {
        registerSqliteProperties(registry, "jdbc:sqlite:" + DATABASE_PATH + "?foreign_keys=ON", "create-drop");
    }

    @Test
    void keepsCardsThroughTheirStatesAndNotesWithTheirSources()
    {
        GoalRecommendation later = card("card-2", NOW.plusSeconds(1));
        GoalRecommendation first = card("card-1", NOW);
        cards.save(later);
        cards.save(first);
        GoalRecommendation other = card("card-3", NOW);
        cards.save(new GoalRecommendation(other.id(), "goal-2", other.workspaceId(), other.card(), other.state(), other.taskId(), other.exchangeId(), NOW, NOW));
        assertThat(cards.list("goal-1")).containsExactly(first, later);
        GoalRecommendation started = first.started(Optional.of("task-1"), NOW.plusSeconds(2));
        cards.save(started);
        assertThat(cards.find("card-1")).contains(started);
        cards.save(started.settled(true, NOW.plusSeconds(3)));
        assertThat(cards.find("card-1")).map(GoalRecommendation::state).contains(GoalRecommendation.State.DONE);
        assertThat(cards.find("missing")).isEmpty();

        GoalKnowledgeNote second = note("note-2", "Snapshots", NOW.plusSeconds(1));
        GoalKnowledgeNote note = note("note-1", "Manifest lists", NOW);
        notes.save(second);
        notes.save(note);
        assertThat(notes.list("goal-1")).containsExactly(note, second);
        assertThat(notes.list("goal-2")).isEmpty();
    }

    private static GoalRecommendation card(String id, Instant at)
    {
        return new GoalRecommendation(id, "goal-1", "workspace-1", new GoalRecommendation.Card(GoalRecommendation.Kind.RESEARCH, "Read the Iceberg specification",
                "You start from the specification", "Snapshots and manifests", ImmutableList.of("https://iceberg.apache.org/spec/"), Optional.empty()), GoalRecommendation.State.SHOWN,
                Optional.empty(), "op-1", at, at);
    }

    private static GoalKnowledgeNote note(String id, String topic, Instant at)
    {
        return new GoalKnowledgeNote(id, "goal-1", "workspace-1", topic, "A snapshot lists its manifests.", ImmutableList.of(
                new GoalKnowledgeNote.Source("https://iceberg.apache.org/spec/#manifest-lists", Optional.empty()),
                new GoalKnowledgeNote.Source("apache/iceberg:core/src/main/java/org/apache/iceberg/ManifestLists.java", Optional.of("c".repeat(40)))), "task-1", at);
    }
}

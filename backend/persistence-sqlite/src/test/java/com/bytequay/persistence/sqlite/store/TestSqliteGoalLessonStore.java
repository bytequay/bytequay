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

import com.bytequay.core.domain.GoalLesson;
import com.bytequay.persistence.sqlite.converter.JsonColumns;
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

class TestSqliteGoalLessonStore
        extends AbstractTestSqliteStore
{
    private static final String DATABASE_PATH = Path.of(System.getProperty("java.io.tmpdir"), "bytequay-goal-lessons-" + UUID.randomUUID() + ".db").toString();
    private static final Instant NOW = Instant.parse("2026-10-03T10:00:00.123Z");

    @Autowired
    private SqliteGoalLessonStore lessons;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry)
    {
        registerSqliteProperties(registry, "jdbc:sqlite:" + DATABASE_PATH + "?foreign_keys=ON", "create-drop");
    }

    @Test
    void keepsALessonFromWritingToWritten()
    {
        GoalLesson writing = GoalLesson.writing("lesson-1", "goal-1", "workspace-1", NOW);
        lessons.save(writing);
        lessons.save(GoalLesson.writing("lesson-2", "goal-1", "workspace-1", NOW));
        assertThat(lessons.writing()).extracting(GoalLesson::id).containsExactlyInAnyOrder("lesson-1", "lesson-2");

        GoalLesson.Diagram diagram = new GoalLesson.Diagram(GoalLesson.Diagram.Kind.TREE, "Table metadata", ImmutableList.of(
                new GoalLesson.Node("table", "Table", Optional.empty(), Optional.empty()),
                new GoalLesson.Node("snapshot", "Snapshot", Optional.of("Never changes"), Optional.of("table"))));
        GoalLesson.Content chapter = new GoalLesson.Content(Optional.of("A table is a tree of metadata."), ImmutableList.of("A snapshot never changes once written."),
                ImmutableList.of(new GoalLesson.Section("Metadata", "Each snapshot lists its manifests.", Optional.of(diagram))), Optional.empty(), "Run one test.",
                "It passes.", "What is a snapshot?");
        GoalLesson written = writing.written("codex/gpt", chapter, NOW.plusSeconds(1));
        lessons.save(written);
        assertThat(lessons.find("lesson-1")).contains(written);
        assertThat(lessons.writing()).extracting(GoalLesson::id).containsExactly("lesson-2");
        assertThat(lessons.find("missing")).isEmpty();
    }

    @Test
    void readsALessonSavedBeforeChapters()
            throws Exception
    {
        GoalLesson saved = JsonColumns.mapper().readValue("""
                {"id": "lesson-1", "goalId": "goal-1", "workspaceId": "workspace-1", "state": "READY", "content": {"explanation": "# Tables",
                "exercise": "Run one test.", "observe": "It passes.", "check": "What is a snapshot?"}, "failure": null, "model": "codex/gpt",
                "requestedAt": "2026-10-03T10:00:00Z", "writtenAt": "2026-10-03T10:01:00Z"}""", GoalLesson.class);
        assertThat(saved.content()).hasValueSatisfying(content -> {
            assertThat(content.explanation()).contains("# Tables");
            assertThat(content.beforeChapters()).isTrue();
        });
    }
}

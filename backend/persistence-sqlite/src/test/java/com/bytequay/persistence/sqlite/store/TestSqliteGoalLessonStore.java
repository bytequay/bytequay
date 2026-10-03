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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
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

        GoalLesson written = writing.written("codex/gpt", new GoalLesson.Content("# Tables\nA table is a tree of metadata.", "Run one test.", "It passes.",
                "What does a snapshot point to?"), NOW.plusSeconds(1));
        lessons.save(written);
        assertThat(lessons.find("lesson-1")).contains(written);
        assertThat(lessons.writing()).extracting(GoalLesson::id).containsExactly("lesson-2");
        assertThat(lessons.find("missing")).isEmpty();
    }
}

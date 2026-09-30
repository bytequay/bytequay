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

import com.bytequay.core.context.ContextEntryKind;
import com.bytequay.core.context.ContextGrade;
import com.bytequay.core.context.ContextGradeStore;
import com.bytequay.core.context.ContextUsageEvent;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TestSqliteContextGradeStore
        extends AbstractTestSqliteStore
{
    private static final String DATABASE_PATH = Path.of(System.getProperty("java.io.tmpdir"),
            "bytequay-context-grade-" + UUID.randomUUID() + ".db").toString();
    private static final String ENTRY = "knowledge:path:plugin/trino-iceberg/:snapshots";

    @DynamicPropertySource
    static void sqlite(DynamicPropertyRegistry registry)
    {
        registerSqliteProperties(registry, "jdbc:sqlite:" + DATABASE_PATH, "create-drop");
    }

    @Autowired
    private SqliteContextGradeStore store;

    @Test
    void aTaskKeepsItsLatestGradePerVersionAndGradesGoWithTheirTask()
    {
        ContextGrade helped = grade("task", ContextGrade.Grade.HELPED, 3, Instant.EPOCH);
        store.save(helped);
        ContextGrade wrong = new ContextGrade("workspace", "task", Optional.empty(), ContextUsageEvent.TaskKind.DEVELOPMENT, ContextGrade.Grader.AGENT,
                ContextGrade.Grade.WRONG, note(3), "The code refreshes it", Optional.of("Avoid stale snapshots"),
                ImmutableList.of(new ContextGrade.Citation("Snapshot.java", 12, 14, "a".repeat(40), "12  refresh();\n13  read();\n14  close();\n")),
                Optional.of(new ContextGrade.Correction(9, "Refresh the snapshot before reading")), Instant.EPOCH.plusSeconds(1));
        store.save(wrong);
        ContextGrade reported = new ContextGrade("workspace", "other", Optional.empty(), ContextUsageEvent.TaskKind.DEVELOPMENT, ContextGrade.Grader.USER,
                ContextGrade.Grade.WRONG, note(3), "", Optional.empty(), ImmutableList.of(), Optional.empty(), Instant.EPOCH.plusSeconds(2));
        store.save(reported);
        store.save(grade("other", ContextGrade.Grade.NOT_RELEVANT, 4, Instant.EPOCH.plusSeconds(3)));

        assertThat(store.find("task", ContextGrade.Grader.AGENT, ENTRY, 3)).contains(wrong);
        assertThat(store.listVersion("workspace", ENTRY, 3, 10)).containsExactly(reported, wrong);
        assertThat(store.listGrades("workspace", ImmutableSet.of(ContextGrade.Grade.WRONG), 10)).containsExactly(reported, wrong);
        assertThat(store.listGrades("another-workspace", ImmutableSet.of(ContextGrade.Grade.WRONG), 10)).isEmpty();
        assertThat(store.count("workspace", Instant.EPOCH)).containsExactlyInAnyOrder(
                new ContextGradeStore.Count(ENTRY, ContextGrade.Grade.WRONG, 2),
                new ContextGradeStore.Count(ENTRY, ContextGrade.Grade.NOT_RELEVANT, 1));
        assertThat(store.count("workspace", Instant.EPOCH.plusSeconds(3))).containsExactly(new ContextGradeStore.Count(ENTRY, ContextGrade.Grade.NOT_RELEVANT, 1));

        store.deleteTask("task");
        assertThat(store.find("task", ContextGrade.Grader.AGENT, ENTRY, 3)).isEmpty();
        assertThat(store.listVersion("workspace", ENTRY, 3, 10)).containsExactly(reported);
    }

    private static ContextGrade grade(String task, ContextGrade.Grade grade, long version, Instant at)
    {
        return new ContextGrade("workspace", task, Optional.empty(), ContextUsageEvent.TaskKind.DEVELOPMENT, ContextGrade.Grader.AGENT, grade, note(version),
                "Reason", Optional.empty(), ImmutableList.of(), Optional.empty(), at);
    }

    private static ContextUsageEvent.Note note(long version)
    {
        return new ContextUsageEvent.Note(ENTRY, version, ContextEntryKind.KNOWLEDGE, "path:plugin/trino-iceberg/", "Iceberg snapshots");
    }
}

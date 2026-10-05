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

import com.bytequay.core.domain.Goal;
import com.bytequay.core.domain.GoalCourse;
import com.bytequay.core.domain.GoalDefinition;
import com.bytequay.core.domain.GoalItem;
import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.GoalLesson;
import com.bytequay.core.domain.GoalSource;
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

class TestSqliteGoalStore
        extends AbstractTestSqliteStore
{
    private static final String DATABASE_PATH = Path.of(System.getProperty("java.io.tmpdir"), "bytequay-goals-" + UUID.randomUUID() + ".db").toString();
    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00.123Z");

    @Autowired
    private SqliteGoalStore store;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry)
    {
        registerSqliteProperties(registry, "jdbc:sqlite:" + DATABASE_PATH + "?foreign_keys=ON", "create-drop");
    }

    @Test
    void savesItemsAndSourceAndReplacesTheStoredGoal()
    {
        GoalSource source = new GoalSource(GoalItemKind.JIRA, GoalItem.jiraKey("connection", "cloud", "10042"));
        Goal linked = new Goal("goal-1", "workspace-1", "Delta Lake correctness", "", true, false, Optional.of(source), Optional.empty(), 0,
                Optional.empty(), false, NOW, NOW);
        Instant later = NOW.plusSeconds(1);
        Goal other = new Goal("goal-2", "workspace-1", "Parquet performance", "Cut wasted reads", false, false, Optional.empty(), Optional.empty(), 0,
                Optional.empty(), false, later, later);
        store.save(linked);
        store.save(other);
        store.save(new Goal("goal-3", "workspace-2", "Elsewhere", "", true, false, Optional.empty(), Optional.empty(), 0, Optional.empty(), false, NOW, NOW));

        Goal withItems = linked.withText("Accepted objective", "Saved plan", NOW.plusSeconds(5));
        store.save(withItems);

        assertThat(store.list("workspace-1")).containsExactly(withItems, other);
        assertThat(store.find("goal-1")).contains(withItems);
        assertThat(store.findBySource(source)).containsExactly(withItems);
        assertThat(store.findBySource(new GoalSource(GoalItemKind.ISSUE, "trinodb/trino#1"))).isEmpty();
        // Pinning leaves the revision; a definition moves it, so a proposal from before it cannot be accepted.
        Goal defined = withItems.withPinned(false, NOW.plusSeconds(7))
                .withDefinition("Iceberg reads", "Explain how reads plan files", new GoalDefinition("Explain a scan", "", "Five hours a week", "exchange-1", NOW),
                        NOW.plusSeconds(8));
        store.save(defined);
        assertThat(store.find("goal-1")).contains(defined);
        assertThat(defined.revision()).isEqualTo(2);
        GoalLesson.Diagram map = new GoalLesson.Diagram(GoalLesson.Diagram.Kind.COMPONENTS, "Iceberg reads", ImmutableList.of(
                new GoalLesson.Node("scan", "TableScan", Optional.of("Plans the files"), Optional.empty(), Optional.of("Engine")),
                new GoalLesson.Node("manifest", "ManifestFile", Optional.empty(), Optional.empty(), Optional.of("Metadata"))),
                ImmutableList.of(new GoalLesson.Edge("scan", "manifest", Optional.of("reads"))));
        Goal planned = defined.withCourse(new GoalCourse("exchange-2", ImmutableList.of(new GoalCourse.Part("Read the specification",
                ImmutableList.of("work-1", "work-2"))), NOW, Optional.of(map)), NOW.plusSeconds(9));
        store.save(planned);
        assertThat(store.find("goal-1")).contains(planned);
        assertThat(planned.revision()).as("a course leaves the revision definitions are checked against").isEqualTo(2);
        Goal paused = planned.withPaused(true, NOW.plusSeconds(10));
        store.save(paused);
        assertThat(store.find("goal-1")).contains(paused);
        assertThat(paused.revision()).as("pausing guidance changes nothing the goal means").isEqualTo(2);

        store.delete("goal-1");
        assertThat(store.list("workspace-1")).containsExactly(other);
        assertThat(store.find("goal-1")).isEmpty();
    }

    @Test
    void aCourseSavedBeforeMapsReadsWithoutOne()
            throws Exception
    {
        GoalCourse course = JsonColumns.mapper().readValue("""
                {"exchangeId": "exchange-2", "parts": [{"title": "Read the specification", "lessons": ["work-1"]}], "acceptedAt": "2026-10-03T10:00:00Z"}""",
                GoalCourse.class);
        assertThat(course.map()).isEmpty();
    }
}

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
import com.bytequay.core.domain.GoalItem;
import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.GoalSource;
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
        Goal seeded = new Goal("goal-1", "workspace-1", "Delta Lake correctness", "", true, false, Optional.of(source), Optional.empty(), NOW, NOW);
        Instant later = NOW.plusSeconds(1);
        Goal other = new Goal("goal-2", "workspace-1", "Parquet performance", "Cut wasted reads", false, false, Optional.empty(), Optional.empty(), later, later);
        store.save(seeded);
        store.save(other);
        store.save(new Goal("goal-3", "workspace-2", "Elsewhere", "", true, false, Optional.empty(), Optional.empty(), NOW, NOW));

        Goal withItems = seeded.withText("Accepted objective", "Saved plan", NOW.plusSeconds(5));
        store.save(withItems);

        assertThat(store.list("workspace-1")).containsExactly(withItems, other);
        assertThat(store.find("goal-1")).contains(withItems);
        assertThat(store.findBySource(source)).containsExactly(withItems);
        assertThat(store.findBySource(new GoalSource(GoalItemKind.ISSUE, "trinodb/trino#1"))).isEmpty();
        // Unmarking keeps the source, so marking it again finds this goal.
        Goal unmarked = withItems.withMarked(false, NOW.plusSeconds(6));
        store.save(unmarked);
        assertThat(store.findBySource(source)).containsExactly(unmarked);
        assertThat(unmarked.markedSource()).isEmpty();

        store.delete("goal-1");
        assertThat(store.list("workspace-1")).containsExactly(other);
        assertThat(store.find("goal-1")).isEmpty();
    }
}

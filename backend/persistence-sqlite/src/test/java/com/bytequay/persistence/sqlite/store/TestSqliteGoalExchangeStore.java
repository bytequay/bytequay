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

import com.bytequay.core.domain.GoalExchange;
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

class TestSqliteGoalExchangeStore
        extends AbstractTestSqliteStore
{
    private static final String DATABASE_PATH = Path.of(System.getProperty("java.io.tmpdir"), "bytequay-goal-exchanges-" + UUID.randomUUID() + ".db").toString();
    private static final Instant NOW = Instant.parse("2026-10-03T10:00:00.123Z");

    @Autowired
    private SqliteGoalExchangeStore store;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry)
    {
        registerSqliteProperties(registry, "jdbc:sqlite:" + DATABASE_PATH + "?foreign_keys=ON", "create-drop");
    }

    @Test
    void keepsTheConversationInOrderAndReplacesASettledExchange()
    {
        GoalExchange second = GoalExchange.ask("op-2", "goal-1", "workspace-1", "Reads first", 0, NOW.plusSeconds(1));
        GoalExchange first = GoalExchange.ask("op-1", "goal-1", "workspace-1", "Become an Iceberg connector expert", 0, NOW);
        store.save(second);
        store.save(first);
        store.save(GoalExchange.ask("op-3", "goal-2", "workspace-1", "Elsewhere", 0, NOW));
        assertThat(store.list("goal-1")).containsExactly(first, second);
        assertThat(store.pending()).extracting(GoalExchange::id).containsExactlyInAnyOrder("op-1", "op-2", "op-3");

        GoalExchange answered = first.answered("codex/gpt", new GoalExchange.Reply("You want to explain reads.",
                Optional.of(new GoalExchange.Question("Do you know the Iceberg specification?", ImmutableList.of("Yes", "Not yet"))),
                Optional.of(new GoalExchange.Proposal("Iceberg reads", "Explain reads", "Trace a scan", "", "")),
                Optional.of(new GoalRecommendation.Card(GoalRecommendation.Kind.RESEARCH, "Read the Iceberg specification", "You start from the specification",
                        "Table metadata, snapshots and manifests", ImmutableList.of("https://iceberg.apache.org/spec/")))), NOW.plusSeconds(2));
        GoalExchange failed = second.failed(Optional.empty(), "ByteQuay stopped before the agent answered.", NOW.plusSeconds(3));
        store.save(answered);
        store.save(failed);

        assertThat(store.find("op-1")).contains(answered);
        assertThat(store.list("goal-1")).containsExactly(answered, failed);
        assertThat(store.pending()).extracting(GoalExchange::id).containsExactly("op-3");
        assertThat(store.find("missing")).isEmpty();
    }
}

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

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskMetrics;
import com.bytequay.agent.core.TaskSummary;
import com.bytequay.agent.core.TestFact;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnMetrics;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.WorkspaceSnapshot;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentSessionRef;
import com.bytequay.agent.spi.CostProvenance;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.persistence.sqlite.config.SqlitePersistenceConfiguration;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = SqlitePersistenceConfiguration.class)
class TestSqliteAgentStores
{
    private static final String DATABASE_PATH = Path.of(
            System.getProperty("java.io.tmpdir"),
            "bytequay-agent-store-" + UUID.randomUUID() + ".db").toString();

    @Autowired
    private SqliteAgentTaskStore taskStore;

    @Autowired
    private SqliteAgentTurnStore turnStore;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry)
    {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE_PATH + "?foreign_keys=ON");
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
    }

    @Test
    void roundTripsTaskSummarySessionMetricsAndCleanupFacts()
    {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        TaskMetrics metrics = new TaskMetrics(
                10,
                2,
                5,
                Optional.of(new BigDecimal("1.25")),
                CostProvenance.PROVIDER_REPORTED,
                Duration.ofSeconds(3));
        WorkspaceSnapshot workspace = new WorkspaceSnapshot(
                Path.of("/tmp/bytequay-worktree"),
                new RepositoryIdentity("bytequay", "sample"),
                Optional.of("main"),
                "0123456789abcdef",
                true);
        TaskSummary summary = new TaskSummary(
                "finished",
                workspace,
                ImmutableList.of(new TestFact("mvn verify", TestFact.Status.PASSED, Optional.empty())),
                metrics,
                ImmutableList.of("keep the boundary small"),
                Optional.of("reviewed"));
        AgentTask task = newTask(now)
                .ready(now.plusSeconds(1))
                .activate(now.plusSeconds(2))
                .withSessionReference(new AgentSessionRef(AgentLaneId.CODEX_CLI, 1, "session-1"), now.plusSeconds(3))
                .beginCompletion(now.plusSeconds(4))
                .complete(summary, now.plusSeconds(5), now.plus(Duration.ofDays(7)));

        taskStore.insert(task);

        assertThat(taskStore.find(task.id())).contains(task);
        assertThat(taskStore.listCleanupDue(now.plus(Duration.ofDays(8)), 10)).containsExactly(task);
        assertThat(taskStore.listLive()).isEmpty();
    }

    @Test
    void enforcesOptimisticTaskUpdates()
    {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        AgentTask original = newTask(now);
        taskStore.insert(original);

        AgentTask updated = taskStore.update(original.ready(now.plusSeconds(1)));

        assertThat(updated.version()).isEqualTo(1);
        assertThat(taskStore.find(original.id())).contains(updated);
        assertThatThrownBy(() -> taskStore.update(original.ready(now.plusSeconds(2))))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("version changed");
    }

    @Test
    void rejectsDuplicateTaskIdentifierInsteadOfMerging()
    {
        AgentTask task = newTask(Instant.parse("2026-08-16T00:00:00Z"));
        taskStore.insert(task);

        assertThatThrownBy(() -> taskStore.insert(task))
                .isInstanceOf(AgentConflictException.class);
    }

    @Test
    void enforcesSingleActiveTurnAndIdempotencyInSqlite()
    {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        AgentTask task = newTask(now);
        taskStore.insert(task);
        AgentTurn first = AgentTurn.accepted(TurnId.random(), task.id(), "request-1", now);
        turnStore.insert(first);

        assertThat(turnStore.findActive(task.id())).contains(first);
        assertThat(turnStore.findByIdempotencyKey(task.id(), "request-1")).contains(first);
        assertThat(turnStore.listRecoverable()).containsExactly(first);
        assertThatThrownBy(() -> turnStore.insert(AgentTurn.accepted(
                TurnId.random(),
                task.id(),
                "request-2",
                now.plusSeconds(1))))
                .isInstanceOf(AgentConflictException.class);
    }

    @Test
    void releasesActiveSlotWhenTurnBecomesTerminal()
    {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        AgentTask task = newTask(now);
        taskStore.insert(task);
        AgentTurn first = AgentTurn.accepted(TurnId.random(), task.id(), "request-1", now)
                .running(new ProviderTurnRef("provider-turn-1"), Optional.of("model"), now.plusSeconds(1));
        turnStore.insert(first);

        AgentTurn terminal = turnStore.update(first.finish(
                TurnState.COMPLETED,
                new TurnMetrics(1, 0, 1, Optional.empty(), CostProvenance.UNAVAILABLE, Duration.ofSeconds(2)),
                Optional.empty(),
                now.plusSeconds(2)));
        AgentTurn second = AgentTurn.accepted(TurnId.random(), task.id(), "request-2", now.plusSeconds(3));
        turnStore.insert(second);

        assertThat(terminal.version()).isEqualTo(1);
        assertThat(turnStore.findActive(task.id())).contains(second);
        assertThat(turnStore.list(task.id())).containsExactly(terminal, second);
        assertThatThrownBy(() -> turnStore.update(first.withMetrics(TurnMetrics.empty())))
                .isInstanceOf(AgentConflictException.class);
    }

    @Test
    void enforcesIdempotencyAfterActiveSlotIsReleased()
    {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        AgentTask task = newTask(now);
        taskStore.insert(task);
        AgentTurn terminal = AgentTurn.accepted(TurnId.random(), task.id(), "same-request", now)
                .finish(
                        TurnState.CANCELLED,
                        TurnMetrics.empty(),
                        Optional.of("cancelled"),
                        now.plusSeconds(1));
        turnStore.insert(terminal);

        assertThatThrownBy(() -> turnStore.insert(AgentTurn.accepted(
                TurnId.random(),
                task.id(),
                "same-request",
                now.plusSeconds(2))))
                .isInstanceOf(AgentConflictException.class);
    }

    private static AgentTask newTask(Instant now)
    {
        return AgentTask.created(
                TaskId.random(),
                "Agent task",
                AgentLaneId.CODEX_CLI,
                new RepositoryIdentity("bytequay", "sample"),
                Path.of("/tmp/bytequay-worktree"),
                AgentPermissionPolicy.MANUAL,
                now);
    }
}

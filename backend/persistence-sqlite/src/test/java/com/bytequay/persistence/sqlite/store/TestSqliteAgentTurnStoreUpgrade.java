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

import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.DevelopmentTurnOrigin;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.DevelopmentResultReport;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static com.bytequay.agent.core.AgentTaskFixtures.created;
import static org.assertj.core.api.Assertions.assertThat;

class TestSqliteAgentTurnStoreUpgrade
        extends AbstractTestSqliteStore
{
    private static final String DATABASE_URL = newDatabaseUrl("bytequay-agent-turn-upgrade-");
    private static final TaskId TASK_ID = TaskId.random();
    private static final TurnId USER_TURN_ID = TurnId.random();
    private static final Instant CREATED_AT = Instant.parse("2026-08-16T00:00:00Z");

    @Autowired
    private SqliteAgentTaskStore tasks;

    @Autowired
    private SqliteAgentTurnStore turns;

    @Autowired
    private EntityManager entities;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry)
    {
        createTableWithRestrictedOrigins();
        registerSqliteProperties(registry, DATABASE_URL + "?foreign_keys=ON", "update");
    }

    @Test
    void retainsTurnsAndPersistsEveryOriginAfterSchemaUpdate()
    {
        AgentTask task = created(TASK_ID, Optional.empty(), false, "Agent task", AgentLaneId.CODEX_CLI, new RepositoryIdentity("owner", "repo"),
                Path.of("/tmp/bytequay-worktree"), AgentPermissionPolicy.AUTO, CREATED_AT);
        tasks.insert(task);
        AgentTurn userTurn = turns.find(USER_TURN_ID).orElseThrow();
        assertThat(userTurn.state()).isEqualTo(TurnState.COMPLETED);
        assertThat(userTurn.createdAt()).isEqualTo(CREATED_AT);
        assertThat(userTurn.endedAt()).contains(CREATED_AT);
        assertThat(userTurn.developmentOrigin()).isEmpty();

        AgentTurnSettings settings = new AgentTurnSettings(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO, Duration.ofMinutes(5),
                ImmutableList.of(), Optional.empty(), Optional.empty());
        AgentTurn latestResult = userTurn;
        for (DevelopmentTurnOrigin.Kind kind : DevelopmentTurnOrigin.Kind.values()) {
            Instant now = CREATED_AT.plusSeconds(kind.ordinal() + 1);
            AgentTurn generated = AgentTurn.accepted(TurnId.random(), task.id(), kind.name(), settings, now)
                    .withDevelopmentOrigin(new DevelopmentTurnOrigin(kind, userTurn.id()));
            turns.insert(generated);
            entities.clear();
            assertThat(turns.find(generated.id())).contains(generated);

            AgentTurn completed = turns.update(generated.markDeliveryStarted(now)
                    .running(new ProviderTurnRef(kind.name()), Optional.empty())
                    .withDevelopmentResult(new DevelopmentResultReport(true, Optional.empty()))
                    .finish(TurnState.COMPLETED, AgentMetrics.empty(), Optional.empty(), now));
            entities.clear();
            assertThat(turns.find(completed.id())).contains(completed);
            assertThat(turns.findLatest(ImmutableSet.of(task.id()))).containsEntry(task.id(), completed);
            if (kind != DevelopmentTurnOrigin.Kind.REFINE_SUMMARY) {
                latestResult = completed;
            }
            assertThat(turns.findLatestDevelopmentResults(ImmutableSet.of(task.id()))).containsEntry(task.id(), latestResult);
        }
        assertThat(turns.list(task.id())).hasSize(DevelopmentTurnOrigin.Kind.values().length + 1).contains(userTurn);
    }

    private static void createTableWithRestrictedOrigins()
    {
        try (var connection = DriverManager.getConnection(DATABASE_URL);
                var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE agent_turn (
                        id varchar(255) NOT NULL PRIMARY KEY,
                        task_id varchar(255) NOT NULL,
                        idempotency_key varchar(255) NOT NULL,
                        state varchar(255) NOT NULL,
                        version bigint NOT NULL,
                        created_at varchar(255) NOT NULL,
                        ended_at varchar(255),
                        input_tokens bigint NOT NULL,
                        cached_input_tokens bigint NOT NULL,
                        output_tokens bigint NOT NULL,
                        elapsed_millis bigint NOT NULL,
                        development_origin_kind varchar(255)
                            CHECK (development_origin_kind in ('AUTO_COMMIT','CREATE_DRAFT_PR','REFINE_SUMMARY')))
                    """);
            try (var insert = connection.prepareStatement("""
                    INSERT INTO agent_turn (id, task_id, idempotency_key, state, version, created_at, ended_at,
                        input_tokens, cached_input_tokens, output_tokens, elapsed_millis)
                    VALUES (?, ?, 'user-turn', 'COMPLETED', 0, ?, ?, 0, 0, 0, 0)
                    """)) {
                insert.setString(1, USER_TURN_ID.toString());
                insert.setString(2, TASK_ID.toString());
                insert.setString(3, CREATED_AT.toString());
                insert.setString(4, CREATED_AT.toString());
                insert.executeUpdate();
            }
        }
        catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}

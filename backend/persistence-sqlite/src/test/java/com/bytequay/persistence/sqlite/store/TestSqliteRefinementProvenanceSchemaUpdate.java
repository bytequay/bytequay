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
import com.bytequay.agent.core.AgentTaskDefaults;
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
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSqliteRefinementProvenanceSchemaUpdate
        extends AbstractTestSqliteStore
{
    private static final String DATABASE_PATH = Path.of(System.getProperty("java.io.tmpdir"), "bytequay-refinement-schema-update-" + UUID.randomUUID() + ".db").toString();
    private static final TaskId TASK_ID = TaskId.random();
    private static final TurnId PARENT_ID = TurnId.random();
    private static final TurnId COMMIT_ID = TurnId.random();
    private static final TurnId DRAFT_ID = TurnId.random();
    private static final Instant NOW = Instant.parse("2026-09-13T00:00:00Z");

    @Autowired
    private SqliteAgentTaskStore tasks;

    @Autowired
    private SqliteAgentTurnStore turns;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry)
    {
        createPreviousTurnTable();
        registerSqliteProperties(registry, "jdbc:sqlite:" + DATABASE_PATH + "?foreign_keys=ON", "update");
    }

    @Test
    void addsRefinementProvenanceWithoutChangingTheStoredDeliveryOriginsOrTheirCheckConstraint()
    {
        tasks.insert(AgentTask.created(TASK_ID, Optional.empty(), false, "Task", AgentLaneId.CODEX_CLI,
                new RepositoryIdentity("bytequay", "sample"), Path.of("/tmp/bytequay-refinement-test"),
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), NOW));
        AgentTurn oldCommit = turns.find(COMMIT_ID).orElseThrow();
        AgentTurn oldDraft = turns.find(DRAFT_ID).orElseThrow();
        assertThat(oldCommit.developmentOrigin()).contains(new DevelopmentTurnOrigin(DevelopmentTurnOrigin.Kind.AUTO_COMMIT, PARENT_ID));
        assertThat(oldDraft.developmentOrigin()).contains(new DevelopmentTurnOrigin(DevelopmentTurnOrigin.Kind.CREATE_DRAFT_PR, PARENT_ID));
        assertThat(jdbc.queryForMap("SELECT \"notnull\" FROM pragma_table_info('agent_turn') WHERE name = 'refinement_parent_user_turn_id'"))
                .containsEntry("notnull", 0);
        assertThat(jdbc.queryForObject("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'agent_turn'", String.class))
                .contains("CHECK (development_origin_kind IN ('AUTO_COMMIT', 'CREATE_DRAFT_PR'))");

        DevelopmentTurnOrigin origin = new DevelopmentTurnOrigin(DevelopmentTurnOrigin.Kind.REFINE_SUMMARY, PARENT_ID);
        AgentTurn refinement = AgentTurn.accepted(TurnId.random(), TASK_ID, "refine-summary",
                        new AgentTurnSettings(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO, Duration.ofMinutes(5), ImmutableList.of()), NOW)
                .withDevelopmentOrigin(origin);
        turns.insert(refinement);
        entityManager.clear();
        assertThat(turns.find(refinement.id())).contains(refinement);
        assertThat(jdbc.queryForMap("SELECT development_origin_kind, parent_user_turn_id, refinement_parent_user_turn_id FROM agent_turn WHERE id = ?", refinement.id().toString()))
                .containsEntry("development_origin_kind", null)
                .containsEntry("parent_user_turn_id", null)
                .containsEntry("refinement_parent_user_turn_id", PARENT_ID.toString());

        AgentTurn completed = turns.update(refinement.markDeliveryStarted(NOW.plusSeconds(1))
                .running(new ProviderTurnRef("refinement-native"), Optional.empty())
                .withDevelopmentResult(new DevelopmentResultReport(true, Optional.empty()))
                .finish(TurnState.COMPLETED, AgentMetrics.empty(), Optional.empty(), NOW.plusSeconds(2)));
        entityManager.clear();
        assertThat(turns.find(completed.id())).contains(completed);
        assertThat(turns.find(COMMIT_ID)).contains(oldCommit);
        assertThat(turns.find(DRAFT_ID)).contains(oldDraft);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_turn WHERE refinement_parent_user_turn_id IS NOT NULL", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("UPDATE agent_turn SET development_origin_kind = 'REFINE_SUMMARY' WHERE id = ?", refinement.id().toString()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("CHECK constraint failed");
    }

    private static void createPreviousTurnTable()
    {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + DATABASE_PATH);
                var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE agent_turn (
                        id varchar(255) NOT NULL PRIMARY KEY,
                        task_id varchar(255) NOT NULL,
                        idempotency_key varchar(255) NOT NULL,
                        idempotency_slot varchar(255) NOT NULL UNIQUE,
                        active_task_id varchar(255) UNIQUE,
                        state varchar(255) NOT NULL,
                        version bigint NOT NULL,
                        created_at varchar(255) NOT NULL,
                        started_at varchar(255),
                        ended_at varchar(255),
                        cancellation_requested_at varchar(255),
                        provider_turn_reference varchar(255),
                        known_unsent boolean,
                        model varchar(255),
                        requested_model varchar(255),
                        effort varchar(255),
                        service_tier varchar(255),
                        development_automation TEXT,
                        permission_policy varchar(255),
                        maximum_runtime_millis bigint,
                        attachments_json TEXT,
                        terminal_reason varchar(255),
                        development_result TEXT,
                        failure_kind varchar(255),
                        input_tokens bigint NOT NULL,
                        cached_input_tokens bigint NOT NULL,
                        output_tokens bigint NOT NULL,
                        cost numeric(38,2),
                        elapsed_millis bigint NOT NULL,
                        tool_calls bigint,
                        explore_actions bigint,
                        development_origin_kind varchar(255) CHECK (development_origin_kind IN ('AUTO_COMMIT', 'CREATE_DRAFT_PR')),
                        parent_user_turn_id varchar(255))
                    """);
            try (var insert = connection.prepareStatement("""
                    INSERT INTO agent_turn (id, task_id, idempotency_key, idempotency_slot, state, version, created_at, ended_at,
                        input_tokens, cached_input_tokens, output_tokens, elapsed_millis, development_origin_kind, parent_user_turn_id)
                    VALUES (?, ?, ?, ?, 'COMPLETED', 0, ?, ?, 0, 0, 0, 0, ?, ?)
                    """)) {
                for (DevelopmentTurnOrigin.Kind kind : ImmutableList.of(DevelopmentTurnOrigin.Kind.AUTO_COMMIT, DevelopmentTurnOrigin.Kind.CREATE_DRAFT_PR)) {
                    insert.setString(1, (kind == DevelopmentTurnOrigin.Kind.AUTO_COMMIT ? COMMIT_ID : DRAFT_ID).toString());
                    insert.setString(2, TASK_ID.toString());
                    insert.setString(3, kind.name());
                    insert.setString(4, TASK_ID + ":" + kind);
                    insert.setString(5, NOW.toString());
                    insert.setString(6, NOW.toString());
                    insert.setString(7, kind.name());
                    insert.setString(8, PARENT_ID.toString());
                    insert.executeUpdate();
                }
            }
        }
        catch (SQLException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}

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
package com.bytequay.app.developmentflow.persistence;

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.persistence.V2UserWaitStore.Grant;
import com.bytequay.app.developmentflow.persistence.V2UserWaitStore.PermissionAnswer;
import com.bytequay.app.service.agents.ActiveAgentContextRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;

import static com.bytequay.app.testing.MigratedSqliteDatabase.copyTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestV2UserWaitStore
{
    @TempDir
    private Path tempDir;

    private JdbcTemplate jdbc;
    private V2UserWaitStore waits;

    @BeforeEach
    void setUp()
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("waits.db")
                + "?foreign_keys=ON&busy_timeout=30000";
        copyTo(tempDir.resolve("waits.db"));
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl(url);
        jdbc = new JdbcTemplate(source);
        waits = new V2UserWaitStore(jdbc);
        seedTrunk();
        insertRunningTurn("turn-1", "operation-1", 1);
    }

    @Test
    void questionProjectsFromItsExactTurnAndAnswersOnce()
    {
        ActiveAgentContextRegistry.TypedOwner owner = owner("turn-1", "operation-1");
        V2UserWaitStore.Question inserted = waits.insertQuestion(
                owner, "question-1", "call-1", "Pick one", "context", "[]",
                true, Instant.ofEpochMilli(10));

        assertThat(inserted.owner()).isEqualTo(owner);
        assertThat(waits.listOpenQuestions("trunk-1"))
                .extracting(V2UserWaitStore.Question::id)
                .containsExactly("question-1");
        assertThat(waits.answerQuestion(
                "question-1", "answer", Instant.ofEpochMilli(20)))
                .get().extracting(V2UserWaitStore.Question::state)
                .isEqualTo("ANSWERED");
        assertThat(waits.answerQuestion(
                "question-1", "late", Instant.ofEpochMilli(21))).isEmpty();
        assertThat(waits.listOpenQuestions("trunk-1")).isEmpty();
    }

    @Test
    void oneTimeGrantSurvivesRestartAndOnlyIdenticalSuccessorConsumesIt()
    {
        ActiveAgentContextRegistry.TypedOwner first = owner("turn-1", "operation-1");
        waits.insertPermission(
                first, "permission-1", "call-1", "CODE_WRITE", "run_shell",
                "{\"command\":\"make test\"}", "digest-1", "{}",
                Instant.ofEpochMilli(10));
        V2UserWaitStore.PermissionResolution accepted = waits.resolvePermission(
                "call-1", 0,
                new PermissionAnswer(
                        "ALLOWED_ONCE", "{\"decision\":\"allow\"}",
                        new Grant("CALL", "digest-1", 1)),
                "user", Instant.ofEpochMilli(20));
        assertThat(accepted.accepted()).isTrue();
        assertThat(waits.resolvePermission(
                "call-1", 0,
                new PermissionAnswer("DENIED", "{\"decision\":\"deny\"}", null),
                "other", Instant.ofEpochMilli(21)).outcome())
                .isEqualTo("ALREADY_TERMINAL");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM permission_answer_attempt
                WHERE permission_id = 'permission-1'
                """, Integer.class)).isEqualTo(2);

        finishTurn("turn-1", 22);
        insertRunningTurn("turn-2", "operation-2", 23);
        waits.markContinuationDispatched(
                "PERMISSION", "permission-1", "turn-2");
        V2UserWaitStore reopened = new V2UserWaitStore(jdbc);
        assertThat(reopened.consumeGrant(
                owner("turn-2", "operation-2"), "call-other", "run_shell", "other-digest",
                Instant.ofEpochMilli(24))).isEmpty();
        assertThat(reopened.consumeGrant(
                owner("turn-2", "operation-2"), "call-successor", "run_shell", "digest-1",
                Instant.ofEpochMilli(25))).hasValue(0);
        assertThat(reopened.consumeGrant(
                owner("turn-2", "operation-2"), "call-successor", "run_shell", "digest-1",
                Instant.ofEpochMilli(26))).hasValue(0);
        assertThat(reopened.consumeGrant(
                owner("turn-2", "operation-2"), "call-second", "run_shell", "digest-1",
                Instant.ofEpochMilli(27))).isEmpty();
    }

    @Test
    void requestRejectsAStaleOrMismatchedOwner()
    {
        finishTurn("turn-1", 11);
        assertThatThrownBy(() -> waits.insertPermission(
                owner("turn-1", "operation-1"), "permission-1", "call-1",
                "CODE_WRITE", "run_shell", "{}", "digest", "{}",
                Instant.ofEpochMilli(12)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not current");
        assertThatThrownBy(() -> waits.insertQuestion(
                owner("turn-1", "wrong-operation"), "question-1", "call-1",
                "question", null, "[]", true, Instant.ofEpochMilli(12)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not current");
    }

    @Test
    void reservedMigrationCallIdsCannotEnterTheRuntimeProtocol()
    {
        ActiveAgentContextRegistry.TypedOwner owner = owner(
                "turn-1", "operation-1");
        String reserved = "__legacy_v263_consumption__:evidence";

        assertThatThrownBy(() -> waits.insertQuestion(
                owner, "question-1", reserved, "question", null, "[]", true,
                Instant.ofEpochMilli(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved V263 migration prefix");
        assertThatThrownBy(() -> waits.insertPermission(
                owner, "permission-1", reserved, "CODE_WRITE", "run_shell",
                "{}", "digest", "{}", Instant.ofEpochMilli(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved V263 migration prefix");
        assertThatThrownBy(() -> waits.consumeGrant(
                owner, reserved, "run_shell", "digest",
                Instant.ofEpochMilli(11)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved V263 migration prefix");
    }

    @Test
    void userWaitReceiptSucceedsThePhysicalTurnAndReplaysExactly()
    {
        ActiveAgentContextRegistry.TypedOwner owner = owner(
                "turn-1", "operation-1");
        waits.insertQuestion(
                owner, "question-1", "call-1", "Pick one", null,
                "[]", true, Instant.ofEpochMilli(10));

        V2UserWaitStore.UserWaitReceipt receipt = waits.recordUserWait(
                owner, "QUESTION", "question-1", "payload-digest",
                "{\"evidence\":true}", Instant.ofEpochMilli(20));

        assertThat(receipt.waitId()).isEqualTo("question-1");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM thread_turn WHERE id = 'turn-1'",
                String.class)).isEqualTo("SUCCEEDED");
        assertThat(waits.recordUserWait(
                owner, "QUESTION", "question-1", "payload-digest",
                "{\"evidence\":true}", Instant.ofEpochMilli(21)))
                .isEqualTo(receipt);
        assertThatThrownBy(() -> waits.recordUserWait(
                owner, "QUESTION", "question-1", "different-digest",
                "{\"evidence\":true}", Instant.ofEpochMilli(22)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different user-wait evidence");
        assertThatThrownBy(() -> waits.recordUserWait(
                owner, "QUESTION", "question-1", "payload-digest",
                "{\"evidence\":false}", Instant.ofEpochMilli(23)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different user-wait evidence");
    }

    private void seedTrunk()
    {
        jdbc.update("""
                INSERT OR IGNORE INTO workspaces(
                    id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES ('workspace-1', 'Workspace', '', 0, 1, 1)
                """);
        jdbc.update("""
                INSERT INTO threads(
                    id, kind, provider, title, status, model,
                    cost_usd_milli, tokens_in, tokens_out,
                    created_at_ms, updated_at_ms, workspace_id, flow,
                    parallel_slots, turn_version, lifecycle_state)
                VALUES ('trunk-1', 'CLI_AGENT', 'claude-code', 'Trunk', 'IDLE',
                    'claude-sonnet-4.6', 0, 0, 0, 1, 1, 'workspace-1',
                    'build', 2, 'V2', 'ACTIVE')
                """);
    }

    private void insertRunningTurn(String turnId, String operationId, long at)
    {
        jdbc.update("""
                INSERT INTO thread_turn(
                    id, trunk_id, purpose, status, operation_id, attempt,
                    delivery_lane, launch_input, requested_at_ms, started_at_ms)
                VALUES (?, 'trunk-1', 'CONVERSATION', 'RUNNING', ?, 1,
                    'CLI', '{}', ?, ?)
                """, turnId, operationId, at, at);
    }

    private void finishTurn(String turnId, long at)
    {
        jdbc.update("""
                UPDATE thread_turn
                SET status = 'SUCCEEDED', finished_at_ms = ?
                WHERE id = ? AND status = 'RUNNING'
                """, at, turnId);
    }

    private static ActiveAgentContextRegistry.TypedOwner owner(
            String turnId, String operationId)
    {
        return new ActiveAgentContextRegistry.TypedOwner(
                DispatchTicket.OwnerKind.THREAD_TURN, turnId, operationId);
    }
}

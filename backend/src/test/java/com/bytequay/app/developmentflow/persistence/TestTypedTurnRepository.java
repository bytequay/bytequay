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

import com.bytequay.app.developmentflow.persistence.TypedTurnRepository.Attachment;
import com.bytequay.app.developmentflow.persistence.TypedTurnRepository.Checkpoint;
import com.bytequay.app.developmentflow.persistence.TypedTurnRepository.Message;
import com.bytequay.app.developmentflow.persistence.TypedTurnRepository.Question;
import com.bytequay.app.developmentflow.persistence.TypedTurnRepository.ReviewAssignmentTurn;
import com.bytequay.app.developmentflow.persistence.TypedTurnRepository.ReviewAssignmentTurnId;
import com.bytequay.app.developmentflow.persistence.TypedTurnRepository.StageTurn;
import com.bytequay.app.developmentflow.persistence.TypedTurnRepository.StageTurnId;
import com.bytequay.app.developmentflow.persistence.TypedTurnRepository.TaskTurn;
import com.bytequay.app.developmentflow.persistence.TypedTurnRepository.TaskTurnId;
import com.bytequay.app.developmentflow.persistence.TypedTurnRepository.ThreadTurn;
import com.bytequay.app.developmentflow.persistence.TypedTurnRepository.ThreadTurnId;
import com.bytequay.app.developmentflow.persistence.TypedTurnRepository.TurnData;
import com.bytequay.app.developmentflow.persistence.TypedTurnRepository.TurnId;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;

import static com.bytequay.app.developmentflow.persistence.TypedTurnRepository.QuestionState.ANSWERED;
import static com.bytequay.app.developmentflow.persistence.TypedTurnRepository.QuestionState.OPEN;
import static com.bytequay.app.developmentflow.persistence.TypedTurnRepository.TurnStatus.CLAIMED;
import static com.bytequay.app.developmentflow.persistence.TypedTurnRepository.TurnStatus.FAILED;
import static com.bytequay.app.developmentflow.persistence.TypedTurnRepository.TurnStatus.QUEUED;
import static com.bytequay.app.developmentflow.persistence.TypedTurnRepository.TurnStatus.REQUESTED;
import static com.bytequay.app.developmentflow.persistence.TypedTurnRepository.TurnStatus.RUNNING;
import static com.bytequay.app.developmentflow.persistence.TypedTurnRepository.TurnStatus.SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestTypedTurnRepository
{
    private static final Instant NOW = Instant.ofEpochMilli(10);

    @TempDir
    private Path tempDir;

    private String databaseUrl;
    private JdbcTemplate jdbc;
    private TypedTurnRepository turns;

    @BeforeEach
    void setUp()
    {
        databaseUrl = "jdbc:sqlite:" + tempDir.resolve("typed-turns.db") + "?foreign_keys=ON";
        Flyway.configure().dataSource(databaseUrl, "", "").target("223").load().migrate();
        jdbc = jdbc();
        seedOwners(jdbc);
        turns = open();
    }

    @Test
    void allTypedTurnsAndSupportingRowsRoundTripWithoutOwnerInference()
    {
        ThreadTurn thread = new ThreadTurn("trunk-1", turn("turn-thread", "op-thread"));
        TaskTurn task = new TaskTurn(
                "task-1", 1, "stage-1", 1, "fp-1", "head-1", "base-1",
                turn("turn-task", "op-task"));
        StageTurn stage = new StageTurn(
                "stage-1", 1, 1, "fp-1", "head-1", "base-1",
                turn("turn-stage", "op-stage"));
        ReviewAssignmentTurn review = new ReviewAssignmentTurn(
                "assignment-1", "head-1", turn("turn-review", "op-review"));

        turns.insert(thread);
        turns.insert(task);
        turns.insert(stage);
        turns.insert(review);

        assertThat(turns.find(new ThreadTurnId("turn-thread"))).contains(thread);
        assertThat(turns.find(new TaskTurnId("turn-task"))).contains(task);
        assertThat(turns.find(new StageTurnId("turn-stage"))).contains(stage);
        assertThat(turns.find(new ReviewAssignmentTurnId("turn-review"))).contains(review);

        assertSupportingRows(new ThreadTurnId("turn-thread"), "thread");
        assertSupportingRows(new TaskTurnId("turn-task"), "task");
        assertSupportingRows(new StageTurnId("turn-stage"), "stage");
        assertSupportingRows(new ReviewAssignmentTurnId("turn-review"), "review");

        assertThat(turns.listMessages(new TaskTurnId("turn-thread"))).isEmpty();
        assertThat(turns.listQuestions(new StageTurnId("turn-task"))).isEmpty();
    }

    @Test
    void legalStatusEdgesAreOptimisticAndTerminalForEveryTurnType()
    {
        ThreadTurn thread = new ThreadTurn("trunk-1", requestedTurn("turn-thread", "op-thread"));
        TaskTurn task = new TaskTurn(
                "task-1", 1, null, null, null, null, null,
                requestedTurn("turn-task", "op-task"));
        StageTurn stage = new StageTurn(
                "stage-1", 1, 1, null, null, null,
                requestedTurn("turn-stage", "op-stage"));
        ReviewAssignmentTurn review = new ReviewAssignmentTurn(
                "assignment-1", "head-1", requestedTurn("turn-review", "op-review"));
        turns.insert(thread);
        turns.insert(task);
        turns.insert(stage);
        turns.insert(review);

        assertDeliveryStateMachine(new ThreadTurnId("turn-thread"));
        assertDeliveryStateMachine(new TaskTurnId("turn-task"));
        assertDeliveryStateMachine(new StageTurnId("turn-stage"));
        assertDeliveryStateMachine(new ReviewAssignmentTurnId("turn-review"));

        ThreadTurn reloaded = turns.find(new ThreadTurnId("turn-thread")).orElseThrow();
        assertThat(reloaded.data().status()).isEqualTo(SUCCEEDED);
        assertThat(reloaded.data().operationId()).isEqualTo("op-thread");
        assertThat(reloaded.data().launchInput()).isEqualTo("input-turn-thread");
        assertThat(reloaded.data().startedAt()).isEqualTo(Instant.ofEpochMilli(20));
        assertThat(reloaded.data().finishedAt()).isEqualTo(Instant.ofEpochMilli(30));

        assertThat(turns.find(new TaskTurnId("turn-task")).orElseThrow().data().status())
                .isEqualTo(SUCCEEDED);
        assertThat(turns.find(new StageTurnId("turn-stage")).orElseThrow().data().status())
                .isEqualTo(SUCCEEDED);
        assertThat(turns.find(new ReviewAssignmentTurnId("turn-review")).orElseThrow().data().status())
                .isEqualTo(SUCCEEDED);

        assertThatThrownBy(() -> new TurnData(
                "bad-queued", "TEST", QUEUED, "op-bad-queued", 1, "CLI", "input",
                NOW, Instant.ofEpochMilli(20), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TurnData(
                "bad-running", "TEST", RUNNING, "op-bad-running", 1, "CLI", "input",
                NOW, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TurnData(
                "bad-terminal", "TEST", SUCCEEDED, "op-bad-terminal", 1, "CLI", "input",
                NOW, Instant.ofEpochMilli(20), null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void questionAnswerCasSurvivesTwoSameFileReopens()
    {
        ThreadTurnId turnId = new ThreadTurnId("turn-thread");
        turns.insert(new ThreadTurn("trunk-1", turn("turn-thread", "op-thread")));
        Question<ThreadTurnId> open = new Question<>(
                "question-thread", turnId, "call-thread", "prompt", OPEN,
                null, 0, NOW, null);
        turns.insert(open);

        TypedTurnRepository afterFirstReopen = open();
        assertThat(afterFirstReopen.answer(
                turnId, "call-thread", OPEN, 0, "first answer", Instant.ofEpochMilli(20)))
                .isTrue();
        assertThat(afterFirstReopen.answer(
                turnId, "call-thread", OPEN, 0, "duplicate", Instant.ofEpochMilli(21)))
                .isFalse();
        assertThat(afterFirstReopen.answer(
                new TaskTurnId("turn-thread"), "call-thread", OPEN, 0,
                "wrong family", Instant.ofEpochMilli(21)))
                .isFalse();

        TypedTurnRepository afterSecondReopen = open();
        assertThat(afterSecondReopen.answer(
                turnId, "call-thread", ANSWERED, 1, "revised answer", Instant.ofEpochMilli(30)))
                .isTrue();
        assertThat(afterSecondReopen.answer(
                turnId, "call-thread", ANSWERED, 1, "stale revision", Instant.ofEpochMilli(31)))
                .isFalse();
        assertThat(afterSecondReopen.listQuestions(turnId)).containsExactly(new Question<>(
                "question-thread", turnId, "call-thread", "prompt", ANSWERED,
                "revised answer", 2, NOW, Instant.ofEpochMilli(30)));

        assertThatThrownBy(() -> new Question<>(
                "bad-open", turnId, "bad-open", "prompt", OPEN,
                "answer", 1, NOW, Instant.ofEpochMilli(20)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Question<>(
                "bad-answered", turnId, "bad-answered", "prompt", ANSWERED,
                null, 0, NOW, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Question<>(
                "bad-canceled", turnId, "bad-canceled", "prompt",
                TypedTurnRepository.QuestionState.CANCELED,
                null, 0, NOW, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCrossFamilyIdentityCollisionsAndStaleOwnerFences()
    {
        turns.insert(new ThreadTurn("trunk-1", turn("turn-thread", "op-thread")));

        assertThatThrownBy(() -> turns.insert(new TaskTurn(
                "task-1", 1, null, null, null, null, null,
                turn("turn-thread", "op-task-duplicate-id"))))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> turns.insert(new StageTurn(
                "stage-1", 1, 1, null, null, null,
                turn("turn-duplicate-operation", "op-thread"))))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> turns.insert(new TaskTurn(
                "task-1", 2, null, null, null, null, null,
                turn("turn-stale-task", "op-stale-task"))))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> turns.insert(new StageTurn(
                "stage-1", 2, 1, null, null, null,
                turn("turn-stale-stage", "op-stale-stage"))))
                .isInstanceOf(DataAccessException.class);
    }

    private <T extends TurnId> void assertSupportingRows(T turnId, String suffix)
    {
        Message<T> message = new Message<>(
                "message-" + suffix, turnId, 1, "assistant", "body", NOW);
        Question<T> question = new Question<>(
                "question-" + suffix, turnId, "call-" + suffix, "prompt", ANSWERED,
                "answer", 1, NOW, Instant.ofEpochMilli(11));
        Attachment<T> attachment = new Attachment<>(
                "attachment-" + suffix, turnId, "image", "blob:" + suffix,
                "image/png", "digest-" + suffix, NOW);
        Checkpoint<T> checkpoint = new Checkpoint<>(
                "checkpoint-" + suffix, turnId, 1, "{\"at\":1}", NOW);

        turns.insert(message);
        turns.insert(question);
        turns.insert(attachment);
        turns.insert(checkpoint);

        assertThat(turns.listMessages(turnId)).containsExactly(message);
        assertThat(turns.listQuestions(turnId)).containsExactly(question);
        assertThat(turns.listAttachments(turnId)).containsExactly(attachment);
        assertThat(turns.listCheckpoints(turnId)).containsExactly(checkpoint);
    }

    private void assertDeliveryStateMachine(TurnId id)
    {
        assertThatThrownBy(() -> turns.transition(
                id, REQUESTED, RUNNING, Instant.ofEpochMilli(20), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(turns.transition(id, REQUESTED, QUEUED, Instant.ofEpochMilli(11), null)).isTrue();
        assertThat(turns.transition(id, QUEUED, CLAIMED, Instant.ofEpochMilli(12), null)).isTrue();
        assertThatThrownBy(() -> turns.transition(
                id, CLAIMED, QUEUED, Instant.ofEpochMilli(13), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(turns.transition(id, CLAIMED, RUNNING, Instant.ofEpochMilli(20), null)).isTrue();
        assertThat(turns.transition(id, RUNNING, SUCCEEDED, Instant.ofEpochMilli(30), null)).isTrue();
        assertThatThrownBy(() -> turns.transition(
                id, SUCCEEDED, FAILED, Instant.ofEpochMilli(31), "rewrite"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> turns.transition(
                id, SUCCEEDED, RUNNING, Instant.ofEpochMilli(31), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(turns.transition(id, REQUESTED, QUEUED, Instant.ofEpochMilli(31), null)).isFalse();
    }

    private static TurnData turn(String id, String operationId)
    {
        return new TurnData(
                id, "TEST", QUEUED, operationId, 1, "CLI", "input-" + id,
                NOW, null, null, null);
    }

    private static TurnData requestedTurn(String id, String operationId)
    {
        return new TurnData(
                id, "TEST", REQUESTED, operationId, 1, "CLI", "input-" + id,
                NOW, null, null, null);
    }

    private TypedTurnRepository open()
    {
        return new TypedTurnRepository(jdbc());
    }

    private JdbcTemplate jdbc()
    {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(databaseUrl);
        return new JdbcTemplate(dataSource);
    }

    private static void seedOwners(JdbcTemplate jdbc)
    {
        jdbc.update("""
                INSERT INTO workspaces(
                    id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES ('workspace-1', 'Workspace', '', 0, 1, 1)
                """);
        jdbc.update("""
                INSERT INTO threads(
                    id, kind, provider, title, status, model, cost_usd_milli,
                    tokens_in, tokens_out, created_at_ms, updated_at_ms, workspace_id,
                    flow, parallel_slots, turn_version, lifecycle_state)
                VALUES ('trunk-1', 'CLI_AGENT', 'claude-code', 'Trunk', 'IDLE',
                    'claude-sonnet-4.6', 0, 0, 0, 1, 1, 'workspace-1', 'build', 2,
                    'V2', 'ACTIVE')
                """);
        jdbc.update("""
                INSERT INTO task_assignment(
                    id, trunk_id, kind, planning_base_sha, plan_seed, prompt,
                    created_by, created_at_ms)
                VALUES ('task-assignment-1', 'trunk-1', 'NEW_FROM_TRUNK',
                    'base-1', 'seed', 'build it', 'user', 2)
                """);
        jdbc.update("""
                INSERT INTO task_policy_revision(
                    id, trunk_id, revision, source, created_by, created_at_ms)
                VALUES ('policy-1', 'trunk-1', 1, 'TRUNK', 'user', 2)
                """);
        jdbc.update("""
                INSERT INTO tasks(
                    id, thread_id, seq, status, phase, created_at_ms,
                    workflow_version, lifecycle_state, assignment_id, policy_revision_id)
                VALUES ('task-1', 'trunk-1', 1, 'IDLE', 'PLANNING', 2,
                    'V2', 'PROVISIONING', 'task-assignment-1', 'policy-1')
                """);
        jdbc.update("""
                INSERT INTO stage(
                    id, task_id, kind, generation, version, checkpoint, opened_at_ms)
                VALUES ('stage-1', 'task-1', 'LOCAL_DEVELOPMENT', 1, 0,
                    'IMPLEMENTING', 3)
                """);
        jdbc.update("""
                INSERT INTO pr(
                    id, branch_name, base_branch, title, description, status,
                    created_at_ms, remote_pr_number, origin, repo)
                VALUES ('pr-1', 'feature', 'main', 'Review', '', 'remote-open',
                    2, 7, 'external', 'acme/widget')
                """);
        jdbc.update("""
                INSERT INTO agent_run(id, kind, status, started_at_ms)
                VALUES ('review-run-1', 'panel_review', 'RUNNING', 2)
                """);
        jdbc.update("""
                INSERT INTO review_session(
                    id, repo_id, pr_id, base_commit, reviewed_head_commit,
                    status, created_at_ms, updated_at_ms)
                VALUES ('review-session-1', 'acme/widget', 'pr-1', 'base-1',
                    'head-1', 'ACTIVE', 2, 2)
                """);
        jdbc.update("""
                INSERT INTO review_round(
                    id, session_id, agent_run_id, trigger, scope, start_commit,
                    status, budget_json, created_at_ms)
                VALUES ('review-round-1', 'review-session-1', 'review-run-1', 'USER',
                    'FULL', 'head-1', 'RUNNING', '{}', 2)
                """);
        jdbc.update("""
                INSERT INTO reviewer_def(
                    id, name, description, runner, runner_json, eligible_kinds)
                VALUES ('reviewer-1', 'Reviewer', 'Checks code', 'API', '{}', 'FULL')
                """);
        jdbc.update("""
                INSERT INTO review_assignment(
                    id, round_id, reviewer_def_id, runner, status,
                    understanding_summary, assumptions_json, unknowns_json, budget_json)
                VALUES ('assignment-1', 'review-round-1', 'reviewer-1', 'API',
                    'RUNNING', '', '[]', '[]', '{}')
                """);
    }
}

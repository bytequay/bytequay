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
package com.bytequay.app.repository.sqlite.migration;

import com.bytequay.app.developmentflow.stage.LocalDevelopmentStageManager;
import com.bytequay.app.developmentflow.stage.StageManager;
import com.bytequay.app.developmentflow.stage.V2LocalReviewControl;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRComment;
import com.bytequay.app.domain.PRTimelineEntry;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class TestV2LocalReviewControl
{
    @TempDir
    private Path tempDir;

    @Test
    void userRevisionFreezesOnceAndRestartReplaysItsTypedTurn()
            throws Exception
    {
        Fixture fixture = fixture("user-feedback.db");
        V2LocalReviewControl control = fixture.control();
        PRComment comment = control.addComment(
                localPr(), PRComment.ORIGIN_LOCAL, PRComment.SCOPE_FILE_LINE,
                "src/Main.java", 18, "RIGHT", null, null,
                PRTimelineEntry.ACTOR_USER, "Handle the empty input", null);

        PRComment edited = control.editComment(comment.id(),
                "Handle both empty and blank input");
        assertThat(edited.body()).isEqualTo("Handle both empty and blank input");
        assertThat(fixture.jdbc().queryForList("""
                SELECT body || '|' || state AS value
                FROM local_review_comment_revision
                WHERE thread_id = ? ORDER BY revision
                """, String.class, comment.id()))
                .containsExactly(
                        "Handle the empty input|SUPERSEDED",
                        "Handle both empty and blank input|PENDING");

        V2LocalReviewControl.Submission first = control.submit(
                "task-1", "", "REQUEST_CHANGES", List.of(comment.id()));
        assertThat(first.submitted()).isOne();
        assertThat(first.turnId()).isNotBlank();
        assertThat(fixture.jdbc().queryForObject(
                "SELECT checkpoint FROM stage WHERE id = 'local-stage-1'",
                String.class)).isEqualTo("ADDRESSING_LOCAL_FEEDBACK");
        assertThat(fixture.jdbc().queryForObject(
                "SELECT status FROM local_feedback_batch", String.class))
                .isEqualTo("DISPATCHED");
        assertThat(fixture.jdbc().queryForObject("""
                SELECT COUNT(*) FROM dispatch_ticket
                WHERE owner_kind = 'STAGE_TURN'
                  AND callback_route = 'STAGE_TURN_RESULT'
                  AND writer_required = 1
                """, Integer.class)).isOne();

        V2LocalReviewControl restarted = fixture.restartedControl();
        V2LocalReviewControl.Submission duplicate = restarted.submit(
                "task-1", "", "REQUEST_CHANGES", List.of(comment.id()));
        assertThat(duplicate.turnId()).isEqualTo(first.turnId());
        assertThat(fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM local_feedback_batch", Integer.class)).isOne();

        control.editComment(comment.id(), "Keep the newer null-handling note open");
        succeedFeedbackTurn(fixture, first.turnId());
        String batchId = fixture.jdbc().queryForObject(
                "SELECT id FROM local_feedback_batch", String.class);
        fixture.commands().executeVoid("task-1", () ->
                control.acceptFeedbackResultInCommand(
                        "task-1", batchId, first.turnId()));
        assertThat(fixture.jdbc().queryForList("""
                SELECT state FROM local_review_comment_revision
                WHERE thread_id = ? ORDER BY revision
                """, String.class, comment.id()))
                .containsExactly("SUPERSEDED", "ADDRESSED", "PENDING");
        assertThat(fixture.jdbc().queryForObject("""
                SELECT resolved_at_ms FROM pr_comment WHERE id = ?
                """, Long.class, comment.id())).isNull();
        assertThat(fixture.jdbc().queryForObject("""
                SELECT COUNT(*) FROM task_blocker
                WHERE blocker_type = 'LOCAL_FEEDBACK_OPEN' AND status = 'OPEN'
                """, Integer.class)).isOne();

        assertThatThrownBy(() -> fixture.jdbc().update("""
                UPDATE local_feedback_batch_item SET frozen_body = 'changed'
                """))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void userReplyCreatesActionableRevisionWithoutMutatingAgentRoot()
            throws Exception
    {
        Fixture fixture = fixture("user-reply.db");
        seedReviewSession(fixture.jdbc(), "review-reply");
        fixture.control().requestAgentReview(
                "task-1", "review-reply", roundId("review-reply"), false);
        PRComment root = fixture.control().addComment(
                localPr(), PRComment.ORIGIN_LOCAL, PRComment.SCOPE_FILE_LINE,
                "src/Main.java", 11, "RIGHT", null, null,
                PRTimelineEntry.ACTOR_AGENT, "Potential null dereference", null);
        fixture.control().addComment(
                localPr(), PRComment.ORIGIN_LOCAL, PRComment.SCOPE_PR,
                null, null, null, null, null,
                PRTimelineEntry.ACTOR_USER, "This also fails for blank values", root.id());

        assertThat(fixture.jdbc().queryForList("""
                SELECT author_kind FROM local_review_comment_revision
                WHERE thread_id = ? ORDER BY revision
                """, String.class, root.id()))
                .containsExactly("ADVISORY_REVIEW", "USER");
        assertThat(fixture.jdbc().queryForObject("""
                SELECT COUNT(*) FROM task_blocker
                WHERE blocker_type = 'LOCAL_FEEDBACK_OPEN' AND status = 'OPEN'
                """, Integer.class)).isOne();
    }

    @Test
    void agentRootWithoutAnExactCurrentReviewRequestFailsClosed()
            throws Exception
    {
        Fixture fixture = fixture("agent-root-without-request.db");

        assertThatThrownBy(() -> fixture.control().addComment(
                localPr(), PRComment.ORIGIN_LOCAL, PRComment.SCOPE_PR,
                null, null, null, null, null,
                PRTimelineEntry.ACTOR_AGENT, "Unowned agent finding", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("exact active AgentReview request");
        assertThat(fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM local_review_thread", Integer.class))
                .isZero();
        assertThat(fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM pr_comment", Integer.class)).isZero();
    }

    @Test
    void successfulBatchAcceptsAnExplicitlyDismissedSubmittedRevision()
            throws Exception
    {
        Fixture fixture = fixture("dismissed-feedback.db");
        PRComment comment = fixture.control().addComment(
                localPr(), PRComment.ORIGIN_LOCAL, PRComment.SCOPE_PR,
                null, null, null, null, null,
                PRTimelineEntry.ACTOR_USER, "No longer relevant", null);
        V2LocalReviewControl.Submission submission = fixture.control().submit(
                "task-1", "", "REQUEST_CHANGES", List.of(comment.id()));

        fixture.control().resolveComment(comment.id());
        succeedFeedbackTurn(fixture, submission.turnId());
        String batchId = fixture.jdbc().queryForObject(
                "SELECT id FROM local_feedback_batch", String.class);
        fixture.commands().executeVoid("task-1", () ->
                fixture.control().acceptFeedbackResultInCommand(
                        "task-1", batchId, submission.turnId()));

        assertThat(fixture.jdbc().queryForObject("""
                SELECT state FROM local_review_comment_revision
                WHERE thread_id = ?
                """, String.class, comment.id())).isEqualTo("DISMISSED");
        assertThat(fixture.jdbc().queryForObject(
                "SELECT status FROM local_feedback_batch", String.class))
                .isEqualTo("ADDRESSED");
    }

    @Test
    void failedFeedbackTurnReopensItsExactRevisionForRetry()
            throws Exception
    {
        Fixture fixture = fixture("failed-feedback.db");
        PRComment comment = fixture.control().addComment(
                localPr(), PRComment.ORIGIN_LOCAL, PRComment.SCOPE_PR,
                null, null, null, null, null,
                PRTimelineEntry.ACTOR_USER, "Retry this feedback", null);
        V2LocalReviewControl.Submission submission = fixture.control().submit(
                "task-1", "", "REQUEST_CHANGES", List.of(comment.id()));
        terminalFeedbackTurn(fixture, submission.turnId(), "FAILED");
        String batchId = fixture.jdbc().queryForObject(
                "SELECT id FROM local_feedback_batch", String.class);

        fixture.commands().executeVoid("task-1", () ->
                fixture.control().rejectFeedbackResultInCommand(
                        "task-1", batchId, submission.turnId(),
                        "FAILED", "writer failed", true));

        assertThat(fixture.jdbc().queryForList("""
                SELECT state FROM local_review_comment_revision
                WHERE thread_id = ? ORDER BY revision
                """, String.class, comment.id()))
                .containsExactly("DISMISSED", "PENDING");
        assertThat(fixture.jdbc().queryForObject(
                "SELECT status FROM local_feedback_batch", String.class))
                .isEqualTo("FAILED");
        assertThat(fixture.jdbc().queryForObject("""
                SELECT COUNT(*) FROM task_blocker
                WHERE blocker_type = 'LOCAL_FEEDBACK_OPEN' AND status = 'OPEN'
                """, Integer.class)).isOne();
    }

    @Test
    void acceptedWriterHeadCarriesPendingUserFeedbackAsANewRevision()
            throws Exception
    {
        Fixture fixture = fixture("carry-feedback.db");
        PRComment comment = fixture.control().addComment(
                localPr(), PRComment.ORIGIN_LOCAL, PRComment.SCOPE_PR,
                null, null, null, null, null,
                PRTimelineEntry.ACTOR_USER, "Preserve this follow-up", null);
        advanceHead(fixture.jdbc());

        fixture.commands().executeVoid("task-1", () ->
                fixture.control().carryFeedbackToCurrentSubjectInCommand(
                        "task-1", "development-turn-2"));

        assertThat(fixture.jdbc().queryForList("""
                SELECT state || '|' || code_fingerprint || '|' || dev_report_id
                    AS value
                FROM local_review_comment_revision
                WHERE thread_id = ? ORDER BY revision
                """, String.class, comment.id()))
                .containsExactly(
                        "SUPERSEDED|fp-1|report-1",
                        "PENDING|fp-2|report-2");
        assertThat(fixture.jdbc().queryForObject("""
                SELECT COUNT(*) FROM task_blocker
                WHERE blocker_type = 'LOCAL_FEEDBACK_OPEN' AND status = 'OPEN'
                """, Integer.class)).isOne();
    }

    @Test
    void staleHeadRejectsOldCommentAndAgentFinding()
            throws Exception
    {
        Fixture fixture = fixture("stale-head.db");
        PRComment comment = fixture.control().addComment(
                localPr(), PRComment.ORIGIN_LOCAL, PRComment.SCOPE_PR,
                null, null, null, null, null,
                PRTimelineEntry.ACTOR_USER, "Old-head feedback", null);
        seedReviewSession(fixture.jdbc(), "review-stale");
        fixture.control().requestAgentReview(
                "task-1", "review-stale", roundId("review-stale"), true);
        seedCompletedFinding(fixture.jdbc(), "review-stale", "finding-stale");
        advanceHead(fixture.jdbc());

        assertThatThrownBy(() -> fixture.control().submit(
                "task-1", "", "REQUEST_CHANGES", List.of(comment.id())))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("stale");
        assertThatThrownBy(() -> fixture.control().importSelectedFindings(
                "review-stale", roundId("review-stale"),
                List.of("finding-stale")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("stale");
        assertThat(fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM local_feedback_batch", Integer.class)).isZero();

        fixture.commands().executeVoid("task-1", () ->
                fixture.control().carryFeedbackToCurrentSubjectInCommand(
                        "task-1", "development-turn-2"));
        assertThat(fixture.jdbc().queryForObject("""
                SELECT status FROM local_review_agent_request
                WHERE review_id = 'review-stale'
                """, String.class)).isEqualTo("STALE");
        assertThat(fixture.jdbc().queryForObject("""
                SELECT status FROM task_blocker
                WHERE blocker_type = 'LOCAL_AGENT_REVIEW_BLOCKING'
                """, String.class)).isEqualTo("RESOLVED");
    }

    @Test
    void continuedReviewGetsANewRoundBoundRequestAndOneBlockingOwner()
            throws Exception
    {
        Fixture fixture = fixture("continued-review.db");
        seedReviewSession(fixture.jdbc(), "review-continued");
        fixture.control().requestAgentReview(
                "task-1", "review-continued", roundId("review-continued"), true);
        seedQueuedRound(
                fixture.jdbc(), "review-continued", "round-review-continued-2",
                "run-review-continued-2", 23);

        V2LocalReviewControl.AgentReviewRequest continued =
                fixture.control().continueAgentReview(
                        "task-1", "review-continued", "round-review-continued-2");

        assertThat(continued.blocking()).isTrue();
        assertThat(continued.reviewRoundId()).isEqualTo("round-review-continued-2");
        assertThat(fixture.jdbc().queryForList("""
                SELECT review_round_id || '|' || status AS value
                FROM local_review_agent_request
                WHERE review_id = 'review-continued'
                ORDER BY requested_at_ms, review_round_id
                """, String.class)).containsExactly(
                        "round-review-continued|STALE",
                        "round-review-continued-2|REQUESTED");
        assertThat(fixture.jdbc().queryForObject("""
                SELECT COUNT(*) FROM task_blocker
                WHERE blocker_type = 'LOCAL_AGENT_REVIEW_BLOCKING'
                  AND status = 'OPEN'
                """, Integer.class)).isOne();

        V2LocalReviewControl.AgentReviewRequest replay =
                fixture.control().continueAgentReview(
                        "task-1", "review-continued",
                        "round-review-continued-2");
        assertThat(replay.id()).isEqualTo(continued.id());
        assertThat(fixture.jdbc().queryForObject("""
                SELECT COUNT(*) FROM local_review_agent_request
                WHERE review_id = 'review-continued'
                """, Integer.class)).isEqualTo(2);
    }

    @Test
    void continuationRequiresAPriorRequestOwnedByTheSameTask()
            throws Exception
    {
        Fixture missing = fixture("continued-review-missing-prior.db");
        seedReviewSession(missing.jdbc(), "review-missing-prior");
        seedQueuedRound(
                missing.jdbc(), "review-missing-prior",
                "round-review-missing-prior-2",
                "run-review-missing-prior-2", 23);

        assertThatThrownBy(() -> missing.control().continueAgentReview(
                "task-1", "review-missing-prior",
                "round-review-missing-prior-2"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no exact prior");
        assertThat(missing.jdbc().queryForObject(
                "SELECT COUNT(*) FROM local_review_agent_request", Integer.class))
                .isZero();

        Fixture mismatched = fixture("continued-review-wrong-task.db");
        seedReviewSession(mismatched.jdbc(), "review-wrong-task");
        mismatched.control().requestAgentReview(
                "task-1", "review-wrong-task",
                roundId("review-wrong-task"), true);
        seedQueuedRound(
                mismatched.jdbc(), "review-wrong-task",
                "round-review-wrong-task-2",
                "run-review-wrong-task-2", 23);

        assertThatThrownBy(() -> mismatched.control().continueAgentReview(
                "another-task", "review-wrong-task",
                "round-review-wrong-task-2"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no exact prior");
        assertThat(mismatched.jdbc().queryForObject("""
                SELECT COUNT(*) FROM local_review_agent_request
                WHERE review_id = 'review-wrong-task'
                """, Integer.class)).isOne();
    }

    @Test
    void blockingReviewImportsSelectedFindingAndNeverCrossesSiblingTask()
            throws Exception
    {
        Fixture fixture = fixture("blocking-review.db");
        seedReviewSession(fixture.jdbc(), "review-blocking");
        V2LocalReviewControl.AgentReviewRequest request =
                fixture.control().requestAgentReview(
                        "task-1", "review-blocking",
                        roundId("review-blocking"), true);
        assertThat(request.blocking()).isTrue();
        assertThat(fixture.jdbc().queryForObject("""
                SELECT COUNT(*) FROM task_blocker
                WHERE blocker_type = 'LOCAL_AGENT_REVIEW_BLOCKING'
                  AND status = 'OPEN'
                """, Integer.class)).isOne();

        seedCompletedFinding(fixture.jdbc(), "review-blocking", "finding-1");
        V2LocalReviewControl.Submission imported =
                fixture.control().importSelectedFindings(
                        "review-blocking", roundId("review-blocking"),
                        List.of("finding-1"));
        assertThat(imported.submitted()).isOne();
        assertThat(imported.turnId()).isNotBlank();
        assertThat(fixture.jdbc().queryForObject("""
                SELECT status FROM local_review_agent_request
                WHERE review_id = 'review-blocking'
                """, String.class)).isEqualTo("IMPORTED");
        assertThat(fixture.jdbc().queryForObject("""
                SELECT status FROM task_blocker
                WHERE blocker_type = 'LOCAL_AGENT_REVIEW_BLOCKING'
                """, String.class)).isEqualTo("RESOLVED");
        assertThat(fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM local_review_imported_finding",
                Integer.class)).isOne();

        Fixture sibling = fixture("sibling-review.db");
        seedReviewSession(sibling.jdbc(), "review-owned-by-task-1");
        sibling.jdbc().update("""
                INSERT INTO tasks(
                    id, thread_id, seq, status, phase, created_at_ms,
                    workflow_version, lifecycle_state)
                VALUES ('task-sibling', 'trunk-1', 2, 'IDLE', 'PLANNING', 40,
                    'LEGACY', 'ACTIVE')
                """);
        assertThatThrownBy(() -> sibling.control().requestAgentReview(
                "task-sibling", "review-owned-by-task-1",
                roundId("review-owned-by-task-1"), true))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(sibling.jdbc().queryForObject(
                "SELECT COUNT(*) FROM local_review_agent_request",
                Integer.class)).isZero();
    }

    @Test
    void findingImportRequiresTheExactRequestedReviewRound()
            throws Exception
    {
        Fixture fixture = fixture("review-round-finding-fence.db");
        String reviewId = "review-round-fence";
        String requestedRoundId = roundId(reviewId);
        String siblingRoundId = "round-review-round-fence-2";
        seedReviewSession(fixture.jdbc(), reviewId);
        V2LocalReviewControl.AgentReviewRequest request =
                fixture.control().requestAgentReview(
                        "task-1", reviewId, requestedRoundId, false);
        seedCompletedFinding(
                fixture.jdbc(), reviewId, requestedRoundId,
                "run-" + reviewId, "finding-requested-round");
        seedQueuedRound(
                fixture.jdbc(), reviewId, siblingRoundId,
                "run-review-round-fence-2", 23);
        seedCompletedFinding(
                fixture.jdbc(), reviewId, siblingRoundId,
                "run-review-round-fence-2", "finding-sibling-round");

        assertThatThrownBy(() -> fixture.control().importSelectedFindings(
                reviewId, requestedRoundId, List.of("finding-sibling-round")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("exact completed review round");
        assertThat(fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM local_review_imported_finding",
                Integer.class)).isZero();

        PRComment comment = fixture.control().addComment(
                localPr(), PRComment.ORIGIN_LOCAL, PRComment.SCOPE_FILE_LINE,
                "src/Main.java", 12, "RIGHT", null, null,
                PRTimelineEntry.ACTOR_AGENT, "Sibling-round finding", null);
        String revisionId = fixture.jdbc().queryForObject("""
                SELECT id FROM local_review_comment_revision
                WHERE thread_id = ?
                """, String.class, comment.id());
        assertThatThrownBy(() -> fixture.jdbc().update("""
                INSERT INTO local_review_imported_finding(
                    request_id, finding_id, thread_id, comment_revision_id,
                    imported_by, imported_at_ms)
                VALUES (?, 'finding-sibling-round', ?, ?, 'user', 30)
                """, request.id(), comment.id(), revisionId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("exact completed review");
    }

    private Fixture fixture(String name)
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve(name) + "?foreign_keys=ON";
        Flyway.configure().dataSource(url, "", "").target("228").load().migrate();
        try (Connection connection = DriverManager.getConnection(url)) {
            execute(connection, "PRAGMA foreign_keys = ON");
            TestDevelopmentFlowLocalPublishProtocolMigration
                    .seedApprovedLocalSubject(connection);
        }
        Flyway.configure().dataSource(url, "", "").target("259").load().migrate();
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TaskCommandExecutor commands = new TaskCommandExecutor(
                new DataSourceTransactionManager(dataSource));
        AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext();
        context.registerBean(JdbcTemplate.class, () -> jdbc);
        context.scan("com.bytequay.app.developmentflow.stage.persistence");
        context.refresh();
        LocalDevelopmentStageManager local = new LocalDevelopmentStageManager(
                commands, context.getBean(StageManager.Store.class),
                context.getBean(LocalDevelopmentStageManager.EvidenceStore.class));
        return new Fixture(
                jdbc, commands, local, context,
                new V2LocalReviewControl(
                        jdbc, commands, local, new ObjectMapper(),
                        mock(ApplicationEventPublisher.class), 53123));
    }

    private static PR localPr()
    {
        return PR.create(
                "pr-1", "task-1", "dev/task-1", "main",
                "Implement feature", "Description", Instant.ofEpochMilli(6))
                .withStatus(PR.STATUS_LOCAL_OPEN, Instant.ofEpochMilli(7));
    }

    private static void seedReviewSession(JdbcTemplate jdbc, String reviewId)
    {
        jdbc.update("""
                INSERT INTO review_session(
                    id, repo_id, pr_id, base_commit, reviewed_head_commit,
                    status, created_at_ms, updated_at_ms, workspace_id,
                    owner_thread_id, owner_task_id)
                VALUES (?, 'local', 'pr-1', 'base-1', 'head-1', 'ACTIVE',
                    20, 20, 'workspace-1', 'trunk-1', 'task-1')
                """, reviewId);
        String runId = "run-" + reviewId;
        String roundId = roundId(reviewId);
        seedQueuedRound(jdbc, reviewId, roundId, runId, 21);
    }

    private static void seedQueuedRound(
            JdbcTemplate jdbc,
            String reviewId,
            String roundId,
            String runId,
            long createdAt)
    {
        jdbc.update("""
                INSERT INTO agent_run(
                    id, kind, review_round_id, status, iterations,
                    started_at_ms)
                VALUES (?, 'panel_review', ?, 'QUEUED', 0, ?)
                """, runId, roundId, createdAt);
        jdbc.update("""
                INSERT INTO review_round(
                    id, session_id, agent_run_id, trigger, scope,
                    start_commit, status, budget_json, cost_cents,
                    created_at_ms)
                VALUES (?, ?, ?, 'initial', 'full', 'head-1', 'QUEUED',
                    '{"costCapCents":100,"wallClockMinutes":5}', 0, ?)
                """, roundId, reviewId, runId, createdAt);
    }

    private static void seedCompletedFinding(
            JdbcTemplate jdbc, String reviewId, String findingId)
    {
        seedCompletedFinding(
                jdbc, reviewId, roundId(reviewId), "run-" + reviewId, findingId);
    }

    private static void seedCompletedFinding(
            JdbcTemplate jdbc,
            String reviewId,
            String roundId,
            String runId,
            String findingId)
    {
        String objectiveId = "objective-" + findingId;
        jdbc.update("""
                UPDATE agent_run
                SET status = 'SUCCEEDED', iterations = 1, finished_at_ms = 22
                WHERE id = ? AND review_round_id = ?
                """, runId, roundId);
        jdbc.update("""
                UPDATE review_round
                SET end_commit = 'head-1', status = 'COMPLETED',
                    cost_cents = 1, finished_at_ms = 22
                WHERE id = ? AND session_id = ? AND agent_run_id = ?
                """, roundId, reviewId, runId);
        jdbc.update("""
                INSERT OR IGNORE INTO criterion(
                    id, repo_id, kind, statement, source_type)
                VALUES ('criterion-local-review', 'local', 'engineering-principle',
                    'Avoid null dereferences', 'builtin')
                """);
        jdbc.update("""
                INSERT INTO review_objective(
                    id, round_id, criterion_id, statement, source,
                    applicability_status, resolution_status)
                VALUES (?, ?, 'criterion-local-review', 'Check null handling',
                    'plan', 'applicable', 'resolved')
                """, objectiveId, roundId);
        jdbc.update("""
                INSERT INTO finding(
                    id, session_id, round_id, objective_id, criterion_kind,
                    path, start_line, end_line, claim, severity,
                    confidence_class, verification_status, requested_action,
                    lifecycle_status, last_checked_commit)
                VALUES (?, ?, ?, ?, 'engineering-principle', 'src/Main.java',
                    12, 12, 'Blank input can dereference null', 4, 'SUPPORTED',
                    'verified', 'Guard blank input', 'open', 'head-1')
                """, findingId, reviewId, roundId, objectiveId);
    }

    private static String roundId(String reviewId)
    {
        return "round-" + reviewId;
    }

    private static void advanceHead(JdbcTemplate jdbc)
    {
        jdbc.update("""
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status,
                    operation_id, attempt, task_epoch,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms, started_at_ms, finished_at_ms)
                VALUES ('development-turn-2', 'local-stage-1', 1,
                    'IMPLEMENT_LOCAL_DEVELOPMENT', 'SUCCEEDED',
                    'development-turn-operation-2', 1, 1,
                    'fp-1', 'head-1', 'base-1', 'CLI', 'implement', 30, 30, 31)
                """);
        jdbc.update("""
                INSERT INTO dev_report(
                    id, task_id, summary, created_at_ms, workflow_version,
                    local_development_stage_id, task_epoch, stage_generation,
                    stage_turn_id, revision, code_fingerprint, head_sha, base_sha,
                    implemented_intent, commit_summary, file_summary,
                    validation_summary, known_risks, unresolved_concerns,
                    context_refs, source_code_fingerprint, source_head_sha,
                    source_base_sha)
                VALUES ('report-2', 'task-1', 'new head', 31, 'V2',
                    'local-stage-1', 1, 1, 'development-turn-2', 2,
                    'fp-2', 'head-2', 'base-1', 'change code', 'one commit',
                    'one file', 'pending', 'none', 'none', 'turn:2',
                    'fp-1', 'head-1', 'base-1')
                """);
    }

    private static void succeedFeedbackTurn(Fixture fixture, String turnId)
    {
        terminalFeedbackTurn(fixture, turnId, "SUCCEEDED");
    }

    private static void terminalFeedbackTurn(
            Fixture fixture, String turnId, String status)
    {
        fixture.jdbc().update("""
                UPDATE stage_turn
                SET status = ?, started_at_ms = requested_at_ms,
                    finished_at_ms = requested_at_ms + 1
                WHERE id = ?
                """, status, turnId);
    }

    private static void execute(Connection connection, String sql)
            throws SQLException
    {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private record Fixture(
            JdbcTemplate jdbc,
            TaskCommandExecutor commands,
            LocalDevelopmentStageManager local,
            AnnotationConfigApplicationContext context,
            V2LocalReviewControl control)
    {
        private V2LocalReviewControl restartedControl()
        {
            return new V2LocalReviewControl(
                    jdbc, commands, local, new ObjectMapper(),
                    mock(ApplicationEventPublisher.class), 53123);
        }
    }
}

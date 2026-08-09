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

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.persistence.SqliteDispatchWakeStore;
import com.bytequay.app.developmentflow.stage.LocalDevelopmentStageManager;
import com.bytequay.app.developmentflow.stage.StageManager;
import com.bytequay.app.developmentflow.stage.V2LocalReviewControl;
import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.DiffFile;
import com.bytequay.app.domain.InvestigationReviewData.AgentReviewRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewRoundRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewerDefRow;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRComment;
import com.bytequay.app.domain.PRTimelineEntry;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.sqlite.InvestigationReviewStore;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.review.InvestigationReviewContext;
import com.bytequay.app.service.review.InvestigationReviewRunner;
import com.bytequay.app.service.review.InvestigationReviewService;
import com.bytequay.app.service.review.ReviewAssignmentTurnRuntime;
import com.bytequay.app.service.review.TaskReviewSnapshotOperationHandler;
import com.bytequay.app.service.review.TaskReviewSnapshotOperationHandler.SnapshotResult;
import com.bytequay.app.service.review.TaskReviewSnapshotResultDeliveryPort;
import com.bytequay.app.service.review.TaskReviewSnapshotRuntime;
import com.bytequay.app.service.runs.AgentRunServiceImpl;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.bytequay.app.service.workspaces.WorkspaceService;
import com.bytequay.app.testing.MigratedSqliteDatabase;
import com.bytequay.app.testing.SqliteTestPools;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(SqliteTestPools.class)
class TestV2LocalReviewControl
{
    @TempDir
    private Path tempDir;

    @Test
    void titleAndBodyEditUsesTheExactCurrentLocalReviewSubject()
            throws Exception
    {
        Fixture fixture = fixture("edit-pr-content.db");

        fixture.control().updateDetails(
                localPr(), "Exact title", "Exact body");

        assertThat(fixture.jdbc().queryForMap(
                "SELECT title, description FROM pr WHERE id = 'pr-1'"))
                .containsEntry("title", "Exact title")
                .containsEntry("description", "Exact body");

        fixture.jdbc().update(
                "UPDATE stage SET checkpoint = 'VALIDATING', version = version + 1 "
                        + "WHERE id = 'local-stage-1'");
        assertThatThrownBy(() -> fixture.control().updateDetails(
                localPr(), "Stale title", "Stale body"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("only during exact Local Review");
    }

    @Test
    void snapshotDeliveryEntersTheTaskCommandBeforeReviewTransaction()
            throws Exception
    {
        Fixture fixture = fixture("task-review-delivery.db");
        Flyway.configure().dataSource(fixture.jdbc().getDataSource()).load().migrate();
        seedTaskReviewSnapshot(fixture.jdbc());

        ObjectMapper json = new ObjectMapper();
        TaskReviewSnapshotRuntime snapshots =
                new TaskReviewSnapshotRuntime(fixture.jdbc(), json);
        InvestigationReviewStore reviews = mock(InvestigationReviewStore.class);
        InvestigationReviewContext contexts = mock(
                InvestigationReviewContext.class);
        InvestigationReviewRunner runner = mock(
                InvestigationReviewRunner.class, invocation ->
                        "investigationPrompt".equals(
                                invocation.getMethod().getName())
                                ? reviewTurnPrompt()
                                : RETURNS_DEFAULTS.answer(invocation));
        AgentRunServiceImpl runs = mock(AgentRunServiceImpl.class);
        PRService prs = mock(PRService.class);
        TaskStore tasks = mock(TaskStore.class);
        ThreadStore threads = mock(ThreadStore.class);
        AgentReviewRow review = new AgentReviewRow(
                "review-1", "local", "pr-1", "base-1", "head-1",
                "ACTIVE", null, null, "task-1");
        ReviewerDefRow reviewer = new ReviewerDefRow(
                "api-reviewer", "API reviewer", "Reviews exact code", "api",
                json.createObjectNode().put("provider", "auto"), null,
                List.of("trivial", "standard", "high-risk"), true);
        AtomicReference<ReviewRoundRow> round = new AtomicReference<>();
        when(reviews.findReview("review-1")).thenReturn(Optional.of(review));
        when(reviews.findActiveReviewByPr("pr-1"))
                .thenReturn(Optional.of(review));
        when(reviews.reviewerDefs()).thenReturn(List.of(reviewer));
        when(reviews.insertLiveRound(any(), any())).thenAnswer(invocation -> {
            ReviewRoundRow inserted = invocation.getArgument(0);
            round.set(inserted);
            fixture.jdbc().update("""
                    INSERT INTO agent_run(
                        id, kind, source, review_round_id, status,
                        started_at_ms, finished_at_ms, outcome)
                    VALUES (?, 'review_compatibility_header',
                        'v2_review_assignment_turn_fk', ?, 'succeeded',
                        30, 30, 'completed')
                    """, inserted.agentRunId(), inserted.id());
            fixture.jdbc().update("""
                    INSERT INTO review_round(
                        id, session_id, agent_run_id, trigger, scope,
                        start_commit, status, budget_json, cost_cents,
                        created_at_ms)
                    VALUES (?, 'review-1', ?, 'initial', 'full', 'head-1',
                        'RUNNING', '{"costCapCents":50,"wallClockMinutes":5}',
                        0, 30)
                    """, inserted.id(), inserted.agentRunId());
            return inserted;
        });
        when(reviews.findRound(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(round.get()).filter(value ->
                        value.id().equals(invocation.getArgument(0))));
        when(prs.findById("pr-1")).thenReturn(Optional.of(localPr()));
        when(tasks.isV2Task("task-1")).thenReturn(true);
        when(runner.reviewKnowledge(any())).thenReturn(List.of());
        InvestigationReviewRunner.ProviderChoice provider =
                new InvestigationReviewRunner.ProviderChoice(
                        "openai", "api", "openai");
        when(runner.choose(anyString(), isNull())).thenReturn(provider);
        when(runs.createReviewCompatibilityHeader(anyString(), any()))
                .thenAnswer(invocation -> new AgentRun(
                        "run-1", null,
                        AgentRun.KIND_REVIEW_COMPATIBILITY_HEADER,
                        AgentRun.SOURCE_V2_REVIEW_FOREIGN_KEY,
                        null, invocation.getArgument(0), null,
                        AgentRun.STATUS_SUCCEEDED, 0, 50, null, null,
                        Instant.EPOCH, Instant.EPOCH));
        ReviewAssignmentTurnRuntime typed = mock(
                ReviewAssignmentTurnRuntime.class);
        when(typed.flow(anyString())).thenAnswer(invocation -> Optional.of(
                new ReviewAssignmentTurnRuntime.RoundFlow(
                        invocation.getArgument(0), "head-1",
                        ReviewAssignmentTurnRuntime.FlowPhase.PRIMARY,
                        null, null, 0)));
        when(typed.turns(anyString())).thenReturn(List.of(
                new ReviewAssignmentTurnRuntime.TurnState(
                        "turn-1", "assignment-1",
                        ReviewAssignmentTurnRuntime.INVESTIGATE,
                        "assignment-1", null, 1, "REQUESTED", "{}",
                        null, 0, 0, 0)));

        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext()) {
            context.register(CommandTransactions.class);
            context.registerBean(PlatformTransactionManager.class,
                    fixture::transactions);
            context.registerBean(InvestigationReviewService.class, () -> {
                InvestigationReviewService service =
                        new InvestigationReviewService(
                                reviews, contexts, runner, runs, prs, tasks,
                                threads, json, mock(WorkspaceService.class));
                service.setReviewAssignmentTurnRuntime(typed);
                service.setV2LocalReview(fixture.control());
                service.setTaskReviewSnapshots(snapshots);
                return service;
            });
            context.refresh();
            TaskReviewSnapshotResultDeliveryPort delivery =
                    new TaskReviewSnapshotResultDeliveryPort(
                            snapshots,
                            context.getBeanProvider(
                                    InvestigationReviewService.class),
                            fixture.commands(), json);
            TaskReviewSnapshotRuntime.ExecutionSubject subject =
                    snapshots.requireExecutionSubject("operation-1");
            SnapshotResult result = snapshotResult();
            String evidence = json.writeValueAsString(result);
            DispatchTicket.OperationFence fence = snapshotFence();

            assertThat(delivery.deliver(
                    new DispatchTicket.OwnerReference(
                            DispatchTicket.OwnerKind.TASK, "task-1",
                            TaskReviewSnapshotOperationHandler.CALLBACK_ROUTE),
                    fence, new DispatchTicket.DispatchResult(
                            fence, DispatchTicket.Outcome.SUCCEEDED,
                            evidence, evidence, null)).acceptance())
                    .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
            assertThat(subject.current()).isTrue();
        }

        assertThat(fixture.jdbc().queryForObject("""
                SELECT COUNT(*) FROM local_review_agent_request
                WHERE task_id = 'task-1' AND review_id = 'review-1'
                  AND status = 'REQUESTED'
                """, Integer.class)).isOne();
        assertThat(fixture.jdbc().queryForObject("""
                SELECT status FROM task_review_snapshot_operation_v286
                WHERE id = 'operation-1'
                """, String.class)).isEqualTo("COMPLETED");
    }

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
                JOIN stage_turn ON stage_turn.id = dispatch_ticket.owner_id
                WHERE owner_kind = 'STAGE_TURN'
                  AND callback_route = 'STAGE_TURN_RESULT'
                  AND writer_required = 1
                  AND stage_turn.purpose = 'ADDRESS_LOCAL_FEEDBACK'
                """, Integer.class)).isOne();
        String writerSystemPrompt = new ObjectMapper().readTree(
                fixture.jdbc().queryForObject("""
                        SELECT launch_input FROM stage_turn WHERE id = ?
                        """, String.class, first.turnId()))
                .path("systemPrompt").asText();
        assertThat(writerSystemPrompt)
                .contains("Return exactly one raw JSON object")
                .contains("first non-whitespace character must be '{'")
                .contains("last non-whitespace character must be '}'")
                .contains("Do not wrap it in Markdown fences")
                .contains("or add prose before or after it");

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
                        "SUPERSEDED|fingerprint-1|report-1",
                        "PENDING|fingerprint-2|report-2");
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
        MigratedSqliteDatabase.migrate(url);
        try (Connection connection = DriverManager.getConnection(url)) {
            execute(connection, "PRAGMA foreign_keys = ON");
            DevelopmentFlowRemoteProtocolFixture.seedWorkspaceAndTrunk(connection);
            DevelopmentFlowRemoteProtocolFixture.seedLocalDevelopmentTask(
                    connection, 1);
            DevelopmentFlowRemoteProtocolFixture.seedApprovedEvidence(
                    connection, 1);
            execute(connection, """
                    UPDATE pr SET title = 'Implement feature'
                    WHERE id = 'pr-1'
                    """);
        }
        DataSource dataSource = SqliteTestPools.open(url);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        DataSourceTransactionManager transactionManager =
                new DataSourceTransactionManager(dataSource);
        TaskCommandExecutor commands = new TaskCommandExecutor(transactionManager);
        AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext();
        context.registerBean(JdbcTemplate.class, () -> jdbc);
        context.registerBean(TransactionTemplate.class,
                () -> new TransactionTemplate(transactionManager));
        context.registerBean(SqliteDispatchWakeStore.class,
                () -> new SqliteDispatchWakeStore(jdbc));
        context.scan("com.bytequay.app.developmentflow.stage.persistence");
        context.refresh();
        LocalDevelopmentStageManager local = new LocalDevelopmentStageManager(
                commands, context.getBean(StageManager.Store.class),
                context.getBean(LocalDevelopmentStageManager.EvidenceStore.class));
        return new Fixture(
                jdbc, transactionManager, commands, local, context,
                new V2LocalReviewControl(
                        jdbc, commands, local, new ObjectMapper(), 53123));
    }

    private static PR localPr()
    {
        return PR.create(
                "pr-1", "task-1", "dev/task-1", "main",
                "Implement feature", "Description", Instant.ofEpochMilli(6))
                .withStatus(PR.STATUS_LOCAL_OPEN, Instant.ofEpochMilli(7));
    }

    private static void seedTaskReviewSnapshot(JdbcTemplate jdbc)
    {
        jdbc.update("""
                INSERT INTO review_session(
                    id, repo_id, pr_id, base_commit, reviewed_head_commit,
                    status, workspace_id, owner_thread_id, owner_task_id,
                    created_at_ms, updated_at_ms)
                VALUES ('review-1', 'local', 'pr-1', 'base-1', 'head-1',
                    'ACTIVE', 'workspace-1', 'trunk-1', 'task-1', 20, 20)
                """);
        jdbc.update("""
                INSERT INTO task_review_snapshot_operation_v286(
                    id, review_id, pr_id, repository, remote_pr_number,
                    base_branch, pr_title, pr_description,
                    task_id, task_epoch, worktree_path,
                    code_fingerprint, expected_head_sha, expected_base_sha,
                    start_options_json, status, requested_at_ms)
                VALUES ('operation-1', 'review-1', 'pr-1', NULL, NULL,
                    'main', 'Implement feature', 'Description', 'task-1', 1,
                    '/tmp/task-1', 'fingerprint-1', 'head-1', 'base-1',
                    '{}', 'REQUESTED', 21)
                """);
    }

    private static SnapshotResult snapshotResult()
    {
        return new SnapshotResult(
                1, "operation-1", "review-1", "pr-1", "task-1",
                null, null, "main", "Implement feature", "Description", 1,
                "/tmp/task-1", "fingerprint-1", "head-1", "base-1", true,
                "diff --git a/src/Main.java b/src/Main.java\n+change\n",
                List.of(new DiffFile(
                        "src/Main.java", "M", 0, 0, null)),
                Map.of("src/Main.java", "change\n"),
                "fingerprint-1", "head-1", 22, 23);
    }

    private static Object reviewTurnPrompt()
            throws ReflectiveOperationException
    {
        Class<?> type = Class.forName(
                "com.bytequay.app.service.review.InvestigationReviewRunner$ReviewTurnPrompt");
        var constructor = type.getDeclaredConstructor(String.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance("review system", "review exact head");
    }

    private static DispatchTicket.OperationFence snapshotFence()
    {
        return new DispatchTicket.OperationFence(
                1L, null, null, "operation-1", 1,
                "fingerprint-1", "head-1", "base-1");
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
                    id, kind, source, review_round_id, status,
                    started_at_ms, finished_at_ms, outcome)
                VALUES (?, 'review_compatibility_header',
                    'v2_review_assignment_turn_fk', ?, 'succeeded',
                    ?, ?, 'completed')
                """, runId, roundId, createdAt, createdAt);
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
            throws Exception
    {
        jdbc.update("""
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status,
                    operation_id, attempt, task_epoch,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                VALUES ('development-turn-2', 'local-stage-1', 1,
                    'IMPLEMENT_LOCAL_PLAN', 'QUEUED',
                    'development-turn-operation-2', 1, 1,
                    'fingerprint-1', 'head-1', 'base-1', 'API', '{}', 30)
                """);
        jdbc.update("""
                INSERT INTO local_stage_turn_request(
                    id, command_id, stage_turn_id, task_id,
                    local_development_stage_id, task_epoch, stage_generation,
                    kind, queue_mode, prompt_digest, requested_by,
                    requested_at_ms)
                VALUES ('development-request-2', 'development-command-2',
                    'development-turn-2', 'task-1', 'local-stage-1', 1, 1,
                    'IMPLEMENTATION', 'IMMEDIATE', ?, 'fixture', 30)
                """, "e".repeat(64));
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, stage_id, stage_generation, attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                VALUES ('development-ticket-2', 'development-turn-operation-2',
                    'EXECUTE_STAGE_TURN', 'AGENT_TURN', 'STAGE_TURN',
                    'development-turn-2', 'STAGE_TURN_RESULT', 2, 1, 1,
                    'workspace-1', 'trunk-1', 'task-1', 1, 'local-stage-1', 1,
                    1, 'fingerprint-1', 'head-1', 'base-1', 'REQUESTED', 30)
                """);

        ObjectMapper json = new ObjectMapper();
        var output = json.createObjectNode();
        output.put("codeFingerprint", "fingerprint-2");
        output.put("headSha", "head-2");
        output.put("baseSha", "base-1");
        output.put("clean", true);
        output.put("mergeBaseSha", "base-1");
        output.put("sourceTreeSha", "source-tree-2");
        output.put("resultTreeSha", "result-tree-2");
        output.put("sourceHeadMergeBaseSha", "head-1");
        output.put("candidateParentSha", "head-1");
        output.put("branchName", "dev/task-1");
        var payload = json.createObjectNode();
        payload.put("schemaVersion", 1);
        payload.put("turnId", "development-turn-2");
        payload.put("ownerKind", "STAGE_TURN");
        payload.put("purpose", "IMPLEMENT_LOCAL_PLAN");
        payload.put("transport", "API");
        payload.put("provider", "openai");
        payload.put("finalText", "new head");
        payload.put("inputTokens", 1);
        payload.put("outputTokens", 1);
        payload.put("costUsdMilli", 0);
        payload.put("disposition", "PROVIDER_SUCCEEDED");
        payload.set("outputCodeSubject", output);
        var writerFence = json.createObjectNode();
        writerFence.put("worktreePath", "/tmp/task-1");
        writerFence.put("taskId", "task-1");
        writerFence.put("operationId", "development-turn-operation-2");
        writerFence.put("taskEpoch", 1);
        writerFence.put("fencingToken", 1);
        var evidence = json.createObjectNode();
        evidence.put("schemaVersion", 1);
        evidence.put("disposition", "PROVIDER_SUCCEEDED");
        evidence.set("writerFence", writerFence);
        evidence.set("outputCodeSubject", output);
        var fence = json.createObjectNode();
        fence.put("taskEpoch", 1);
        fence.put("stageId", "local-stage-1");
        fence.put("stageGeneration", 1);
        fence.put("operationId", "development-turn-operation-2");
        fence.put("attempt", 1);
        fence.put("expectedCodeFingerprint", "fingerprint-1");
        fence.put("expectedHeadSha", "head-1");
        fence.put("expectedBaseSha", "base-1");
        var raw = json.createObjectNode();
        raw.set("fence", fence);
        raw.put("outcome", "SUCCEEDED");
        raw.put("payloadJson", payload.toString());
        raw.put("evidenceJson", evidence.toString());
        raw.putNull("error");
        jdbc.update("""
                UPDATE dispatch_ticket
                SET version = 1, status = 'RESULT_PENDING',
                    infrastructure_attempts = 1, started_at_ms = 30,
                    pending_result_outcome = 'SUCCEEDED',
                    pending_result_payload = ?, pending_result_evidence = ?,
                    pending_result_task_epoch = 1,
                    pending_result_stage_id = 'local-stage-1',
                    pending_result_stage_generation = 1,
                    pending_result_operation_id = 'development-turn-operation-2',
                    pending_result_attempt = 1,
                    pending_result_expected_code_fingerprint = 'fingerprint-1',
                    pending_result_expected_head_sha = 'head-1',
                    pending_result_expected_base_sha = 'base-1'
                WHERE id = 'development-ticket-2'
                """, payload.toString(), evidence.toString());
        jdbc.update("""
                INSERT INTO agent_execution(
                    id, ticket_id, infrastructure_attempt, provider,
                    status, started_at_ms, finished_at_ms, raw_result)
                VALUES ('development-execution-2', 'development-ticket-2', 1,
                    'openai', 'SUCCEEDED', 30, 31, ?)
                """, raw.toString());
        jdbc.update("""
                UPDATE stage_turn
                SET status = 'SUCCEEDED', started_at_ms = 30,
                    finished_at_ms = 31
                WHERE id = 'development-turn-2'
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
                    'fingerprint-2', 'head-2', 'base-1', 'change code', 'one commit',
                    'one file', 'pending', 'none', 'none', 'turn:2',
                    'fingerprint-1', 'head-1', 'base-1')
                """);
        jdbc.update("""
                INSERT INTO validation_operation(
                    id, local_development_stage_id, task_id, task_epoch,
                    stage_generation, dev_report_id, operation_id,
                    semantic_attempt, code_fingerprint, expected_head_sha,
                    expected_base_sha, status, requested_at_ms)
                VALUES ('validation-operation-2', 'local-stage-1', 'task-1', 1,
                    1, 'report-2', 'validation-operation-id-2', 1,
                    'fingerprint-2', 'head-2', 'base-1', 'REQUESTED', 31)
                """);
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, stage_id, stage_generation, attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                VALUES ('validation-ticket-2', 'validation-operation-id-2',
                    'VALIDATE_LOCAL_DEVELOPMENT', 'VALIDATION', 'STAGE',
                    'local-stage-1', 'STAGE_VALIDATION_RESULT', 4, 1, 0,
                    'workspace-1', 'trunk-1', 'task-1', 1, 'local-stage-1', 1,
                    1, 'fingerprint-2', 'head-2', 'base-1', 'REQUESTED', 31)
                """);
        jdbc.update("""
                UPDATE validation_operation SET status = 'DISPATCHED'
                WHERE id = 'validation-operation-2'
                """);
        jdbc.update("""
                INSERT INTO local_stage_turn_delivery_receipt(
                    stage_turn_id, operation_id, raw_outcome,
                    raw_result_digest, acceptance, dev_report_id,
                    validation_operation_id, recorded_at_ms)
                VALUES ('development-turn-2', 'development-turn-operation-2',
                    'SUCCEEDED', ?, 'ACCEPTED', 'report-2',
                    'validation-operation-2', 31)
                """, "f".repeat(64));
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
            DataSourceTransactionManager transactions,
            TaskCommandExecutor commands,
            LocalDevelopmentStageManager local,
            AnnotationConfigApplicationContext context,
            V2LocalReviewControl control)
    {
        private V2LocalReviewControl restartedControl()
        {
            return new V2LocalReviewControl(
                    jdbc, commands, local, new ObjectMapper(), 53123);
        }
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class CommandTransactions {}
}

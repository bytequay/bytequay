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
package com.bytequay.app.flow.ci;

import com.bytequay.app.flow.ci.CiAutofixRecords.CiLearningCompletion;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiLearningSubject;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiLesson;
import com.bytequay.app.flow.ci.CiAutofixRecords.LearningCompletionState;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Concrete persistence for immutable CI-learning subjects and results. */
final class CiLearningStore
{
    private static final int MAX_REPAIR_LESSONS = 5;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    CiLearningStore(DataSource dataSource, Clock clock)
    {
        this.jdbc = new JdbcTemplate(
                requireNonNull(dataSource, "dataSource is null"));
        this.clock = requireNonNull(clock, "clock is null");
    }

    List<CiLesson> candidateLessons(String repositoryId)
    {
        requireText(repositoryId, "repositoryId");
        return jdbc.query(
                """
                SELECT * FROM flow_ci_lesson
                WHERE repository_id = ? AND status = 'CANDIDATE'
                ORDER BY created_at DESC, lesson_id
                LIMIT ?
                """,
                (result, row) -> new CiLesson(
                        result.getString("lesson_id"),
                        result.getString("repository_id"),
                        result.getString("learning_operation_id"),
                        result.getString("run_id"),
                        result.getString("subject_id"),
                        result.getString("title"),
                        result.getString("markdown"),
                        result.getString("content_digest"),
                        Instant.ofEpochMilli(result.getLong("created_at"))),
                repositoryId, MAX_REPAIR_LESSONS);
    }

    Optional<LearningOrigin> learningOrigin(String receiptId)
    {
        return jdbc.query(
                """
                SELECT r.receipt_id, r.receipt_digest, r.operation_id,
                       r.head_repository_external_id,
                       r.head_repository_owner, r.head_repository_name,
                       r.branch_ref,
                       r.expected_remote_head, r.proposed_head,
                       p.plan_id, p.plan_digest,
                       p.required_ci_policy_revision_id,
                       a.authorization_id, a.gate_id, a.gate_revision,
                       a.subject_digest AS gate_subject_digest,
                       a.action_digest AS gate_action_digest,
                       s.task_id, s.pr_id, s.repository_id,
                       s.change_set_revision_id, s.diff_digest,
                       s.ci_round_id AS gate_ci_round_id,
                       s.repair_attempt_id AS gate_repair_attempt_id,
                       s.repair_result_id AS gate_repair_result_id,
                       s.cleanup_id AS gate_cleanup_id,
                       s.cleanup_result_id AS gate_cleanup_result_id,
                       ra.round_id AS red_round_id,
                       ra.attempt_id AS repair_attempt_id,
                       ra.result_ref AS repair_result_id,
                       cc.cleanup_id, cc.result_ref AS cleanup_result_id
                FROM flow_github_external_effect_receipt r
                JOIN flow_github_external_effect_plan p
                  ON p.plan_id = r.plan_id
                 AND p.operation_id = r.operation_id
                JOIN flow_user_gate_authorization a
                  ON a.authorization_id = p.authorization_id
                 AND a.operation_id = p.operation_id
                 AND a.effect_plan_ref = p.plan_id
                JOIN flow_user_gate_revision gr
                  ON gr.gate_id = a.gate_id
                 AND gr.revision = a.gate_revision
                 AND gr.subject_digest = a.subject_digest
                 AND gr.action_digest = a.action_digest
                JOIN flow_user_gate_subject s
                  ON s.subject_id = gr.subject_manifest_ref
                 AND s.subject_digest = gr.subject_digest
                JOIN flow_ci_repair_attempt ra
                  ON ra.attempt_id = s.repair_attempt_id
                 AND ra.round_id = s.ci_round_id
                 AND ra.result_ref = s.repair_result_id
                LEFT JOIN flow_ci_cleanup_completion cc
                  ON cc.cleanup_id = s.cleanup_id
                 AND cc.result_ref = s.cleanup_result_id
                JOIN flow_runtime_operation publish
                  ON publish.operation_id = r.operation_id
                 AND publish.kind = 'PUBLISH'
                 AND publish.state = 'SUCCEEDED'
                 AND publish.result_ref = r.receipt_id
                WHERE r.receipt_id = ?
                  AND EXISTS (
                      SELECT 1 FROM flow_user_gate_transition t
                      WHERE t.gate_id = a.gate_id
                        AND t.gate_revision = a.gate_revision
                        AND t.to_state = 'CONSUMED'
                        AND t.reason_code = 'EFFECT_APPLIED'
                        AND t.actor_type = 'PROGRAM'
                        AND t.actor_id = r.operation_id
                        AND t.detail_ref = r.receipt_id
                        AND t.sequence = (
                            SELECT MAX(t2.sequence)
                            FROM flow_user_gate_transition t2
                            WHERE t2.gate_id = a.gate_id
                              AND t2.gate_revision = a.gate_revision
                        )
                  )
                """,
                (result, row) -> new LearningOrigin(
                        result.getString("receipt_id"),
                        result.getString("receipt_digest"),
                        result.getString("operation_id"),
                        result.getString("head_repository_external_id"),
                        result.getString("head_repository_owner"),
                        result.getString("head_repository_name"),
                        result.getString("branch_ref"),
                        result.getString("expected_remote_head"),
                        result.getString("proposed_head"),
                        result.getString("plan_id"),
                        result.getString("plan_digest"),
                        result.getString("required_ci_policy_revision_id"),
                        result.getString("authorization_id"),
                        result.getString("gate_id"),
                        result.getLong("gate_revision"),
                        result.getString("gate_subject_digest"),
                        result.getString("gate_action_digest"),
                        result.getString("task_id"),
                        result.getString("pr_id"),
                        result.getString("repository_id"),
                        result.getString("change_set_revision_id"),
                        result.getString("diff_digest"),
                        result.getString("gate_ci_round_id"),
                        result.getString("gate_repair_attempt_id"),
                        result.getString("gate_repair_result_id"),
                        result.getString("gate_cleanup_id"),
                        result.getString("gate_cleanup_result_id"),
                        result.getString("red_round_id"),
                        result.getString("repair_attempt_id"),
                        result.getString("repair_result_id"),
                        result.getString("cleanup_id"),
                        result.getString("cleanup_result_id")),
                receiptId).stream().findFirst();
    }

    void insertLearningSubject(CiLearningSubject subject)
    {
        int inserted = jdbc.update(
                """
                INSERT OR IGNORE INTO flow_ci_learning_subject (
                    subject_id, operation_id, task_id, pr_id, repository_id,
                    receipt_id, receipt_digest, plan_id, plan_digest,
                    publication_operation_id, head_repository_external_id,
                    head_repository_owner, head_repository_name, branch_ref,
                    expected_remote_head,
                    authorization_id, gate_id, gate_revision,
                    gate_subject_digest, gate_action_digest,
                    publication_policy_revision_id, published_head,
                    green_round_id, green_policy_revision_id,
                    green_evidence_revision,
                    green_observation_operation_id, red_round_id,
                    repair_attempt_id, repair_result_id,
                    repair_result_digest, cleanup_id, cleanup_result_id,
                    cleanup_result_digest, output_change_set_revision_id,
                    output_diff_digest, subject_digest, created_at
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                )
                """,
                subject.subjectId(), subject.operationId(), subject.taskId(),
                subject.prId(), subject.repositoryId(), subject.receiptId(),
                subject.receiptDigest(), subject.planId(),
                subject.planDigest(), subject.publicationOperationId(),
                subject.headRepositoryExternalId(),
                subject.headRepositoryOwner(), subject.headRepositoryName(),
                subject.branchRef(), subject.expectedRemoteHead(),
                subject.authorizationId(),
                subject.gateId(), subject.gateRevision(),
                subject.gateSubjectDigest(), subject.gateActionDigest(),
                subject.publicationPolicyRevisionId(), subject.publishedHead(),
                subject.greenRoundId(), subject.greenPolicyRevisionId(),
                subject.greenEvidenceRevision(),
                subject.greenObservationOperationId(), subject.redRoundId(),
                subject.repairAttemptId(), subject.repairResultId(),
                subject.repairResultDigest(), subject.cleanupId(),
                subject.cleanupResultId(), subject.cleanupResultDigest(),
                subject.outputChangeSetRevisionId(),
                subject.outputDiffDigest(), subject.subjectDigest(),
                subject.createdAt().toEpochMilli());
        if (inserted == 0) {
            CiLearningSubject replay = learningSubject(
                    subject.subjectId()).orElseThrow(() ->
                            new IllegalStateException(
                                    "CI learning subject insert conflicted"));
            if (!replay.equals(subject)) {
                throw new IllegalStateException(
                        "CI learning subject replay changed identity");
            }
            return;
        }
        for (int index = 0; index < subject.greenObservationIds().size();
                index++) {
            jdbc.update(
                    "INSERT INTO flow_ci_learning_green_observation "
                            + "(subject_id, ordinal, observation_id, "
                            + "evidence_digest) VALUES (?, ?, ?, ?)",
                    subject.subjectId(), index,
                    subject.greenObservationIds().get(index),
                    subject.greenObservationDigests().get(index));
        }
        for (int index = 0; index < subject.failedLogRefs().size(); index++) {
            jdbc.update(
                    "INSERT INTO flow_ci_learning_failed_log "
                            + "(subject_id, ordinal, log_ref, content_digest) "
                            + "VALUES (?, ?, ?, ?)",
                    subject.subjectId(), index,
                    subject.failedLogRefs().get(index),
                    subject.failedLogDigests().get(index));
        }
    }

    Optional<CiLearningSubject> learningSubject(String subjectId)
    {
        requireText(subjectId, "subjectId");
        return jdbc.query(
                "SELECT * FROM flow_ci_learning_subject WHERE subject_id = ?",
                (result, row) -> new CiLearningSubject(
                        result.getString("subject_id"),
                        result.getString("operation_id"),
                        result.getString("task_id"),
                        result.getString("pr_id"),
                        result.getString("repository_id"),
                        result.getString("receipt_id"),
                        result.getString("receipt_digest"),
                        result.getString("publication_operation_id"),
                        result.getString("head_repository_external_id"),
                        result.getString("head_repository_owner"),
                        result.getString("head_repository_name"),
                        result.getString("branch_ref"),
                        result.getString("expected_remote_head"),
                        result.getString("plan_id"),
                        result.getString("plan_digest"),
                        result.getString("authorization_id"),
                        result.getString("gate_id"),
                        result.getLong("gate_revision"),
                        result.getString("gate_subject_digest"),
                        result.getString("gate_action_digest"),
                        result.getString("publication_policy_revision_id"),
                        result.getString("published_head"),
                        result.getString("green_round_id"),
                        result.getString("green_policy_revision_id"),
                        result.getLong("green_evidence_revision"),
                        result.getString("green_observation_operation_id"),
                        learningList(
                                "flow_ci_learning_green_observation",
                                "observation_id", subjectId),
                        learningList(
                                "flow_ci_learning_green_observation",
                                "evidence_digest", subjectId),
                        result.getString("red_round_id"),
                        result.getString("repair_attempt_id"),
                        result.getString("repair_result_id"),
                        result.getString("repair_result_digest"),
                        result.getString("cleanup_id"),
                        result.getString("cleanup_result_id"),
                        result.getString("cleanup_result_digest"),
                        result.getString("output_change_set_revision_id"),
                        result.getString("output_diff_digest"),
                        learningList(
                                "flow_ci_learning_failed_log", "log_ref",
                                subjectId),
                        learningList(
                                "flow_ci_learning_failed_log",
                                "content_digest", subjectId),
                        result.getString("subject_digest"),
                        Instant.ofEpochMilli(
                                result.getLong("created_at"))),
                subjectId).stream().findFirst();
    }

    Optional<CiLearningSubject> learningSubjectForReceipt(
            String receiptId)
    {
        requireText(receiptId, "receiptId");
        return jdbc.queryForList(
                        "SELECT subject_id FROM flow_ci_learning_subject "
                                + "WHERE receipt_id = ?",
                        String.class, receiptId).stream()
                .findFirst()
                .flatMap(this::learningSubject);
    }

    int countOutputChangeSet(CiLearningSubject subject)
    {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM flow_runtime_change_set_revision
                WHERE change_set_revision_id = ? AND task_id = ?
                  AND head_sha = ? AND diff_digest = ?
                """,
                Integer.class, subject.outputChangeSetRevisionId(),
                subject.taskId(), subject.publishedHead(),
                subject.outputDiffDigest());
        return requireNonNull(
                count, "CI learning output revision count is null");
    }

    String storeLessonCandidate(
            CiLearningSubject subject, String runId, LessonRequest request)
    {
        String lessonId = stableId("ci-lesson", subject.receiptId());
        jdbc.update(
                """
                INSERT OR IGNORE INTO flow_ci_lesson (
                    lesson_id, repository_id, learning_operation_id,
                    run_id, subject_id, status, title, markdown,
                    content_digest, created_at
                ) VALUES (?, ?, ?, ?, ?, 'CANDIDATE', ?, ?, ?, ?)
                """,
                lessonId, subject.repositoryId(), subject.operationId(),
                runId, subject.subjectId(), request.title(),
                request.markdown(), request.contentDigest(),
                clock.instant().toEpochMilli());
        CiLesson stored = lesson(lessonId).orElseThrow();
        if (!stored.repositoryId().equals(subject.repositoryId())
                || !stored.learningOperationId().equals(
                    subject.operationId())
                || !stored.runId().equals(runId)
                || !stored.subjectId().equals(subject.subjectId())
                || !stored.title().equals(request.title())
                || !stored.markdown().equals(request.markdown())
                || !stored.contentDigest().equals(
                    request.contentDigest())) {
            throw new IllegalStateException(
                    "CI lesson replay changed immutable content");
        }
        return lessonId;
    }

    CiLearningCompletion storeLearningCompletion(
            CiLearningSubject subject,
            String runId,
            String resultId,
            LearningCompletionState state,
            String lessonId,
            String reason)
    {
        jdbc.update(
                """
                INSERT OR IGNORE INTO flow_ci_learning_completion (
                    operation_id, run_id, result_id, state, lesson_id,
                    reason_code, completed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                subject.operationId(), runId, resultId, state.name(),
                lessonId, reason, clock.instant().toEpochMilli());
        CiLearningCompletion stored = learningCompletion(
                subject.operationId()).orElseThrow();
        if (!stored.runId().equals(runId)
                || !stored.resultId().equals(resultId)
                || stored.state() != state
                || !Objects.equals(stored.lessonId(), lessonId)
                || !stored.reasonCode().equals(reason)) {
            throw new IllegalStateException(
                    "CI learning finalization replay changed identity");
        }
        return stored;
    }

    int lockGreenRound(CiLearningSubject subject)
    {
        return jdbc.update(
                """
                UPDATE flow_ci_round SET round_id = round_id
                WHERE round_id = ? AND task_id = ? AND pr_id = ?
                  AND remote_head = ? AND policy_revision_id = ?
                  AND evidence_revision = ? AND state = 'GREEN'
                  AND superseded_by IS NULL
                  AND source_observation_operation_id = ?
                  AND source_receipt_id = ?
                """,
                subject.greenRoundId(), subject.taskId(), subject.prId(),
                subject.publishedHead(), subject.greenPolicyRevisionId(),
                subject.greenEvidenceRevision(),
                subject.greenObservationOperationId(), subject.receiptId());
    }

    Optional<LessonRequest> lessonRequest(String operationId)
    {
        return jdbc.query(
                "SELECT * FROM flow_ci_learning_lesson_request "
                        + "WHERE operation_id = ?",
                (result, row) -> new LessonRequest(
                        result.getString("operation_id"),
                        result.getString("run_id"),
                        result.getString("subject_id"),
                        result.getString("process_attempt_id"),
                        result.getString("title"),
                        result.getString("markdown"),
                        result.getString("content_digest"),
                        Instant.ofEpochMilli(
                                result.getLong("sealed_at"))),
                operationId).stream().findFirst();
    }

    Optional<ResultView> resultView(String resultId)
    {
        return jdbc.query(
                "SELECT result_id, run_id, terminal_outcome, final_content, "
                        + "error_ref, stop_proof_ref "
                        + "FROM flow_runtime_agent_result "
                        + "WHERE result_id = ?",
                (result, row) -> new ResultView(
                        result.getString("result_id"),
                        result.getString("run_id"),
                        result.getString("terminal_outcome"),
                        result.getString("final_content"),
                        result.getString("error_ref"),
                        result.getString("stop_proof_ref")),
                resultId).stream().findFirst();
    }

    Optional<CiLearningCompletion> learningCompletion(
            String operationId)
    {
        requireText(operationId, "operationId");
        return jdbc.query(
                "SELECT * FROM flow_ci_learning_completion "
                        + "WHERE operation_id = ?",
                (result, row) -> new CiLearningCompletion(
                        result.getString("operation_id"),
                        result.getString("run_id"),
                        result.getString("result_id"),
                        LearningCompletionState.valueOf(
                                result.getString("state")),
                        result.getString("lesson_id"),
                        result.getString("reason_code"),
                        Instant.ofEpochMilli(
                                result.getLong("completed_at"))),
                operationId).stream().findFirst();
    }

    Optional<CiLesson> lesson(String lessonId)
    {
        requireText(lessonId, "lessonId");
        return jdbc.query(
                "SELECT * FROM flow_ci_lesson WHERE lesson_id = ?",
                (result, row) -> new CiLesson(
                        result.getString("lesson_id"),
                        result.getString("repository_id"),
                        result.getString("learning_operation_id"),
                        result.getString("run_id"),
                        result.getString("subject_id"),
                        result.getString("title"),
                        result.getString("markdown"),
                        result.getString("content_digest"),
                        Instant.ofEpochMilli(
                                result.getLong("created_at"))),
                lessonId).stream().findFirst();
    }

    record LessonRequest(
            String operationId,
            String runId,
            String subjectId,
            String processAttemptId,
            String title,
            String markdown,
            String contentDigest,
            Instant sealedAt) {}

    record ResultView(
            String resultId,
            String runId,
            String terminalOutcome,
            String finalContent,
            String errorRef,
            String stopProofRef) {}

    private List<String> learningList(
            String table, String column, String subjectId)
    {
        boolean green = table.equals(
                "flow_ci_learning_green_observation")
                && (column.equals("observation_id")
                    || column.equals("evidence_digest"));
        boolean red = table.equals("flow_ci_learning_failed_log")
                && (column.equals("log_ref")
                    || column.equals("content_digest"));
        if (!green && !red) {
            throw new IllegalArgumentException("unsupported learning list");
        }
        return jdbc.queryForList(
                "SELECT " + column + " FROM " + table
                        + " WHERE subject_id = ? ORDER BY ordinal",
                String.class, subjectId);
    }

    record LearningOrigin(
            String receiptId,
            String receiptDigest,
            String publicationOperationId,
            String headRepositoryExternalId,
            String headRepositoryOwner,
            String headRepositoryName,
            String branchRef,
            String expectedRemoteHead,
            String publishedHead,
            String planId,
            String planDigest,
            String publicationPolicyRevisionId,
            String authorizationId,
            String gateId,
            long gateRevision,
            String gateSubjectDigest,
            String gateActionDigest,
            String taskId,
            String prId,
            String repositoryId,
            String gateChangeSetRevisionId,
            String gateDiffDigest,
            String gateCiRoundId,
            String gateRepairAttemptId,
            String gateRepairResultId,
            String gateCleanupId,
            String gateCleanupResultId,
            String redRoundId,
            String repairAttemptId,
            String repairResultId,
            String cleanupId,
            String cleanupResultId) {}

    /** Atomically freezes failed logs and records one exact runtime cause. */

    private static String stableId(String prefix, String... parts)
    {
        return prefix + ":" + digest(prefix, parts);
    }

    private static String digest(String prefix, String... parts)
    {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, prefix);
            for (String part : parts) {
                updateDigest(digest, requireNonNull(part, "digest part is null"));
            }
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void updateDigest(MessageDigest digest, String value)
    {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length)
                .getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
        digest.update((byte) '\n');
    }

    private static void requireText(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}

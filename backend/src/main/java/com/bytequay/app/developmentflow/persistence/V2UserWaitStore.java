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
import com.bytequay.app.service.agents.ActiveAgentContextRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/** Exact durable questions and permission grants for typed V2 Turns. */
@Repository
public class V2UserWaitStore
{
    private static final String LEGACY_CONSUMPTION_CALL_ID_PREFIX =
            "__legacy_v263_consumption__:";

    private final JdbcTemplate jdbc;

    public V2UserWaitStore(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    @Transactional
    public synchronized Question insertQuestion(
            ActiveAgentContextRegistry.TypedOwner owner,
            String id,
            String callId,
            String prompt,
            String context,
            String optionsJson,
            boolean allowFreeForm,
            Instant createdAt)
    {
        requireOwner(owner);
        requireText(id, "id");
        requireCallId(callId);
        requireText(prompt, "prompt");
        requireText(optionsJson, "optionsJson");
        requireNonNull(createdAt, "createdAt is null");
        if (!ownerExists(owner)) {
            throw new IllegalArgumentException("typed question owner is not current");
        }
        requireOnlyWait(owner, "QUESTION", id);
        String table = supportPrefix(owner.kind()) + "_question";
        jdbc.update("""
                INSERT OR IGNORE INTO %s(
                    id, turn_id, call_id, prompt, context, options_json,
                    allow_free_form, state, created_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'OPEN', ?)
                """.formatted(table),
                id, owner.turnId(), callId, prompt, context, optionsJson,
                allowFreeForm ? 1 : 0, createdAt.toEpochMilli());
        requireOnlyWait(owner, "QUESTION", id);
        Question persisted = findQuestionByCall(owner, callId).orElseThrow(() ->
                new IllegalStateException("typed question insert was not durable"));
        if (!persisted.id().equals(id)
                || !persisted.prompt().equals(prompt)
                || !Objects.equals(persisted.context(), context)
                || !persisted.optionsJson().equals(optionsJson)
                || persisted.allowFreeForm() != allowFreeForm) {
            throw new IllegalArgumentException(
                    "question call id was reused with different input");
        }
        return persisted;
    }

    public Optional<Question> findQuestion(String id)
    {
        requireText(id, "id");
        return one(jdbc.query(questionUnion("id = ?"),
                V2UserWaitStore::question, id));
    }

    public Optional<Question> findQuestionByCall(
            ActiveAgentContextRegistry.TypedOwner owner, String callId)
    {
        requireOwner(owner);
        requireCallId(callId);
        return one(jdbc.query(questionUnion(
                        "owner_kind = ? AND turn_id = ? AND operation_id = ? AND call_id = ?"),
                V2UserWaitStore::question, owner.kind().name(), owner.turnId(),
                owner.operationId(), callId));
    }

    public List<Question> listOpenQuestions(String trunkId)
    {
        requireText(trunkId, "trunkId");
        return jdbc.query(questionUnion("state = 'OPEN' AND owner_trunk_id = ?")
                        + " ORDER BY created_at_ms, id",
                V2UserWaitStore::question, trunkId);
    }

    /**
     * Archive liveness for one Task. An unanswered wait and an answered wait
     * whose owner has not admitted its exact successor are both live work.
     */
    public boolean hasArchiveBlockingWait(String taskId)
    {
        requireText(taskId, "taskId");
        Integer count = jdbc.queryForObject("""
                SELECT
                    (SELECT COUNT(*)
                     FROM task_question question
                     JOIN task_turn turn ON turn.id = question.turn_id
                     WHERE turn.task_id = ?
                       AND (question.state = 'OPEN'
                         OR question.continuation_state = 'READY'))
                  + (SELECT COUNT(*)
                     FROM stage_question question
                     JOIN stage_turn turn ON turn.id = question.turn_id
                     JOIN stage owner ON owner.id = turn.stage_id
                     WHERE owner.task_id = ?
                       AND (question.state = 'OPEN'
                         OR question.continuation_state = 'READY'))
                  + (SELECT COUNT(*)
                     FROM review_assignment_question question
                     JOIN review_assignment_turn turn ON turn.id = question.turn_id
                     JOIN review_assignment assignment
                       ON assignment.id = turn.assignment_id
                     JOIN review_round round ON round.id = assignment.round_id
                     JOIN review_session session ON session.id = round.session_id
                     WHERE session.owner_task_id = ?
                       AND (question.state = 'OPEN'
                         OR question.continuation_state = 'READY'))
                  + (SELECT COUNT(*)
                     FROM permission_request permission
                     WHERE (permission.state = 'OPEN'
                         OR permission.continuation_state = 'READY')
                       AND ((permission.turn_kind = 'TASK' AND EXISTS (
                           SELECT 1 FROM task_turn turn
                           WHERE turn.id = permission.turn_id
                             AND turn.task_id = ?))
                         OR (permission.turn_kind = 'STAGE' AND EXISTS (
                           SELECT 1 FROM stage_turn turn
                           JOIN stage owner ON owner.id = turn.stage_id
                           WHERE turn.id = permission.turn_id
                             AND owner.task_id = ?))
                         OR (permission.turn_kind = 'REVIEW_ASSIGNMENT'
                           AND EXISTS (
                               SELECT 1 FROM review_assignment_turn turn
                               JOIN review_assignment assignment
                                 ON assignment.id = turn.assignment_id
                               JOIN review_round round
                                 ON round.id = assignment.round_id
                               JOIN review_session session
                                 ON session.id = round.session_id
                               WHERE turn.id = permission.turn_id
                                 AND session.owner_task_id = ?))))
                """, Integer.class,
                taskId, taskId, taskId, taskId, taskId, taskId);
        return count != null && count > 0;
    }

    public WaitOwnerContext requireWaitOwnerContext(
            ActiveAgentContextRegistry.TypedOwner owner)
    {
        requireOwner(owner);
        List<WaitOwnerContext> rows = switch (owner.kind()) {
            case THREAD_TURN -> jdbc.query("""
                    SELECT turn.trunk_id, NULL AS task_id,
                        NULL AS task_epoch, NULL AS stage_id,
                        NULL AS stage_generation, turn.attempt,
                        NULL AS expected_code_fingerprint,
                        NULL AS expected_head_sha, NULL AS expected_base_sha,
                        NULL AS stage_kind, turn.purpose
                    FROM thread_turn turn
                    WHERE turn.id = ? AND turn.operation_id = ?
                    """, V2UserWaitStore::waitOwnerContext,
                    owner.turnId(), owner.operationId());
            case TASK_TURN -> jdbc.query("""
                    SELECT task.thread_id AS trunk_id, turn.task_id,
                        turn.task_epoch, turn.trigger_stage_id AS stage_id,
                        turn.trigger_stage_generation AS stage_generation,
                        turn.attempt, turn.expected_code_fingerprint,
                        turn.expected_head_sha, turn.expected_base_sha,
                        stage.kind AS stage_kind, turn.purpose
                    FROM task_turn turn
                    JOIN tasks task ON task.id = turn.task_id
                    LEFT JOIN stage ON stage.id = turn.trigger_stage_id
                    WHERE turn.id = ? AND turn.operation_id = ?
                    """, V2UserWaitStore::waitOwnerContext,
                    owner.turnId(), owner.operationId());
            case STAGE_TURN -> jdbc.query("""
                    SELECT task.thread_id AS trunk_id, task.id AS task_id,
                        turn.task_epoch, turn.stage_id,
                        turn.stage_generation, turn.attempt,
                        turn.expected_code_fingerprint,
                        turn.expected_head_sha, turn.expected_base_sha,
                        stage.kind AS stage_kind, turn.purpose
                    FROM stage_turn turn
                    JOIN stage ON stage.id = turn.stage_id
                    JOIN tasks task ON task.id = stage.task_id
                    WHERE turn.id = ? AND turn.operation_id = ?
                    """, V2UserWaitStore::waitOwnerContext,
                    owner.turnId(), owner.operationId());
            case REVIEW_ASSIGNMENT_TURN -> jdbc.query("""
                    SELECT session.owner_thread_id AS trunk_id,
                        session.owner_task_id AS task_id,
                        task.epoch AS task_epoch, NULL AS stage_id,
                        NULL AS stage_generation, turn.attempt,
                        NULL AS expected_code_fingerprint,
                        turn.start_commit AS expected_head_sha,
                        NULL AS expected_base_sha, NULL AS stage_kind,
                        turn.purpose
                    FROM review_assignment_turn turn
                    JOIN review_assignment assignment
                      ON assignment.id = turn.assignment_id
                    JOIN review_round round ON round.id = assignment.round_id
                    JOIN review_session session ON session.id = round.session_id
                    LEFT JOIN tasks task ON task.id = session.owner_task_id
                    WHERE turn.id = ? AND turn.operation_id = ?
                    """, V2UserWaitStore::waitOwnerContext,
                    owner.turnId(), owner.operationId());
            default -> throw unsupported(owner.kind());
        };
        return one(rows).orElseThrow(() -> new IllegalArgumentException(
                "typed wait owner does not exist"));
    }

    @Transactional
    public QuestionResolution answerQuestion(
            String id,
            int expectedRevision,
            String answerOptionId,
            String answerFreeForm,
            String actor,
            Instant answeredAt)
    {
        Question question = findQuestion(id).orElse(null);
        if (question == null) {
            throw new IllegalArgumentException("typed question not found: " + id);
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision is negative");
        }
        String option = blankToNull(answerOptionId);
        String freeForm = blankToNull(answerFreeForm);
        if (option == null && freeForm == null) {
            throw new IllegalArgumentException("question answer is blank");
        }
        requireText(actor, "actor");
        requireNonNull(answeredAt, "answeredAt is null");
        String table = supportPrefix(question.owner().kind()) + "_question";
        String outcome;
        boolean accepted = false;
        if (!"OPEN".equals(question.state())) {
            outcome = "ALREADY_TERMINAL";
        }
        else if (question.answerRevision() != expectedRevision) {
            outcome = "REVISION_CONFLICT";
        }
        else {
            String answer = answerText(option, freeForm);
            int updated = jdbc.update("""
                    UPDATE %s
                    SET state = 'ANSWERED', answer = ?,
                        answer_option_id = ?, answer_free_form = ?,
                        answer_revision = answer_revision + 1,
                        answer_actor = ?, answered_at_ms = ?,
                        continuation_state = 'READY'
                    WHERE id = ? AND turn_id = ? AND call_id = ?
                      AND state = 'OPEN' AND answer_revision = ?
                      AND answer IS NULL AND answered_at_ms IS NULL
                    """.formatted(table), answer, option, freeForm, actor,
                    answeredAt.toEpochMilli(), id, question.owner().turnId(),
                    question.callId(), expectedRevision);
            accepted = updated == 1;
            outcome = accepted ? "ACCEPTED" : "REVISION_CONFLICT";
        }
        jdbc.update("""
                INSERT INTO typed_question_answer_attempt(
                    id, owner_kind, question_id, expected_revision,
                    answer_option_id, answer_free_form, actor, outcome,
                    attempted_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), question.owner().kind().name(),
                id, expectedRevision, option, freeForm, actor, outcome,
                answeredAt.toEpochMilli());
        return new QuestionResolution(
                accepted, outcome, findQuestion(id).orElseThrow());
    }

    /** Compatibility helper retained for focused persistence callers. */
    public Optional<Question> answerQuestion(
            String id, String answer, Instant answeredAt)
    {
        QuestionResolution result = answerQuestion(
                id, 0, null, answer, "user", answeredAt);
        return result.accepted() ? Optional.of(result.question()) : Optional.empty();
    }

    @Transactional
    public synchronized PermissionRequest insertPermission(
            ActiveAgentContextRegistry.TypedOwner owner,
            String id,
            String callId,
            String capability,
            String toolName,
            String parametersJson,
            String parametersDigest,
            String policySnapshot,
            Instant requestedAt)
    {
        requireOwner(owner);
        requireText(id, "id");
        requireCallId(callId);
        requireText(capability, "capability");
        requireText(parametersJson, "parametersJson");
        requireText(parametersDigest, "parametersDigest");
        requireText(policySnapshot, "policySnapshot");
        requireNonNull(requestedAt, "requestedAt is null");
        if (!ownerExists(owner)) {
            throw new IllegalArgumentException("typed permission owner is not current");
        }
        requireOnlyWait(owner, "PERMISSION", id);
        jdbc.update("""
                INSERT OR IGNORE INTO permission_request(
                    id, call_id, turn_kind, turn_id, operation_id, capability,
                    tool_name, parameters_json, parameters_digest, policy_snapshot,
                    state, requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?)
                """, id, callId, turnKind(owner.kind()), owner.turnId(),
                owner.operationId(), capability, blankToNull(toolName),
                parametersJson, parametersDigest, policySnapshot,
                requestedAt.toEpochMilli());
        requireOnlyWait(owner, "PERMISSION", id);
        PermissionRequest persisted = findPermission(callId).orElseThrow(() ->
                new IllegalStateException("typed permission insert was not durable"));
        if (!persisted.id().equals(id)
                || !persisted.owner().equals(owner)
                || !persisted.capability().equals(capability)
                || !Objects.equals(persisted.toolName(), blankToNull(toolName))
                || !persisted.parametersJson().equals(parametersJson)
                || !persisted.parametersDigest().equals(parametersDigest)
                || !persisted.policySnapshot().equals(policySnapshot)) {
            throw new IllegalArgumentException(
                    "permission call id was reused with different input");
        }
        return persisted;
    }

    public Optional<PermissionRequest> findPermission(String callId)
    {
        requireCallId(callId);
        return one(jdbc.query("""
                SELECT id, call_id, turn_kind, turn_id, operation_id, capability,
                    tool_name, parameters_json, parameters_digest, policy_snapshot,
                    state, answer, answer_revision, requested_at_ms, answered_at_ms,
                    grant_scope_kind, grant_scope_id, granted_uses,
                    remaining_uses, consumed_uses, answer_actor,
                    last_consumed_at_ms, continuation_state,
                    successor_turn_id, continuation_error
                FROM permission_request WHERE call_id = ?
                """, V2UserWaitStore::permission, callId));
    }

    public Optional<PermissionRequest> findPermissionById(String id)
    {
        requireText(id, "id");
        return one(jdbc.query("""
                SELECT id, call_id, turn_kind, turn_id, operation_id, capability,
                    tool_name, parameters_json, parameters_digest, policy_snapshot,
                    state, answer, answer_revision, requested_at_ms, answered_at_ms,
                    grant_scope_kind, grant_scope_id, granted_uses,
                    remaining_uses, consumed_uses, answer_actor,
                    last_consumed_at_ms, continuation_state,
                    successor_turn_id, continuation_error
                FROM permission_request WHERE id = ?
                """, V2UserWaitStore::permission, id));
    }

    public List<ReadyContinuation> listReadyThreadContinuations(int limit)
    {
        if (limit < 1) {
            return List.of();
        }
        return jdbc.query("""
                SELECT 'QUESTION' AS wait_kind, question.id AS wait_id,
                    question.answered_at_ms AS ready_at_ms
                FROM thread_question question
                WHERE question.state = 'ANSWERED'
                  AND question.continuation_state = 'READY'
                UNION ALL
                SELECT 'PERMISSION', permission.id, permission.answered_at_ms
                FROM permission_request permission
                WHERE permission.turn_kind = 'THREAD'
                  AND permission.state <> 'OPEN'
                  AND permission.continuation_state = 'READY'
                ORDER BY ready_at_ms, wait_id
                LIMIT ?
                """, (row, ignored) -> new ReadyContinuation(
                        DispatchTicket.OwnerKind.THREAD_TURN,
                        row.getString("wait_kind"), row.getString("wait_id")),
                limit);
    }

    public List<ReadyContinuation> listReadyContinuations(int limit)
    {
        if (limit < 1) {
            return List.of();
        }
        return jdbc.query("""
                SELECT 'THREAD_TURN' AS owner_kind, 'QUESTION' AS wait_kind,
                    question.id AS wait_id,
                    question.answered_at_ms AS ready_at_ms
                FROM thread_question question
                WHERE question.state = 'ANSWERED'
                  AND question.continuation_state = 'READY'
                UNION ALL
                SELECT 'TASK_TURN', 'QUESTION', question.id,
                    question.answered_at_ms
                FROM task_question question
                WHERE question.state = 'ANSWERED'
                  AND question.continuation_state = 'READY'
                UNION ALL
                SELECT 'STAGE_TURN', 'QUESTION', question.id,
                    question.answered_at_ms
                FROM stage_question question
                WHERE question.state = 'ANSWERED'
                  AND question.continuation_state = 'READY'
                UNION ALL
                SELECT 'REVIEW_ASSIGNMENT_TURN', 'QUESTION', question.id,
                    question.answered_at_ms
                FROM review_assignment_question question
                WHERE question.state = 'ANSWERED'
                  AND question.continuation_state = 'READY'
                UNION ALL
                SELECT CASE permission.turn_kind
                        WHEN 'THREAD' THEN 'THREAD_TURN'
                        WHEN 'TASK' THEN 'TASK_TURN'
                        WHEN 'STAGE' THEN 'STAGE_TURN'
                        WHEN 'REVIEW_ASSIGNMENT' THEN 'REVIEW_ASSIGNMENT_TURN'
                       END,
                    'PERMISSION', permission.id, permission.answered_at_ms
                FROM permission_request permission
                WHERE permission.state <> 'OPEN'
                  AND permission.continuation_state = 'READY'
                ORDER BY ready_at_ms, wait_id
                LIMIT ?
                """, (row, ignored) -> new ReadyContinuation(
                        DispatchTicket.OwnerKind.valueOf(
                                row.getString("owner_kind")),
                        row.getString("wait_kind"), row.getString("wait_id")),
                limit);
    }

    public boolean typedTurnExists(
            DispatchTicket.OwnerKind kind, String turnId)
    {
        requireNonNull(kind, "kind is null");
        requireText(turnId, "turnId");
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + turnTable(kind) + " WHERE id = ?",
                Integer.class, turnId);
        return count != null && count == 1;
    }

    @Transactional
    public void markContinuationDispatched(
            String waitKind, String waitId, String successorTurnId)
    {
        requireText(waitKind, "waitKind");
        requireText(waitId, "waitId");
        requireText(successorTurnId, "successorTurnId");
        String table;
        if (waitKind.equals("QUESTION")) {
            Question question = findQuestion(waitId).orElseThrow(() ->
                    new IllegalArgumentException("typed question not found: " + waitId));
            table = supportPrefix(question.owner().kind()) + "_question";
        }
        else if (waitKind.equals("PERMISSION")) {
            table = "permission_request";
        }
        else {
            throw new IllegalArgumentException(
                    "unsupported continuation wait kind: " + waitKind);
        }
        int changed = jdbc.update("""
                UPDATE %s
                SET continuation_state = 'DISPATCHED', successor_turn_id = ?,
                    continuation_error = NULL
                WHERE id = ? AND continuation_state = 'READY'
                """.formatted(table), successorTurnId, waitId);
        if (changed == 0) {
            String state;
            String persistedSuccessor;
            if (waitKind.equals("QUESTION")) {
                Question persisted = findQuestion(waitId).orElseThrow();
                state = persisted.continuationState();
                persistedSuccessor = persisted.successorTurnId();
            }
            else {
                PermissionRequest persisted = findPermissionById(waitId)
                        .orElseThrow();
                state = persisted.continuationState();
                persistedSuccessor = persisted.successorTurnId();
            }
            if (!state.equals("DISPATCHED")
                    || !successorTurnId.equals(persistedSuccessor)) {
                throw new IllegalStateException(
                        "typed wait changed before continuation dispatch");
            }
        }
    }

    @Transactional
    public void markContinuationSuperseded(
            String waitKind, String waitId, String detail)
    {
        requireText(waitKind, "waitKind");
        requireText(waitId, "waitId");
        requireText(detail, "detail");
        String table;
        if (waitKind.equals("QUESTION")) {
            Question question = findQuestion(waitId).orElseThrow(() ->
                    new IllegalArgumentException(
                            "typed question not found: " + waitId));
            table = supportPrefix(question.owner().kind()) + "_question";
        }
        else if (waitKind.equals("PERMISSION")) {
            table = "permission_request";
        }
        else {
            throw new IllegalArgumentException(
                    "unsupported continuation wait kind: " + waitKind);
        }
        int changed = jdbc.update("""
                UPDATE %s
                SET continuation_state = 'SUPERSEDED',
                    successor_turn_id = NULL, continuation_error = ?
                WHERE id = ? AND continuation_state = 'READY'
                """.formatted(table), detail, waitId);
        if (changed == 0) {
            String state = waitKind.equals("QUESTION")
                    ? findQuestion(waitId).orElseThrow().continuationState()
                    : findPermissionById(waitId).orElseThrow()
                            .continuationState();
            if (!state.equals("SUPERSEDED")) {
                throw new IllegalStateException(
                        "typed wait changed before continuation supersession");
            }
        }
    }

    @Transactional
    public PermissionResolution resolvePermission(
            String callId,
            int expectedRevision,
            PermissionChoice choice,
            String actor,
            Instant answeredAt)
    {
        requireCallId(callId);
        requireNonNull(choice, "choice is null");
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision is negative");
        }
        requireText(actor, "actor");
        requireNonNull(answeredAt, "answeredAt is null");
        PermissionRequest request = findPermission(callId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "permission request not found: " + callId));
        String outcome;
        boolean accepted = false;
        if (!"OPEN".equals(request.state())) {
            outcome = "ALREADY_TERMINAL";
        }
        else if (request.answerRevision() != expectedRevision) {
            outcome = "REVISION_CONFLICT";
        }
        else {
            OwnerScope scope = ownerScope(request.owner());
            ResolvedChoice answer = resolveChoice(request, scope, choice);
            int updated = jdbc.update("""
                    UPDATE permission_request
                    SET state = ?, answer = ?, answer_revision = answer_revision + 1,
                        answered_at_ms = ?, answer_actor = ?,
                        grant_scope_kind = ?, grant_scope_id = ?,
                        granted_uses = ?, remaining_uses = ?,
                        continuation_state = ?
                    WHERE id = ? AND call_id = ? AND state = 'OPEN'
                      AND answer_revision = ? AND answer IS NULL
                      AND answered_at_ms IS NULL
                    """,
                    answer.state(), answer.answerJson(), answeredAt.toEpochMilli(), actor,
                    answer.grantScopeKind(), answer.grantScopeId(),
                    answer.uses(), answer.uses(), answer.continuationState(),
                    request.id(), callId, expectedRevision);
            accepted = updated == 1;
            outcome = accepted ? "ACCEPTED" : "REVISION_CONFLICT";
        }
        jdbc.update("""
                INSERT INTO permission_answer_attempt(
                    id, permission_id, expected_revision, proposed_state,
                    actor, answer, outcome, attempted_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), request.id(), expectedRevision,
                proposedState(choice), actor, choice.answerJson(), outcome,
                answeredAt.toEpochMilli());
        return new PermissionResolution(
                accepted, outcome, findPermission(callId).orElseThrow());
    }

    /** Compatibility adapter; scope ids supplied by a caller are ignored. */
    public PermissionResolution resolvePermission(
            String callId,
            int expectedRevision,
            PermissionAnswer answer,
            String actor,
            Instant answeredAt)
    {
        requireNonNull(answer, "answer is null");
        PermissionChoice choice = switch (answer.state()) {
            case "DENIED" -> PermissionChoice.deny(answer.answerJson());
            case "ALLOWED_ONCE" -> PermissionChoice.allowOnce(answer.answerJson());
            case "ALLOWED_NEXT" -> PermissionChoice.allowNext(
                    answer.grant().uses(), answer.answerJson());
            case "ALLOWED_TASK" -> PermissionChoice.alwaysTask(answer.answerJson());
            case "ALLOWED_REPOSITORY" ->
                    PermissionChoice.alwaysRepository(answer.answerJson());
            default -> throw new IllegalArgumentException(
                    "unsupported permission answer state: " + answer.state());
        };
        return resolvePermission(
                callId, expectedRevision, choice, actor, answeredAt);
    }

    /** Atomically consumes the narrowest matching durable grant. */
    @Transactional
    public OptionalInt consumeGrant(
            ActiveAgentContextRegistry.TypedOwner currentOwner,
            String callId,
            String toolName,
            String parametersDigest,
            Instant consumedAt)
    {
        requireOwner(currentOwner);
        requireCallId(callId);
        requireText(toolName, "toolName");
        requireText(parametersDigest, "parametersDigest");
        requireNonNull(consumedAt, "consumedAt is null");
        OptionalInt replay = consumedGrantRemaining(
                currentOwner, callId, toolName, parametersDigest);
        if (replay.isPresent()) {
            return replay;
        }
        OwnerScope current = ownerScope(currentOwner);
        List<PermissionRequest> candidates = jdbc.query("""
                SELECT id, call_id, turn_kind, turn_id, operation_id, capability,
                    tool_name, parameters_json, parameters_digest, policy_snapshot,
                    state, answer, answer_revision, requested_at_ms, answered_at_ms,
                    grant_scope_kind, grant_scope_id, granted_uses,
                    remaining_uses, consumed_uses, answer_actor,
                    last_consumed_at_ms, continuation_state,
                    successor_turn_id, continuation_error
                FROM permission_request
                WHERE tool_name = ? AND remaining_uses <> 0
                  AND state IN ('ALLOWED_ONCE', 'ALLOWED_NEXT',
                      'ALLOWED_TASK', 'ALLOWED_REPOSITORY')
                ORDER BY CASE state
                    WHEN 'ALLOWED_ONCE' THEN 0
                    WHEN 'ALLOWED_NEXT' THEN 1
                    WHEN 'ALLOWED_TASK' THEN 2
                    ELSE 3 END,
                    requested_at_ms, id
                """, V2UserWaitStore::permission, toolName);
        for (PermissionRequest candidate : candidates) {
            if (!grantMatches(
                    candidate, currentOwner, current, parametersDigest)) {
                continue;
            }
            int remaining = candidate.remainingUses();
            int next = remaining == -1 ? -1 : remaining - 1;
            int updated = jdbc.update("""
                    UPDATE permission_request
                    SET remaining_uses = ?, consumed_uses = consumed_uses + 1,
                        last_consumed_at_ms = ?
                    WHERE id = ? AND state = ? AND remaining_uses = ?
                      AND consumed_uses = ?
                      AND NOT EXISTS (
                          SELECT 1 FROM permission_grant_consumption consumption
                          WHERE consumption.permission_id = permission_request.id
                            AND consumption.turn_kind = ?
                            AND consumption.turn_id = ?
                            AND consumption.operation_id = ?
                            AND consumption.call_id = ?
                            AND consumption.parameters_digest = ?)
                    """, next, consumedAt.toEpochMilli(), candidate.id(),
                    candidate.state(), remaining, candidate.consumedUses(),
                    turnKind(currentOwner.kind()), currentOwner.turnId(),
                    currentOwner.operationId(), callId, parametersDigest);
            if (updated == 1) {
                jdbc.update("""
                        INSERT INTO permission_grant_consumption(
                            id, permission_id, turn_kind, turn_id,
                            operation_id, call_id, parameters_digest,
                            remaining_after, consumed_at_ms)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, UUID.randomUUID().toString(), candidate.id(),
                        turnKind(currentOwner.kind()), currentOwner.turnId(),
                        currentOwner.operationId(), callId, parametersDigest, next,
                        consumedAt.toEpochMilli());
                return OptionalInt.of(next);
            }
        }
        return consumedGrantRemaining(
                currentOwner, callId, toolName, parametersDigest);
    }

    private OptionalInt consumedGrantRemaining(
            ActiveAgentContextRegistry.TypedOwner owner,
            String callId,
            String toolName,
            String parametersDigest)
    {
        requireOwner(owner);
        requireCallId(callId);
        requireText(toolName, "toolName");
        requireText(parametersDigest, "parametersDigest");
        List<Integer> remaining = jdbc.query("""
                SELECT consumption.remaining_after
                FROM permission_grant_consumption consumption
                JOIN permission_request permission
                  ON permission.id = consumption.permission_id
                WHERE consumption.turn_kind = ?
                  AND consumption.turn_id = ?
                  AND consumption.operation_id = ?
                  AND consumption.call_id = ?
                  AND consumption.parameters_digest = ?
                  AND permission.tool_name = ?
                """, (row, ignored) -> row.getInt("remaining_after"),
                turnKind(owner.kind()), owner.turnId(), owner.operationId(),
                callId, parametersDigest, toolName);
        return remaining.isEmpty()
                ? OptionalInt.empty()
                : OptionalInt.of(remaining.getFirst());
    }

    public Optional<PermissionRequest> findPermissionForTrunk(
            String trunkId, String callId)
    {
        requireText(trunkId, "trunkId");
        PermissionRequest request = findPermission(callId).orElse(null);
        if (request == null) {
            return Optional.empty();
        }
        OwnerScope scope = ownerScope(request.owner());
        return trunkId.equals(scope.trunkId()) ? Optional.of(request) : Optional.empty();
    }

    public List<PermissionRequest> listOpenPermissions(String trunkId)
    {
        requireText(trunkId, "trunkId");
        return jdbc.query("""
                SELECT permission.id, permission.call_id, permission.turn_kind,
                    permission.turn_id, permission.operation_id,
                    permission.capability, permission.tool_name,
                    permission.parameters_json, permission.parameters_digest,
                    permission.policy_snapshot, permission.state,
                    permission.answer, permission.answer_revision,
                    permission.requested_at_ms, permission.answered_at_ms,
                    permission.grant_scope_kind, permission.grant_scope_id,
                    permission.granted_uses, permission.remaining_uses,
                    permission.consumed_uses, permission.answer_actor,
                    permission.last_consumed_at_ms,
                    permission.continuation_state,
                    permission.successor_turn_id,
                    permission.continuation_error
                FROM permission_request permission
                WHERE permission.state = 'OPEN' AND (
                    (permission.turn_kind = 'THREAD' AND EXISTS (
                        SELECT 1 FROM thread_turn turn
                        WHERE turn.id = permission.turn_id
                          AND turn.trunk_id = ?))
                    OR (permission.turn_kind = 'TASK' AND EXISTS (
                        SELECT 1 FROM task_turn turn
                        JOIN tasks task ON task.id = turn.task_id
                        WHERE turn.id = permission.turn_id
                          AND task.thread_id = ?))
                    OR (permission.turn_kind = 'STAGE' AND EXISTS (
                        SELECT 1 FROM stage_turn turn
                        JOIN stage owner ON owner.id = turn.stage_id
                        JOIN tasks task ON task.id = owner.task_id
                        WHERE turn.id = permission.turn_id
                          AND task.thread_id = ?))
                    OR (permission.turn_kind = 'REVIEW_ASSIGNMENT' AND EXISTS (
                        SELECT 1 FROM review_assignment_turn turn
                        JOIN review_assignment assignment
                          ON assignment.id = turn.assignment_id
                        JOIN review_round round ON round.id = assignment.round_id
                        JOIN review_session session ON session.id = round.session_id
                        WHERE turn.id = permission.turn_id
                          AND session.owner_thread_id = ?)))
                ORDER BY permission.requested_at_ms, permission.id
                """, V2UserWaitStore::permission,
                trunkId, trunkId, trunkId, trunkId);
    }

    /**
     * Accepts the executor's distinct successful USER_WAIT disposition and
     * terminalizes only the exact typed Turn. The owning domain checkpoint is
     * intentionally unchanged; the durable wait becomes its blocker.
     */
    @Transactional
    public UserWaitReceipt recordUserWait(
            ActiveAgentContextRegistry.TypedOwner owner,
            String waitKind,
            String waitId,
            String payloadDigest,
            String resultEvidence,
            Instant acceptedAt)
    {
        requireOwner(owner);
        requireText(waitKind, "waitKind");
        requireText(waitId, "waitId");
        requireText(payloadDigest, "payloadDigest");
        requireText(resultEvidence, "resultEvidence");
        requireNonNull(acceptedAt, "acceptedAt is null");
        if (!waitKind.equals("QUESTION") && !waitKind.equals("PERMISSION")) {
            throw new IllegalArgumentException("unsupported user wait kind: " + waitKind);
        }
        int inserted = jdbc.update("""
                INSERT OR IGNORE INTO typed_user_wait_result(
                    operation_id, owner_kind, turn_id, wait_kind, wait_id,
                    payload_digest, result_evidence, accepted_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, owner.operationId(), owner.kind().name(), owner.turnId(),
                waitKind, waitId, payloadDigest, resultEvidence,
                acceptedAt.toEpochMilli());
        UserWaitReceipt receipt = findUserWaitResult(owner.operationId())
                .orElseThrow(() -> new IllegalStateException(
                        "user-wait result insert was not durable"));
        if (!receipt.owner().equals(owner)
                || !receipt.waitKind().equals(waitKind)
                || !receipt.waitId().equals(waitId)
                || !receipt.payloadDigest().equals(payloadDigest)
                || !receipt.resultEvidence().equals(resultEvidence)) {
            throw new IllegalArgumentException(
                    "operation already names different user-wait evidence");
        }
        if (inserted == 1) {
            int terminal = jdbc.update("""
                    UPDATE %s
                    SET status = 'SUCCEEDED', finished_at_ms = ?, error_message = NULL
                    WHERE id = ? AND operation_id = ? AND status = 'RUNNING'
                      AND started_at_ms IS NOT NULL AND finished_at_ms IS NULL
                    """.formatted(turnTable(owner.kind())), acceptedAt.toEpochMilli(),
                    owner.turnId(), owner.operationId());
            if (terminal != 1) {
                throw new IllegalStateException(
                        "typed Turn was not running at user-wait delivery");
            }
        }
        return receipt;
    }

    public Optional<UserWaitReceipt> findUserWaitResult(String operationId)
    {
        requireText(operationId, "operationId");
        return one(jdbc.query("""
                SELECT operation_id, owner_kind, turn_id, wait_kind, wait_id,
                    payload_digest, result_evidence, accepted_at_ms
                FROM typed_user_wait_result WHERE operation_id = ?
                """, (rs, row) -> new UserWaitReceipt(
                        new ActiveAgentContextRegistry.TypedOwner(
                                DispatchTicket.OwnerKind.valueOf(
                                        rs.getString("owner_kind")),
                                rs.getString("turn_id"),
                                rs.getString("operation_id")),
                        rs.getString("wait_kind"), rs.getString("wait_id"),
                        rs.getString("payload_digest"),
                        rs.getString("result_evidence"),
                        instant(rs, "accepted_at_ms")), operationId));
    }

    @Transactional
    public int cancelOpenWaitsForTask(
            String taskId, String actor, String reason, Instant canceledAt)
    {
        requireText(taskId, "taskId");
        requireText(actor, "actor");
        requireText(reason, "reason");
        requireNonNull(canceledAt, "canceledAt is null");
        int canceled = cancelQuestionsForTask(
                "task_question", "task_turn", "turn.task_id = ?",
                "TASK_TURN", taskId, actor, reason, canceledAt);
        canceled += cancelQuestionsForTask(
                "stage_question", "stage_turn",
                "EXISTS (SELECT 1 FROM stage owner WHERE owner.id = turn.stage_id AND owner.task_id = ?)",
                "STAGE_TURN", taskId, actor, reason, canceledAt);
        String reviewOwner = """
                EXISTS (
                    SELECT 1 FROM review_assignment assignment
                    JOIN review_round round ON round.id = assignment.round_id
                    JOIN review_session session ON session.id = round.session_id
                    WHERE assignment.id = turn.assignment_id
                      AND session.owner_task_id = ?)
                """;
        canceled += cancelQuestionsForTask(
                "review_assignment_question", "review_assignment_turn",
                reviewOwner, "REVIEW_ASSIGNMENT_TURN", taskId, actor, reason,
                canceledAt);
        canceled += cancelReadyQuestionsForTask(
                "task_question", "task_turn", "turn.task_id = ?", taskId);
        canceled += cancelReadyQuestionsForTask(
                "stage_question", "stage_turn",
                "EXISTS (SELECT 1 FROM stage owner WHERE owner.id = turn.stage_id AND owner.task_id = ?)",
                taskId);
        canceled += cancelReadyQuestionsForTask(
                "review_assignment_question", "review_assignment_turn",
                reviewOwner, taskId);
        jdbc.update("""
                INSERT INTO permission_answer_attempt(
                    id, permission_id, expected_revision, proposed_state,
                    actor, answer, outcome, attempted_at_ms)
                SELECT lower(hex(randomblob(16))), permission.id,
                    permission.answer_revision, 'CANCELED', ?, ?, 'ACCEPTED', ?
                FROM permission_request permission
                WHERE permission.state = 'OPEN' AND (
                    (permission.turn_kind = 'TASK' AND EXISTS (
                        SELECT 1 FROM task_turn turn
                        WHERE turn.id = permission.turn_id AND turn.task_id = ?))
                    OR (permission.turn_kind = 'STAGE' AND EXISTS (
                        SELECT 1 FROM stage_turn turn
                        JOIN stage owner ON owner.id = turn.stage_id
                        WHERE turn.id = permission.turn_id AND owner.task_id = ?))
                    OR (permission.turn_kind = 'REVIEW_ASSIGNMENT' AND EXISTS (
                        SELECT 1 FROM review_assignment_turn turn
                        JOIN review_assignment assignment
                          ON assignment.id = turn.assignment_id
                        JOIN review_round round ON round.id = assignment.round_id
                        JOIN review_session session ON session.id = round.session_id
                        WHERE turn.id = permission.turn_id
                          AND session.owner_task_id = ?)))
                """, actor, reason, canceledAt.toEpochMilli(),
                taskId, taskId, taskId);
        canceled += jdbc.update("""
                UPDATE permission_request
                SET state = 'CANCELED', answer = ?,
                    answer_revision = answer_revision + 1,
                    answered_at_ms = ?, answer_actor = ?,
                    continuation_state = 'CANCELED'
                WHERE state = 'OPEN' AND (
                    (turn_kind = 'TASK' AND EXISTS (
                        SELECT 1 FROM task_turn turn
                        WHERE turn.id = permission_request.turn_id
                          AND turn.task_id = ?))
                    OR (turn_kind = 'STAGE' AND EXISTS (
                        SELECT 1 FROM stage_turn turn
                        JOIN stage owner ON owner.id = turn.stage_id
                        WHERE turn.id = permission_request.turn_id
                          AND owner.task_id = ?))
                    OR (turn_kind = 'REVIEW_ASSIGNMENT' AND EXISTS (
                        SELECT 1 FROM review_assignment_turn turn
                        JOIN review_assignment assignment
                          ON assignment.id = turn.assignment_id
                        JOIN review_round round ON round.id = assignment.round_id
                        JOIN review_session session ON session.id = round.session_id
                        WHERE turn.id = permission_request.turn_id
                          AND session.owner_task_id = ?)))
                """, reason, canceledAt.toEpochMilli(), actor,
                taskId, taskId, taskId);
        canceled += jdbc.update("""
                UPDATE permission_request
                SET continuation_state = 'CANCELED', continuation_error = ?
                WHERE state <> 'OPEN' AND continuation_state = 'READY' AND (
                    (turn_kind = 'TASK' AND EXISTS (
                        SELECT 1 FROM task_turn turn
                        WHERE turn.id = permission_request.turn_id
                          AND turn.task_id = ?))
                    OR (turn_kind = 'STAGE' AND EXISTS (
                        SELECT 1 FROM stage_turn turn
                        JOIN stage owner ON owner.id = turn.stage_id
                        WHERE turn.id = permission_request.turn_id
                          AND owner.task_id = ?))
                    OR (turn_kind = 'REVIEW_ASSIGNMENT' AND EXISTS (
                        SELECT 1 FROM review_assignment_turn turn
                        JOIN review_assignment assignment
                          ON assignment.id = turn.assignment_id
                        JOIN review_round round ON round.id = assignment.round_id
                        JOIN review_session session ON session.id = round.session_id
                        WHERE turn.id = permission_request.turn_id
                          AND session.owner_task_id = ?)))
                """, reason, taskId, taskId, taskId);
        return canceled;
    }

    private int cancelReadyQuestionsForTask(
            String questionTable,
            String turnTable,
            String ownerPredicate,
            String taskId)
    {
        return jdbc.update("""
                UPDATE %s
                SET continuation_state = 'CANCELED',
                    continuation_error = 'Task is no longer runnable'
                WHERE state <> 'OPEN' AND continuation_state = 'READY'
                  AND turn_id IN (
                    SELECT turn.id FROM %s turn WHERE %s)
                """.formatted(questionTable, turnTable, ownerPredicate), taskId);
    }

    private int cancelQuestionsForTask(
            String questionTable,
            String turnTable,
            String ownerPredicate,
            String ownerKind,
            String taskId,
            String actor,
            String reason,
            Instant canceledAt)
    {
        jdbc.update("""
                INSERT INTO typed_question_answer_attempt(
                    id, owner_kind, question_id, expected_revision,
                    answer_option_id, answer_free_form, actor, outcome,
                    attempted_at_ms)
                SELECT lower(hex(randomblob(16))), ?, question.id,
                    question.answer_revision, NULL, ?, ?, 'ACCEPTED', ?
                FROM %s question
                JOIN %s turn ON turn.id = question.turn_id
                WHERE question.state = 'OPEN' AND %s
                """.formatted(questionTable, turnTable, ownerPredicate),
                ownerKind, reason, actor, canceledAt.toEpochMilli(), taskId);
        return jdbc.update("""
                UPDATE %s
                SET state = 'CANCELED', answer = ?, answer_free_form = ?,
                    answer_revision = answer_revision + 1, answer_actor = ?,
                    answered_at_ms = ?, continuation_state = 'CANCELED'
                WHERE state = 'OPEN' AND turn_id IN (
                    SELECT turn.id FROM %s turn WHERE %s)
                """.formatted(questionTable, turnTable, ownerPredicate),
                reason, reason, actor, canceledAt.toEpochMilli(), taskId);
    }

    private boolean ownerExists(ActiveAgentContextRegistry.TypedOwner owner)
    {
        String table = turnTable(owner.kind());
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM %s
                WHERE id = ? AND operation_id = ? AND status = 'RUNNING'
                """.formatted(table), Integer.class,
                owner.turnId(), owner.operationId());
        return count != null && count == 1;
    }

    private void requireOnlyWait(
            ActiveAgentContextRegistry.TypedOwner owner,
            String waitKind,
            String waitId)
    {
        String questionTable = supportPrefix(owner.kind()) + "_question";
        List<String> waits = jdbc.queryForList("""
                SELECT 'QUESTION:' || id
                FROM %s WHERE turn_id = ?
                UNION ALL
                SELECT 'PERMISSION:' || id
                FROM permission_request
                WHERE turn_kind = ? AND turn_id = ? AND operation_id = ?
                """.formatted(questionTable), String.class,
                owner.turnId(), turnKind(owner.kind()), owner.turnId(),
                owner.operationId());
        String expected = waitKind + ":" + waitId;
        if (waits.stream().anyMatch(wait -> !wait.equals(expected))) {
            throw new IllegalStateException(
                    "typed Turn already owns another durable user wait");
        }
    }

    private OwnerScope ownerScope(ActiveAgentContextRegistry.TypedOwner owner)
    {
        List<OwnerScope> scopes = switch (owner.kind()) {
            case THREAD_TURN -> jdbc.query("""
                    SELECT turn.trunk_id, NULL AS task_id,
                        repository.repo_full_name AS repository_id
                    FROM thread_turn turn
                    JOIN threads trunk ON trunk.id = turn.trunk_id
                    LEFT JOIN workspace_repos repository
                      ON repository.workspace_id = trunk.workspace_id
                    WHERE turn.id = ? AND turn.operation_id = ?
                    """, V2UserWaitStore::scope, owner.turnId(), owner.operationId());
            case TASK_TURN -> jdbc.query("""
                    SELECT task.thread_id AS trunk_id, task.id AS task_id,
                        context.repository_id
                    FROM task_turn turn
                    JOIN tasks task ON task.id = turn.task_id
                    LEFT JOIN task_creation_context context ON context.task_id = task.id
                    WHERE turn.id = ? AND turn.operation_id = ?
                    """, V2UserWaitStore::scope, owner.turnId(), owner.operationId());
            case STAGE_TURN -> jdbc.query("""
                    SELECT task.thread_id AS trunk_id, task.id AS task_id,
                        context.repository_id
                    FROM stage_turn turn
                    JOIN stage stage ON stage.id = turn.stage_id
                    JOIN tasks task ON task.id = stage.task_id
                    LEFT JOIN task_creation_context context ON context.task_id = task.id
                    WHERE turn.id = ? AND turn.operation_id = ?
                    """, V2UserWaitStore::scope, owner.turnId(), owner.operationId());
            case REVIEW_ASSIGNMENT_TURN -> jdbc.query("""
                    SELECT session.owner_thread_id AS trunk_id,
                        session.owner_task_id AS task_id,
                        session.repo_id AS repository_id
                    FROM review_assignment_turn turn
                    JOIN review_assignment assignment ON assignment.id = turn.assignment_id
                    JOIN review_round round ON round.id = assignment.round_id
                    JOIN review_session session ON session.id = round.session_id
                    WHERE turn.id = ? AND turn.operation_id = ?
                    """, V2UserWaitStore::scope, owner.turnId(), owner.operationId());
            default -> throw unsupported(owner.kind());
        };
        return one(scopes).orElseThrow(() -> new IllegalArgumentException(
                "typed permission owner does not exist"));
    }

    private boolean grantMatches(
            PermissionRequest grant,
            ActiveAgentContextRegistry.TypedOwner currentOwner,
            OwnerScope current,
            String parametersDigest)
    {
        return switch (grant.grantScopeKind()) {
            case "CALL" -> grant.grantScopeId().equals(parametersDigest)
                    && currentOwner.turnId().equals(grant.successorTurnId());
            case "TRUNK" -> grant.grantScopeId().equals(current.trunkId());
            case "TASK" -> grant.grantScopeId().equals(current.taskId());
            case "REPOSITORY" -> grant.grantScopeId().equals(current.repositoryId());
            default -> false;
        };
    }

    private static String questionUnion(String predicate)
    {
        return """
                SELECT * FROM (
                SELECT question.id, question.call_id, question.prompt,
                    question.context, question.options_json,
                    question.allow_free_form, question.state, question.answer,
                    question.answer_option_id, question.answer_free_form,
                    question.answer_actor, question.answer_revision,
                    question.continuation_state, question.successor_turn_id,
                    question.continuation_error, question.created_at_ms,
                    question.answered_at_ms, 'THREAD_TURN' AS owner_kind,
                    question.turn_id, turn.operation_id,
                    turn.trunk_id AS owner_trunk_id, NULL AS task_id,
                    NULL AS stage_id
                FROM thread_question question
                JOIN thread_turn turn ON turn.id = question.turn_id
                UNION ALL
                SELECT question.id, question.call_id, question.prompt,
                    question.context, question.options_json,
                    question.allow_free_form, question.state, question.answer,
                    question.answer_option_id, question.answer_free_form,
                    question.answer_actor, question.answer_revision,
                    question.continuation_state, question.successor_turn_id,
                    question.continuation_error, question.created_at_ms,
                    question.answered_at_ms, 'TASK_TURN' AS owner_kind,
                    question.turn_id, turn.operation_id,
                    task.thread_id AS owner_trunk_id, task.id AS task_id,
                    NULL AS stage_id
                FROM task_question question
                JOIN task_turn turn ON turn.id = question.turn_id
                JOIN tasks task ON task.id = turn.task_id
                UNION ALL
                SELECT question.id, question.call_id, question.prompt,
                    question.context, question.options_json,
                    question.allow_free_form, question.state, question.answer,
                    question.answer_option_id, question.answer_free_form,
                    question.answer_actor, question.answer_revision,
                    question.continuation_state, question.successor_turn_id,
                    question.continuation_error, question.created_at_ms,
                    question.answered_at_ms, 'STAGE_TURN' AS owner_kind,
                    question.turn_id, turn.operation_id,
                    task.thread_id AS owner_trunk_id, task.id AS task_id,
                    stage.id AS stage_id
                FROM stage_question question
                JOIN stage_turn turn ON turn.id = question.turn_id
                JOIN stage stage ON stage.id = turn.stage_id
                JOIN tasks task ON task.id = stage.task_id
                UNION ALL
                SELECT question.id, question.call_id, question.prompt,
                    question.context, question.options_json,
                    question.allow_free_form, question.state, question.answer,
                    question.answer_option_id, question.answer_free_form,
                    question.answer_actor, question.answer_revision,
                    question.continuation_state, question.successor_turn_id,
                    question.continuation_error, question.created_at_ms,
                    question.answered_at_ms,
                    'REVIEW_ASSIGNMENT_TURN' AS owner_kind,
                    question.turn_id, turn.operation_id,
                    session.owner_thread_id AS owner_trunk_id,
                    session.owner_task_id AS task_id,
                    NULL AS stage_id
                FROM review_assignment_question question
                JOIN review_assignment_turn turn ON turn.id = question.turn_id
                JOIN review_assignment assignment
                  ON assignment.id = turn.assignment_id
                JOIN review_round round ON round.id = assignment.round_id
                JOIN review_session session ON session.id = round.session_id
                ) typed_questions
                WHERE %s
                """.formatted(predicate);
    }

    private static Question question(ResultSet row, int ignored)
            throws SQLException
    {
        DispatchTicket.OwnerKind kind = DispatchTicket.OwnerKind.valueOf(
                row.getString("owner_kind"));
        return new Question(
                row.getString("id"),
                new ActiveAgentContextRegistry.TypedOwner(
                        kind, row.getString("turn_id"), row.getString("operation_id")),
                row.getString("owner_trunk_id"), row.getString("task_id"),
                row.getString("stage_id"), row.getString("call_id"),
                row.getString("prompt"), row.getString("context"),
                row.getString("options_json"), row.getInt("allow_free_form") != 0,
                row.getString("state"), row.getString("answer"),
                row.getString("answer_option_id"), row.getString("answer_free_form"),
                row.getString("answer_actor"), row.getInt("answer_revision"),
                row.getString("continuation_state"),
                row.getString("successor_turn_id"),
                row.getString("continuation_error"),
                instant(row, "created_at_ms"),
                instant(row, "answered_at_ms"));
    }

    private static PermissionRequest permission(ResultSet row, int ignored)
            throws SQLException
    {
        return new PermissionRequest(
                row.getString("id"), row.getString("call_id"),
                new ActiveAgentContextRegistry.TypedOwner(
                        ownerKind(row.getString("turn_kind")),
                        row.getString("turn_id"), row.getString("operation_id")),
                row.getString("capability"), row.getString("tool_name"),
                row.getString("parameters_json"), row.getString("parameters_digest"),
                row.getString("policy_snapshot"), row.getString("state"),
                row.getString("answer"), row.getInt("answer_revision"),
                instant(row, "requested_at_ms"), instant(row, "answered_at_ms"),
                row.getString("grant_scope_kind"), row.getString("grant_scope_id"),
                integer(row, "granted_uses"), integer(row, "remaining_uses"),
                row.getInt("consumed_uses"), row.getString("answer_actor"),
                instant(row, "last_consumed_at_ms"),
                row.getString("continuation_state"),
                row.getString("successor_turn_id"),
                row.getString("continuation_error"));
    }

    private static OwnerScope scope(ResultSet row, int ignored)
            throws SQLException
    {
        return new OwnerScope(
                row.getString("trunk_id"), row.getString("task_id"),
                row.getString("repository_id"));
    }

    private static WaitOwnerContext waitOwnerContext(ResultSet row, int ignored)
            throws SQLException
    {
        Long taskEpoch = longValue(row, "task_epoch");
        Long stageGeneration = longValue(row, "stage_generation");
        return new WaitOwnerContext(
                row.getString("trunk_id"), row.getString("task_id"), taskEpoch,
                row.getString("stage_id"), stageGeneration,
                row.getInt("attempt"), row.getString("expected_code_fingerprint"),
                row.getString("expected_head_sha"),
                row.getString("expected_base_sha"),
                row.getString("stage_kind"), row.getString("purpose"));
    }

    private static String turnTable(DispatchTicket.OwnerKind kind)
    {
        return switch (kind) {
            case THREAD_TURN -> "thread_turn";
            case TASK_TURN -> "task_turn";
            case STAGE_TURN -> "stage_turn";
            case REVIEW_ASSIGNMENT_TURN -> "review_assignment_turn";
            default -> throw unsupported(kind);
        };
    }

    private static String supportPrefix(DispatchTicket.OwnerKind kind)
    {
        return switch (kind) {
            case THREAD_TURN -> "thread";
            case TASK_TURN -> "task";
            case STAGE_TURN -> "stage";
            case REVIEW_ASSIGNMENT_TURN -> "review_assignment";
            default -> throw unsupported(kind);
        };
    }

    private static String turnKind(DispatchTicket.OwnerKind kind)
    {
        return switch (kind) {
            case THREAD_TURN -> "THREAD";
            case TASK_TURN -> "TASK";
            case STAGE_TURN -> "STAGE";
            case REVIEW_ASSIGNMENT_TURN -> "REVIEW_ASSIGNMENT";
            default -> throw unsupported(kind);
        };
    }

    private static DispatchTicket.OwnerKind ownerKind(String kind)
    {
        return switch (kind) {
            case "THREAD" -> DispatchTicket.OwnerKind.THREAD_TURN;
            case "TASK" -> DispatchTicket.OwnerKind.TASK_TURN;
            case "STAGE" -> DispatchTicket.OwnerKind.STAGE_TURN;
            case "REVIEW_ASSIGNMENT" -> DispatchTicket.OwnerKind.REVIEW_ASSIGNMENT_TURN;
            default -> throw new IllegalArgumentException("unknown typed Turn kind: " + kind);
        };
    }

    private static IllegalArgumentException unsupported(DispatchTicket.OwnerKind kind)
    {
        return new IllegalArgumentException("unsupported typed Turn owner: " + kind);
    }

    private static void requireOwner(ActiveAgentContextRegistry.TypedOwner owner)
    {
        requireNonNull(owner, "owner is null");
        turnTable(owner.kind());
    }

    private static String blankToNull(String value)
    {
        return value == null || value.isBlank() ? null : value;
    }

    private static String answerText(String option, String freeForm)
    {
        if (option == null) {
            return freeForm;
        }
        if (freeForm == null) {
            return option;
        }
        return option + ": " + freeForm;
    }

    private static String proposedState(PermissionChoice choice)
    {
        return switch (choice.kind()) {
            case DENY -> "DENIED";
            case ALLOW_ONCE -> "ALLOWED_ONCE";
            case ALLOW_NEXT -> "ALLOWED_NEXT";
            case ALWAYS_TASK -> "ALLOWED_TASK";
            case ALWAYS_REPOSITORY -> "ALLOWED_REPOSITORY";
        };
    }

    private static ResolvedChoice resolveChoice(
            PermissionRequest request,
            OwnerScope scope,
            PermissionChoice choice)
    {
        return switch (choice.kind()) {
            case DENY -> new ResolvedChoice(
                    "DENIED", choice.answerJson(), null, null, null, "READY");
            case ALLOW_ONCE -> new ResolvedChoice(
                    "ALLOWED_ONCE", choice.answerJson(), "CALL",
                    request.parametersDigest(), 1, "READY");
            case ALLOW_NEXT -> {
                if (choice.uses() == null || choice.uses() < 1) {
                    throw new IllegalArgumentException(
                            "ALLOW_NEXT requires a positive count");
                }
                if (scope.taskId() != null) {
                    yield new ResolvedChoice(
                            "ALLOWED_NEXT", choice.answerJson(), "TASK",
                            scope.taskId(), choice.uses(), "READY");
                }
                if (scope.trunkId() != null) {
                    yield new ResolvedChoice(
                            "ALLOWED_NEXT", choice.answerJson(), "TRUNK",
                            scope.trunkId(), choice.uses(), "READY");
                }
                if (scope.repositoryId() != null) {
                    yield new ResolvedChoice(
                            "ALLOWED_NEXT", choice.answerJson(), "REPOSITORY",
                            scope.repositoryId(), choice.uses(), "READY");
                }
                throw new IllegalArgumentException(
                        "permission owner has no finite grant scope");
            }
            case ALWAYS_TASK -> {
                if (scope.taskId() == null) {
                    throw new IllegalArgumentException(
                            "permission owner has no Task scope");
                }
                yield new ResolvedChoice(
                        "ALLOWED_TASK", choice.answerJson(), "TASK",
                        scope.taskId(), -1, "READY");
            }
            case ALWAYS_REPOSITORY -> {
                if (scope.repositoryId() == null) {
                    throw new IllegalArgumentException(
                            "permission owner has no repository scope");
                }
                yield new ResolvedChoice(
                        "ALLOWED_REPOSITORY", choice.answerJson(), "REPOSITORY",
                        scope.repositoryId(), -1, "READY");
            }
        };
    }

    private static void requireText(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }

    private static void requireCallId(String callId)
    {
        requireText(callId, "callId");
        if (callId.startsWith(LEGACY_CONSUMPTION_CALL_ID_PREFIX)) {
            throw new IllegalArgumentException(
                    "callId uses the reserved V263 migration prefix");
        }
    }

    private static Instant instant(ResultSet row, String column)
            throws SQLException
    {
        long value = row.getLong(column);
        return row.wasNull() ? null : Instant.ofEpochMilli(value);
    }

    private static Integer integer(ResultSet row, String column)
            throws SQLException
    {
        int value = row.getInt(column);
        return row.wasNull() ? null : value;
    }

    private static Long longValue(ResultSet row, String column)
            throws SQLException
    {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private static <T> Optional<T> one(List<T> values)
    {
        return values.stream().findFirst();
    }

    public record Question(
            String id,
            ActiveAgentContextRegistry.TypedOwner owner,
            String trunkId,
            String taskId,
            String stageId,
            String callId,
            String prompt,
            String context,
            String optionsJson,
            boolean allowFreeForm,
            String state,
            String answer,
            String answerOptionId,
            String answerFreeForm,
            String answerActor,
            int answerRevision,
            String continuationState,
            String successorTurnId,
            String continuationError,
            Instant createdAt,
            Instant answeredAt) {}

    public record QuestionResolution(
            boolean accepted, String outcome, Question question) {}

    public record PermissionRequest(
            String id,
            String callId,
            ActiveAgentContextRegistry.TypedOwner owner,
            String capability,
            String toolName,
            String parametersJson,
            String parametersDigest,
            String policySnapshot,
            String state,
            String answer,
            int answerRevision,
            Instant requestedAt,
            Instant answeredAt,
            String grantScopeKind,
            String grantScopeId,
            Integer grantedUses,
            Integer remainingUses,
            int consumedUses,
            String answerActor,
            Instant lastConsumedAt,
            String continuationState,
            String successorTurnId,
            String continuationError) {}

    public record PermissionChoice(
            PermissionChoiceKind kind, Integer uses, String answerJson)
    {
        public PermissionChoice
        {
            requireNonNull(kind, "kind is null");
            requireText(answerJson, "answerJson");
            if (kind == PermissionChoiceKind.ALLOW_NEXT) {
                if (uses == null || uses < 1) {
                    throw new IllegalArgumentException(
                            "ALLOW_NEXT requires a positive count");
                }
            }
            else if (uses != null) {
                throw new IllegalArgumentException(
                        "only ALLOW_NEXT carries a count");
            }
        }

        public static PermissionChoice deny(String answerJson)
        {
            return new PermissionChoice(
                    PermissionChoiceKind.DENY, null, answerJson);
        }

        public static PermissionChoice allowOnce(String answerJson)
        {
            return new PermissionChoice(
                    PermissionChoiceKind.ALLOW_ONCE, null, answerJson);
        }

        public static PermissionChoice allowNext(int uses, String answerJson)
        {
            return new PermissionChoice(
                    PermissionChoiceKind.ALLOW_NEXT, uses, answerJson);
        }

        public static PermissionChoice alwaysTask(String answerJson)
        {
            return new PermissionChoice(
                    PermissionChoiceKind.ALWAYS_TASK, null, answerJson);
        }

        public static PermissionChoice alwaysRepository(String answerJson)
        {
            return new PermissionChoice(
                    PermissionChoiceKind.ALWAYS_REPOSITORY, null, answerJson);
        }
    }

    public enum PermissionChoiceKind
    {
        DENY,
        ALLOW_ONCE,
        ALLOW_NEXT,
        ALWAYS_TASK,
        ALWAYS_REPOSITORY
    }

    public record Grant(String scopeKind, String scopeId, int uses)
    {
        public Grant
        {
            requireText(scopeKind, "scopeKind");
            requireText(scopeId, "scopeId");
            if (uses == 0 || uses < -1) {
                throw new IllegalArgumentException("grant uses is invalid");
            }
        }
    }

    public record PermissionAnswer(String state, String answerJson, Grant grant)
    {
        public PermissionAnswer
        {
            requireText(state, "state");
            requireText(answerJson, "answerJson");
            boolean allowed = state.startsWith("ALLOWED_");
            if (allowed != (grant != null)) {
                throw new IllegalArgumentException(
                        "permission answer and grant disagree");
            }
        }
    }

    public record PermissionResolution(
            boolean accepted, String outcome, PermissionRequest request) {}

    public record UserWaitReceipt(
            ActiveAgentContextRegistry.TypedOwner owner,
            String waitKind,
            String waitId,
            String payloadDigest,
            String resultEvidence,
            Instant acceptedAt) {}

    public record WaitOwnerContext(
            String trunkId,
            String taskId,
            Long taskEpoch,
            String stageId,
            Long stageGeneration,
            int attempt,
            String expectedCodeFingerprint,
            String expectedHeadSha,
            String expectedBaseSha,
            String stageKind,
            String purpose) {}

    public record ReadyContinuation(
            DispatchTicket.OwnerKind ownerKind,
            String waitKind,
            String waitId) {}

    private record OwnerScope(String trunkId, String taskId, String repositoryId) {}

    private record ResolvedChoice(
            String state,
            String answerJson,
            String grantScopeKind,
            String grantScopeId,
            Integer uses,
            String continuationState) {}
}

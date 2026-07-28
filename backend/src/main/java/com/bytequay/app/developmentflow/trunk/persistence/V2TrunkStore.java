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
package com.bytequay.app.developmentflow.trunk.persistence;

import com.bytequay.app.developmentflow.CommandRejectedException;
import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.developmentflow.task.creation.TaskAssignment;
import com.bytequay.app.developmentflow.task.creation.TaskCreationInput;
import com.bytequay.app.developmentflow.trunk.TrunkLifecycle;
import com.bytequay.app.developmentflow.trunk.TrunkManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.CONCURRENT_UPDATE;
import static java.util.Objects.requireNonNull;

/** Spring-transaction-bound persistence for the Trunk aggregate. */
@Component
final class V2TrunkStore
        implements TrunkManager.Store
{
    private final JdbcTemplate jdbc;

    V2TrunkStore(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    @Override
    public Optional<TrunkManager.State> findById(String trunkId)
    {
        return jdbc.query("""
                SELECT id, lifecycle_state, aggregate_version
                FROM threads
                WHERE id = ? AND turn_version = 'V2'
                """,
                (rs, row) -> new TrunkManager.State(
                        rs.getString("id"),
                        TrunkLifecycle.valueOf(rs.getString("lifecycle_state")),
                        rs.getLong("aggregate_version")),
                trunkId).stream().findFirst();
    }

    @Override
    public Optional<TrunkManager.CommandReceipt> findCommandResult(
            String trunkId, String commandId)
    {
        return jdbc.query("""
                SELECT cause, actor, disposition, expected_version,
                       returned_lifecycle, returned_version
                FROM trunk_command_receipt
                WHERE trunk_id = ? AND command_id = ?
                """,
                (rs, row) -> new TrunkManager.CommandReceipt(
                        new TrunkManager.State(
                                trunkId,
                                TrunkLifecycle.valueOf(rs.getString("returned_lifecycle")),
                                rs.getLong("returned_version")),
                        rs.getString("cause"),
                        rs.getString("actor"),
                        rs.getLong("expected_version"),
                        CommandResult.Disposition.valueOf(rs.getString("disposition"))),
                trunkId, commandId).stream().findFirst();
    }

    @Override
    public Optional<TrunkManager.TaskCreationAuthorizationReceipt>
            findTaskCreationAuthorization(String trunkId, String commandId)
    {
        if (!taskCreationProtocolExists()) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT actor, expected_trunk_version, returned_trunk_version,
                       returned_lifecycle, assignment_id, id, policy_revision_id
                FROM trunk_task_creation_authorization
                WHERE trunk_id = ? AND command_id = ?
                """,
                (rs, row) -> new TrunkManager.TaskCreationAuthorizationReceipt(
                        new TrunkManager.State(
                                trunkId,
                                TrunkLifecycle.valueOf(
                                        rs.getString("returned_lifecycle")),
                                rs.getLong("returned_trunk_version")),
                        rs.getString("actor"),
                        rs.getLong("expected_trunk_version"),
                        rs.getString("assignment_id"),
                        rs.getString("id"),
                        rs.getString("policy_revision_id")),
                trunkId, commandId).stream().findFirst();
    }

    private boolean taskCreationProtocolExists()
    {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sqlite_master
                WHERE type = 'table'
                  AND name = 'trunk_task_creation_authorization'
                """, Integer.class);
        return count != null && count == 1;
    }

    @Override
    public boolean matchesTaskCreationAuthorization(
            TrunkManager.TaskCreationCommand command)
    {
        TaskCreationInput input = command.input();
        TaskAssignment assignment = input.assignment();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT authorization.workspace_id AS workspace_id,
                       authorization.actor AS actor,
                       authorization.expected_trunk_version AS expected_version,
                       authorization.returned_trunk_version AS returned_version,
                       authorization.returned_lifecycle AS returned_lifecycle,
                       authorization.provenance AS provenance,
                       authorization.repository_id AS repository_id,
                       authorization.upstream_repository_id AS upstream_repository_id,
                       authorization.publish_repository_id AS publish_repository_id,
                       authorization.base_source AS base_source,
                       authorization.base_repository_id AS base_repository_id,
                       authorization.base_ref AS base_ref,
                       authorization.planning_base_sha AS authorized_planning_base_sha,
                       authorization.assignment_base_sha AS assignment_base_sha,
                       authorization.assignment_head_sha AS assignment_head_sha,
                       authorization.engine_snapshot AS engine_snapshot,
                       authorization.work_model_snapshot AS work_model_snapshot,
                       authorization.task_name AS task_name,
                       authorization.task_type AS task_type,
                       authorization.linked_issue_number AS linked_issue_number,
                       authorization.opening_prompt AS opening_prompt,
                       authorization.task_origin AS task_origin,
                       authorization.recorded_at_ms AS authorization_recorded_at_ms,
                       assignment.kind AS assignment_kind,
                       assignment.source_id AS source_id,
                       assignment.repository_id AS assignment_repository_id,
                       assignment.pr_number AS pr_number,
                       assignment.remote_head_sha AS remote_head_sha,
                       assignment.planning_base_sha AS assignment_planning_base_sha,
                       assignment.plan_seed AS plan_seed,
                       assignment.prompt AS prompt,
                       assignment.producer AS producer,
                       assignment.reason AS reason,
                       assignment.selected_findings_json AS selected_findings_json,
                       assignment.created_by AS assignment_created_by,
                       assignment.created_at_ms AS assignment_created_at_ms,
                       assignment.base_repository_id AS assignment_base_repository_id,
                       assignment.head_repository_id AS assignment_head_repository_id,
                       assignment.base_ref AS assignment_base_ref,
                       assignment.head_ref AS assignment_head_ref,
                       assignment.remote_base_sha AS remote_base_sha,
                       assignment.repository_route AS repository_route,
                       policy.revision AS policy_revision,
                       policy.source AS policy_source,
                       policy.auto_approve AS auto_approve,
                       policy.auto_merge AS auto_merge,
                       policy.min_approvals AS min_approvals,
                       policy.max_brain_rounds AS max_brain_rounds,
                       policy.max_ci_fix_pushes AS max_ci_fix_pushes,
                       policy.require_remote_branch_cleanup
                           AS require_remote_branch_cleanup,
                       policy.permission_policy_ref AS permission_policy_ref,
                       policy.created_by AS policy_created_by,
                       policy.created_at_ms AS policy_created_at_ms
                FROM trunk_task_creation_authorization authorization
                JOIN task_assignment assignment
                  ON assignment.id = authorization.assignment_id
                JOIN task_policy_revision policy
                  ON policy.id = authorization.policy_revision_id
                WHERE authorization.trunk_id = ?
                  AND authorization.command_id = ?
                  AND authorization.assignment_id = ?
                  AND authorization.id = ?
                  AND authorization.policy_revision_id = ?
                """,
                assignment.identity().trunkId(), command.commandId(),
                assignment.identity().id(),
                assignment.identity().creationAuthorizationId(), input.policy().id());
        if (rows.size() != 1 || !matchesCommon(rows.getFirst(), command)) {
            return false;
        }
        if (assignment instanceof TaskAssignment.ReviewFindings review) {
            List<TaskAssignment.ReviewFindingRef> findings = jdbc.query("""
                    SELECT source_review_id, finding_id, finding_revision,
                           content_digest
                    FROM task_assignment_review_finding
                    WHERE assignment_id = ? ORDER BY position
                    """,
                    (rs, row) -> new TaskAssignment.ReviewFindingRef(
                            rs.getString("source_review_id"),
                            rs.getString("finding_id"),
                            rs.getInt("finding_revision"),
                            rs.getString("content_digest")),
                    assignment.identity().id());
            return findings.equals(review.findings());
        }
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM task_assignment_review_finding
                WHERE assignment_id = ?
                """, Integer.class, assignment.identity().id()) == 0;
    }

    @Override
    public TrunkManager.State authorizeTaskCreation(
            TrunkManager.TaskCreationCommand command,
            TrunkManager.State expected,
            TrunkManager.State updated)
    {
        requireTransaction();
        TaskCreationInput input = command.input();
        TaskAssignment assignment = input.assignment();
        if (!expected.id().equals(assignment.identity().trunkId())
                || !expected.id().equals(updated.id())
                || expected.lifecycle() != updated.lifecycle()
                || expected.version() != command.expectedTrunkVersion()
                || updated.version() != expected.version() + 1) {
            throw new IllegalArgumentException("Task creation Trunk fence is inconsistent");
        }

        ensurePolicy(input.policy());
        insertAssignment(assignment);
        if (assignment instanceof TaskAssignment.ReviewFindings review) {
            int position = 1;
            for (TaskAssignment.ReviewFindingRef finding : review.findings()) {
                jdbc.update("""
                        INSERT INTO task_assignment_review_finding(
                            assignment_id, position, source_review_id, finding_id,
                            finding_revision, content_digest)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                        assignment.identity().id(), position++,
                        finding.sourceReviewId(), finding.findingId(),
                        finding.findingRevision(), finding.contentDigest());
            }
        }

        int changed = jdbc.update("""
                UPDATE threads
                SET aggregate_version = ?
                WHERE id = ? AND turn_version = 'V2'
                  AND lifecycle_state = ? AND aggregate_version = ?
                """,
                updated.version(), expected.id(), expected.lifecycle().name(),
                expected.version());
        if (changed != 1) {
            throw concurrent("Trunk changed before Task authorization: " + expected.id());
        }

        long recordedAt = input.createdAt().toEpochMilli();
        jdbc.update("""
                INSERT INTO trunk_transition(
                    id, trunk_id, command_id, from_state, to_state,
                    aggregate_version, cause, actor, occurred_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, 'AUTHORIZE_TASK_CREATION', ?, ?)
                """,
                id(), expected.id(), command.commandId(), expected.lifecycle().name(),
                updated.lifecycle().name(), updated.version(), command.actor(), recordedAt);

        TaskCreationInput.CreationBase base = input.base();
        TaskAssignment.RepositoryRouting repositories = base.repositories();
        jdbc.update("""
                INSERT INTO trunk_task_creation_authorization(
                    id, trunk_id, workspace_id, command_id, actor, disposition,
                    expected_trunk_version, returned_trunk_version,
                    returned_lifecycle, assignment_id, policy_revision_id,
                    provenance, repository_id, upstream_repository_id,
                    publish_repository_id, base_source, base_repository_id,
                    base_ref, planning_base_sha, assignment_base_sha,
                    assignment_head_sha, engine_snapshot, work_model_snapshot,
                    task_name, task_type, linked_issue_number, opening_prompt,
                    task_origin,
                    recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, 'AUTHORIZED', ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                assignment.identity().creationAuthorizationId(), expected.id(),
                input.workspaceId(), command.commandId(), command.actor(),
                expected.version(), updated.version(), updated.lifecycle().name(),
                assignment.identity().id(), input.policy().id(),
                input.provenance().name(), repositories.repositoryId(),
                repositories.upstreamRepositoryId().orElse(null),
                repositories.publishRepositoryId(), input.base().source().name(),
                repositories.baseRepositoryId(), base.baseRef(), planningBase(input),
                assignmentBase(input), assignmentHead(input),
                input.engine().canonicalValue(),
                input.workModel().value(), input.presentation().name(),
                input.presentation().taskType(),
                input.presentation().linkedIssueNumber(),
                input.presentation().openingPrompt(),
                input.presentation().origin(), recordedAt);
        return updated;
    }

    @Override
    public TrunkManager.State commit(
            String commandId,
            String cause,
            String actor,
            long expectedVersion,
            TrunkManager.State expected,
            TrunkManager.State updated)
    {
        requireTransaction();
        if (!expected.id().equals(updated.id())
                || expected.version() != expectedVersion
                || updated.version() != expectedVersion + 1) {
            throw new IllegalArgumentException("Trunk commit fence is inconsistent");
        }
        int changed = jdbc.update("""
                UPDATE threads
                SET lifecycle_state = ?, aggregate_version = ?
                WHERE id = ?
                  AND turn_version = 'V2'
                  AND lifecycle_state = ?
                  AND aggregate_version = ?
                """,
                updated.lifecycle().name(), updated.version(), expected.id(),
                expected.lifecycle().name(), expectedVersion);
        if (changed != 1) {
            throw concurrent("Trunk changed before commit: " + expected.id());
        }

        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO trunk_transition(
                    id, trunk_id, command_id, from_state, to_state,
                    aggregate_version, cause, actor, occurred_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id(), expected.id(), commandId, expected.lifecycle().name(),
                updated.lifecycle().name(), updated.version(), cause, actor, now);
        jdbc.update("""
                INSERT INTO trunk_command_receipt(
                    id, trunk_id, command_id, cause, actor, disposition,
                    expected_version, returned_lifecycle, returned_version, recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, 'APPLIED', ?, ?, ?, ?)
                """,
                id(), expected.id(), commandId, cause, actor, expectedVersion,
                updated.lifecycle().name(), updated.version(), now);
        return updated;
    }

    private static void requireTransaction()
    {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Trunk writes require the command transaction");
        }
    }

    private static CommandRejectedException concurrent(String message)
    {
        return new CommandRejectedException(CONCURRENT_UPDATE, message);
    }

    private static String id()
    {
        return UUID.randomUUID().toString();
    }

    private void ensurePolicy(TaskCreationInput.TaskPolicy policy)
    {
        Integer exists = jdbc.queryForObject("""
                SELECT COUNT(*) FROM task_policy_revision WHERE id = ?
                """, Integer.class, policy.id());
        if (exists != null && exists == 1) {
            if (!policyMatches(policy)) {
                throw new DataIntegrityViolationException(
                        "Task policy id already describes another revision");
            }
            return;
        }
        jdbc.update("""
                INSERT INTO task_policy_revision(
                    id, trunk_id, revision, source, auto_approve, auto_merge,
                    min_approvals, max_brain_rounds, max_ci_fix_pushes,
                    require_remote_branch_cleanup, permission_policy_ref,
                    created_by, created_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                policy.id(), policy.trunkId(), policy.revision(), policy.source(),
                policy.autoApprove(), policy.autoMerge(), policy.minApprovals(),
                policy.maxBrainRounds(), policy.maxCiFixPushes(),
                policy.requireRemoteBranchCleanup(),
                policy.permissionPolicyRef().orElse(null), policy.createdBy(),
                policy.createdAt().toEpochMilli());
    }

    private boolean policyMatches(TaskCreationInput.TaskPolicy policy)
    {
        Integer matches = jdbc.queryForObject("""
                SELECT COUNT(*) FROM task_policy_revision
                WHERE id = ? AND trunk_id = ? AND revision = ? AND source = ?
                  AND auto_approve = ? AND auto_merge = ? AND min_approvals = ?
                  AND max_brain_rounds = ? AND max_ci_fix_pushes = ?
                  AND require_remote_branch_cleanup = ?
                  AND permission_policy_ref IS ? AND created_by = ?
                  AND created_at_ms = ?
                """, Integer.class,
                policy.id(), policy.trunkId(), policy.revision(), policy.source(),
                policy.autoApprove(), policy.autoMerge(), policy.minApprovals(),
                policy.maxBrainRounds(), policy.maxCiFixPushes(),
                policy.requireRemoteBranchCleanup(),
                policy.permissionPolicyRef().orElse(null), policy.createdBy(),
                policy.createdAt().toEpochMilli());
        return matches != null && matches == 1;
    }

    private void insertAssignment(TaskAssignment assignment)
    {
        TaskAssignment.PullRequestRef pullRequest = pullRequest(assignment);
        TaskAssignment.RepositoryRouting route = pullRequest == null
                ? null
                : pullRequest.repositories();
        jdbc.update("""
                INSERT INTO task_assignment(
                    id, trunk_id, kind, source_id, repository_id, pr_number,
                    remote_head_sha, planning_base_sha, plan_seed, prompt,
                    producer, reason, selected_findings_json, created_by,
                    created_at_ms, base_repository_id, head_repository_id,
                    base_ref, head_ref, remote_base_sha, repository_route,
                    creation_authorization_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?)
                """,
                assignment.identity().id(), assignment.identity().trunkId(),
                assignment.kind().name(), sourceId(assignment),
                route == null ? null : route.repositoryId(),
                pullRequest == null ? null : pullRequest.number(),
                pullRequest == null ? null : pullRequest.remoteHeadSha(),
                assignment instanceof TaskAssignment.NewFromTrunk newTask
                        && newTask.origin() instanceof TaskAssignment.AgentHandoff origin
                        ? origin.planningBaseSha()
                        : null,
                assignment instanceof TaskAssignment.NewFromTrunk newTask
                        ? newTask.planSeed()
                        : null,
                assignment instanceof TaskAssignment.NewFromTrunk newTask
                        ? newTask.prompt()
                        : null,
                assignment instanceof TaskAssignment.Automation automation
                        ? automation.producer()
                        : null,
                assignment instanceof TaskAssignment.Automation automation
                        ? automation.reason()
                        : null,
                assignment instanceof TaskAssignment.ReviewFindings ? "[]" : null,
                assignment.identity().createdBy(),
                assignment.identity().createdAt().toEpochMilli(),
                route == null ? null : route.baseRepositoryId(),
                route == null ? null : route.publishRepositoryId(),
                pullRequest == null ? null : pullRequest.baseRef(),
                pullRequest == null ? null : pullRequest.headRef(),
                pullRequest == null ? null : pullRequest.remoteBaseSha(),
                route == null ? null : route.route().name(),
                assignment.identity().creationAuthorizationId());
    }

    private static boolean matchesCommon(
            Map<String, Object> row, TrunkManager.TaskCreationCommand command)
    {
        TaskCreationInput input = command.input();
        TaskAssignment assignment = input.assignment();
        TaskCreationInput.TaskPolicy policy = input.policy();
        TaskCreationInput.CreationBase base = input.base();
        TaskAssignment.RepositoryRouting repositories = base.repositories();
        TaskAssignment.PullRequestRef pullRequest = pullRequest(assignment);
        return same(row, "workspace_id", input.workspaceId())
                && same(row, "actor", command.actor())
                && number(row, "expected_version") == command.expectedTrunkVersion()
                && number(row, "returned_version") == command.expectedTrunkVersion() + 1
                && same(row, "provenance", input.provenance().name())
                && same(row, "repository_id", repositories.repositoryId())
                && same(row, "upstream_repository_id",
                        repositories.upstreamRepositoryId().orElse(null))
                && same(row, "publish_repository_id",
                        repositories.publishRepositoryId())
                && same(row, "base_source", base.source().name())
                && same(row, "base_repository_id", repositories.baseRepositoryId())
                && same(row, "base_ref", base.baseRef())
                && same(row, "authorized_planning_base_sha", planningBase(input))
                && same(row, "assignment_base_sha", assignmentBase(input))
                && same(row, "assignment_head_sha", assignmentHead(input))
                && same(row, "engine_snapshot", input.engine().canonicalValue())
                && same(row, "work_model_snapshot", input.workModel().value())
                && same(row, "task_name", input.presentation().name())
                && same(row, "task_type", input.presentation().taskType())
                && Objects.equals(
                        nullableNumber(row, "linked_issue_number"),
                        input.presentation().linkedIssueNumber() == null
                                ? null
                                : input.presentation().linkedIssueNumber().longValue())
                && same(row, "opening_prompt", input.presentation().openingPrompt())
                && same(row, "task_origin", input.presentation().origin())
                && number(row, "authorization_recorded_at_ms")
                        == input.createdAt().toEpochMilli()
                && same(row, "assignment_kind", assignment.kind().name())
                && same(row, "source_id", sourceId(assignment))
                && same(row, "assignment_repository_id",
                        pullRequest == null ? null
                                : pullRequest.repositories().repositoryId())
                && Objects.equals(
                        nullableNumber(row, "pr_number"),
                        pullRequest == null ? null : (long) pullRequest.number())
                && same(row, "remote_head_sha",
                        pullRequest == null ? null : pullRequest.remoteHeadSha())
                && same(row, "assignment_planning_base_sha",
                        assignment instanceof TaskAssignment.NewFromTrunk newTask
                                && newTask.origin()
                                        instanceof TaskAssignment.AgentHandoff origin
                                ? origin.planningBaseSha()
                                : null)
                && same(row, "plan_seed",
                        assignment instanceof TaskAssignment.NewFromTrunk newTask
                                ? newTask.planSeed()
                                : null)
                && same(row, "prompt",
                        assignment instanceof TaskAssignment.NewFromTrunk newTask
                                ? newTask.prompt()
                                : null)
                && same(row, "producer",
                        assignment instanceof TaskAssignment.Automation automation
                                ? automation.producer()
                                : null)
                && same(row, "reason",
                        assignment instanceof TaskAssignment.Automation automation
                                ? automation.reason()
                                : null)
                && same(row, "selected_findings_json",
                        assignment instanceof TaskAssignment.ReviewFindings ? "[]" : null)
                && same(row, "assignment_created_by", assignment.identity().createdBy())
                && number(row, "assignment_created_at_ms")
                        == assignment.identity().createdAt().toEpochMilli()
                && same(row, "assignment_base_repository_id",
                        pullRequest == null ? null
                                : pullRequest.repositories().baseRepositoryId())
                && same(row, "assignment_head_repository_id",
                        pullRequest == null ? null
                                : pullRequest.repositories().publishRepositoryId())
                && same(row, "assignment_base_ref",
                        pullRequest == null ? null : pullRequest.baseRef())
                && same(row, "assignment_head_ref",
                        pullRequest == null ? null : pullRequest.headRef())
                && same(row, "remote_base_sha",
                        pullRequest == null ? null : pullRequest.remoteBaseSha())
                && same(row, "repository_route",
                        pullRequest == null ? null
                                : pullRequest.repositories().route().name())
                && number(row, "policy_revision") == policy.revision()
                && same(row, "policy_source", policy.source())
                && number(row, "auto_approve") == (policy.autoApprove() ? 1 : 0)
                && number(row, "auto_merge") == (policy.autoMerge() ? 1 : 0)
                && number(row, "min_approvals") == policy.minApprovals()
                && number(row, "max_brain_rounds") == policy.maxBrainRounds()
                && number(row, "max_ci_fix_pushes") == policy.maxCiFixPushes()
                && number(row, "require_remote_branch_cleanup")
                        == (policy.requireRemoteBranchCleanup() ? 1 : 0)
                && same(row, "permission_policy_ref",
                        policy.permissionPolicyRef().orElse(null))
                && same(row, "policy_created_by", policy.createdBy())
                && number(row, "policy_created_at_ms")
                        == policy.createdAt().toEpochMilli();
    }

    private static String sourceId(TaskAssignment assignment)
    {
        return switch (assignment) {
            case TaskAssignment.ReviewFindings review -> review.sourceReviewId();
            case TaskAssignment.Issue issue -> issue.issueIdentity();
            case TaskAssignment.QualityScan quality -> quality.evidenceIdentity();
            default -> null;
        };
    }

    private static TaskAssignment.PullRequestRef pullRequest(TaskAssignment assignment)
    {
        return switch (assignment) {
            case TaskAssignment.ExistingOwnPr existing -> existing.pullRequest();
            case TaskAssignment.ReviewFindings review -> review.pullRequest();
            default -> null;
        };
    }

    private static String planningBase(TaskCreationInput input)
    {
        return input.base() instanceof TaskCreationInput.PlanningSnapshot planning
                ? planning.baseSha()
                : null;
    }

    private static String assignmentBase(TaskCreationInput input)
    {
        return input.base() instanceof TaskCreationInput.ExistingPrHead existing
                ? existing.pullRequest().remoteBaseSha()
                : null;
    }

    private static String assignmentHead(TaskCreationInput input)
    {
        return input.base() instanceof TaskCreationInput.ExistingPrHead existing
                ? existing.pullRequest().remoteHeadSha()
                : null;
    }

    private static boolean same(Map<String, Object> row, String name, Object expected)
    {
        return Objects.equals(row.get(name), expected);
    }

    private static long number(Map<String, Object> row, String name)
    {
        return ((Number) row.get(name)).longValue();
    }

    private static Long nullableNumber(Map<String, Object> row, String name)
    {
        Object value = row.get(name);
        return value == null ? null : ((Number) value).longValue();
    }
}

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
package com.bytequay.app.flow.timeline;

import com.bytequay.app.flow.ci.CiAutofixSchema;
import com.bytequay.app.flow.gate.UserGatesSchema;
import com.bytequay.app.flow.github.GitHubEffectsSchema;
import com.bytequay.app.flow.runtime.FlowRuntimeSchema;
import com.bytequay.app.flow.timeline.PrTimelineProjection.EventActor;
import com.bytequay.app.flow.timeline.PrTimelineProjection.EventKind;
import com.bytequay.app.flow.timeline.PrTimelineProjection.PageStatus;
import com.bytequay.app.flow.timeline.PrTimelineProjection.TimelineCursor;
import com.bytequay.app.flow.timeline.PrTimelineProjection.TimelineEvent;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestPrTimelineProjection
{
    private static final long AT = 1_787_500_000_000L;

    @TempDir
    private Path temporaryDirectory;

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private PrTimelineProjection projection;

    @BeforeEach
    void setUp()
    {
        dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:" + temporaryDirectory.resolve("timeline.db"));
        FlowRuntimeSchema.install(dataSource);
        CiAutofixSchema.install(dataSource);
        UserGatesSchema.install(dataSource);
        GitHubEffectsSchema.install(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        insertTwelveFacts();
        projection = new PrTimelineProjection(dataSource);
    }

    @Test
    void projectsTheTwelveImmutableOwnersInBinaryTupleOrder()
    {
        var page = projection.page("pr-1", null, 100);

        assertThat(page.status()).isEqualTo(PageStatus.OK);
        assertThat(page.eventCount()).isEqualTo(12);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.events()).extracting(TimelineEvent::kind)
                .containsExactly(
                        EventKind.TASK_LIFECYCLE,
                        EventKind.TASK_BASE_REVISION,
                        EventKind.CHANGE_SET_REVISION,
                        EventKind.PR_MATERIALIZED,
                        EventKind.REMOTE_IDENTITY_BOUND,
                        EventKind.LOCAL_CHECK_COMPLETED,
                        EventKind.AGENT_RESULT_STORED,
                        EventKind.CI_CONSENT_REVISION,
                        EventKind.GATE_TRANSITION,
                        EventKind.EXTERNAL_EFFECT_RECEIPT,
                        EventKind.CI_CHECK_OBSERVED,
                        EventKind.CI_LESSON_CANDIDATE);
        assertThat(page.events()).extracting(TimelineEvent::eventId)
                .containsExactly(
                        "task-lifecycle:life-1:1",
                        "task-base:base-1:1",
                        "change-set:change-1:1",
                        "pr:pr-1:1",
                        "remote-identity:remote-1:1",
                        "check:check-1:1",
                        "agent-result:result-1:1",
                        "ci-consent:consent-1:1",
                        "gate:gate-1:1",
                        "effect-receipt:receipt-1:1",
                        "ci-check:observation-1:1",
                        "ci-lesson:lesson-1:1");
        assertThat(page.events()).extracting(TimelineEvent::typeRank)
                .containsExactly(10, 20, 30, 40, 50, 60,
                        70, 80, 90, 100, 110, 120);
        assertThat(page.events()).allSatisfy(event -> {
            assertThat(event.recordedAt()).isEqualTo(Instant.ofEpochMilli(AT));
            assertThat(event.ownerRef().ownerId()).isNotBlank();
            assertThat(event.ownerRef().revision()).isPositive();
        });
        assertThat(page.events().get(1).headSha()).isNull();
        assertThat(page.events().get(5).headSha()).isEqualTo("H1");
        assertThat(page.events().get(5).occurredAt()).isNull();
        assertThat(page.events().toString())
                .doesNotContain(
                        "private", "FIXED_ERROR", "stop-proof",
                        "consent-digest", "tree-digest", "diff-digest");
        assertThat(List.of(TimelineEvent.class.getRecordComponents())
                .stream().map(component -> component.getName()).toList())
                .containsExactly(
                        "eventId", "recordedAt", "occurredAt", "typeRank",
                        "source", "kind", "actor", "status", "headSha",
                        "ownerRef");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sqlite_master
                WHERE type = 'table' AND name LIKE '%timeline%'
                """, Integer.class)).isZero();
    }

    @Test
    void exactGateRevisionAuthorityAndPrIsolationArePreserved()
    {
        jdbc.update("""
                INSERT INTO flow_user_gate_transition (
                    gate_id, gate_revision, sequence, from_state, to_state,
                    actor_type, actor_id, reason_code, detail_ref, recorded_at
                ) VALUES ('gate-1', 1, 2, 'OPEN', 'AUTHORIZED', 'PROGRAM',
                    'USER_GATES_CI_CONSENT', 'CI_UPDATE_CONSENT_AUTHORIZATION',
                    'authorization-1', ?)
                """, AT);
        insertGateSubject("gate-subject-2", "H2", "run-gate-2");
        insertGateRevision(2, "gate-subject-2", "run-gate-2");
        jdbc.update("UPDATE flow_user_gate SET current_revision = 2 "
                + "WHERE gate_id = 'gate-1'");
        jdbc.update("""
                INSERT INTO flow_user_gate_transition (
                    gate_id, gate_revision, sequence, from_state, to_state,
                    actor_type, actor_id, reason_code, detail_ref, recorded_at
                ) VALUES ('gate-1', 2, 3, NULL, 'OPEN', 'PROGRAM',
                    'USER_GATES', 'READY', NULL, ?)
                """, AT);
        jdbc.update("""
                INSERT INTO flow_runtime_task (
                    task_id, request_key, repository_id, repository_owner,
                    repository_name, goal_text, repository_root,
                    git_common_dir, remote_name, base_ref, launch_digest,
                    status, branch_name, worktree_path
                ) VALUES ('task-2', 'request-2', 'repo-2', 'other', 'repo',
                    'other private goal', '/other/repo', '/other/repo/.git',
                    'origin', 'main', 'launch-2', 'ACTIVE', 'branch-2',
                    '/other/private/worktree')
                """);
        jdbc.update("""
                INSERT INTO flow_runtime_pr (
                    pr_id, task_id, repository_id, base_ref, base_sha,
                    target_base_ref, scope_key, branch_name,
                    created_from_change_set_revision_id,
                    created_from_head_sha, created_at
                ) VALUES ('pr-2', 'task-2', 'repo-2', 'main', 'B2', 'main',
                    'scope-2', 'branch-2', 'other-change', 'OTHER_HEAD', ?)
                """, AT);

        var events = projection.page("pr-1", null, 100).events();
        assertThat(events).filteredOn(
                event -> event.kind() == EventKind.GATE_TRANSITION)
                .extracting(
                        TimelineEvent::eventId,
                        TimelineEvent::headSha,
                        TimelineEvent::actor)
                .containsExactly(
                        Tuple.tuple(
                                "gate:gate-1:1", "H1", EventActor.PROGRAM),
                        Tuple.tuple(
                                "gate:gate-1:2", "H1",
                                EventActor.CI_UPDATE_CONSENT),
                        Tuple.tuple(
                                "gate:gate-1:3", "H2", EventActor.PROGRAM));
        assertThat(events).extracting(TimelineEvent::eventId)
                .noneMatch(id -> id.contains("pr-2"));
    }

    @Test
    void cursorPagesRestartOnEveryLateFactOrChangedProjection()
    {
        List<String> ids = new ArrayList<>();
        TimelineCursor cursor = null;
        do {
            var page = projection.page("pr-1", cursor, 3);
            assertThat(page.status()).isEqualTo(PageStatus.OK);
            page.events().forEach(event -> ids.add(event.eventId()));
            cursor = page.nextCursor();
            if (!page.hasMore()) {
                break;
            }
        }
        while (true);
        assertThat(ids).hasSize(12).doesNotHaveDuplicates();

        TimelineCursor oldCursor = projection.page("pr-1", null, 3)
                .nextCursor();
        TimelineCursor wrongPr = new TimelineCursor(
                "pr-2", oldCursor.schemaVersion(), oldCursor.eventCount(),
                oldCursor.recordedAt(), oldCursor.typeRank(),
                oldCursor.eventId());
        assertThat(projection.page("pr-1", wrongPr, 3).status())
                .isEqualTo(PageStatus.RESTART_REQUIRED);
        insertLifecycle("life-0", 2, AT);
        var stale = projection.page("pr-1", oldCursor, 3);
        assertThat(stale.status()).isEqualTo(PageStatus.RESTART_REQUIRED);
        assertThat(stale.events()).isEmpty();
        assertThat(stale.nextCursor()).isNull();
        assertThat(stale.eventCount()).isEqualTo(13);

        var restarted = new PrTimelineProjection(dataSource)
                .page("pr-1", null, 100);
        assertThat(restarted.events()).hasSize(13);
        assertThat(restarted.events().subList(0, 2))
                .extracting(TimelineEvent::eventId)
                .containsExactly(
                        "task-lifecycle:life-0:1",
                        "task-lifecycle:life-1:1");

        TimelineCursor wrongVersion = new TimelineCursor(
                oldCursor.prId(), 1,
                restarted.eventCount(), oldCursor.recordedAt(),
                oldCursor.typeRank(), oldCursor.eventId());
        assertThat(projection.page("pr-1", wrongVersion, 3).status())
                .isEqualTo(PageStatus.RESTART_REQUIRED);
        TimelineCursor forged = new TimelineCursor(
                "pr-1", PrTimelineProjection.SCHEMA_VERSION,
                restarted.eventCount(), Instant.ofEpochMilli(AT),
                10, "task-lifecycle:not-retained:1");
        assertThat(projection.page("pr-1", forged, 3).status())
                .isEqualTo(PageStatus.RESTART_REQUIRED);
    }

    @Test
    void projectsInitialGateAndFinalReceiptButNotPartialReceipt()
    {
        jdbc.update("""
                INSERT INTO flow_user_gate_subject_manifest (
                    subject_id, kind, subject_digest
                ) VALUES ('initial-subject', 'INITIAL_PUBLISH',
                    'initial-subject-digest')
                """);
        jdbc.update("""
                INSERT INTO flow_user_gate_action_manifest (
                    action_ref, kind, action_digest
                ) VALUES ('initial-action', 'INITIAL_PUBLISH',
                    'initial-action-digest')
                """);
        jdbc.update("""
                INSERT INTO flow_user_gate_initial_publish_subject (
                    subject_id, subject_digest, task_id, pr_id, repository_id,
                    launch_digest, change_set_revision_id, base_revision_id,
                    expected_base_sha, proposed_head, head_tree_digest,
                    diff_digest, draft_revision_id, draft_digest,
                    required_ci_policy_revision_id,
                    local_check_policy_revision_id, reviewer_request_id,
                    reviewer_run_id, reviewer_result_id,
                    local_review_binding_id, local_review_digest,
                    base_repository_external_id, base_repository_owner,
                    base_repository_name, head_repository_external_id,
                    head_repository_owner, head_repository_name, branch_ref,
                    target_base_ref, target_snapshot_id,
                    target_snapshot_digest, created_by_run_id, created_at
                ) VALUES ('initial-subject', 'initial-subject-digest',
                    'task-1', 'pr-1', 'repo-1', 'launch-1', 'change-1',
                    'base-1', 'B1', 'H_INITIAL', 'tree-initial',
                    'diff-initial', 'draft-initial', 'draft-digest-initial',
                    'ci-policy-1', 'local-policy-1', 'request-initial',
                    'run-initial-review', 'result-initial-review',
                    'binding-initial', 'binding-digest-initial', '100',
                    'owner', 'repo', '100', 'owner', 'repo',
                    'refs/heads/branch-1', 'main', 'snapshot-initial',
                    'snapshot-digest-initial', 'run-initial-gate', ?)
                """, AT);
        jdbc.update("""
                INSERT INTO flow_user_gate (
                    gate_id, task_id, pr_id, kind, current_revision, created_at
                ) VALUES ('gate-initial', 'task-1', 'pr-1',
                    'INITIAL_PUBLISH', 1, ?)
                """, AT);
        jdbc.update("""
                INSERT INTO flow_user_gate_revision (
                    gate_id, kind, revision, subject_manifest_ref,
                    subject_digest, action_manifest_ref, action_digest,
                    readiness_evidence_ref, created_by_run_id, created_at
                ) VALUES ('gate-initial', 'INITIAL_PUBLISH', 1,
                    'initial-subject', 'initial-subject-digest',
                    'initial-action', 'initial-action-digest', NULL,
                    'run-initial-gate', ?)
                """, AT);
        jdbc.update("""
                INSERT INTO flow_user_gate_transition (
                    gate_id, gate_revision, sequence, from_state, to_state,
                    actor_type, actor_id, reason_code, detail_ref, recorded_at
                ) VALUES ('gate-initial', 1, 1, NULL, 'OPEN', 'PROGRAM',
                    'USER_GATES', 'READY', NULL, ?)
                """, AT);
        jdbc.update("""
                INSERT INTO flow_github_effect_plan_envelope (
                    plan_id, operation_id, authorization_id, pr_id,
                    pr_sequence, kind, action_ref, action_digest,
                    plan_digest, created_at
                ) VALUES ('plan-initial', 'operation-initial',
                    'authorization-initial', 'pr-1', 2, 'INITIAL_PUBLISH',
                    'initial-action', 'initial-action-digest',
                    'plan-initial-digest', ?)
                """, AT);
        jdbc.update("""
                INSERT INTO flow_github_effect_receipt_envelope (
                    receipt_id, operation_id, plan_id, kind, proposed_head,
                    receipt_digest, recorded_at
                ) VALUES ('receipt-initial', 'operation-initial',
                    'plan-initial', 'INITIAL_PUBLISH', 'H_INITIAL',
                    'receipt-initial-digest', ?)
                """, AT);
        jdbc.update("""
                INSERT INTO flow_github_initial_publish_partial_receipt (
                    partial_receipt_id, operation_id, plan_id, kind,
                    reason_code, attention_detail, branch_receipt_id,
                    branch_step_ordinal,
                    branch_step_kind, base_preflight_id,
                    base_preflight_step_id, base_preflight_claim_generation,
                    base_preflight_claim_token_digest, base_preflight_digest,
                    proposed_head, expected_base_sha, observed_base_sha,
                    partial_digest, recorded_at
                ) VALUES ('partial-initial', 'operation-partial',
                    'plan-partial', 'BRANCH_ONLY_BASE_DRIFT',
                    'REMOTE_BASE_DRIFT', 'REMOTE_BASE_DRIFT',
                    'branch-receipt-partial', 1,
                    'CREATE_REF_EXACT', 'preflight-partial', 'step-partial',
                    1, 'token-partial', 'preflight-digest-partial',
                    'H_PARTIAL', 'B1', 'B2', 'partial-digest', ?)
                """, AT);

        var page = projection.page("pr-1", null, 100);
        assertThat(page.events()).filteredOn(event ->
                event.ownerRef().ownerId().equals("gate-initial"))
                .singleElement().satisfies(event -> {
                    assertThat(event.kind()).isEqualTo(EventKind.GATE_TRANSITION);
                    assertThat(event.headSha()).isEqualTo("H_INITIAL");
                });
        assertThat(page.events()).filteredOn(event ->
                event.ownerRef().ownerId().equals("receipt-initial"))
                .singleElement().satisfies(event -> {
                    assertThat(event.kind())
                            .isEqualTo(EventKind.EXTERNAL_EFFECT_RECEIPT);
                    assertThat(event.status()).isEqualTo(
                            PrTimelineProjection.EventStatus.EFFECT_APPLIED);
                });
        assertThat(page.events()).extracting(TimelineEvent::eventId)
                .noneMatch(id -> id.contains("partial-initial"));
        assertThat(page.events()).filteredOn(event ->
                        event.recordedAt().equals(Instant.ofEpochMilli(AT)))
                .isSortedAccordingTo((left, right) -> {
                    int rank = Integer.compare(left.typeRank(), right.typeRank());
                    return rank != 0 ? rank
                            : left.eventId().compareTo(right.eventId());
                });
        TimelineCursor v1 = new TimelineCursor(
                "pr-1", 1, page.eventCount(), page.events().getFirst().recordedAt(),
                page.events().getFirst().typeRank(),
                page.events().getFirst().eventId());
        assertThat(projection.page("pr-1", v1, 10).status())
                .isEqualTo(PageStatus.RESTART_REQUIRED);
    }

    @Test
    void oneSelectIsReadOnlyRestartSafeAndIndependentOfTheWorktree()
            throws Exception
    {
        Path worktree = temporaryDirectory.resolve("discarded-worktree");
        Files.createDirectories(worktree);
        jdbc.update("UPDATE flow_runtime_task SET worktree_path = ?",
                worktree.toString());
        Files.delete(worktree);
        jdbc.update("UPDATE flow_runtime_agent_session SET state = 'CLOSED', "
                + "close_reason = 'TEST', updated_at = ?", AT + 1);
        long before = retainedRowCount();

        DataSource counting = mock(DataSource.class);
        Connection connection = spy(dataSource.getConnection());
        when(counting.getConnection()).thenReturn(connection);
        var restarted = new PrTimelineProjection(counting);
        var page = restarted.page("pr-1", null, 100);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection, times(1)).prepareStatement(sql.capture());
        assertThat(sql.getValue().stripLeading()).startsWith("WITH pr_owner AS");
        assertThat(sql.getValue().toUpperCase(Locale.ROOT))
                .doesNotContain(" INSERT ", " UPDATE ", " DELETE ");
        assertThat(page.events()).hasSize(12);
        assertThat(retainedRowCount()).isEqualTo(before);
        assertThatThrownBy(() -> projection.page("missing-pr", null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown greenfield PR");
        assertThatThrownBy(() -> projection.page("pr-1", null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> projection.page("pr-1", null, 101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private long retainedRowCount()
    {
        return jdbc.queryForObject("""
                SELECT
                    (SELECT COUNT(*) FROM flow_runtime_task_lifecycle_revision)
                  + (SELECT COUNT(*) FROM flow_runtime_task_base_revision)
                  + (SELECT COUNT(*) FROM flow_runtime_change_set_revision)
                  + (SELECT COUNT(*) FROM flow_runtime_pr)
                  + (SELECT COUNT(*) FROM flow_runtime_remote_identity)
                  + (SELECT COUNT(*) FROM flow_runtime_local_check_run)
                  + (SELECT COUNT(*) FROM flow_runtime_agent_result)
                  + (SELECT COUNT(*) FROM flow_user_gate_ci_consent_revision)
                  + (SELECT COUNT(*) FROM flow_user_gate_transition)
                  + (SELECT COUNT(*) FROM flow_github_external_effect_receipt)
                  + (SELECT COUNT(*) FROM flow_ci_check_observation)
                  + (SELECT COUNT(*) FROM flow_ci_lesson)
                """, Long.class);
    }

    private void insertTwelveFacts()
    {
        jdbc.update("""
                INSERT INTO flow_runtime_task (
                    task_id, request_key, repository_id, repository_owner,
                    repository_name, goal_text, repository_root,
                    git_common_dir, remote_name, base_ref, launch_digest,
                    status, branch_name, worktree_path
                ) VALUES ('task-1', 'request-1', 'repo-1', 'owner', 'repo',
                    'private goal', '/repo', '/repo/.git', 'origin', 'main',
                    'launch-1', 'ACTIVE', 'branch-1', '/private/worktree')
                """);
        jdbc.update("""
                INSERT INTO flow_runtime_agent_session (
                    session_id, task_id, role, state, created_at, updated_at
                ) VALUES ('session-1', 'task-1', 'CI_LEARNER', 'IDLE', ?, ?)
                """, AT, AT);
        jdbc.update("""
                INSERT INTO flow_runtime_agent_run (
                    run_id, operation_id, session_id, role, head_sha,
                    prompt_manifest_ref, capability_set_ref, input_ref,
                    state, created_at, completed_at
                ) VALUES ('run-1', 'operation-agent', 'session-1', 'CI_LEARNER',
                    'H1', 'private prompt', 'private capabilities',
                    'private input', 'FAILED', ?, ?)
                """, AT, AT);
        jdbc.update("""
                INSERT INTO flow_runtime_agent_result (
                    result_id, run_id, terminal_outcome, final_content,
                    error_ref, stop_proof_ref, stored_at
                ) VALUES ('result-1', 'run-1', 'FAILED',
                    'opaque private result', 'FIXED_ERROR', 'stop-proof', ?)
                """, AT);
        jdbc.update("""
                INSERT INTO flow_runtime_task_lifecycle_revision (
                    lifecycle_revision_id, task_id, sequence, from_status,
                    to_status, reason_code, recorded_at
                ) VALUES ('life-1', 'task-1', 1, NULL, 'ACTIVE', 'START', ?)
                """, AT);
        jdbc.update("""
                INSERT INTO flow_runtime_task_base_revision (
                    base_revision_id, task_id, sequence, previous_base_sha,
                    base_sha, reason_code, evidence_ref, source_operation_id,
                    recorded_at
                ) VALUES ('base-1', 'task-1', 1, NULL, 'B1', 'INITIAL',
                    'private base evidence', 'operation-base', ?)
                """, AT);
        jdbc.update("""
                INSERT INTO flow_runtime_change_set_revision (
                    change_set_revision_id, task_id, sequence,
                    previous_change_set_revision_id, previous_head_sha,
                    head_sha, base_revision_id, base_sha, head_tree_digest,
                    diff_digest, differs_from_base, source, source_run_id,
                    source_operation_id, adopted_at
                ) VALUES ('change-1', 'task-1', 1, NULL, 'B1', 'H1',
                    'base-1', 'B1', 'tree-digest', 'diff-digest', 1,
                    'TASK_AGENT', 'run-1', 'operation-change', ?)
                """, AT);
        jdbc.update("""
                INSERT INTO flow_runtime_remote_identity (
                    remote_identity_id, provider, repository_external_id,
                    repository_owner, repository_name,
                    head_repository_external_id, head_repository_owner,
                    head_repository_name, pr_number, pr_node_id, html_url,
                    publication_receipt_id, bound_at
                ) VALUES ('remote-1', 'GITHUB', '100', 'owner', 'repo',
                    '100', 'owner', 'repo', 7, 'node-7',
                    'https://github.com/owner/repo/pull/7', 'receipt-1', ?)
                """, AT);
        jdbc.update("""
                INSERT INTO flow_runtime_pr (
                    pr_id, task_id, repository_id, base_ref, base_sha,
                    target_base_ref, scope_key, branch_name,
                    created_from_change_set_revision_id,
                    created_from_head_sha, remote_identity_id,
                    current_remote_head, created_at
                ) VALUES ('pr-1', 'task-1', 'repo-1', 'main', 'B1', 'main',
                    'scope-1', 'branch-1', 'change-1', 'H1', 'remote-1',
                    'H1', ?)
                """, AT);
        jdbc.update("""
                INSERT INTO flow_runtime_local_check_run (
                    check_run_id, task_id, change_set_revision_id,
                    policy_revision_id, profile_id, operation_id, agent_run_id,
                    command_json, working_directory,
                    attempt_sequence, observed_start_head, observed_end_head,
                    started_at, completed_at, conclusion, exit_code, output_ref,
                    output_text, output_truncated, tracked_tree_clean_before,
                    tracked_tree_clean_after
                ) VALUES ('check-1', 'task-1', 'change-1', 'local-policy-1',
                    'profile-1', 'operation-check', 'run-1',
                    '["true"]', '.', 1, 'H1', 'HX',
                    ?, ?, 'PASSED', 0, 'private-output-ref',
                    'private check output', 0, 1, 1)
                """, AT, AT);
        jdbc.update("""
                INSERT INTO flow_user_gate_ci_consent_revision (
                    consent_id, revision, task_id, pr_id, repository_id,
                    remote_identity_id, provider,
                    head_repository_external_id, head_repository_owner,
                    head_repository_name, branch_name, branch_ref, enabled,
                    expires_at, actor_id, idempotency_key, revision_digest,
                    recorded_at
                ) VALUES ('consent-1', 1, 'task-1', 'pr-1', 'repo-1',
                    'remote-1', 'GITHUB', '100', 'owner', 'repo', 'branch-1',
                    'refs/heads/branch-1', 1, ?, 'LOCAL_DESKTOP_USER',
                    'private-idempotency-key', 'consent-digest', ?)
                """, AT + 86_400_000, AT);
        jdbc.update("""
                INSERT INTO flow_user_gate (
                    gate_id, task_id, pr_id, kind, current_revision, created_at
                ) VALUES ('gate-1', 'task-1', 'pr-1', 'CI_UPDATE', 1, ?)
                """, AT);
        insertGateSubject("gate-subject-1", "H1", "run-gate-1");
        insertGateRevision(1, "gate-subject-1", "run-gate-1");
        jdbc.update("""
                INSERT INTO flow_user_gate_transition (
                    gate_id, gate_revision, sequence, from_state, to_state,
                    actor_type, actor_id, reason_code, detail_ref, recorded_at
                ) VALUES ('gate-1', 1, 1, NULL, 'OPEN', 'PROGRAM',
                    'USER_GATES', 'READY', NULL, ?)
                """, AT);
        jdbc.update("""
                INSERT INTO flow_github_effect_plan_envelope (
                    plan_id, operation_id, authorization_id, pr_id,
                    pr_sequence, kind, action_ref, action_digest,
                    plan_digest, created_at
                ) VALUES ('plan-1', 'operation-publish', 'authorization-1',
                    'pr-1', 1, 'CI_UPDATE', 'action-1', 'action-digest',
                    'plan-digest', ?)
                """, AT);
        jdbc.update("""
                INSERT INTO flow_github_external_effect_plan (
                    plan_id, operation_id, authorization_id, pr_id, pr_sequence,
                    kind, head_repository_external_id, head_repository_owner,
                    head_repository_name, expected_remote_head, action_ref,
                    action_digest, required_ci_policy_revision_id, plan_digest,
                    created_at
                ) VALUES ('plan-1', 'operation-publish', 'authorization-1',
                    'pr-1', 1, 'CI_UPDATE', '100', 'owner', 'repo', 'H0',
                    'action-1', 'action-digest', 'ci-policy-1', 'plan-digest', ?)
                """, AT);
        jdbc.update("""
                INSERT INTO flow_github_effect_receipt_envelope (
                    receipt_id, operation_id, plan_id, kind, proposed_head,
                    receipt_digest, recorded_at
                ) VALUES ('receipt-1', 'operation-publish', 'plan-1',
                    'CI_UPDATE', 'H1', 'receipt-digest', ?)
                """, AT);
        jdbc.update("""
                INSERT INTO flow_github_external_effect_receipt (
                    receipt_id, operation_id, plan_id, step_id, attempt_id,
                    probe_id, probe_outcome, observed_head,
                    head_repository_external_id, head_repository_owner,
                    head_repository_name, branch_ref, expected_remote_head,
                    proposed_head, receipt_digest, recorded_at
                ) VALUES ('receipt-1', 'operation-publish', 'plan-1', 'step-1',
                    NULL, 'probe-1', 'APPLIED', 'H1', '100', 'owner', 'repo',
                    'refs/heads/branch-1', 'H0', 'H1', 'receipt-digest', ?)
                """, AT);
        jdbc.update("""
                INSERT INTO flow_ci_check_observation (
                    observation_id, pr_id, source_operation_id,
                    source_receipt_id, head_sha, selector_key,
                    provider_check_id, provider_run_id, attempt,
                    provider_state_revision, name, status, conclusion,
                    started_at, completed_at, observed_at, raw_evidence_ref
                ) VALUES ('observation-1', 'pr-1', 'operation-observe',
                    'receipt-1', 'H1', 'GITHUB_CHECK:7:build', 'check-provider',
                    'run-provider', 1, 'provider-revision', 'private check name',
                    'COMPLETED', 'SUCCESS', ?, ?, ?, 'private-raw-evidence')
                """, AT - 10_000, AT - 5_000, AT);
        insertLearningSubject();
        jdbc.update("""
                INSERT INTO flow_ci_lesson (
                    lesson_id, repository_id, learning_operation_id, run_id,
                    subject_id, status, title, markdown, content_digest,
                    created_at
                ) VALUES ('lesson-1', 'repo-1', 'operation-learning', 'run-1',
                    'learning-subject-1', 'CANDIDATE', 'private lesson title',
                    'private lesson markdown', 'lesson-digest', ?)
                """, AT);
    }

    private void insertGateRevision(
            int revision, String subjectId, String runId)
    {
        jdbc.update("""
                INSERT INTO flow_user_gate_revision (
                    gate_id, kind, revision, subject_manifest_ref, subject_digest,
                    action_manifest_ref, action_digest, readiness_evidence_ref,
                    created_by_run_id, created_at
                ) VALUES ('gate-1', 'CI_UPDATE', ?, ?, ?, 'action-1', 'action-digest',
                    'ready-1', ?, ?)
                """, revision, subjectId, subjectId + "-digest", runId, AT);
    }

    private void insertGateSubject(String id, String head, String runId)
    {
        jdbc.update("""
                INSERT INTO flow_user_gate_subject (
                    subject_id, subject_digest, task_id, pr_id, repository_id,
                    head_repository_external_id, head_repository_owner,
                    head_repository_name, branch_ref, expected_remote_head,
                    change_set_revision_id, base_revision_id, base_sha,
                    proposed_head, head_tree_digest, diff_digest,
                    local_check_policy_revision_id, reviewer_request_id,
                    reviewer_run_id, reviewer_result_id,
                    origin_ci_fix_pending_id, origin_ci_fix_source_kind,
                    origin_ci_fix_source_id, ci_round_id,
                    required_ci_policy_revision_id, ci_evidence_revision,
                    repair_attempt_id, repair_result_id, cleanup_id,
                    cleanup_result_id, local_review_owner_present,
                    local_review_binding_id, local_review_batch_refs_json,
                    local_review_revision_refs_json, local_review_digest,
                    ci_memory_refs_json, manual_only, created_by_run_id,
                    created_at
                ) VALUES (?, ?, 'task-1', 'pr-1', 'repo-1', '100', 'owner',
                    'repo', 'refs/heads/branch-1', 'H0', 'change-1', 'base-1',
                    'B1', ?, 'tree-digest', 'diff-digest', 'local-policy-1',
                    'review-request-1', 'review-run-1', 'review-result-1',
                    'pending-1', 'REPAIR_ATTEMPT', 'repair-1', 'red-round-1',
                    'ci-policy-1', 0, 'repair-1', 'result-1', NULL, NULL,
                    0, NULL, '[]', '[]', 'local-review-digest', '[]', 0, ?, ?)
                """, id, id + "-digest", head, runId, AT);
    }

    private void insertLearningSubject()
    {
        jdbc.update("""
                INSERT INTO flow_ci_learning_subject (
                    subject_id, operation_id, task_id, pr_id, repository_id,
                    receipt_id, receipt_digest, publication_operation_id,
                    head_repository_external_id, head_repository_owner,
                    head_repository_name, branch_ref, expected_remote_head,
                    plan_id, plan_digest, authorization_id, gate_id,
                    gate_revision, gate_subject_digest, gate_action_digest,
                    publication_policy_revision_id, published_head,
                    green_round_id, green_policy_revision_id,
                    green_evidence_revision, green_observation_operation_id,
                    red_round_id, repair_attempt_id, repair_result_id,
                    repair_result_digest, cleanup_id, cleanup_result_id,
                    cleanup_result_digest, output_change_set_revision_id,
                    output_diff_digest, subject_digest, created_at
                ) VALUES (
                    'learning-subject-1', 'operation-learning', 'task-1',
                    'pr-1', 'repo-1', 'receipt-1', 'receipt-digest',
                    'operation-publish', '100', 'owner', 'repo',
                    'refs/heads/branch-1', 'H0', 'plan-1', 'plan-digest',
                    'authorization-1', 'gate-1', 1, 'gate-subject-digest',
                    'gate-action-digest', 'ci-policy-1', 'H1', 'green-round-1',
                    'green-policy-1', 0, 'operation-observe', 'red-round-1',
                    'repair-1', 'result-1', 'repair-result-digest',
                    NULL, NULL, NULL, 'change-1', 'diff-digest',
                    'learning-subject-digest', ?)
                """, AT);
    }

    private void insertLifecycle(String id, int sequence, long recordedAt)
    {
        jdbc.update("""
                INSERT INTO flow_runtime_task_lifecycle_revision (
                    lifecycle_revision_id, task_id, sequence, from_status,
                    to_status, reason_code, recorded_at
                ) VALUES (?, 'task-1', ?, 'ACTIVE', 'ACTIVE', 'REPLAY', ?)
                """, id, sequence, recordedAt);
    }
}

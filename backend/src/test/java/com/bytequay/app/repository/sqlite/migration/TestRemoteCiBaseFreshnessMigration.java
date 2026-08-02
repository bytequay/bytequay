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

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;

import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.acceptSnapshot;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.connect;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.execute;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertCiPolicy;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertFailedCi;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertGreenCi;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertRemoteOwner;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertSnapshot;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.migrate;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedPublishedRemoteTask;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedWorkspaceAndTrunk;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestRemoteCiBaseFreshnessMigration
{
    @TempDir
    private Path tempDir;

    @Test
    void populatedDispatchRowsAndInboundForeignKeysSurviveV319Rebuild()
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("v319-forward.db")
                + "?foreign_keys=ON";
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        Flyway.configure().dataSource(dataSource).target("318").load().migrate();
        try (Connection connection = connect(url)) {
            seedWorkspaceAndTrunk(connection);
            seedPublishedRemoteTask(connection, 1);
            insertRemoteOwner(connection, 1);
            insertCiPolicy(connection, 1);
            insertSnapshot(
                    connection, 1, 1, "head-1", "base-1", "OPEN",
                    "MERGEABLE");
            acceptSnapshot(connection, 1, 1, "head-1", "base-1");
            execute(connection, """
                    INSERT INTO task_branch_sync_policy_revision(
                        id, task_id, revision, enabled, schedule, source,
                        attempt_limit, command_id, actor, created_at_ms)
                    VALUES ('branch-policy', 'task-1', 1, 1, 'nightly',
                        'USER_CONFIGURED', 8, 'branch-policy-command',
                        'user', 80)
                    """);
            execute(connection, """
                    INSERT INTO branch_sync_episode(
                        id, remote_development_stage_id, task_id, task_epoch,
                        stage_generation, remote_pr_binding_id,
                        source_snapshot_id, old_head_sha, observed_base_sha,
                        target_base_sha, branch_sync_policy_revision_id,
                        policy_source, status, attempt_limit, opened_at_ms)
                    VALUES ('branch-episode', 'remote-stage-1', 'task-1', 1,
                        1, 'binding-1', 'snapshot-1-1', 'head-1', 'base-1',
                        'base-2', 'branch-policy', 'USER_CONFIGURED', 'OPEN',
                        8, 81)
                    """);
            execute(connection, """
                    INSERT INTO branch_sync_effect_step(
                        id, branch_sync_episode_id, ordinal, kind,
                        idempotency_key, status, attempt_limit)
                    VALUES ('branch-step', 'branch-episode', 1,
                        'FETCH_COMPARE', 'branch-effect-key', 'REQUESTED', 8)
                    """);
            execute(connection, """
                    INSERT INTO branch_sync_dispatch_operation(
                        id, branch_sync_episode_id, branch_sync_effect_step_id,
                        remote_development_stage_id, task_id, task_epoch,
                        stage_generation, kind, operation_id, semantic_attempt,
                        expected_code_fingerprint, expected_head_sha,
                        expected_base_sha, target_base_sha, status,
                        requested_at_ms)
                    SELECT 'branch-dispatch', 'branch-episode', 'branch-step',
                        'remote-stage-1', 'task-1', 1, 1, 'FETCH_COMPARE',
                        'branch-operation', 1, code_fingerprint, head_sha,
                        base_sha, 'base-2', 'REQUESTED', 82
                    FROM task_current_code_subject_v230
                    WHERE task_id = 'task-1'
                    """);
            execute(connection, """
                    CREATE TABLE populated_branch_dispatch_inbound (
                        id TEXT PRIMARY KEY,
                        dispatch_id TEXT NOT NULL
                            REFERENCES branch_sync_dispatch_operation(id))
                    """);
            execute(connection, """
                    INSERT INTO populated_branch_dispatch_inbound
                    VALUES ('inbound-1', 'branch-dispatch')
                    """);
        }

        Flyway.configure().dataSource(dataSource).load().migrate();

        try (Connection connection = connect(url)) {
            assertThat(DevelopmentFlowRemoteProtocolFixture.number(
                    connection, """
                            SELECT COUNT(*)
                            FROM branch_sync_dispatch_operation
                            WHERE id = 'branch-dispatch'
                            """)).isOne();
            assertThat(DevelopmentFlowRemoteProtocolFixture.text(
                    connection, """
                            SELECT \"table\"
                            FROM pragma_foreign_key_list(
                                'populated_branch_dispatch_inbound')
                            """)).isEqualTo("branch_sync_dispatch_operation");
            assertThat(DevelopmentFlowRemoteProtocolFixture.number(
                    connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"))
                    .isZero();
            assertThat(DevelopmentFlowRemoteProtocolFixture.number(
                    connection, """
                            SELECT COUNT(*)
                            FROM pragma_index_list(
                                'branch_sync_dispatch_operation') index_list
                            WHERE index_list.\"unique\" = 1
                              AND (SELECT COUNT(*)
                                   FROM pragma_index_info(index_list.name)) = 2
                              AND EXISTS (
                                  SELECT 1
                                  FROM pragma_index_info(index_list.name) info
                                  WHERE info.seqno = 0
                                    AND info.name =
                                        'branch_sync_effect_step_id')
                              AND EXISTS (
                                  SELECT 1
                                  FROM pragma_index_info(index_list.name) info
                                  WHERE info.seqno = 1
                                    AND info.name = 'semantic_attempt')
                            """)).isOne();
        }
    }

    @Test
    void continuationRequiresADistinctLaterAcceptedObservation()
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("ci-freshness.db");
        migrate(url);
        try (Connection connection = connect(url)) {
            seedWorkspaceAndTrunk(connection);
            seedPublishedRemoteTask(connection, 1);
            insertRemoteOwner(connection, 1);
            insertCiPolicy(connection, 1);
            insertSnapshot(
                    connection, 1, 1, "head-1", "base-1", "OPEN",
                    "MERGEABLE");
            insertFailedCi(connection, 1, 1, "head-1", "base-1");
            acceptSnapshot(connection, 1, 1, "head-1", "base-1");
            insertValidatingEpisodeAndDue(connection);

            assertThatThrownBy(() -> execute(connection, freshnessProof(
                    "snapshot-1-1", 1, "ci-evaluation-1-1")))
                    .hasMessageContaining(
                            "CI repair Turn lacks fresh exact Remote proof");

            insertSnapshot(
                    connection, 1, 2, "head-1", "base-1", "OPEN",
                    "MERGEABLE");
            insertGreenCi(connection, 1, 2, "head-1", "base-1");
            acceptSnapshot(connection, 1, 2, "head-1", "base-1");
            assertThatThrownBy(() -> execute(connection, freshnessProof(
                    "snapshot-1-2", 2, "green-ci-1-2")))
                    .hasMessageContaining(
                            "CI repair Turn lacks fresh exact Remote proof");

            insertSnapshot(
                    connection, 1, 3, "head-1", "base-1", "OPEN",
                    "MERGEABLE");
            insertFailedCi(connection, 1, 3, "head-1", "base-1");
            acceptSnapshot(connection, 1, 3, "head-1", "base-1");
            execute(connection, freshnessProof(
                    "snapshot-1-3", 3, "ci-evaluation-1-3"));

            assertThat(DevelopmentFlowRemoteProtocolFixture.number(
                    connection, """
                            SELECT accepted_observation_revision
                            FROM ci_repair_turn_freshness_v319
                            WHERE intent_kind = 'NEXT_FIX'
                            """)).isEqualTo(3);
        }
    }

    @Test
    void ciStageTurnCannotBypassFreshnessProof()
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("ci-turn-guard.db");
        migrate(url);
        try (Connection connection = connect(url)) {
            seedWorkspaceAndTrunk(connection);
            seedPublishedRemoteTask(connection, 1);
            insertRemoteOwner(connection, 1);
            insertCiPolicy(connection, 1);
            insertSnapshot(
                    connection, 1, 1, "head-1", "base-1", "OPEN",
                    "MERGEABLE");
            insertFailedCi(connection, 1, 1, "head-1", "base-1");
            acceptSnapshot(connection, 1, 1, "head-1", "base-1");
            insertOpenEpisode(connection);
            insertQueuedStageTurn(connection);

            assertThatThrownBy(() -> execute(connection, """
                    INSERT INTO ci_repair_operation(
                        id, ci_repair_episode_id,
                        remote_development_stage_id, task_id, task_epoch,
                        stage_generation, kind, operation_id,
                        semantic_attempt, stage_turn_id,
                        expected_code_fingerprint, expected_head_sha,
                        expected_base_sha, status, requested_at_ms)
                    SELECT 'fix-row', 'episode-1', 'remote-stage-1', 'task-1',
                        1, 1, 'FIX_STAGE_TURN', 'fix-operation', 1,
                        'fix-turn', code_fingerprint, head_sha, base_sha,
                        'REQUESTED', 100
                    FROM task_current_code_subject_v230
                    WHERE task_id = 'task-1'
                    """))
                    .hasMessageContaining(
                            "CI repair StageTurn lacks freshness authorization");
        }
    }

    @Test
    void manualBranchSyncAuthorityWaitsForALaterAcceptedObservation()
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("manual-branch-sync.db");
        migrate(url);
        try (Connection connection = connect(url)) {
            seedWorkspaceAndTrunk(connection);
            seedPublishedRemoteTask(connection, 1);
            insertRemoteOwner(connection, 1);
            insertCiPolicy(connection, 1);
            insertSnapshot(
                    connection, 1, 1, "head-1", "base-1", "OPEN",
                    "MERGEABLE");
            insertFailedCi(connection, 1, 1, "head-1", "base-1");
            acceptSnapshot(connection, 1, 1, "head-1", "base-1");
            insertOpenEpisode(connection);
            execute(connection, """
                    INSERT INTO task_branch_sync_policy_revision(
                        id, task_id, revision, enabled, schedule, source,
                        attempt_limit, command_id, actor, created_at_ms)
                    VALUES ('branch-policy', 'task-1', 1, 0, 'nightly',
                        'USER_CONFIGURED', 8, 'branch-policy-command',
                        'user', 90)
                    """);
            execute(connection, """
                    INSERT INTO task_blocker(
                        id, task_id, stage_id, owner_kind, owner_id,
                        subject_revision, blocker_type, status, payload_json,
                        opened_at_ms)
                    VALUES ('branch-blocker', 'task-1', 'remote-stage-1',
                        'STAGE', 'remote-stage-1', 'snapshot-1-1',
                        'CI_BRANCH_SYNC_REQUIRED', 'OPEN', '{}', 91)
                    """);
            execute(connection, """
                    INSERT INTO remote_observation_operation(
                        id, remote_development_stage_id, task_id, task_epoch,
                        stage_generation, remote_pr_binding_id,
                        ci_policy_revision_id, operation_id, semantic_attempt,
                        expected_head_sha, expected_base_sha, status,
                        requested_at_ms)
                    VALUES ('manual-observation-row', 'remote-stage-1',
                        'task-1', 1, 1, 'binding-1', 'ci-policy-1',
                        'manual-observation-operation', 1, 'head-1', 'base-1',
                        'REQUESTED', 91)
                    """);
            execute(connection, """
                    INSERT INTO ci_branch_sync_manual_authorization_v319(
                        id, blocker_id, ci_repair_episode_id, task_id,
                        stage_id, predecessor_snapshot_id,
                        predecessor_observation_revision, command_id,
                        observation_operation_id, actor, reason, status,
                        claimed_at_ms)
                    VALUES ('manual-authority', 'branch-blocker', 'episode-1',
                        'task-1', 'remote-stage-1', 'snapshot-1-1', 1,
                        'manual-command', 'manual-observation-operation',
                        'user', 'approve base sync', 'CLAIMED', 92)
                    """);

            assertThatThrownBy(() -> execute(
                    connection, manualBranchEpisode("snapshot-1-1")))
                    .hasMessageContaining(
                            "Branch sync lacks exact scheduled or CI authority");

            insertSnapshot(
                    connection, 1, 2, "head-1", "base-1", "OPEN",
                    "MERGEABLE");
            insertFailedCi(connection, 1, 2, "head-1", "base-1");
            acceptSnapshot(connection, 1, 2, "head-1", "base-1");
            execute(connection, manualBranchEpisode("snapshot-1-2"));
            execute(connection, """
                    UPDATE ci_branch_sync_manual_authorization_v319
                    SET status = 'CONSUMED',
                        branch_sync_episode_id = 'manual-branch-episode',
                        consumed_at_ms = 100
                    WHERE id = 'manual-authority'
                    """);

            assertThat(DevelopmentFlowRemoteProtocolFixture.text(
                    connection, """
                            SELECT status
                            FROM ci_branch_sync_manual_authorization_v319
                            WHERE id = 'manual-authority'
                            """)).isEqualTo("CONSUMED");
        }
    }

    @Test
    void localPreconditionCannotSucceedFromAnUnrelatedCodeSubject()
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("local-proof.db");
        migrate(url);
        try (Connection connection = connect(url)) {
            seedWorkspaceAndTrunk(connection);
            seedPublishedRemoteTask(connection, 1);
            insertRemoteOwner(connection, 1);
            insertCiPolicy(connection, 1);
            insertSnapshot(
                    connection, 1, 1, "head-1", "base-2", "OPEN",
                    "MERGEABLE");
            insertFailedCi(connection, 1, 1, "head-1", "base-2");
            acceptSnapshot(connection, 1, 1, "head-1", "base-2");
            execute(connection, """
                    INSERT INTO ci_repair_episode(
                        id, remote_development_stage_id, task_id, task_epoch,
                        stage_generation, remote_pr_binding_id,
                        failed_ci_evaluation_id, subject_head_sha,
                        subject_base_sha, classification, status,
                        rerun_limit, fix_attempt_count, fix_attempt_limit,
                        delivery_retry_limit, push_limit, opened_at_ms)
                    VALUES ('episode-1', 'remote-stage-1', 'task-1', 1, 1,
                        'binding-1', 'ci-evaluation-1-1', 'head-1', 'base-2',
                        'TASK_DETERMINISTIC', 'VALIDATING', 0, 1, 2, 2, 2, 80)
                    """);
            execute(connection, """
                    INSERT INTO ci_repair_next_fix_due_v318(
                        id, ci_repair_episode_id, source_kind,
                        source_semantic_attempt, requested_semantic_attempt,
                        predecessor_accepted_snapshot_id,
                        predecessor_accepted_observation_revision,
                        prompt, status, recorded_at_ms)
                    VALUES ('next-due', 'episode-1', 'VALIDATION_FAILED', 1, 2,
                        'snapshot-1-1', 1, 'fix validation', 'PENDING', 90)
                    """);
            execute(connection, """
                    INSERT INTO task_branch_sync_policy_revision(
                        id, task_id, revision, enabled, schedule, source,
                        attempt_limit, command_id, actor, created_at_ms)
                    VALUES ('branch-policy', 'task-1', 1, 0, 'nightly',
                        'USER_CONFIGURED', 8, 'branch-command', 'user', 91)
                    """);
            execute(connection, """
                    INSERT INTO task_automation_policy(
                        id, task_id, revision, source, auto_approve,
                        auto_merge, keep_draft, minimum_write_approvals,
                        max_merge_queue_reenqueues, require_low_risk,
                        require_small_effort, stewardship_exception,
                        created_by, created_at_ms)
                    VALUES ('auto-policy', 'task-1', 1, 'TEST', 1, 0, 0, 0,
                        0, 0, 0, 0, 'test', 92)
                    """);
            execute(connection, """
                    INSERT INTO branch_sync_episode(
                        id, remote_development_stage_id, task_id, task_epoch,
                        stage_generation, remote_pr_binding_id,
                        source_snapshot_id, old_head_sha, observed_base_sha,
                        target_base_sha, branch_sync_policy_revision_id,
                        policy_source, purpose, authority_kind, authority_id,
                        ci_repair_episode_id, ci_turn_intent_kind,
                        ci_turn_intent_id, source_code_fingerprint,
                        source_code_head_sha, source_code_base_sha, status,
                        attempt_limit, opened_at_ms)
                    VALUES ('local-branch', 'remote-stage-1', 'task-1', 1, 1,
                        'binding-1', 'snapshot-1-1', 'head-1', 'base-2',
                        'base-2', 'branch-policy', 'CI_PRECONDITION',
                        'CI_PRECONDITION_LOCAL', 'AUTO_APPROVE_POLICY',
                        'auto-policy', 'episode-1', 'NEXT_FIX', 'next-due',
                        'fingerprint-1', 'head-1', 'base-1',
                        'REBASING', 8, 93)
                    """);
            insertCompletedLocalBranchSteps(connection);
            insertRemoteCodeSubject(
                    connection, "unrelated-result", "local-fingerprint",
                    "local-head", "base-1", "rebased-fingerprint",
                    "rebased-head", "base-2", 110);

            assertThatThrownBy(() -> execute(connection, """
                    UPDATE branch_sync_episode
                    SET status = 'SUCCEEDED',
                        result_code_fingerprint = 'rebased-fingerprint',
                        result_head_sha = 'rebased-head',
                        result_snapshot_id = source_snapshot_id,
                        completed_at_ms = 111
                    WHERE id = 'local-branch'
                    """))
                    .hasMessageContaining(
                            "Branch sync success lacks complete ordered effect proof");
        }
    }

    @Test
    void prepublishLineageSurvivesLaterRepairTurnsAndFencesThePush()
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("prepublish-lineage.db");
        migrate(url);
        try (Connection connection = connect(url)) {
            seedWorkspaceAndTrunk(connection);
            seedPublishedRemoteTask(connection, 1);
            insertRemoteOwner(connection, 1);
            insertCiPolicy(connection, 1);
            insertSnapshot(
                    connection, 1, 1, "head-1", "base-2", "OPEN",
                    "MERGEABLE");
            insertFailedCi(connection, 1, 1, "head-1", "base-2");
            acceptSnapshot(connection, 1, 1, "head-1", "base-2");
            insertLocalPreconditionRepairOwners(connection);
            insertLocalPreconditionBranch(connection);
            insertSuccessfulLocalBranchSteps(connection);
            execute(connection, """
                    UPDATE branch_sync_episode
                    SET status = 'SUCCEEDED',
                        result_code_fingerprint = 'rebased-fingerprint',
                        result_head_sha = 'rebased-head',
                        result_snapshot_id = source_snapshot_id,
                        completed_at_ms = 111
                    WHERE id = 'local-branch'
                    """);

            insertSnapshot(
                    connection, 1, 2, "head-1", "base-2", "OPEN",
                    "MERGEABLE");
            insertFailedCi(connection, 1, 2, "head-1", "base-2");
            acceptSnapshot(connection, 1, 2, "head-1", "base-2");
            execute(connection, localFreshnessProof(
                    "fresh-proof-2", "next-due", 2, "snapshot-1-1", 1,
                    "snapshot-1-2", 2, "ci-evaluation-1-2",
                    "local-branch"));

            insertSuccessfulCiRepairSubject(
                    connection, "repair-one", 2, "rebased-fingerprint",
                    "rebased-head", "base-2", "repair-one-fingerprint",
                    "repair-one-head", "base-2", 120);
            assertThatThrownBy(() -> execute(
                    connection, ciPush("ambiguous-push", 2, null)))
                    .hasMessageContaining(
                            "CI push lacks exact local-precondition lineage");
            execute(connection, "SAVEPOINT exact_prepublish_push");
            execute(connection, ciPush(
                    "exact-push", 2, "local-branch"));
            assertThat(DevelopmentFlowRemoteProtocolFixture.text(
                    connection, """
                            SELECT prepublish_branch_sync_episode_id
                            FROM ci_repair_operation
                            WHERE id = 'exact-push-row'
                            """)).isEqualTo("local-branch");
            execute(connection, "ROLLBACK TO exact_prepublish_push");
            execute(connection, "RELEASE exact_prepublish_push");
            recordAcceptedChangedTreeReceipt(connection, "repair-one", 2, 120);
            execute(connection, """
                    UPDATE ci_repair_episode SET fix_attempt_count = 2
                    WHERE id = 'episode-1'
                    """);
            execute(connection, """
                    INSERT INTO ci_repair_next_fix_due_v318(
                        id, ci_repair_episode_id, source_kind,
                        source_semantic_attempt, requested_semantic_attempt,
                        predecessor_accepted_snapshot_id,
                        predecessor_accepted_observation_revision,
                        prompt, status, recorded_at_ms)
                    VALUES ('next-due-2', 'episode-1', 'VALIDATION_FAILED', 2,
                        3, 'snapshot-1-2', 2, 'fix again', 'PENDING', 121)
                    """);
            insertSnapshot(
                    connection, 1, 3, "head-1", "base-2", "OPEN",
                    "MERGEABLE");
            insertFailedCi(connection, 1, 3, "head-1", "base-2");
            acceptSnapshot(connection, 1, 3, "head-1", "base-2");
            assertThatThrownBy(() -> execute(connection, localFreshnessProof(
                    "fresh-proof-3", "next-due-2", 3, "snapshot-1-2", 2,
                    "snapshot-1-3", 3, "ci-evaluation-1-3", null)))
                    .hasMessageContaining(
                            "CI repair proof dropped prepublish BranchSync lineage");
            execute(connection, localFreshnessProof(
                    "fresh-proof-3", "next-due-2", 3, "snapshot-1-2", 2,
                    "snapshot-1-3", 3, "ci-evaluation-1-3",
                    "local-branch"));

            assertThat(DevelopmentFlowRemoteProtocolFixture.text(
                    connection, """
                            SELECT prepublish_branch_sync_episode_id
                            FROM ci_repair_turn_freshness_v319
                            WHERE id = 'fresh-proof-3'
                            """)).isEqualTo("local-branch");

            insertSuccessfulCiRepairSubject(
                    connection, "repair-two", 3, "repair-one-fingerprint",
                    "repair-one-head", "base-2", "repair-two-fingerprint",
                    "repair-two-head", "base-2", 130);
            assertThatThrownBy(() -> execute(
                    connection, ciPush("push", 3, null)))
                    .hasMessageContaining(
                            "CI push lacks exact local-precondition lineage");
            execute(connection, ciPush("push", 3, "local-branch"));

            assertThat(DevelopmentFlowRemoteProtocolFixture.text(
                    connection, """
                            SELECT prepublish_branch_sync_episode_id
                            FROM ci_repair_operation
                            WHERE id = 'push-row'
                            """)).isEqualTo("local-branch");
        }
    }

    private static void insertOpenEpisode(Connection connection)
            throws Exception
    {
        execute(connection, """
                INSERT INTO ci_repair_episode(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_binding_id,
                    failed_ci_evaluation_id, subject_head_sha,
                    subject_base_sha, classification, status,
                    rerun_limit, fix_attempt_limit, delivery_retry_limit,
                    push_limit, opened_at_ms)
                VALUES ('episode-1', 'remote-stage-1', 'task-1', 1, 1,
                    'binding-1', 'ci-evaluation-1-1', 'head-1', 'base-1',
                    'TASK_DETERMINISTIC', 'OPEN', 0, 2, 2, 2, 80)
                """);
    }

    private static void insertLocalPreconditionRepairOwners(
            Connection connection)
            throws Exception
    {
        execute(connection, """
                INSERT INTO ci_repair_episode(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_binding_id,
                    failed_ci_evaluation_id, subject_head_sha,
                    subject_base_sha, classification, status,
                    rerun_limit, fix_attempt_count, fix_attempt_limit,
                    delivery_retry_limit, push_limit, opened_at_ms)
                VALUES ('episode-1', 'remote-stage-1', 'task-1', 1, 1,
                    'binding-1', 'ci-evaluation-1-1', 'head-1', 'base-2',
                    'TASK_DETERMINISTIC', 'VALIDATING', 0, 1, 4, 2, 2, 80)
                """);
        execute(connection, """
                INSERT INTO ci_repair_next_fix_due_v318(
                    id, ci_repair_episode_id, source_kind,
                    source_semantic_attempt, requested_semantic_attempt,
                    predecessor_accepted_snapshot_id,
                    predecessor_accepted_observation_revision,
                    prompt, status, recorded_at_ms)
                VALUES ('next-due', 'episode-1', 'VALIDATION_FAILED', 1, 2,
                    'snapshot-1-1', 1, 'fix validation', 'PENDING', 90)
                """);
        execute(connection, """
                INSERT INTO task_branch_sync_policy_revision(
                    id, task_id, revision, enabled, schedule, source,
                    attempt_limit, command_id, actor, created_at_ms)
                VALUES ('branch-policy', 'task-1', 1, 0, 'nightly',
                    'USER_CONFIGURED', 8, 'branch-command', 'user', 91)
                """);
        execute(connection, """
                INSERT INTO task_automation_policy(
                    id, task_id, revision, source, auto_approve,
                    auto_merge, keep_draft, minimum_write_approvals,
                    max_merge_queue_reenqueues, require_low_risk,
                    require_small_effort, stewardship_exception,
                    created_by, created_at_ms)
                VALUES ('auto-policy', 'task-1', 1, 'TEST', 1, 0, 0, 0,
                    0, 0, 0, 0, 'test', 92)
                """);
    }

    private static void insertLocalPreconditionBranch(Connection connection)
            throws Exception
    {
        execute(connection, """
                INSERT INTO branch_sync_episode(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_binding_id,
                    source_snapshot_id, old_head_sha, observed_base_sha,
                    target_base_sha, branch_sync_policy_revision_id,
                    policy_source, purpose, authority_kind, authority_id,
                    ci_repair_episode_id, ci_turn_intent_kind,
                    ci_turn_intent_id, source_code_fingerprint,
                    source_code_head_sha, source_code_base_sha, status,
                    attempt_limit, opened_at_ms)
                VALUES ('local-branch', 'remote-stage-1', 'task-1', 1, 1,
                    'binding-1', 'snapshot-1-1', 'head-1', 'base-2',
                    'base-2', 'branch-policy', 'CI_PRECONDITION',
                    'CI_PRECONDITION_LOCAL', 'AUTO_APPROVE_POLICY',
                    'auto-policy', 'episode-1', 'NEXT_FIX', 'next-due',
                    'fingerprint-1', 'head-1', 'base-1',
                    'REBASING', 8, 93)
                """);
    }

    private static void insertSuccessfulLocalBranchSteps(Connection connection)
            throws Exception
    {
        String[] kinds = {
                "FETCH_COMPARE", "MECHANICAL_REBASE", "CONFLICT_REPAIR",
                "VALIDATE", "BRAIN_REVIEW", "FORCE_WITH_LEASE_PUSH"};
        for (int ordinal = 1; ordinal <= kinds.length; ordinal++) {
            execute(connection, """
                    INSERT INTO branch_sync_effect_step(
                        id, branch_sync_episode_id, ordinal, kind,
                        idempotency_key, status, attempt_limit)
                    VALUES ('local-step-%1$s', 'local-branch', %1$s, '%2$s',
                        'local-step-key-%1$s', 'REQUESTED', 8)
                    """.formatted(ordinal, kinds[ordinal - 1]));
        }
        insertSuccessfulBranchEffect(
                connection, 1, "FETCH_COMPARE", "fingerprint-1", "head-1");
        insertSuccessfulBranchEffect(
                connection, 2, "MECHANICAL_REBASE", "rebased-fingerprint",
                "rebased-head");
        execute(connection, """
                INSERT INTO remote_worktree_subject(
                    id, task_id, task_epoch, remote_development_stage_id,
                    stage_generation, revision, source_kind,
                    source_operation_id, code_fingerprint, head_sha, base_sha,
                    recorded_at_ms)
                VALUES ('rebased-worktree', 'task-1', 1, 'remote-stage-1', 1,
                    1, 'BRANCH_EFFECT', 'local-operation-2',
                    'rebased-fingerprint', 'rebased-head', 'base-2', 105)
                """);
        execute(connection, """
                UPDATE branch_sync_effect_step
                SET status = 'SKIPPED', evidence = 'local precondition',
                    completed_at_ms = 106
                WHERE branch_sync_episode_id = 'local-branch'
                  AND ordinal BETWEEN 3 AND 6
                """);
    }

    private static void insertSuccessfulBranchEffect(
            Connection connection,
            int ordinal,
            String kind,
            String resultFingerprint,
            String resultHead)
            throws Exception
    {
        execute(connection, """
                INSERT INTO branch_sync_dispatch_operation(
                    id, branch_sync_episode_id, branch_sync_effect_step_id,
                    remote_development_stage_id, task_id, task_epoch,
                    stage_generation, kind, operation_id, semantic_attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, target_base_sha, status,
                    requested_at_ms)
                VALUES ('local-operation-row-%1$s', 'local-branch',
                    'local-step-%1$s', 'remote-stage-1', 'task-1', 1, 1,
                    '%2$s', 'local-operation-%1$s', 1, 'fingerprint-1',
                    'head-1', 'base-1', 'base-2', 'REQUESTED', 100)
                """.formatted(ordinal, kind));
        execute(connection, """
                UPDATE branch_sync_effect_step
                SET status = 'CLAIMED', attempt_count = 1,
                    claim_mode = 'EXECUTE',
                    claim_owner = 'local-operation-%1$s', claimed_at_ms = 101,
                    lease_until_ms = 200
                WHERE id = 'local-step-%1$s'
                """.formatted(ordinal));
        execute(connection, """
                UPDATE branch_sync_dispatch_operation
                SET status = 'SUCCEEDED', result_code_fingerprint = '%2$s',
                    result_head_sha = '%3$s', result_evidence = 'test',
                    completed_at_ms = 102
                WHERE id = 'local-operation-row-%1$s'
                """.formatted(ordinal, resultFingerprint, resultHead));
        execute(connection, """
                UPDATE branch_sync_effect_step
                SET status = 'SUCCEEDED', claim_mode = NULL,
                    claim_owner = NULL, claimed_at_ms = NULL,
                    lease_until_ms = NULL, evidence = 'test',
                    completed_at_ms = 103
                WHERE id = 'local-step-%1$s'
                """.formatted(ordinal));
    }

    private static String localFreshnessProof(
            String proofId,
            String intentId,
            int attempt,
            String predecessorSnapshot,
            int predecessorRevision,
            String snapshotId,
            int revision,
            String evaluationId,
            String prepublishBranchId)
    {
        String prepublish = prepublishBranchId == null
                ? "NULL" : "'" + prepublishBranchId + "'";
        return """
                INSERT INTO ci_repair_turn_freshness_v319(
                    id, ci_repair_episode_id, intent_kind, intent_id,
                    semantic_attempt, execution_attempt,
                    predecessor_snapshot_id,
                    predecessor_observation_revision,
                    accepted_snapshot_id, accepted_observation_revision,
                    accepted_ci_evaluation_id, remote_head_sha,
                    authoritative_base_sha, code_fingerprint, code_head_sha,
                    code_base_sha, prepublish_branch_sync_episode_id,
                    authorized_at_ms)
                SELECT '%1$s', 'episode-1', 'NEXT_FIX', '%2$s', %3$s, %3$s,
                    '%4$s', %5$s, '%6$s', %7$s, '%8$s', 'head-1', 'base-2',
                    code_fingerprint, head_sha, base_sha, %9$s, 125
                FROM task_current_code_subject_v230
                WHERE task_id = 'task-1'
                """.formatted(
                proofId, intentId, attempt, predecessorSnapshot,
                predecessorRevision, snapshotId, revision, evaluationId,
                prepublish);
    }

    private static String ciPush(
            String id, int semanticAttempt, String prepublishBranchId)
    {
        String prepublish = prepublishBranchId == null
                ? "NULL" : "'" + prepublishBranchId + "'";
        return """
                INSERT INTO ci_repair_operation(
                    id, ci_repair_episode_id, remote_development_stage_id,
                    task_id, task_epoch, stage_generation, kind, operation_id,
                    semantic_attempt, expected_code_fingerprint,
                    expected_head_sha, expected_base_sha,
                    prepublish_branch_sync_episode_id, lease_expected_sha,
                    status, requested_at_ms)
                SELECT '%1$s-row', 'episode-1', 'remote-stage-1', 'task-1',
                    1, 1, 'PUSH_HEAD', '%1$s-operation', %2$s,
                    code_fingerprint, head_sha, base_sha, %3$s, 'head-1',
                    'REQUESTED', 140
                FROM task_current_code_subject_v230
                WHERE task_id = 'task-1'
                """.formatted(id, semanticAttempt, prepublish);
    }

    private static void insertValidatingEpisodeAndDue(Connection connection)
            throws Exception
    {
        execute(connection, """
                INSERT INTO ci_repair_episode(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_binding_id,
                    failed_ci_evaluation_id, subject_head_sha,
                    subject_base_sha, classification, status,
                    rerun_limit, fix_attempt_count, fix_attempt_limit,
                    delivery_retry_limit, push_limit, opened_at_ms)
                VALUES ('episode-1', 'remote-stage-1', 'task-1', 1, 1,
                    'binding-1', 'ci-evaluation-1-1', 'head-1', 'base-1',
                    'TASK_DETERMINISTIC', 'VALIDATING', 0, 1, 2, 2, 2, 80)
                """);
        execute(connection, """
                INSERT INTO ci_repair_next_fix_due_v318(
                    id, ci_repair_episode_id, source_kind,
                    source_semantic_attempt, requested_semantic_attempt,
                    predecessor_accepted_snapshot_id,
                    predecessor_accepted_observation_revision,
                    prompt, status, recorded_at_ms)
                VALUES ('next-due', 'episode-1', 'VALIDATION_FAILED', 1, 2,
                    'snapshot-1-1', 1, 'fix validation', 'PENDING', 90)
                """);
    }

    private static void insertQueuedStageTurn(Connection connection)
            throws Exception
    {
        execute(connection, """
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status,
                    operation_id, attempt, task_epoch,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                SELECT 'fix-turn', 'remote-stage-1', 1, 'REMOTE_CI_REPAIR',
                    'QUEUED', 'fix-operation', 1, 1, code_fingerprint,
                    head_sha, base_sha, 'CLI', '{}', 100
                FROM task_current_code_subject_v230
                WHERE task_id = 'task-1'
                """);
    }

    private static String freshnessProof(
            String snapshotId, int revision, String evaluationId)
    {
        return """
                INSERT INTO ci_repair_turn_freshness_v319(
                    id, ci_repair_episode_id, intent_kind, intent_id,
                    semantic_attempt, execution_attempt,
                    predecessor_snapshot_id,
                    predecessor_observation_revision,
                    accepted_snapshot_id, accepted_observation_revision,
                    accepted_ci_evaluation_id, remote_head_sha,
                    authoritative_base_sha, code_fingerprint, code_head_sha,
                    code_base_sha, authorized_at_ms)
                SELECT 'fresh-proof', 'episode-1', 'NEXT_FIX', 'next-due',
                    2, 2, 'snapshot-1-1', 1, '%s', %s, '%s',
                    'head-1', 'base-1', code_fingerprint, head_sha, base_sha,
                    100
                FROM task_current_code_subject_v230
                WHERE task_id = 'task-1'
                """.formatted(snapshotId, revision, evaluationId);
    }

    private static String manualBranchEpisode(String snapshotId)
    {
        return """
                INSERT INTO branch_sync_episode(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_binding_id, source_snapshot_id,
                    old_head_sha, observed_base_sha, target_base_sha,
                    branch_sync_policy_revision_id, policy_source, purpose,
                    authority_kind, authority_id, ci_repair_episode_id,
                    source_code_fingerprint, source_code_head_sha,
                    source_code_base_sha, status, attempt_limit, opened_at_ms)
                SELECT 'manual-branch-episode', 'remote-stage-1', 'task-1', 1,
                    1, 'binding-1', '%s', 'head-1', 'base-1', 'base-1',
                    'branch-policy', 'CI_PRECONDITION', 'CI_PRECONDITION',
                    'MANUAL', 'manual-authority', 'episode-1',
                    code_fingerprint, head_sha, base_sha, 'OPEN', 8, 99
                FROM task_current_code_subject_v230 WHERE task_id = 'task-1'
                """.formatted(snapshotId);
    }

    private static void insertRemoteCodeSubject(
            Connection connection,
            String id,
            String sourceFingerprint,
            String sourceHead,
            String sourceBase,
            String resultFingerprint,
            String resultHead,
            String resultBase,
            long at)
            throws Exception
    {
        execute(connection, """
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status,
                    operation_id, attempt, task_epoch,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms, started_at_ms, finished_at_ms)
                VALUES ('%1$s-turn', 'remote-stage-1', 1,
                    'REMOTE_CI_REPAIR', 'SUCCEEDED', '%1$s-operation', 1, 1,
                    '%2$s', '%3$s', '%4$s', 'CLI', '{}', %8$s, %8$s, %8$s)
                """.formatted(
                id, sourceFingerprint, sourceHead, sourceBase,
                resultFingerprint, resultHead, resultBase, at));
        execute(connection, """
                INSERT INTO remote_code_subject(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, stage_turn_id, source_code_fingerprint,
                    source_head_sha, source_base_sha, code_fingerprint,
                    head_sha, base_sha, created_at_ms)
                VALUES ('%1$s-subject', 'remote-stage-1', 'task-1', 1, 1,
                    '%1$s-turn', '%2$s', '%3$s', '%4$s', '%5$s', '%6$s',
                    '%7$s', %8$s)
                """.formatted(
                id, sourceFingerprint, sourceHead, sourceBase,
                resultFingerprint, resultHead, resultBase, at));
    }

    private static void insertSuccessfulCiRepairSubject(
            Connection connection,
            String id,
            int semanticAttempt,
            String sourceFingerprint,
            String sourceHead,
            String sourceBase,
            String resultFingerprint,
            String resultHead,
            String resultBase,
            long at)
            throws Exception
    {
        execute(connection, """
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status,
                    operation_id, attempt, task_epoch,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                VALUES ('%1$s-turn', 'remote-stage-1', 1,
                    'REMOTE_CI_REPAIR', 'QUEUED', '%1$s-operation', %2$s, 1,
                    '%3$s', '%4$s', '%5$s', 'CLI', '{}', %9$s)
                """.formatted(
                id, semanticAttempt, sourceFingerprint, sourceHead,
                sourceBase, resultFingerprint, resultHead, resultBase, at));
        execute(connection, """
                INSERT INTO ci_repair_operation(
                    id, ci_repair_episode_id, remote_development_stage_id,
                    task_id, task_epoch, stage_generation, kind, operation_id,
                    semantic_attempt, stage_turn_id,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, requested_at_ms)
                VALUES ('%1$s-row', 'episode-1', 'remote-stage-1', 'task-1',
                    1, 1, 'FIX_STAGE_TURN', '%1$s-operation', %2$s,
                    '%1$s-turn', '%3$s', '%4$s', '%5$s', 'REQUESTED', %9$s)
                """.formatted(
                id, semanticAttempt, sourceFingerprint, sourceHead,
                sourceBase, resultFingerprint, resultHead, resultBase, at));
        execute(connection, """
                UPDATE stage_turn
                SET status = 'SUCCEEDED', started_at_ms = %9$s,
                    finished_at_ms = %9$s
                WHERE id = '%1$s-turn'
                """.formatted(
                id, semanticAttempt, sourceFingerprint, sourceHead,
                sourceBase, resultFingerprint, resultHead, resultBase, at));
        execute(connection, """
                UPDATE ci_repair_operation
                SET status = 'SUCCEEDED',
                    result_code_fingerprint = '%6$s',
                    result_head_sha = '%7$s', result_evidence = 'test',
                    completed_at_ms = %9$s
                WHERE id = '%1$s-row'
                """.formatted(
                id, semanticAttempt, sourceFingerprint, sourceHead,
                sourceBase, resultFingerprint, resultHead, resultBase, at));
        execute(connection, """
                INSERT INTO remote_code_subject(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, stage_turn_id, source_code_fingerprint,
                    source_head_sha, source_base_sha, code_fingerprint,
                    head_sha, base_sha, created_at_ms)
                VALUES ('%1$s-subject', 'remote-stage-1', 'task-1', 1, 1,
                    '%1$s-turn', '%3$s', '%4$s', '%5$s', '%6$s', '%7$s',
                    '%8$s', %9$s)
                """.formatted(
                id, semanticAttempt, sourceFingerprint, sourceHead,
                sourceBase, resultFingerprint, resultHead, resultBase, at));
        execute(connection, """
                INSERT INTO remote_worktree_subject(
                    id, task_id, task_epoch, remote_development_stage_id,
                    stage_generation, revision, source_kind,
                    source_operation_id, code_fingerprint, head_sha, base_sha,
                    recorded_at_ms)
                SELECT '%1$s-worktree', 'task-1', 1, 'remote-stage-1', 1,
                    COALESCE(MAX(revision), 0) + 1, 'CI_STAGE_TURN',
                    '%1$s-operation', '%6$s', '%7$s', '%8$s', %9$s
                FROM remote_worktree_subject
                WHERE task_id = 'task-1' AND task_epoch = 1
                """.formatted(
                id, semanticAttempt, sourceFingerprint, sourceHead,
                sourceBase, resultFingerprint, resultHead, resultBase, at));
    }

    private static void recordAcceptedChangedTreeReceipt(
            Connection connection, String id, int semanticAttempt, long at)
            throws Exception
    {
        execute(connection, """
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch,
                    stage_id, stage_generation, attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                SELECT '%1$s-ticket', operation.operation_id,
                    'EXECUTE_STAGE_TURN', 'AGENT_TURN', 'STAGE_TURN',
                    operation.stage_turn_id, 'REMOTE_CI_STAGE_TURN_RESULT',
                    1, 0, 1, 1, trunk.workspace_id, task.thread_id,
                    operation.task_id, operation.task_epoch,
                    operation.remote_development_stage_id,
                    operation.stage_generation, operation.semantic_attempt,
                    operation.expected_code_fingerprint,
                    operation.expected_head_sha, operation.expected_base_sha,
                    'REQUESTED', %3$s
                FROM ci_repair_operation operation
                JOIN tasks task ON task.id = operation.task_id
                JOIN threads trunk ON trunk.id = task.thread_id
                WHERE operation.id = '%1$s-row'
                  AND operation.kind = 'FIX_STAGE_TURN'
                  AND operation.semantic_attempt = %2$s
                """.formatted(id, semanticAttempt, at));
        execute(connection, """
                UPDATE dispatch_ticket
                   SET version = version + 1, status = 'RESULT_PENDING',
                       pending_result_outcome = 'SUCCEEDED',
                       pending_result_payload = 'changed tree',
                       pending_result_evidence = 'provider completed',
                       pending_result_task_epoch = 1,
                       pending_result_stage_id = 'remote-stage-1',
                       pending_result_stage_generation = 1,
                       pending_result_operation_id = '%1$s-operation',
                       pending_result_attempt = %2$s
                 WHERE id = '%1$s-ticket'
                """.formatted(id, semanticAttempt));
        execute(connection, """
                INSERT INTO ci_repair_delivery_receipt(
                    ci_repair_operation_id, operation_id, raw_outcome,
                    raw_result_digest, acceptance, recorded_at_ms)
                VALUES ('%1$s-row', '%1$s-operation', 'SUCCEEDED',
                    '%2$s', 'ACCEPTED', %3$s)
                """.formatted(id, "0".repeat(64), at));
        execute(connection, """
                INSERT INTO ci_repair_fix_tree_result_v318(
                    id, ci_repair_episode_id, source_kind,
                    source_operation_row_id, operation_id, stage_turn_id,
                    semantic_attempt, execution_attempt, disposition,
                    source_tree_sha, result_tree_sha, raw_result_digest,
                    recorded_at_ms)
                VALUES ('%1$s-tree', 'episode-1', 'ORIGINAL', '%1$s-row',
                    '%1$s-operation', '%1$s-turn', %2$s, %2$s, 'CHANGED',
                    'tree-before-%1$s', 'tree-after-%1$s', '%3$s', %4$s)
                """.formatted(id, semanticAttempt, "0".repeat(64), at));
    }

    private static void insertCompletedLocalBranchSteps(Connection connection)
            throws Exception
    {
        String[] kinds = {
                "FETCH_COMPARE", "MECHANICAL_REBASE", "CONFLICT_REPAIR",
                "VALIDATE", "BRAIN_REVIEW", "FORCE_WITH_LEASE_PUSH"};
        for (int ordinal = 1; ordinal <= kinds.length; ordinal++) {
            String status = ordinal <= 2 ? "SUCCEEDED" : "SKIPPED";
            int attempt = ordinal <= 2 ? 1 : 0;
            execute(connection, """
                    INSERT INTO branch_sync_effect_step(
                        id, branch_sync_episode_id, ordinal, kind,
                        idempotency_key, status, attempt_count, attempt_limit,
                        evidence, completed_at_ms)
                    VALUES ('local-step-%1$s', 'local-branch', %1$s, '%2$s',
                        'local-step-key-%1$s', '%3$s', %4$s, 8, 'test', 100)
                    """.formatted(ordinal, kinds[ordinal - 1], status, attempt));
        }
    }
}

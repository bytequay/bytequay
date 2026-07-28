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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.Locale;

import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.acceptSnapshot;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.assertFails;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.connect;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.execute;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertCiPolicy;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertFailedCi;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertRemoteOwner;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertSnapshot;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.migrate;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.number;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedLegacyTask;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedPublishedRemoteTask;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedWorkspaceAndTrunk;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.text;
import static org.assertj.core.api.Assertions.assertThat;

class TestDevelopmentFlowRemoteCiProtocolMigration
{
    @TempDir
    private Path tempDir;

    @Test
    void upgradesPopulatedLegacyRowsAndRestartsWithoutBackfill()
            throws Exception
    {
        String url = url("legacy.db");
        migrate(url, "231");
        try (Connection connection = connect(url)) {
            seedLegacyTask(connection);
        }

        migrate(url, "232");
        migrate(url, "232");
        try (Connection connection = connect(url)) {
            assertThat(text(connection, """
                    SELECT workflow_version || '|' || status
                    FROM tasks WHERE id = 'legacy-task'
                    """)).isEqualTo("LEGACY|IDLE");
            assertThat(number(connection, "SELECT COUNT(*) FROM remote_pr_snapshot"))
                    .isZero();
            assertThat(number(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"))
                    .isZero();
        }
    }

    @Test
    void persistsOldHeadHistoryButRejectsItAsTheCurrentCiRepairSubject()
            throws Exception
    {
        String url = remoteUrl("stale-head.db", 1);
        try (Connection connection = connect(url)) {
            insertRemoteOwner(connection, 1);
            insertCiPolicy(connection, 1);
            insertSnapshot(connection, 1, 1, "head-1", "base-1", "OPEN", "UNKNOWN");
            assertFails(connection, """
                    UPDATE remote_development_stage
                    SET accepted_snapshot_id = 'snapshot-1-1',
                        accepted_observation_revision = 1,
                        subject_changed_at_ms = 999
                    WHERE stage_id = 'remote-stage-1'
                    """);
            acceptSnapshot(connection, 1, 1, "head-1", "base-1");
            insertFailedCi(connection, 1, 1, "head-1", "base-1");

            insertSnapshot(connection, 1, 2, "head-new", "base-1", "OPEN", "UNKNOWN");
            acceptSnapshot(connection, 1, 2, "head-new", "base-1");
            insertSnapshot(connection, 1, 3, "head-1", "base-1", "OPEN", "UNKNOWN");
            execute(connection, """
                    INSERT INTO remote_ci_check_snapshot(
                        id, remote_pr_snapshot_id, check_kind, external_check_id,
                        check_name, normalized_state, observed_at_ms)
                    VALUES ('old-check', 'snapshot-1-3', 'CHECK_RUN', 'old-check',
                        'old build', 'PASSED', 80)
                    """);
            execute(connection, """
                    INSERT INTO remote_ci_evaluation(
                        id, remote_development_stage_id, remote_pr_snapshot_id,
                        ci_policy_revision_id, task_id, task_epoch, stage_generation,
                        head_sha, base_sha, normalized_status, policy_outcome,
                        check_count, missing_required_count, evidence, evaluated_at_ms)
                    VALUES ('old-green', 'remote-stage-1', 'snapshot-1-3',
                        'ci-policy-1', 'task-1', 1, 1, 'head-1', 'base-1',
                        'PASSED', 'ACCEPTED', 1, 0, 'historical green', 81)
                    """);

            assertThat(number(connection, """
                    SELECT COUNT(*) FROM remote_pr_snapshot
                    WHERE head_sha = 'head-1'
                    """)).isEqualTo(2);
            assertFails(connection, episodeSql(
                    "stale-episode", "ci-evaluation-1-1", "head-1", "base-1"));
            assertThat(text(connection, """
                    SELECT current_head_sha FROM remote_development_stage
                    WHERE stage_id = 'remote-stage-1'
                    """)).isEqualTo("head-new");
        }
    }

    @Test
    void appliesEveryNonGreenCiShapeOnlyThroughItsExplicitPolicyOutcome()
            throws Exception
    {
        String url = remoteUrl("ci-policy.db", 1);
        try (Connection connection = connect(url)) {
            insertRemoteOwner(connection, 1);
            insertCiPolicy(connection, 1);
            insertSnapshot(connection, 1, 1, "head-1", "base-1", "OPEN", "UNKNOWN");
            acceptSnapshot(connection, 1, 1, "head-1", "base-1");
            execute(connection, evaluationSql(
                    "none-evaluation", "snapshot-1-1", "head-1", "NONE", "WAITING", 0, 0));
            assertFails(connection, evaluationSql(
                    "silent-green", "snapshot-1-1", "head-1", "NONE", "ACCEPTED", 0, 0));

            String[] states = {"MISSING", "NEUTRAL", "SKIPPED", "CANCELED"};
            String[] outcomes = {"FAILED", "WAITING", "FAILED", "FAILED"};
            for (int index = 0; index < states.length; index++) {
                int revision = index + 2;
                String state = states[index];
                insertSnapshot(connection, 1, revision, "head-1", "base-1", "OPEN", "UNKNOWN");
                execute(connection, checkSql(revision, state));
                execute(connection, evaluationSql(
                        "evaluation-" + state.toLowerCase(Locale.ROOT),
                        "snapshot-1-" + revision,
                        "head-1", state, outcomes[index], 1,
                        state.equals("MISSING") ? 1 : 0));
                String wrong = outcomes[index].equals("ACCEPTED") ? "FAILED" : "ACCEPTED";
                assertFails(connection, evaluationSql(
                        "wrong-" + state.toLowerCase(Locale.ROOT),
                        "snapshot-1-" + revision,
                        "head-1", state, wrong, 1,
                        state.equals("MISSING") ? 1 : 0));
            }

            insertSnapshot(connection, 1, 6, "head-1", "base-1", "OPEN", "UNKNOWN");
            execute(connection, """
                    INSERT INTO remote_ci_check_snapshot(
                        id, remote_pr_snapshot_id, check_kind, external_check_id,
                        check_name, normalized_state, observed_at_ms)
                    VALUES ('only-passed-check', 'snapshot-1-6', 'CHECK_RUN',
                        'only-passed-check', 'build', 'PASSED', 100)
                    """);
            assertFails(connection, evaluationSql(
                    "unsupported-failure", "snapshot-1-6", "head-1",
                    "FAILED", "FAILED", 1, 0));
        }
    }

    @Test
    void keepsCiBudgetsIndependentAndWaitsForTheLastPushResultBeforeExhaustion()
            throws Exception
    {
        String url = remoteUrl("ci-budget.db", 1);
        try (Connection connection = connect(url)) {
            seedFailedCurrentHead(connection, 1);
            execute(connection, episodeSql(
                    "repair-1", "ci-evaluation-1-1", "head-1", "base-1"));
            execute(connection, """
                    UPDATE ci_repair_episode SET rerun_count = 1
                    WHERE id = 'repair-1'
                    """);
            assertThat(text(connection, """
                    SELECT rerun_count || '|' || fix_attempt_count || '|'
                        || delivery_retry_count || '|' || push_count
                    FROM ci_repair_episode WHERE id = 'repair-1'
                    """)).isEqualTo("1|0|0|0");
            assertFails(connection, """
                    UPDATE ci_repair_episode SET fix_attempt_count = 2
                    WHERE id = 'repair-1'
                    """);
            assertFails(connection, """
                    UPDATE ci_repair_episode SET push_count = 1
                    WHERE id = 'repair-1'
                    """);
            assertFails(connection, """
                    UPDATE ci_repair_episode
                    SET last_pushed_head_sha = 'head-after-fix'
                    WHERE id = 'repair-1'
                    """);
            assertFails(connection, """
                    UPDATE ci_repair_episode
                    SET push_count = 1, last_pushed_head_sha = 'head-1'
                    WHERE id = 'repair-1'
                    """);
            execute(connection, """
                    UPDATE ci_repair_episode
                    SET fix_attempt_count = 1, delivery_retry_count = 1,
                        push_count = 1, last_pushed_head_sha = 'head-after-fix',
                        status = 'AWAITING_PUSH_CI'
                    WHERE id = 'repair-1'
                    """);
            assertFails(connection, """
                    UPDATE ci_repair_episode
                    SET terminal_ci_evaluation_id = 'ci-evaluation-1-1',
                        status = 'EXHAUSTED', completed_at_ms = 90
                    WHERE id = 'repair-1'
                    """);

            insertSnapshot(connection, 1, 2, "head-after-fix", "base-1", "OPEN", "UNKNOWN");
            insertFailedCi(connection, 1, 2, "head-after-fix", "base-1");
            acceptSnapshot(connection, 1, 2, "head-after-fix", "base-1");
            execute(connection, """
                    UPDATE ci_repair_episode
                    SET last_push_result_evaluation_id = 'ci-evaluation-1-2',
                        terminal_ci_evaluation_id = 'ci-evaluation-1-2'
                    WHERE id = 'repair-1'
                    """);
            execute(connection, """
                    UPDATE ci_repair_episode
                    SET status = 'EXHAUSTED', completed_at_ms = 90
                    WHERE id = 'repair-1'
                    """);
            assertThat(text(connection, """
                    SELECT status FROM ci_repair_episode WHERE id = 'repair-1'
                    """)).isEqualTo("EXHAUSTED");
            assertFails(connection, """
                    UPDATE ci_repair_episode SET rerun_count = 0 WHERE id = 'repair-1'
                    """);

            execute(connection, """
                    INSERT INTO ci_repair_episode(
                        id, remote_development_stage_id, task_id, task_epoch,
                        stage_generation, remote_pr_binding_id,
                        failed_ci_evaluation_id, subject_head_sha, subject_base_sha,
                        classification, status, rerun_limit, fix_attempt_limit,
                        delivery_retry_limit, push_limit, opened_at_ms)
                    VALUES ('rerun-only', 'remote-stage-1', 'task-1', 1, 1,
                        'binding-1', 'ci-evaluation-1-2', 'head-after-fix',
                        'base-1', 'FLAKY', 'OPEN', 1, 0, 2, 0, 91)
                    """);
            execute(connection, """
                    UPDATE ci_repair_episode
                    SET rerun_count = 1, status = 'AWAITING_RERUN'
                    WHERE id = 'rerun-only'
                    """);
            insertSnapshot(connection, 1, 3, "head-after-fix", "base-1", "OPEN", "UNKNOWN");
            execute(connection, """
                    INSERT INTO remote_ci_check_snapshot(
                        id, remote_pr_snapshot_id, check_kind, external_check_id,
                        check_name, normalized_state, observed_at_ms)
                    VALUES ('rerun-green-check', 'snapshot-1-3', 'CHECK_RUN',
                        'rerun-green-check', 'build', 'PASSED', 95)
                    """);
            execute(connection, evaluationSql(
                    "rerun-green", "snapshot-1-3", "head-after-fix",
                    "PASSED", "ACCEPTED", 1, 0));
            acceptSnapshot(connection, 1, 3, "head-after-fix", "base-1");
            assertFails(connection, """
                    UPDATE ci_repair_episode
                    SET status = 'SUCCEEDED', completed_at_ms = 96
                    WHERE id = 'rerun-only'
                    """);
            execute(connection, """
                    UPDATE ci_repair_episode
                    SET status = 'SUCCEEDED',
                        terminal_ci_evaluation_id = 'rerun-green',
                        completed_at_ms = 96
                    WHERE id = 'rerun-only'
                    """);
        }
    }

    @Test
    void isolatesSiblingEpisodesAndRecoversOneForceWithLeaseEffectLedger()
            throws Exception
    {
        String url = url("siblings-and-branch-sync.db");
        migrate(url, "228");
        try (Connection connection = connect(url)) {
            seedWorkspaceAndTrunk(connection);
            seedPublishedRemoteTask(connection, 1);
            seedPublishedRemoteTask(connection, 2);
        }
        migrate(url, "232");
        try (Connection connection = connect(url)) {
            for (int task = 1; task <= 2; task++) {
                insertRemoteOwner(connection, task);
                insertCiPolicy(connection, task);
                insertSnapshot(connection, task, 1, "head-" + task, "base-" + task,
                        "OPEN", "UNKNOWN");
                acceptSnapshot(connection, task, 1, "head-" + task, "base-" + task);
                insertFailedCi(connection, task, 1, "head-" + task, "base-" + task);
                execute(connection, episodeSql(
                        "repair-" + task, "ci-evaluation-" + task + "-1",
                        "head-" + task, "base-" + task).replace("'task-1'", "'task-" + task + "'")
                        .replace("'remote-stage-1'", "'remote-stage-" + task + "'")
                        .replace("'binding-1'", "'binding-" + task + "'"));
            }
            execute(connection, """
                    UPDATE ci_repair_episode SET delivery_retry_count = 1
                    WHERE id = 'repair-1'
                    """);
            assertThat(number(connection, """
                    SELECT delivery_retry_count FROM ci_repair_episode
                    WHERE id = 'repair-2'
                    """)).isZero();

            execute(connection, branchEpisodeSql());
            insertBranchSteps(connection);
            assertFails(connection, claimBranchStepSql(2, "EXECUTE", 70));
            for (int ordinal = 1; ordinal <= 5; ordinal++) {
                execute(connection, claimBranchStepSql(ordinal, "EXECUTE", 70 + ordinal));
                execute(connection, completeBranchStepSql(ordinal, 80 + ordinal));
            }
            execute(connection, claimBranchStepSql(6, "EXECUTE", 90));
            assertFails(connection, completeBranchStepSql(6, 91));
            execute(connection, """
                    INSERT INTO branch_sync_push_proof(
                        branch_sync_effect_step_id, branch_sync_episode_id,
                        old_head_sha, observed_base_sha, target_base_sha,
                        force_with_lease_expected_sha, pushed_head_sha,
                        force_with_lease_used, remote_probe_identity,
                        evidence, recorded_at_ms)
                    VALUES ('branch-step-6', 'branch-1', 'head-1', 'base-1',
                        'base-target', 'head-1', 'head-rebased', 1,
                        'probe-1', 'remote ref now points at head-rebased', 91)
                    """);
            execute(connection, completeBranchStepSql(6, 92));
            assertFails(connection, """
                    UPDATE branch_sync_episode
                    SET status = 'SUCCEEDED', result_head_sha = 'head-rebased',
                        completed_at_ms = 93
                    WHERE id = 'branch-1'
                    """);
            insertSnapshot(connection, 1, 2, "head-rebased", "base-target",
                    "OPEN", "UNKNOWN");
            acceptSnapshot(connection, 1, 2, "head-rebased", "base-target");
            execute(connection, """
                    UPDATE branch_sync_episode
                    SET status = 'SUCCEEDED', result_head_sha = 'head-rebased',
                        result_snapshot_id = 'snapshot-1-2',
                        completed_at_ms = 93
                    WHERE id = 'branch-1'
                    """);
        }

        try (Connection connection = connect(url)) {
            assertThat(text(connection, """
                    SELECT status || '|' || result_head_sha
                    FROM branch_sync_episode WHERE id = 'branch-1'
                    """)).isEqualTo("SUCCEEDED|head-rebased");
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM branch_sync_push_proof
                    WHERE branch_sync_episode_id = 'branch-1'
                    """)).isOne();
            assertFails(connection, """
                    UPDATE branch_sync_push_proof SET evidence = 'changed'
                    WHERE branch_sync_episode_id = 'branch-1'
                    """);
            assertThat(number(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"))
                    .isZero();
        }
    }

    private String url(String file)
    {
        return "jdbc:sqlite:" + tempDir.resolve(file) + "?foreign_keys=ON";
    }

    private String remoteUrl(String file, int taskCount)
            throws Exception
    {
        String url = url(file);
        migrate(url, "228");
        try (Connection connection = connect(url)) {
            seedWorkspaceAndTrunk(connection);
            for (int task = 1; task <= taskCount; task++) {
                seedPublishedRemoteTask(connection, task);
            }
        }
        migrate(url, "232");
        return url;
    }

    private static void seedFailedCurrentHead(Connection connection, int task)
            throws Exception
    {
        insertRemoteOwner(connection, task);
        insertCiPolicy(connection, task);
        insertSnapshot(connection, task, 1, "head-" + task, "base-" + task,
                "OPEN", "UNKNOWN");
        acceptSnapshot(connection, task, 1, "head-" + task, "base-" + task);
        insertFailedCi(connection, task, 1, "head-" + task, "base-" + task);
    }

    private static String episodeSql(
            String id, String evaluationId, String head, String base)
    {
        return """
                INSERT INTO ci_repair_episode(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_binding_id,
                    failed_ci_evaluation_id, subject_head_sha, subject_base_sha,
                    classification, status, rerun_limit, fix_attempt_limit,
                    delivery_retry_limit, push_limit, opened_at_ms)
                VALUES ('%s', 'remote-stage-1', 'task-1', 1, 1, 'binding-1',
                    '%s', '%s', '%s', 'TASK_DETERMINISTIC', 'OPEN', 1, 1, 3, 1, 75)
                """.formatted(id, evaluationId, head, base);
    }

    private static String evaluationSql(
            String id, String snapshotId, String head, String status,
            String outcome, int checks, int missing)
    {
        return """
                INSERT INTO remote_ci_evaluation(
                    id, remote_development_stage_id, remote_pr_snapshot_id,
                    ci_policy_revision_id, task_id, task_epoch, stage_generation,
                    head_sha, base_sha, normalized_status, policy_outcome,
                    check_count, missing_required_count, evidence, evaluated_at_ms)
                VALUES ('%s', 'remote-stage-1', '%s', 'ci-policy-1', 'task-1',
                    1, 1, '%s', 'base-1', '%s', '%s', %s, %s,
                    'explicit policy evaluation', 90)
                """.formatted(id, snapshotId, head, status, outcome, checks, missing);
    }

    private static String checkSql(int revision, String state)
    {
        String kind = state.equals("MISSING") ? "REQUIRED_MISSING" : "CHECK_RUN";
        return """
                INSERT INTO remote_ci_check_snapshot(
                    id, remote_pr_snapshot_id, check_kind, external_check_id,
                    check_name, normalized_state, observed_at_ms)
                VALUES ('policy-check-%1$s', 'snapshot-1-%1$s', '%2$s',
                    'policy-check-%1$s', 'required build', '%3$s', 90)
                """.formatted(revision, kind, state);
    }

    private static String branchEpisodeSql()
    {
        return """
                INSERT INTO branch_sync_episode(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_binding_id, source_snapshot_id,
                    old_head_sha, observed_base_sha, target_base_sha,
                    policy_source, status, attempt_limit, opened_at_ms)
                VALUES ('branch-1', 'remote-stage-1', 'task-1', 1, 1,
                    'binding-1', 'snapshot-1-1', 'head-1', 'base-1',
                    'base-target', 'SCHEDULED_GUARD', 'OPEN', 2, 70)
                """;
    }

    private static void insertBranchSteps(Connection connection)
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
                    VALUES ('branch-step-%1$s', 'branch-1', %1$s, '%2$s',
                        'branch-1:%2$s', 'REQUESTED', 2)
                    """.formatted(ordinal, kinds[ordinal - 1]));
        }
    }

    private static String claimBranchStepSql(int ordinal, String mode, long at)
    {
        return """
                UPDATE branch_sync_effect_step
                SET status = 'CLAIMED', attempt_count = attempt_count + 1,
                    claim_mode = '%s', claim_owner = 'worker',
                    claimed_at_ms = %s, lease_until_ms = %s
                WHERE id = 'branch-step-%s'
                """.formatted(mode, at, at + 100, ordinal);
    }

    private static String completeBranchStepSql(int ordinal, long at)
    {
        return """
                UPDATE branch_sync_effect_step
                SET status = 'SUCCEEDED', claim_mode = NULL, claim_owner = NULL,
                    claimed_at_ms = NULL, lease_until_ms = NULL,
                    evidence = 'complete', completed_at_ms = %s
                WHERE id = 'branch-step-%s'
                """.formatted(at, ordinal);
    }
}

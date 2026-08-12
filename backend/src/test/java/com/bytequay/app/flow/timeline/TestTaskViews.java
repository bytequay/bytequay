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
import com.bytequay.app.flow.timeline.PrTimelineProjection.OwnerType;
import com.bytequay.app.flow.timeline.TaskViews.RoundView;
import com.bytequay.app.flow.timeline.TaskViews.TaskSummary;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestTaskViews
{
    private static final long AT = 1_787_500_000_000L;

    @TempDir
    private Path temporaryDirectory;

    private JdbcTemplate jdbc;
    private TaskViews views;

    @BeforeEach
    void setUp()
    {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:" + temporaryDirectory.resolve("views.db"));
        FlowRuntimeSchema.install(dataSource);
        CiAutofixSchema.install(dataSource);
        UserGatesSchema.install(dataSource);
        GitHubEffectsSchema.install(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        views = new TaskViews(dataSource, new PrTimelineProjection(dataSource));
    }

    @Test
    void numbersRunsByCreationOrderWithinOneRepository()
    {
        insertTask("task-a", "acme/app", "first goal");
        insertLifecycle("life-a", "task-a", AT);
        insertTask("task-b", "acme/app", "second goal");
        insertLifecycle("life-b", "task-b", AT + 1_000);
        insertTask("task-c", "other/app", "unrelated goal");
        insertLifecycle("life-c", "task-c", AT + 2_000);

        assertThat(views.list("acme/app", 25))
                .extracting(TaskSummary::taskId, TaskSummary::runNumber)
                .containsExactly(
                        Tuple.tuple("task-b", 2),
                        Tuple.tuple("task-a", 1));

        // The number must not depend on how the row was reached: a single-Task
        // lookup is numbered across its whole repository, not its own result set.
        assertThat(views.summary("task-b").orElseThrow().runNumber()).isEqualTo(2);
        // A different repository restarts at one rather than continuing.
        assertThat(views.summary("task-c").orElseThrow().runNumber()).isOne();
    }

    @Test
    void reportsNoRemoteIdentityBeforePublication()
    {
        insertTask("task-a", "acme/app", "first goal");
        insertLifecycle("life-a", "task-a", AT);

        TaskSummary summary = views.summary("task-a").orElseThrow();
        assertThat(summary.prId()).isNull();
        assertThat(summary.prNumber()).isNull();
        assertThat(summary.prUrl()).isNull();
        assertThat(summary.goalText()).isEqualTo("first goal");
        assertThat(summary.branchName()).isEqualTo("branch-task-a");

        // No PR means no timeline anchor; that is a normal state, not an error.
        assertThat(views.timeline("task-a", null, 50)).isEmpty();
        assertThat(views.summary("missing")).isEmpty();
    }

    @Test
    void countsFailingRequiredChecksPerRoundOldestFirst()
    {
        insertTask("task-a", "acme/app", "first goal");
        insertLifecycle("life-a", "task-a", AT);
        insertPr("pr-1", "task-a", "acme/app");

        insertObservation("obs-1", "pr-1", "H1", "FAILURE");
        insertObservation("obs-2", "pr-1", "H1", "FAILURE");
        insertObservation("obs-3", "pr-1", "H1", "SUCCESS");
        insertRound("round-1", "pr-1", "H1", "FINAL_RED", AT,
                "[\"obs-1\",\"obs-2\",\"obs-3\"]");

        insertObservation("obs-4", "pr-1", "H2", "FAILURE");
        insertObservation("obs-5", "pr-1", "H2", "SUCCESS");
        insertRound("round-2", "pr-1", "H2", "FINAL_RED", AT + 1_000,
                "[\"obs-4\",\"obs-5\"]");

        assertThat(views.rounds("task-a"))
                .extracting(
                        RoundView::ordinal,
                        RoundView::remoteHead,
                        RoundView::observedCount,
                        RoundView::failingCount)
                .containsExactly(
                        Tuple.tuple(1, "H1", 3, 2),
                        Tuple.tuple(2, "H2", 2, 1));
    }

    @Test
    void countsOnlyTheObservationsTheRoundFroze()
    {
        insertTask("task-a", "acme/app", "first goal");
        insertLifecycle("life-a", "task-a", AT);
        insertPr("pr-1", "task-a", "acme/app");
        insertObservation("obs-1", "pr-1", "H1", "FAILURE");
        // Observed against the same head but outside the frozen selection: a
        // superseded attempt of the same check must not be counted twice.
        insertObservation("obs-stale", "pr-1", "H1", "FAILURE");
        insertRound("round-1", "pr-1", "H1", "FINAL_RED", AT,
                "[\"obs-1\"]", 1);
        // A superseded round is history, not a rung on the rail.
        insertRound("round-old", "pr-1", "H1", "SUPERSEDED", AT - 1_000,
                "[\"obs-1\",\"obs-stale\"]", 0);

        assertThat(views.rounds("task-a"))
                .extracting(RoundView::roundId, RoundView::failingCount)
                .containsExactly(Tuple.tuple("round-1", 1));
    }

    @Test
    void loadsBodiesOnlyForOwnersThatHaveOne()
    {
        jdbc.update("""
                INSERT INTO flow_runtime_agent_result (
                    result_id, run_id, terminal_outcome, final_content,
                    stop_proof_ref, stored_at
                ) VALUES ('result-1', 'run-1', 'COMPLETED',
                    'Fixed all four root causes.', 'stop-proof', ?)
                """, AT);
        jdbc.update("""
                INSERT INTO flow_ci_lesson (
                    lesson_id, repository_id, learning_operation_id, run_id,
                    subject_id, status, title, markdown, content_digest,
                    created_at
                ) VALUES ('lesson-1', 'acme/app', 'op-1', 'run-2', 'subject-1',
                    'CANDIDATE', 'Lockfiles regenerate',
                    'Do not hand-edit them.', 'digest-1', ?)
                """, AT);

        assertThat(views.detail(OwnerType.AGENT_RESULT, "result-1").orElseThrow())
                .satisfies(detail -> {
                    assertThat(detail.label()).isEqualTo("COMPLETED");
                    assertThat(detail.body())
                            .isEqualTo("Fixed all four root causes.");
                    assertThat(detail.truncated()).isFalse();
                });
        assertThat(views.detail(OwnerType.CI_LESSON, "lesson-1").orElseThrow().body())
                .isEqualTo("Do not hand-edit them.");
        // An owner the timeline event already describes in full has no body.
        assertThat(views.detail(OwnerType.TASK_LIFECYCLE_REVISION, "life-1"))
                .isEmpty();
        assertThat(views.detail(OwnerType.AGENT_RESULT, "missing")).isEmpty();
    }

    @Test
    void refusesUnboundedReads()
    {
        assertThatThrownBy(() -> views.list("acme/app", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> views.list("acme/app", TaskViews.MAX_LIST_SIZE + 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> views.summary(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void insertTask(String taskId, String repositoryId, String goal)
    {
        jdbc.update("""
                INSERT INTO flow_runtime_task (
                    task_id, request_key, repository_id, repository_owner,
                    repository_name, goal_text, repository_root,
                    git_common_dir, remote_name, base_ref, launch_digest,
                    status, branch_name, worktree_path
                ) VALUES (?, ?, ?, 'acme', 'app', ?, '/repo', '/repo/.git',
                    'origin', 'main', ?, 'ACTIVE', ?, ?)
                """,
                taskId, "request-" + taskId, repositoryId, goal,
                "launch-" + taskId, "branch-" + taskId, "/worktree/" + taskId);
    }

    private void insertLifecycle(String id, String taskId, long recordedAt)
    {
        jdbc.update("""
                INSERT INTO flow_runtime_task_lifecycle_revision (
                    lifecycle_revision_id, task_id, sequence, from_status,
                    to_status, reason_code, evidence_ref, operation_id,
                    recorded_at
                ) VALUES (?, ?, 1, NULL, 'CREATED', 'REQUESTED', NULL, NULL, ?)
                """, id, taskId, recordedAt);
    }

    private void insertPr(String prId, String taskId, String repositoryId)
    {
        jdbc.update("""
                INSERT INTO flow_runtime_pr (
                    pr_id, task_id, repository_id, base_ref, base_sha,
                    target_base_ref, scope_key, branch_name,
                    created_from_change_set_revision_id,
                    created_from_head_sha, created_at
                ) VALUES (?, ?, ?, 'main', 'B1', 'main', 'scope-1',
                    'branch-1', 'change-1', 'H1', ?)
                """, prId, taskId, repositoryId, AT);
    }

    private void insertRound(
            String roundId,
            String prId,
            String head,
            String state,
            long createdAt,
            String observationIdsJson)
    {
        insertRound(roundId, prId, head, state, createdAt,
                observationIdsJson, 0);
    }

    private void insertRound(
            String roundId,
            String prId,
            String head,
            String state,
            long createdAt,
            String observationIdsJson,
            int evidenceRevision)
    {
        jdbc.update("""
                INSERT INTO flow_ci_round (
                    round_id, task_id, pr_id, remote_head, policy_revision_id,
                    evidence_revision, check_observation_ids_json,
                    failed_log_refs_json, state, created_at
                ) VALUES (?, 'task-a', ?, ?, 'policy-1', ?, ?, '[]', ?, ?)
                """,
                roundId, prId, head, evidenceRevision, observationIdsJson,
                state, createdAt);
    }

    private void insertObservation(
            String observationId, String prId, String head, String conclusion)
    {
        jdbc.update("""
                INSERT INTO flow_ci_check_observation (
                    observation_id, pr_id, head_sha, selector_key,
                    provider_check_id, provider_run_id, attempt,
                    provider_state_revision, name, status, conclusion,
                    observed_at, raw_evidence_ref
                ) VALUES (?, ?, ?, ?, ?, 'run-1', 1, ?, 'build', 'COMPLETED',
                    ?, ?, ?)
                """,
                observationId, prId, head, "selector-" + observationId,
                "check-" + observationId, "revision-" + observationId,
                conclusion, AT, "evidence-" + observationId);
    }
}

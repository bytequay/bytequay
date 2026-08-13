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
package com.bytequay.app.flow.upstream;

import com.bytequay.app.flow.runtime.NewFlowDatabase;
import com.bytequay.app.flow.timeline.PrTimelineProjection;
import com.bytequay.app.flow.timeline.TaskViews;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.RunState;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.SelectedCommit;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

final class TestUpstreamSyncViews
{
    private static final long AT = 1_786_600_000_000L;
    private static final Clock CLOCK = Clock.fixed(
            Instant.ofEpochMilli(AT), ZoneOffset.UTC);

    @TempDir
    private Path temporaryDirectory;

    private JdbcTemplate jdbc;
    private UpstreamSync sync;
    private UpstreamSyncViews views;

    @BeforeEach
    void setUp()
    {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:" + temporaryDirectory.resolve("views.db")
                        + "?foreign_keys=ON");
        new NewFlowDatabase(dataSource, CLOCK).bootstrap();
        jdbc = new JdbcTemplate(dataSource);
        ObjectMapper mapper = new ObjectMapper();
        sync = new UpstreamSync(dataSource, mapper, CLOCK);
        views = new UpstreamSyncViews(
                dataSource,
                sync,
                new TaskViews(
                        dataSource, new PrTimelineProjection(dataSource)),
                mapper);
    }

    @Test
    void deletingAClosedRunDoesNotRenumberLaterRuns()
    {
        insertTask("task-1", "upstream-sync-command:v3:first", AT);
        var first = start("first", "task-1", "a");
        // An ordinary Task in the same repository is not a sync run number.
        insertTask("ordinary", "ordinary-task", AT + 1_000);
        insertTask("task-2", "upstream-sync-command:v3:second", AT + 2_000);
        var second = start("second", "task-2", "b");

        assertThat(views.job(first.runId()).orElseThrow().runNumber()).isOne();
        assertThat(views.job(second.runId()).orElseThrow().runNumber()).isEqualTo(2);

        sync.advanceState(first.runId(), RunState.CANCELED);
        sync.delete(first.runId());

        assertThat(views.job(first.runId())).isEmpty();
        assertThat(views.job(second.runId()).orElseThrow().runNumber()).isEqualTo(2);
    }

    @Test
    void projectsTheSeparateReviewerRunIntoTheSyncConversation()
    {
        insertTask("task-review", "upstream-sync-command:v3:review", AT);
        var run = start("review", "task-review", "abc123");
        jdbc.update("""
                INSERT INTO flow_runtime_operation (
                    operation_id, owner_kind, owner_id, task_id, kind,
                    subject_digest, input_ref, state, created_at
                ) VALUES ('review-operation', 'REVIEW_REQUEST',
                    'review-request', 'task-review', 'RUN_REVIEWER',
                    'review-subject', 'review-input', 'CLAIMED', ?)
                """, AT + 1);
        jdbc.update("""
                INSERT INTO flow_runtime_agent_session (
                    session_id, task_id, role, state, last_run_id,
                    created_at, updated_at
                ) VALUES ('review-session', 'task-review',
                    'ADVERSARIAL_REVIEWER', 'RUNNING', 'review-run', ?, ?)
                """, AT + 1, AT + 2);
        jdbc.update("""
                INSERT INTO flow_runtime_agent_run (
                    run_id, operation_id, session_id, role, head_sha,
                    prompt_manifest_ref, capability_set_ref, input_ref,
                    state, created_at, started_at
                ) VALUES ('review-run', 'review-operation', 'review-session',
                    'ADVERSARIAL_REVIEWER', 'abc123', 'review-prompt',
                    'read-only-tools', 'review-input', 'RUNNING', ?, ?)
                """, AT + 1, AT + 2);

        UpstreamSyncViews.SyncReviewer reviewer = views.detail(run.runId())
                .orElseThrow()
                .reviewer();

        assertThat(reviewer.runId()).isEqualTo("review-run");
        assertThat(reviewer.state()).isEqualTo("RUNNING");
        assertThat(reviewer.startedAt())
                .isEqualTo(Instant.ofEpochMilli(AT + 2).toString());
        assertThat(reviewer.completedAt()).isNull();
    }

    @Test
    void failedTaskDoesNotKeepACompletedRangeLookingActive()
    {
        insertTask("task-failed", "upstream-sync-command:v3:failed", AT);
        var run = start("failed", "task-failed", "abc123");
        sync.recordVerification(
                run.runId(), RunState.FINAL_REVIEW,
                "abc123", "verification-1");
        jdbc.update("""
                UPDATE flow_runtime_task
                SET status = 'NEEDS_ATTENTION'
                WHERE task_id = 'task-failed'
                """);
        jdbc.update("""
                INSERT INTO flow_runtime_operation (
                    operation_id, owner_kind, owner_id, task_id, kind,
                    subject_digest, input_ref, state, created_at
                ) VALUES ('failed-operation', 'TASK', 'task-failed',
                    'task-failed', 'RUN_TASK_TURN', 'failed-subject',
                    'failed-input', 'FAILED', ?)
                """, AT + 1);
        jdbc.update("""
                INSERT INTO flow_runtime_agent_session (
                    session_id, task_id, role, state, last_run_id,
                    created_at, updated_at
                ) VALUES ('failed-session', 'task-failed', 'TASK_AGENT',
                    'IDLE', 'failed-run', ?, ?)
                """, AT + 1, AT + 2);
        jdbc.update("""
                INSERT INTO flow_runtime_agent_run (
                    run_id, operation_id, session_id, role, head_sha,
                    prompt_manifest_ref, capability_set_ref, input_ref,
                    wake_kind, intended_gate_kind, state,
                    failure_reason_code, created_at, started_at, completed_at
                ) VALUES ('failed-run', 'failed-operation', 'failed-session',
                    'TASK_AGENT', 'abc123', 'prompt', 'capabilities',
                    'failed-input', 'INITIAL_TASK', 'INITIAL_PUBLISH',
                    'FAILED', 'MISSING_TERMINAL_TOOL', ?, ?, ?)
                """, AT + 1, AT + 1, AT + 2);

        UpstreamSyncViews.SyncJob job = views.detail(run.runId())
                .orElseThrow()
                .job();

        assertThat(job.status()).isEqualTo("FAILED");
        assertThat(job.errorMessage()).isEqualTo("MISSING_TERMINAL_TOOL");
    }

    private UpstreamSyncRecords.UpstreamSyncRun start(
            String key, String taskId, String sha)
    {
        return sync.startRun(
                "upstream-sync-command:v3:" + key,
                "acme/app",
                "sync " + key,
                "Sync " + key,
                "upstream",
                sha,
                sha,
                "refs/heads/main",
                List.of(new SelectedCommit(sha, "subject " + key)),
                null,
                taskId,
                0);
    }

    private void insertTask(String taskId, String requestKey, long recordedAt)
    {
        jdbc.update("""
                INSERT INTO flow_runtime_task (
                    task_id, request_key, repository_id, repository_owner,
                    repository_name, goal_text, repository_root,
                    git_common_dir, remote_name, base_ref, launch_digest,
                    status, branch_name, worktree_path
                ) VALUES (?, ?, 'acme/app', 'acme', 'app', 'goal', '/repo',
                    '/repo/.git', 'origin', 'refs/heads/main', ?, 'ACTIVE',
                    ?, ?)
                """,
                taskId, requestKey, "launch-" + taskId,
                "branch-" + taskId, "/worktree/" + taskId);
        jdbc.update("""
                INSERT INTO flow_runtime_task_lifecycle_revision (
                    lifecycle_revision_id, task_id, sequence, from_status,
                    to_status, reason_code, evidence_ref, operation_id,
                    recorded_at
                ) VALUES (?, ?, 1, NULL, 'CREATED', 'REQUESTED', NULL, NULL, ?)
                """,
                "lifecycle-" + taskId, taskId, recordedAt);
    }
}

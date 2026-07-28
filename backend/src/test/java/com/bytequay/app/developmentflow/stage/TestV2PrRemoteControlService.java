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
package com.bytequay.app.developmentflow.stage;

import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.developmentflow.stage.LocalDevelopmentStageManager.PublishCommand;
import com.bytequay.app.developmentflow.stage.RemoteMergeRuntimeCoordinator.Command;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteMergeRuntimeStore.AuthorityKind;
import com.bytequay.app.service.checks.CodeFingerprints;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestV2PrRemoteControlService
{
    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

    private final TaskCommandExecutor commands = mock(TaskCommandExecutor.class);
    private final LocalDevelopmentStageManager local = mock(LocalDevelopmentStageManager.class);
    private final RemoteMergeRuntimeCoordinator merges = mock(RemoteMergeRuntimeCoordinator.class);
    private final GitRunner git = mock(GitRunner.class);
    private final CodeFingerprints fingerprints = mock(CodeFingerprints.class);
    private final PatResolver pats = mock(PatResolver.class);

    @Test
    void approveAndShipBuildsOneTypedPublishGraphAndMovesOnlyTheLocalManager()
            throws Exception
    {
        RecordingJdbc jdbc = RecordingJdbc.publish();
        V2PrRemoteControlService service = service(jdbc);
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(commands).executeVoid(eq("task-1"), any(Runnable.class));
        when(git.isGitWorkingTree(Path.of("/tmp"))).thenReturn(true);
        when(git.currentBranch(Path.of("/tmp"))).thenReturn("dev/task-1");
        when(git.headSha(Path.of("/tmp"))).thenReturn("head-1");
        when(git.statusPorcelainZ(Path.of("/tmp"))).thenReturn("");
        when(git.commitCountUniqueTo(Path.of("/tmp"), "HEAD", "base-1"))
                .thenReturn(1);
        when(fingerprints.fingerprint(Path.of("/tmp"))).thenReturn("fp-1");
        when(pats.resolve("acme/widget")).thenReturn("token");
        when(local.authorizePublishInCommand(any()))
                .thenReturn(CommandResult.applied(mock(StageManager.State.class)));

        try (MockedStatic<TaskCommandExecutor> ignored =
                mockStatic(TaskCommandExecutor.class)) {
            service.approveAndShip("task-1", "pr-1", true);
        }

        assertThat(jdbc.updates()).anyMatch(sql -> sql.contains("INSERT INTO promotion_manifest"));
        assertThat(jdbc.updates()).anyMatch(sql -> sql.contains("INSERT INTO publish_authorization"));
        assertThat(jdbc.updates()).anyMatch(sql -> sql.contains("INSERT INTO publish_operation"));
        assertThat(jdbc.updates().stream()
                .filter(sql -> sql.contains("INSERT INTO publish_effect_step")))
                .hasSize(6);
        assertThat(jdbc.updates()).anyMatch(sql -> sql.contains("INSERT INTO dispatch_ticket"));
        assertThat(jdbc.updates()).anyMatch(sql -> sql.contains("SET status = 'DISPATCHED'"));

        ArgumentCaptor<PublishCommand> authorization =
                ArgumentCaptor.forClass(PublishCommand.class);
        verify(local).authorizePublishInCommand(authorization.capture());
        assertThat(authorization.getValue().policyRevisionId()).isEqualTo("policy-1");
        assertThat(authorization.getValue().consentId()).isNotBlank();
        assertThat(authorization.getValue().resultFence().expectedHeadSha())
                .isEqualTo("head-1");
        verify(merges, never()).start(any());
    }

    @Test
    void manualMergeUsesCurrentReadinessAndManualAuthority()
    {
        RecordingJdbc jdbc = RecordingJdbc.merge("SUPPORTED");
        V2PrRemoteControlService service = service(jdbc);

        service.merge("task-1", "rebase");

        ArgumentCaptor<Command> command = ArgumentCaptor.forClass(Command.class);
        verify(merges).start(command.capture());
        assertThat(command.getValue().taskId()).isEqualTo("task-1");
        assertThat(command.getValue().stageId()).isEqualTo("remote-stage-1");
        assertThat(command.getValue().readinessEvidenceId()).isEqualTo("readiness-1");
        assertThat(command.getValue().authorityKind()).isEqualTo(AuthorityKind.MANUAL);
        assertThat(command.getValue().mergeMethod()).isEqualTo("rebase");
    }

    @Test
    void directMergePreservesTheSelectedMethod()
    {
        RecordingJdbc jdbc = RecordingJdbc.merge("UNSUPPORTED");
        V2PrRemoteControlService service = service(jdbc);

        service.merge("task-1", "rebase");

        ArgumentCaptor<Command> command = ArgumentCaptor.forClass(Command.class);
        verify(merges).start(command.capture());
        assertThat(command.getValue().mergeMethod()).isEqualTo("rebase");
    }

    private V2PrRemoteControlService service(JdbcTemplate jdbc)
    {
        return new V2PrRemoteControlService(
                jdbc, commands, local, merges, git, fingerprints, pats,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final class RecordingJdbc
            extends JdbcTemplate
    {
        private final Mode mode;
        private final String queueCapability;
        private final List<String> updates = new ArrayList<>();

        private RecordingJdbc(Mode mode, String queueCapability)
        {
            this.mode = mode;
            this.queueCapability = queueCapability;
        }

        static RecordingJdbc publish()
        {
            return new RecordingJdbc(Mode.PUBLISH, null);
        }

        static RecordingJdbc merge(String queueCapability)
        {
            return new RecordingJdbc(Mode.MERGE, queueCapability);
        }

        List<String> updates()
        {
            return List.copyOf(updates);
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args)
        {
            if (sql.contains("local_review_comment_revision")) {
                return List.of();
            }
            boolean publishCandidate = mode == Mode.PUBLISH
                    && sql.contains("JOIN local_development_stage")
                    && sql.contains("JOIN task_policy_revision policy"
                            + " ON policy.id = task.policy_revision_id");
            boolean mergeCandidate = mode == Mode.MERGE
                    && sql.contains("remote_readiness_evidence readiness")
                    && sql.contains("JOIN task_automation_policy policy")
                    && sql.contains("MAX(current_policy.revision)");
            if (!publishCandidate && !mergeCandidate) {
                return List.of();
            }
            try {
                return List.of(mapper.mapRow(
                        publishCandidate ? publishRow() : mergeRow(), 0));
            }
            catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }

        @Override
        public <T> T queryForObject(
                String sql, Class<T> requiredType, Object... args)
        {
            int value = sql.contains("COUNT(*)") ? 0 : 1;
            return requiredType.cast(value);
        }

        @Override
        public int update(String sql, Object... args)
        {
            updates.add(sql);
            return 1;
        }

        private static ResultSet publishRow()
                throws Exception
        {
            ResultSet row = mock(ResultSet.class);
            Map<String, String> strings = Map.ofEntries(
                    Map.entry("task_id", "task-1"),
                    Map.entry("trunk_id", "trunk-1"),
                    Map.entry("workspace_id", "workspace-1"),
                    Map.entry("policy_revision_id", "policy-1"),
                    Map.entry("stage_id", "local-stage-1"),
                    Map.entry("dev_report_id", "report-1"),
                    Map.entry("code_fingerprint", "fp-1"),
                    Map.entry("head_sha", "head-1"),
                    Map.entry("base_sha", "base-1"),
                    Map.entry("validation_evidence_id", "validation-1"),
                    Map.entry("brain_episode_id", "brain-1"),
                    Map.entry("brain_status", "SUCCEEDED"),
                    Map.entry("brain_verdict", "APPROVED"),
                    Map.entry("pr_id", "pr-1"),
                    Map.entry("pr_title", "Implement feature"),
                    Map.entry("pr_body", "Description"),
                    Map.entry("branch_name", "dev/task-1"),
                    Map.entry("base_branch", "main"),
                    Map.entry("worktree_path", "/tmp"),
                    Map.entry("repository_id", "acme/widget"),
                    Map.entry("publish_repository_id", "acme/widget"));
            when(row.getString(anyString()))
                    .thenAnswer(invocation -> strings.get(invocation.getArgument(0)));
            when(row.getLong("task_epoch")).thenReturn(1L);
            when(row.getLong("stage_generation")).thenReturn(1L);
            when(row.getLong("stage_version")).thenReturn(4L);
            when(row.getInt("validation_passed")).thenReturn(1);
            when(row.getInt("unresolved_finding_count")).thenReturn(0);
            when(row.getInt("auto_approve")).thenReturn(1);
            return row;
        }

        private ResultSet mergeRow()
                throws Exception
        {
            ResultSet row = mock(ResultSet.class);
            when(row.getString("stage_id")).thenReturn("remote-stage-1");
            when(row.getString("readiness_id")).thenReturn("readiness-1");
            when(row.getString("merge_queue_capability"))
                    .thenReturn(queueCapability);
            return row;
        }

        private enum Mode
        {
            PUBLISH,
            MERGE
        }
    }
}

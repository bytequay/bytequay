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
import com.bytequay.app.developmentflow.stage.RemoteDevelopmentStageManager.PolicyReadinessCommand;
import com.bytequay.app.developmentflow.stage.RemoteDevelopmentStageManager.RemoteGateCommand;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.AutomationPolicy;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.ReadinessEvidence;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.RemoteContext;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteMergeRuntimeStore.AuthorityKind;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestRemotePolicyRedriveRuntime
{
    private static final Instant NOW = Instant.parse("2026-07-29T00:00:00Z");

    private final TaskCommandExecutor commands = mock(TaskCommandExecutor.class);
    private final RemoteDevelopmentStageManager remote =
            mock(RemoteDevelopmentStageManager.class);
    private final SqliteRemoteDevelopmentRuntimeStore store =
            mock(SqliteRemoteDevelopmentRuntimeStore.class);
    private final RemoteMergeRuntimeCoordinator merges =
            mock(RemoteMergeRuntimeCoordinator.class);

    @Test
    void stricterPolicyRegressesStaleReadyStageThroughItsOwner()
    {
        RemoteContext context = context("READY_TO_MERGE", 7);
        AutomationPolicy policy = policy("policy-2", 2, false);
        ReadinessEvidence notReady = readiness("readiness-2", policy.id(), false);
        runCommandsInline();
        when(store.findPolicyRedriveContext("task-1"))
                .thenReturn(Optional.of(context));
        when(store.requireAutomationPolicy("task-1")).thenReturn(policy);
        when(store.findCurrentReadiness("snapshot-1", policy.id()))
                .thenReturn(Optional.of(notReady));
        when(remote.reconsiderReadinessPolicyInCommand(any()))
                .thenReturn(CommandResult.applied(mock(StageManager.State.class)));

        try (MockedStatic<TaskCommandExecutor> ignored =
                mockStatic(TaskCommandExecutor.class)) {
            assertThat(runtime().redrive("task-1")).isTrue();
        }

        ArgumentCaptor<PolicyReadinessCommand> command =
                ArgumentCaptor.forClass(PolicyReadinessCommand.class);
        verify(remote).reconsiderReadinessPolicyInCommand(command.capture());
        assertThat(command.getValue().stage().expectedStageVersion()).isEqualTo(7);
        assertThat(command.getValue().readinessEvidenceId())
                .isEqualTo("readiness-2");
        assertThat(command.getValue().automationPolicyId()).isEqualTo("policy-2");
        assertThat(command.getValue().headSha()).isEqualTo("head-1");
        assertThat(command.getValue().baseSha()).isEqualTo("base-1");
        verify(remote, never()).acceptReadinessEvidenceInCommand(any());
        verify(merges, never()).startInCommand(any());
    }

    @Test
    void relaxedPolicyAcceptsNewProofAndStartsAutoMerge()
    {
        RemoteContext context = context("WAITING_REMOTE_REVIEW", 5);
        AutomationPolicy policy = policy("policy-2", 0, true);
        ReadinessEvidence ready = readiness("readiness-2", policy.id(), true);
        runCommandsInline();
        when(store.findPolicyRedriveContext("task-1"))
                .thenReturn(Optional.of(context));
        when(store.requireAutomationPolicy("task-1")).thenReturn(policy);
        when(store.findCurrentReadiness("snapshot-1", policy.id()))
                .thenReturn(Optional.empty());
        when(store.findAutomationEligibilityEvidenceId("task-1", 3))
                .thenReturn(Optional.of("eligibility-1"));
        when(store.proveReadiness(
                any(), eq("task-1"), eq("stage-1"), eq("eligibility-1"),
                any(), eq(NOW))).thenReturn(ready);
        when(remote.acceptReadinessEvidenceInCommand(any()))
                .thenReturn(CommandResult.applied(mock(StageManager.State.class)));

        try (MockedStatic<TaskCommandExecutor> ignored =
                mockStatic(TaskCommandExecutor.class)) {
            assertThat(runtime().redrive("task-1")).isTrue();
        }

        ArgumentCaptor<RemoteGateCommand> gate =
                ArgumentCaptor.forClass(RemoteGateCommand.class);
        verify(remote).acceptReadinessEvidenceInCommand(gate.capture());
        assertThat(gate.getValue().proofId()).isEqualTo("readiness-2");
        assertThat(gate.getValue().stage().expectedStageVersion()).isEqualTo(5);

        ArgumentCaptor<RemoteMergeRuntimeCoordinator.Command> merge =
                ArgumentCaptor.forClass(RemoteMergeRuntimeCoordinator.Command.class);
        verify(merges).startInCommand(merge.capture());
        assertThat(merge.getValue().readinessEvidenceId())
                .isEqualTo("readiness-2");
        assertThat(merge.getValue().authorityKind())
                .isEqualTo(AuthorityKind.AUTO_MERGE_POLICY);
    }

    @Test
    void repeatedRestartRedriveDoesNotDuplicateAnAutoMerge()
    {
        RemoteContext context = context("READY_TO_MERGE", 6);
        AutomationPolicy policy = policy("policy-2", 0, true);
        ReadinessEvidence ready = readiness("readiness-2", policy.id(), true);
        runCommandsInline();
        when(store.findPolicyRedriveContext("task-1"))
                .thenReturn(Optional.of(context), Optional.empty());
        when(store.requireAutomationPolicy("task-1")).thenReturn(policy);
        when(store.findCurrentReadiness("snapshot-1", policy.id()))
                .thenReturn(Optional.of(ready));

        try (MockedStatic<TaskCommandExecutor> ignored =
                mockStatic(TaskCommandExecutor.class)) {
            assertThat(runtime().redrive("task-1")).isTrue();
            assertThat(runtime().redrive("task-1")).isFalse();
        }

        verify(merges, times(1)).startInCommand(any());
        verify(store, never()).proveReadiness(
                any(), any(), any(), any(), any(), any());
        verify(remote, never()).acceptReadinessEvidenceInCommand(any());
        verify(remote, never()).reconsiderReadinessPolicyInCommand(any());
    }

    private RemotePolicyRedriveRuntime runtime()
    {
        return new RemotePolicyRedriveRuntime(
                commands, remote, store, merges,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void runCommandsInline()
    {
        when(commands.execute(eq("task-1"), any())).thenAnswer(invocation ->
                invocation.<Supplier<?>>getArgument(1).get());
    }

    private static RemoteContext context(String checkpoint, long stageVersion)
    {
        return new RemoteContext(
                "workspace-1", "trunk-1", "task-1", 3, 11,
                "stage-1", 2, stageVersion, checkpoint, "binding-1",
                "snapshot-1", 4, "head-1", "base-1", "fingerprint-1",
                "/tmp/task-1");
    }

    private static AutomationPolicy policy(
            String id, int minimumWriteApprovals, boolean autoMerge)
    {
        return new AutomationPolicy(
                id, "task-1", 2, "TASK_POLICY_COMMAND", true, autoMerge,
                false, minimumWriteApprovals, 2, false, false, false,
                "user", NOW);
    }

    private static ReadinessEvidence readiness(
            String id, String policyId, boolean ready)
    {
        return new ReadinessEvidence(
                id, "snapshot-1", "ci-1", policyId,
                "head-1", "base-1", ready);
    }
}

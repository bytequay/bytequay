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
package com.bytequay.app.agent;

import com.bytequay.agent.cli.process.OwnedProcessRecovery;
import com.bytequay.agent.cli.process.ProcessSupervisor;
import com.bytequay.agent.core.AgentApprovalService;
import com.bytequay.agent.core.AgentCompletionService;
import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentMetricsService;
import com.bytequay.agent.core.AgentProcessLaunch;
import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.CleanupState;
import com.bytequay.agent.core.LaunchOwnerKind;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.WorktreeService;
import com.bytequay.agent.core.impl.AgentMaintenance;
import com.bytequay.agent.core.impl.AgentRuntimeRegistry;
import com.bytequay.agent.core.store.AgentEventOutboxStore;
import com.bytequay.agent.core.store.AgentProcessLaunchStore;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.AgentUnitOfWork;
import com.bytequay.agent.core.store.QueuedInputStore;
import com.bytequay.agent.core.store.SteerDeliveryStore;
import com.bytequay.agent.core.store.TaskLogStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.agent.runtime.AgentServingGate;
import com.bytequay.app.agent.runtime.AgentStartupRecovery;
import com.bytequay.app.service.settings.AppSettingsService;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.agent.core.AgentTaskFixtures.created;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestAgentCleanupRecovery
{
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private final TaskId taskId = TaskId.random();
    private final TaskWorktree worktree = new TaskWorktree(taskId, "workspace", new RepositoryIdentity("owner", "repo"),
            Path.of("/tmp", taskId.toString()), "bytequay/task/" + taskId, "a".repeat(40));
    private final AtomicReference<AgentTask> task = new AtomicReference<>(created(taskId, true, "Recovered task", TitleSource.USER,
            AgentLaneId.CODEX_CLI, worktree, AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), NOW)
            .claimWorktreeMutation(WorktreeMutationClaim.recovery("old-provider", NOW), NOW));
    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final AgentTurnStore turns = mock(AgentTurnStore.class);
    private final AgentUnitOfWork unitOfWork = mock(AgentUnitOfWork.class);
    private final AgentEventOutboxStore outbox = mock(AgentEventOutboxStore.class);
    private final AgentEventService events = mock(AgentEventService.class);
    private final AgentResourceService resources = mock(AgentResourceService.class);
    private final WorktreeService worktrees = mock(WorktreeService.class);
    private final ProcessSupervisor supervisor = mock(ProcessSupervisor.class);
    private final AgentProcessLaunchStore launches = mock(AgentProcessLaunchStore.class);
    private final AppSettingsService settings = mock(AppSettingsService.class);
    private final DevelopmentBranchService branches = mock(DevelopmentBranchService.class);
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private final AgentRuntimeRegistry runtime = new AgentRuntimeRegistry(tasks, ignored -> {}, ignored -> false, true);

    @BeforeEach
    void setUp()
    {
        when(tasks.require(taskId)).thenAnswer(ignored -> task.get());
        when(tasks.find(taskId)).thenAnswer(ignored -> Optional.of(task.get()));
        when(tasks.update(any())).thenAnswer(call -> {
            AgentTask updated = call.getArgument(0);
            task.set(updated);
            return updated;
        });
        when(unitOfWork.execute(any())).thenAnswer(call -> call.<AgentUnitOfWork.Work<?>>getArgument(0).run());
        when(settings.taskRuntimeRetention()).thenReturn(Duration.ofDays(7));
        when(branches.recover(any())).thenAnswer(call -> call.getArgument(0));
        when(launches.list()).thenReturn(ImmutableList.of(launch("old-provider", taskId)));
        when(supervisor.recoverOwnedProcess("old-provider")).thenReturn(recovery(OwnedProcessRecovery.Outcome.UNVERIFIABLE));
        AgentMaintenance maintenance = new AgentMaintenance(tasks, turns, mock(QueuedInputStore.class), mock(SteerDeliveryStore.class), unitOfWork, outbox,
                mock(AgentApprovalService.class), mock(AgentMetricsService.class), mock(AgentCompletionService.class), runtime, Clock.fixed(NOW, ZoneOffset.UTC));
        AgentStartupRecovery recovery = new AgentStartupRecovery(outbox, events, maintenance, supervisor, new AgentServingGate(),
                launches, tasks, turns, mock(AgentTurnService.class));
        beans.registerSingleton("branches", branches);
        beans.registerSingleton("recovery", recovery);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedRecoveryTaskCannotBeCleanedUntilItsLaunchIsVerifiedStopped(boolean retentionCleanup)
    {
        completion(NOW).abandon(taskId, "Keep this blocked task for inspection");
        assertThat(task.get().state()).isEqualTo(TaskState.FAILED);
        assertThat(task.get().worktreeMutationClaim()).isEmpty();
        verifyNoInteractions(supervisor, worktrees, resources);

        AgentCompletionService completion = completion(NOW.plus(Duration.ofDays(8)));
        if (retentionCleanup) {
            completion.cleanup(taskId);
            assertThat(task.get().cleanupState()).isEqualTo(CleanupState.RETRY_PENDING);
            assertThat(task.get().cleanupFailure()).hasValueSatisfying(reason -> assertThat(reason).contains("not verified stopped"));
        }
        else {
            assertThatThrownBy(() -> completion.cleanupWorktree(taskId))
                    .isInstanceOf(AgentConflictException.class)
                    .hasMessageContaining("old-provider")
                    .hasMessageContaining("not verified stopped");
        }
        assertThat(task.get().worktreeRemovedAt()).isEmpty();
        verifyNoInteractions(worktrees, resources);
        verify(supervisor, never()).acknowledge(any());

        when(supervisor.recoverOwnedProcess("old-provider")).thenReturn(recovery(OwnedProcessRecovery.Outcome.VERIFIED_STOPPED));
        if (retentionCleanup) {
            completion.cleanup(taskId);
            assertThat(task.get().cleanupState()).isEqualTo(CleanupState.DELETED);
            verify(resources).deleteExceptTranscript(taskId);
        }
        else {
            completion.cleanupWorktree(taskId);
            verify(resources, never()).deleteExceptTranscript(taskId);
        }
        assertThat(task.get().worktreeRemovedAt()).isPresent();
        verify(worktrees).cleanup(worktree);
        verify(resources).deleteWorktreeCache(taskId);
        verify(supervisor).acknowledge("old-provider");
    }

    @Test
    void cleanupLeavesCurrentBackendAndOtherTaskLaunchesToTheirOwners()
    {
        completion(NOW).abandon(taskId, "Keep this blocked task for inspection");
        when(launches.list()).thenReturn(ImmutableList.of(launch("old-provider", taskId), launch("current-ide", taskId), launch("other-task", TaskId.random())));
        when(supervisor.launchedHere("current-ide")).thenReturn(true);
        when(supervisor.recoverOwnedProcess("old-provider")).thenReturn(recovery(OwnedProcessRecovery.Outcome.KNOWN_UNLAUNCHED));

        completion(NOW).cleanupWorktree(taskId);

        verify(supervisor, never()).recoverOwnedProcess("current-ide");
        verify(supervisor, never()).recoverOwnedProcess("other-task");
        verify(supervisor, never()).acknowledge("current-ide");
        verify(supervisor, never()).acknowledge("other-task");
        verify(supervisor).acknowledge("old-provider");
        verify(worktrees).cleanup(worktree);
    }

    private AgentCompletionService completion(Instant now)
    {
        return new AgentConfiguration().agentCompletionService(tasks, turns, mock(QueuedInputStore.class), unitOfWork, outbox, events, mock(TaskLogStore.class),
                worktrees, resources, runtime, mock(AgentMetricsService.class), settings, beans.getBeanProvider(DevelopmentBranchService.class),
                beans.getBeanProvider(AgentStartupRecovery.class), Clock.fixed(now, ZoneOffset.UTC));
    }

    private static AgentProcessLaunch launch(String launchId, TaskId taskId)
    {
        return new AgentProcessLaunch(launchId, LaunchOwnerKind.APP_SERVER, taskId.toString(), true, "/usr/local/bin/codex", "argv-digest", NOW);
    }

    private static OwnedProcessRecovery recovery(OwnedProcessRecovery.Outcome outcome)
    {
        return new OwnedProcessRecovery("old-provider", Optional.empty(), Optional.empty(), outcome, outcome.name());
    }
}

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
import com.bytequay.agent.cli.process.ProcessOwner;
import com.bytequay.agent.cli.process.ProcessSupervisor;
import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentProcessLaunch;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskNotFoundException;
import com.bytequay.agent.core.InvalidAgentRequestException;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.impl.AgentMaintenance;
import com.bytequay.agent.core.store.AgentEventOutboxStore;
import com.bytequay.agent.core.store.AgentProcessLaunchStore;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Component
public final class AgentStartupRecovery
        implements SmartInitializingSingleton
{
    private static final Logger log = LoggerFactory.getLogger(AgentStartupRecovery.class);
    private static final int OUTBOX_BATCH_SIZE = 100;

    private final AgentEventOutboxStore outboxStore;
    private final AgentEventService eventService;
    private final AgentMaintenance maintenance;
    private final ProcessSupervisor supervisor;
    private final AgentServingGate servingGate;
    private final AgentProcessLaunchStore launchStore;
    private final AgentTaskStore taskStore;
    private final AgentTurnStore turnStore;

    public AgentStartupRecovery(
            AgentEventOutboxStore outboxStore,
            AgentEventService eventService,
            AgentMaintenance maintenance,
            ProcessSupervisor supervisor,
            AgentServingGate servingGate,
            AgentProcessLaunchStore launchStore,
            AgentTaskStore taskStore,
            AgentTurnStore turnStore)
    {
        this.outboxStore = requireNonNull(outboxStore, "outboxStore is null");
        this.eventService = requireNonNull(eventService, "eventService is null");
        this.maintenance = requireNonNull(maintenance, "maintenance is null");
        this.supervisor = requireNonNull(supervisor, "supervisor is null");
        this.servingGate = requireNonNull(servingGate, "servingGate is null");
        this.launchStore = requireNonNull(launchStore, "launchStore is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.turnStore = requireNonNull(turnStore, "turnStore is null");
    }

    @Override
    public void afterSingletonsInstantiated()
    {
        try {
            drainPendingEvents();
            maintenance.recover();
            drainPendingEvents();
            boolean attributable = recoverOwnedProcesses();
            // Settling a restarted turn records its interruption, which must reach the log before
            // anything can be served.
            drainPendingEvents();
            if (!attributable) {
                log.error("Agent operations remain unavailable: an owned provider process could not be "
                        + "verified stopped, so a worktree may still have a live writer");
                return;
            }
            servingGate.open();
        }
        catch (RuntimeException failure) {
            log.error("Agent startup recovery failed; agent operations remain unavailable", failure);
        }
    }

    /**
     * Explicitly retries process verification for a task blocked during startup recovery.
     *
     * <p>All durable launches attributable to the task are checked, not only the launch currently
     * named by its single recovery claim. Safe markers are acknowledged only after the claim is
     * released or moved to another unverifiable launch. The operation derives everything from
     * durable task, turn, launch, and marker facts and adds no in-memory recovery state.
     *
     * @param taskId task whose retained provider processes should be stopped and verified
     * @param generation task generation returned by the stop-impact endpoint
     * @return task after its recovery claim is released
     */
    public AgentTask stopRecoveredRuntime(TaskId taskId, String generation)
    {
        requireNonNull(taskId, "taskId is null");
        if (generation == null || generation.isBlank()) {
            throw new InvalidAgentRequestException("generation must not be blank");
        }
        AgentTask task = taskStore.find(taskId).orElseThrow(() -> new AgentTaskNotFoundException(taskId));
        if (task.state().terminal()) {
            throw new AgentConflictException("Task is already terminal: " + taskId);
        }
        if (!generation.equals("task:" + task.version())) {
            throw new AgentConflictException("Task runtime changed since the stop was confirmed: " + taskId);
        }
        if (turnStore.findActive(taskId).isPresent()) {
            throw new AgentConflictException("Task has an active turn: " + taskId);
        }
        WorktreeMutationClaim claim = task.worktreeMutationClaim()
                .filter(held -> held.kind() == WorktreeMutationClaim.Kind.RECOVERY)
                .orElseThrow(() -> new AgentConflictException("Task has no recovered provider runtime to stop: " + taskId));

        ImmutableSet.Builder<String> launchIds = ImmutableSet.builder();
        launchIds.add(claim.ownerId());
        launchStore.list().stream()
                .filter(launch -> ownerTask(launch).filter(taskId::equals).isPresent())
                .map(AgentProcessLaunch::launchId)
                .forEach(launchIds::add);
        List<OwnedProcessRecovery> recoveries = launchIds.build().stream()
                .map(supervisor::recoverOwnedProcess)
                .collect(toImmutableList());
        Optional<OwnedProcessRecovery> blocking = recoveries.stream()
                .filter(recovery -> recovery.outcome() == OwnedProcessRecovery.Outcome.UNVERIFIABLE)
                .findFirst();
        AgentTask settled = maintenance.settleRecoveryClaim(
                taskId,
                task.version(),
                claim.ownerId(),
                blocking.map(OwnedProcessRecovery::launchId));
        recoveries.stream()
                .filter(recovery -> recovery.outcome() != OwnedProcessRecovery.Outcome.UNVERIFIABLE)
                .map(OwnedProcessRecovery::launchId)
                .forEach(supervisor::acknowledge);
        if (blocking.isPresent()) {
            OwnedProcessRecovery unresolved = blocking.orElseThrow();
            throw new AgentUnavailableException(
                    "Provider process " + unresolved.launchId() + " could not be verified stopped: " + unresolved.detail());
        }
        return settled;
    }

    /**
     * Stops provider process trees left by an earlier backend instance.
     *
     * <p>An unverifiable tree that can be attributed to one live task holds that task's worktree
     * mutation claim, so only that task is blocked. One that cannot be attributed blocks every
     * agent operation, because something may still be writing somewhere nobody can name. Either
     * way the launch marker is retained and never acknowledged, so the block survives a restart
     * until the process is resolved.
     *
     * <p>Classification runs before any write. A task can leak more than one launch and can hold
     * only one claim, so deciding per launch as it is read would release a block that another
     * launch still needs. Once every launch is classified, the turns that were mid-flight at the
     * crash are settled by that verdict: a verified stop releases the turn's worktree, an
     * unverifiable one reserves it until a later startup can verify it.
     *
     * @return true when nothing unattributable was left running
     */
    private boolean recoverOwnedProcesses()
    {
        // Snapshotted first: acknowledging a launch deletes its row, and an unverifiable launch
        // whose marker is missing can only be attributed through that row.
        Map<String, AgentProcessLaunch> launches = new HashMap<>();
        launchStore.list().forEach(launch -> launches.put(launch.launchId(), launch));

        Map<TaskId, String> blockedTasks = new LinkedHashMap<>();
        List<String> resolved = new ArrayList<>();
        boolean attributable = true;
        for (OwnedProcessRecovery recovery : supervisor.recoverOwnedProcesses()) {
            if (recovery.outcome() != OwnedProcessRecovery.Outcome.UNVERIFIABLE) {
                log.info("Owned provider process {} recovered as {}: {}",
                        recovery.launchId(), recovery.outcome(), recovery.detail());
                resolved.add(recovery.launchId());
                continue;
            }
            Optional<TaskId> owner = blockableTask(recovery, launches.get(recovery.launchId()));
            if (owner.isEmpty()) {
                log.error("Owned provider process {} owned by {} is unverifiable and belongs to no "
                                + "live task, so agent operations remain unavailable: {}",
                        recovery.launchId(), recovery.owner().orElse(null), recovery.detail());
                attributable = false;
                continue;
            }
            log.error("Owned provider process {} is unverifiable; task {} keeps its worktree: {}",
                    recovery.launchId(), owner.orElseThrow(), recovery.detail());
            blockedTasks.putIfAbsent(owner.orElseThrow(), recovery.launchId());
        }

        maintenance.settleAfterProcessRecovery(
                ImmutableMap.copyOf(blockedTasks),
                ImmutableSet.copyOf(resolved),
                attributable);
        resolved.forEach(supervisor::acknowledge);
        return attributable;
    }

    /**
     * Resolves the one live task an unverifiable launch can be pinned to.
     *
     * <p>The owner is read from the launch marker when it survived and from the durable launch row
     * when it did not, which is the case that most needs attributing. An App Server launch names a
     * task directly; a Claude launch names a durable turn whose task is resolved here. A task that
     * is gone or already terminal cannot hold a claim, so those fall back to blocking everything.
     *
     * @param recovery what the supervisor reported
     * @param launch the durable row for that launch, when it still exists
     * @return the task to block, or empty when nothing can be
     */
    private Optional<TaskId> blockableTask(OwnedProcessRecovery recovery, AgentProcessLaunch launch)
    {
        Optional<TaskId> taskId = recovery.owner()
                .flatMap(this::ownerTask)
                .or(() -> Optional.ofNullable(launch).flatMap(this::ownerTask));
        return taskId.flatMap(taskStore::find)
                .filter(task -> !task.state().terminal())
                .map(AgentTask::id);
    }

    private Optional<TaskId> ownerTask(ProcessOwner owner)
    {
        return switch (owner.kind()) {
            case APP_SERVER -> parseTaskId(owner.id());
            case CLAUDE_TURN -> parseTurnTask(owner.id());
            case TASK_EXEC_SERVER, CLI_PROBE -> Optional.empty();
        };
    }

    private Optional<TaskId> ownerTask(AgentProcessLaunch launch)
    {
        return switch (launch.ownerKind()) {
            case APP_SERVER -> parseTaskId(launch.ownerId());
            case CLAUDE_TURN -> parseTurnTask(launch.ownerId());
            case TASK_EXEC_SERVER, CLI_PROBE -> Optional.empty();
        };
    }

    private Optional<TaskId> parseTaskId(String ownerId)
    {
        try {
            return Optional.of(TaskId.valueOf(ownerId));
        }
        catch (IllegalArgumentException notATask) {
            // An App Server owner that is not a task id belongs to something without a worktree,
            // such as the model catalog probe.
            return Optional.empty();
        }
    }

    private Optional<TaskId> parseTurnTask(String ownerId)
    {
        try {
            return turnStore.find(TurnId.valueOf(ownerId)).map(turn -> turn.taskId());
        }
        catch (IllegalArgumentException notATurn) {
            return Optional.empty();
        }
    }

    private void drainPendingEvents()
    {
        while (true) {
            var pending = outboxStore.listPending(OUTBOX_BATCH_SIZE);
            if (pending.isEmpty()) {
                return;
            }
            pending.forEach(entry -> eventService.drain(entry.eventId()));
        }
    }
}

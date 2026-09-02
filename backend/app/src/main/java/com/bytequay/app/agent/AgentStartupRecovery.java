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
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentProcessLaunch;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.LaunchOwnerKind;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.impl.AgentMaintenance;
import com.bytequay.agent.core.store.AgentEventOutboxStore;
import com.bytequay.agent.core.store.AgentProcessLaunchStore;
import com.bytequay.agent.core.store.AgentTaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    private final Clock clock;

    public AgentStartupRecovery(
            AgentEventOutboxStore outboxStore,
            AgentEventService eventService,
            AgentMaintenance maintenance,
            ProcessSupervisor supervisor,
            AgentServingGate servingGate,
            AgentProcessLaunchStore launchStore,
            AgentTaskStore taskStore,
            Clock clock)
    {
        this.outboxStore = requireNonNull(outboxStore, "outboxStore is null");
        this.eventService = requireNonNull(eventService, "eventService is null");
        this.maintenance = requireNonNull(maintenance, "maintenance is null");
        this.supervisor = requireNonNull(supervisor, "supervisor is null");
        this.servingGate = requireNonNull(servingGate, "servingGate is null");
        this.launchStore = requireNonNull(launchStore, "launchStore is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public void afterSingletonsInstantiated()
    {
        try {
            drainPendingEvents();
            maintenance.recover();
            drainPendingEvents();
            if (!recoverOwnedProcesses()) {
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
     * launch still needs.
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

        blockedTasks.forEach(this::holdRecoveryClaim);
        resolved.forEach(supervisor::acknowledge);
        return attributable;
    }

    /**
     * Resolves the one live task an unverifiable launch can be pinned to.
     *
     * <p>The owner is read from the launch marker when it survived and from the durable launch row
     * when it did not, which is the case that most needs attributing. Only an App Server launch
     * names a task; a Claude turn names a turn and a CLI probe names neither. A task that is gone
     * or already terminal cannot hold a claim, so those fall back to blocking everything.
     *
     * @param recovery what the supervisor reported
     * @param launch the durable row for that launch, when it still exists
     * @return the task to block, or empty when nothing can be
     */
    private Optional<TaskId> blockableTask(OwnedProcessRecovery recovery, AgentProcessLaunch launch)
    {
        boolean appServer = recovery.owner()
                .map(owner -> owner.kind() == ProcessOwner.Kind.APP_SERVER)
                .orElseGet(() -> launch != null && launch.ownerKind() == LaunchOwnerKind.APP_SERVER);
        if (!appServer) {
            return Optional.empty();
        }
        String ownerId = recovery.owner()
                .map(ProcessOwner::id)
                .orElseGet(() -> launch == null ? null : launch.ownerId());
        if (ownerId == null) {
            return Optional.empty();
        }
        TaskId taskId;
        try {
            taskId = TaskId.valueOf(ownerId);
        }
        catch (IllegalArgumentException notATask) {
            // An App Server owner that is not a task id belongs to something without a worktree,
            // such as the model catalog probe.
            return Optional.empty();
        }
        return taskStore.find(taskId)
                .filter(task -> !task.state().terminal())
                .map(AgentTask::id);
    }

    private void holdRecoveryClaim(TaskId taskId, String launchId)
    {
        AgentTask task = taskStore.find(taskId).orElseThrow();
        if (task.worktreeMutationClaim().isPresent()) {
            // Already blocked by a turn or completion that did not finish; one claim is enough.
            return;
        }
        taskStore.update(task.claimWorktreeMutation(
                WorktreeMutationClaim.recovery(launchId, Instant.now(clock)),
                Instant.now(clock)));
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

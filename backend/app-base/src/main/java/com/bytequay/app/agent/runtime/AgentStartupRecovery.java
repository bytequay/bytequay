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
package com.bytequay.app.agent.runtime;

import com.bytequay.agent.cli.process.OwnedProcessRecovery;
import com.bytequay.agent.cli.process.ProcessOwner;
import com.bytequay.agent.cli.process.ProcessSupervisor;
import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentProcessLaunch;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.InvalidAgentRequestException;
import com.bytequay.agent.core.LaunchOwnerKind;
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
import java.util.Set;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Component
public final class AgentStartupRecovery
        implements SmartInitializingSingleton
{
    private static final Logger log = LoggerFactory.getLogger(AgentStartupRecovery.class);
    private static final Set<String> CONTROL_APP_SERVER_OWNERS = ImmutableSet.of("codex-model-catalog", "codex-usage");
    private static final int OUTBOX_BATCH_SIZE = 100;

    private final AgentEventOutboxStore outboxStore;
    private final AgentEventService eventService;
    private final AgentMaintenance maintenance;
    private final ProcessSupervisor supervisor;
    private final AgentServingGate servingGate;
    private final AgentProcessLaunchStore launchStore;
    private final AgentTaskStore taskStore;
    private final AgentTurnStore turnStore;
    private final AgentTurnService turns;

    public AgentStartupRecovery(
            AgentEventOutboxStore outboxStore,
            AgentEventService eventService,
            AgentMaintenance maintenance,
            ProcessSupervisor supervisor,
            AgentServingGate servingGate,
            AgentProcessLaunchStore launchStore,
            AgentTaskStore taskStore,
            AgentTurnStore turnStore,
            AgentTurnService turns)
    {
        this.outboxStore = requireNonNull(outboxStore, "outboxStore is null");
        this.eventService = requireNonNull(eventService, "eventService is null");
        this.maintenance = requireNonNull(maintenance, "maintenance is null");
        this.supervisor = requireNonNull(supervisor, "supervisor is null");
        this.servingGate = requireNonNull(servingGate, "servingGate is null");
        this.launchStore = requireNonNull(launchStore, "launchStore is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.turnStore = requireNonNull(turnStore, "turnStore is null");
        this.turns = requireNonNull(turns, "turns is null");
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
                log.error("Agent operations remain unavailable: an owned provider process could not be " + "verified stopped, so a worktree may still have a live writer");
                return;
            }
            taskStore.listLive().stream()
                    .filter(AgentTask::developmentContinuationUnfinished)
                    .map(AgentTask::id)
                    .forEach(taskId -> {
                        try {
                            turns.failLostDevelopmentContinuation(taskId);
                        }
                        catch (RuntimeException failure) {
                            // The retained parent marker blocks its writers while leaving explicit Stop available.
                            log.error("Lost development task {} could not stop its owned work; its task remains blocked", taskId, failure);
                        }
                    });
            drainPendingEvents();
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
        AgentTask task = taskStore.require(taskId);
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

        AgentTask settled;
        try {
            settled = stopRecoveredProvider(task, claim);
        }
        catch (RuntimeException sourceFailure) {
            if (task.developmentContinuationUnfinished()) {
                try {
                    turns.failLostDevelopmentContinuation(taskId);
                }
                catch (RuntimeException childFailure) {
                    if (sourceFailure != childFailure) {
                        sourceFailure.addSuppressed(childFailure);
                    }
                }
            }
            throw sourceFailure;
        }
        return settled.developmentContinuationUnfinished() ? turns.failLostDevelopmentContinuation(taskId) : settled;
    }

    /**
     * Deletes a task only once no process an earlier backend started for it can still be running.
     *
     * <p>Deleting the task removes the durable facts that attribute such a process to it, and a process that names no task
     * blocks every agent operation at the next startup. So an unverifiable launch refuses the delete, and verified ones are
     * acknowledged only after the delete has committed. This backend's own launches are left to the delete, which stops them.
     *
     * @param taskId task to delete
     * @param delete the deletion itself
     */
    public void deleteAfterVerifiedStop(TaskId taskId, Runnable delete)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(delete, "delete is null");
        List<OwnedProcessRecovery> recoveries = verifiedRecoveredProcesses(taskId);
        delete.run();
        recoveries.forEach(recovery -> supervisor.acknowledge(recovery.launchId()));
    }

    /** Verifies durable launches before cleanup, including failed tasks whose recovery claim was cleared. */
    public void requireRecoveredRuntimeStopped(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");
        verifiedRecoveredProcesses(taskId).forEach(recovery -> supervisor.acknowledge(recovery.launchId()));
    }

    private List<OwnedProcessRecovery> verifiedRecoveredProcesses(TaskId taskId)
    {
        ImmutableSet.Builder<String> launchIds = ImmutableSet.builder();
        taskStore.find(taskId).flatMap(AgentTask::worktreeMutationClaim)
                .filter(claim -> claim.kind() == WorktreeMutationClaim.Kind.RECOVERY)
                .ifPresent(claim -> launchIds.add(claim.ownerId()));
        launchStore.list().stream()
                .filter(launch -> ownerTask(launch).filter(taskId::equals).isPresent())
                .map(AgentProcessLaunch::launchId)
                .forEach(launchIds::add);
        List<OwnedProcessRecovery> recoveries = launchIds.build().stream()
                .filter(launchId -> !supervisor.launchedHere(launchId))
                .map(supervisor::recoverOwnedProcess)
                .collect(toImmutableList());
        recoveries.stream()
                .filter(recovery -> recovery.outcome() == OwnedProcessRecovery.Outcome.UNVERIFIABLE)
                .findFirst()
                .ifPresent(unresolved -> {
                    throw new AgentConflictException("Task " + taskId + " cannot remove its runtime while its provider process " + unresolved.launchId()
                            + " from before the restart is not verified stopped (" + unresolved.detail() + ")");
                });
        return recoveries;
    }

    private AgentTask stopRecoveredProvider(AgentTask task, WorktreeMutationClaim claim)
    {
        TaskId taskId = task.id();
        Map<String, AgentProcessLaunch> launches = new LinkedHashMap<>();
        launchStore.list().forEach(launch -> launches.put(launch.launchId(), launch));
        ImmutableSet.Builder<String> launchIds = ImmutableSet.builder();
        launchIds.add(claim.ownerId());
        // A launch of this backend, such as the task's open IDE, is live work its own owner stops; it is not a leftover.
        launches.values().stream()
                .filter(launch -> ownerTask(launch).filter(taskId::equals).isPresent())
                .map(AgentProcessLaunch::launchId)
                .filter(launchId -> !supervisor.launchedHere(launchId))
                .forEach(launchIds::add);
        List<OwnedProcessRecovery> recoveries = launchIds.build().stream()
                .map(supervisor::recoverOwnedProcess)
                .collect(toImmutableList());
        for (OwnedProcessRecovery recovery : recoveries) {
            AgentProcessLaunch launch = launches.get(recovery.launchId());
            Optional<TaskId> owner = recovery.owner().flatMap(this::ownerTask)
                    .or(() -> Optional.ofNullable(launch).flatMap(this::ownerTask));
            // An already-verified claimed receipt may outlive its launch row and marker owner.
            boolean ownedByTask = owner.filter(taskId::equals).isPresent()
                    || recovery.owner().isEmpty() && launch == null && claim.ownerId().equals(recovery.launchId());
            if (!ownerFactsAgree(recovery.owner(), launch) || !ownedByTask) {
                throw new AgentConflictException("Provider process " + recovery.launchId() + " has conflicting recovery ownership for task " + taskId);
            }
        }
        Optional<OwnedProcessRecovery> blocking = recoveries.stream()
                .filter(recovery -> recovery.outcome() == OwnedProcessRecovery.Outcome.UNVERIFIABLE)
                .findFirst();
        AgentTask settled = maintenance.settleRecoveryClaim(taskId, task.version(), claim.ownerId(), blocking.map(OwnedProcessRecovery::launchId));
        recoveries.stream()
                .filter(recovery -> recovery.outcome() != OwnedProcessRecovery.Outcome.UNVERIFIABLE)
                .map(OwnedProcessRecovery::launchId)
                .forEach(supervisor::acknowledge);
        if (blocking.isPresent()) {
            OwnedProcessRecovery unresolved = blocking.orElseThrow();
            throw new AgentUnavailableException("Provider process " + unresolved.launchId() + " could not be verified stopped: " + unresolved.detail());
        }
        return settled;
    }

    /**
     * Stops provider process trees left by an earlier backend instance.
     *
     * <p>An unverifiable tree that can be attributed to one live task holds that task's worktree
     * mutation claim, so only that task is blocked. One whose task the user has since failed
     * blocks nothing more than that task did. One that cannot be attributed blocks every
     * agent operation, because something may still be writing somewhere nobody can name. The only
     * exception is a launch durably identified as non-worktree control work or the model-catalog or
     * usage App Server: none receives a task worktree, so its retained receipt cannot represent a stray writer.
     * An unverifiable launch marker is never acknowledged, so the evidence survives a restart. A
     * control-only launch whose marker is gone is dropped, because nothing is left to verify.
     *
     * <p>Classification runs before any write. A task can leak more than one launch and can hold
     * only one claim, so deciding per launch as it is read would release a block that another
     * launch still needs. Once every launch is classified, the turns that were mid-flight at the
     * crash are settled by that verdict: a verified stop releases the turn's worktree, an
     * unverifiable one reserves it until a later startup can verify it.
     *
     * @return true when every unverifiable launch is task-attributable or known not to own a task worktree
     */
    private boolean recoverOwnedProcesses()
    {
        // Snapshotted first: acknowledging a launch deletes its row, and an unverifiable launch
        // whose marker is missing can only be attributed through that row.
        Map<String, AgentProcessLaunch> launches = new HashMap<>();
        launchStore.list().forEach(launch -> launches.put(launch.launchId(), launch));

        Map<TaskId, String> blockedTasks = new LinkedHashMap<>();
        List<String> resolved = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        boolean attributable = true;
        for (OwnedProcessRecovery recovery : supervisor.recoverOwnedProcesses()) {
            AgentProcessLaunch launch = launches.get(recovery.launchId());
            if (!ownerFactsAgree(recovery.owner(), launch)) {
                log.error("Owned provider process {} has conflicting marker and launch-row owners, "
                                + "so agent operations remain unavailable: marker={}, row={}",
                        recovery.launchId(),
                        recovery.owner().orElse(null), launch);
                attributable = false;
                continue;
            }
            if (recovery.outcome() != OwnedProcessRecovery.Outcome.UNVERIFIABLE) {
                log.info("Owned provider process {} recovered as {}: {}", recovery.launchId(), recovery.outcome(), recovery.detail());
                resolved.add(recovery.launchId());
                continue;
            }
            if (knownControlOnly(recovery.owner(), launch) && recovery.owner().isEmpty()) {
                // Without a marker there is no process identity left to check, so no later startup
                // could ever resolve it, and the launch never had a task worktree to protect.
                log.warn("Owned control-only provider process {} has no usable launch marker; its launch record is dropped: {}", recovery.launchId(), recovery.detail());
                dropped.add(recovery.launchId());
                continue;
            }
            if (knownControlOnly(recovery.owner(), launch)) {
                log.warn("Owned control-only provider process {} is unverifiable; its recovery evidence "
                                + "is retained without blocking task worktrees: {}",
                        recovery.launchId(), recovery.detail());
                continue;
            }
            Optional<TaskId> owner = blockableTask(recovery, launch);
            if (owner.isEmpty() && namesEndedTask(recovery, launch)) {
                // Failing a blocked task is how the user gives it up. The launch could only ever have reserved that
                // task's worktree, so it keeps its evidence for a later check without blocking every other task.
                log.warn("Owned provider process {} is unverifiable, but the task it belongs to has ended; its recovery evidence is retained: {}",
                        recovery.launchId(), recovery.detail());
                continue;
            }
            if (owner.isEmpty()) {
                log.error("Owned provider process {} owned by {} is unverifiable and belongs to no "
                                + "live task, so agent operations remain unavailable: {}",
                        recovery.launchId(), recovery.owner().orElse(null), recovery.detail());
                attributable = false;
                continue;
            }
            log.error("Owned provider process {} is unverifiable; task {} keeps its worktree: {}", recovery.launchId(), owner.orElseThrow(), recovery.detail());
            blockedTasks.putIfAbsent(owner.orElseThrow(), recovery.launchId());
        }

        maintenance.settleAfterProcessRecovery(ImmutableMap.copyOf(blockedTasks), ImmutableSet.copyOf(resolved), attributable);
        resolved.forEach(supervisor::acknowledge);
        dropped.forEach(supervisor::acknowledge);
        return attributable;
    }

    private static boolean ownerFactsAgree(Optional<ProcessOwner> markerOwner, AgentProcessLaunch launch)
    {
        return markerOwner
                .map(owner -> launch == null
                        || owner.kind().name().equals(launch.ownerKind().name()) && owner.id().equals(launch.ownerId()))
                .orElse(true);
    }

    private static boolean knownControlOnly(Optional<ProcessOwner> markerOwner, AgentProcessLaunch launch)
    {
        return markerOwner
                .map(AgentStartupRecovery::controlOnly)
                .orElseGet(() -> launch != null && controlOnly(launch));
    }

    private static boolean controlOnly(ProcessOwner owner)
    {
        return owner.kind() == ProcessOwner.Kind.CLI_PROBE
                || owner.kind() == ProcessOwner.Kind.APP_SERVER && CONTROL_APP_SERVER_OWNERS.contains(owner.id());
    }

    private static boolean controlOnly(AgentProcessLaunch launch)
    {
        return launch.ownerKind() == LaunchOwnerKind.CLI_PROBE
                || launch.ownerKind() == LaunchOwnerKind.APP_SERVER && CONTROL_APP_SERVER_OWNERS.contains(launch.ownerId());
    }

    /**
     * Resolves the one live task an unverifiable launch can be pinned to.
     *
     * <p>The owner is read from the launch marker when it survived and from the durable launch row
     * when it did not, which is the case that most needs attributing. An App Server launch names a
     * task directly; a Claude launch names a durable turn whose task is resolved here. A task that
     * is gone or already terminal cannot hold a claim; {@link #namesEndedTask} decides those.
     *
     * @param recovery what the supervisor reported
     * @param launch the durable row for that launch, when it still exists
     * @return the task to block, or empty when nothing can be
     */
    private Optional<TaskId> blockableTask(OwnedProcessRecovery recovery, AgentProcessLaunch launch)
    {
        return ownerTask(recovery, launch).flatMap(taskStore::find)
                .filter(task -> !task.state().terminal())
                .map(AgentTask::id);
    }

    /** Whether a launch names a task that still exists and has ended; a missing task is not known to have been given up. */
    private boolean namesEndedTask(OwnedProcessRecovery recovery, AgentProcessLaunch launch)
    {
        return ownerTask(recovery, launch).flatMap(taskStore::find).filter(task -> task.state().terminal()).isPresent();
    }

    private Optional<TaskId> ownerTask(OwnedProcessRecovery recovery, AgentProcessLaunch launch)
    {
        return recovery.owner()
                .flatMap(this::ownerTask)
                .or(() -> Optional.ofNullable(launch).flatMap(this::ownerTask));
    }

    private Optional<TaskId> ownerTask(ProcessOwner owner)
    {
        return switch (owner.kind()) {
            case APP_SERVER, DISCUSSION, IDE -> parseTaskId(owner.id());
            case CLAUDE_TURN -> parseTurnTask(owner.id());
            case LOCAL_RUN -> parseRunTask(owner.id());
            case CLI_PROBE -> Optional.empty();
        };
    }

    private Optional<TaskId> ownerTask(AgentProcessLaunch launch)
    {
        return switch (launch.ownerKind()) {
            case APP_SERVER, DISCUSSION, IDE -> parseTaskId(launch.ownerId());
            case CLAUDE_TURN -> parseTurnTask(launch.ownerId());
            case LOCAL_RUN -> parseRunTask(launch.ownerId());
            case CLI_PROBE -> Optional.empty();
        };
    }

    private Optional<TaskId> parseTaskId(String ownerId)
    {
        try {
            return Optional.of(TaskId.valueOf(ownerId));
        }
        catch (IllegalArgumentException notATask) {
            // Recognized non-task App Server owners are classified before task attribution.
            // Every other malformed owner stays unattributable and therefore fails closed.
            return Optional.empty();
        }
    }

    /** A local run launch is owned by {@code <taskId>/<runId>}. */
    private Optional<TaskId> parseRunTask(String ownerId)
    {
        int separator = ownerId.indexOf('/');
        return separator < 0 ? Optional.empty() : parseTaskId(ownerId.substring(0, separator));
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

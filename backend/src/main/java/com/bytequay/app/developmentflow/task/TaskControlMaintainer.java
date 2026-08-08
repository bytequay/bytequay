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
package com.bytequay.app.developmentflow.task;

import com.bytequay.app.developmentflow.execution.DispatchTicketControl;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.stage.CancellationToCleanupHandoff;
import com.bytequay.app.developmentflow.stage.StageKind;
import com.bytequay.app.developmentflow.task.persistence.SqliteTaskControlRuntimeStore;
import com.bytequay.app.developmentflow.task.persistence.SqliteTaskControlRuntimeStore.CancellationTarget;
import com.bytequay.app.developmentflow.task.persistence.SqliteTaskControlRuntimeStore.ControlContext;
import com.bytequay.app.developmentflow.task.persistence.SqliteTaskControlRuntimeStore.ResumeHandoff;
import com.bytequay.app.developmentflow.task.persistence.SqliteTaskControlRuntimeStore.TerminalAcceptance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

/** Restart-safe producer for Task control proofs and Cleanup startup. */
@Component
public final class TaskControlMaintainer
        implements ExecutionPorts.MaintenanceWork
{
    private static final String ACTOR = "task-control-maintainer";

    private final SqliteTaskControlRuntimeStore store;
    private final TaskControlHandoff controls;
    private final Map<StageKind, TaskResumeOwner> resumeOwners;
    private final Map<StageKind, CancellationToCleanupHandoff> cancellations;
    private final Consumer<String> cancellationPort;

    @Autowired
    public TaskControlMaintainer(
            SqliteTaskControlRuntimeStore store,
            TaskControlHandoff controls,
            List<TaskResumeOwner> resumeOwners,
            List<CancellationToCleanupHandoff> cancellations,
            DispatchTicketControl tickets)
    {
        this(store, controls, resumeOwners, cancellations,
                tickets::requestCancel);
    }

    public TaskControlMaintainer(
            SqliteTaskControlRuntimeStore store,
            TaskControlHandoff controls,
            List<CancellationToCleanupHandoff> cancellations,
            Consumer<String> cancellationPort)
    {
        this(store, controls, List.of(), cancellations, cancellationPort);
    }

    public TaskControlMaintainer(
            SqliteTaskControlRuntimeStore store,
            TaskControlHandoff controls,
            List<TaskResumeOwner> resumeOwners,
            List<CancellationToCleanupHandoff> cancellations,
            Consumer<String> cancellationPort)
    {
        this.store = requireNonNull(store, "store is null");
        this.controls = requireNonNull(controls, "controls is null");
        this.cancellationPort = requireNonNull(
                cancellationPort, "cancellationPort is null");
        EnumMap<StageKind, TaskResumeOwner> resumeByKind =
                new EnumMap<>(StageKind.class);
        for (TaskResumeOwner owner : List.copyOf(
                requireNonNull(resumeOwners, "resumeOwners is null"))) {
            if (owner.kind() == StageKind.CLEANUP) {
                throw new IllegalArgumentException(
                        "Cleanup Stage cannot own a Task resume");
            }
            if (resumeByKind.put(owner.kind(), owner) != null) {
                throw new IllegalArgumentException(
                        "duplicate resume owner: " + owner.kind());
            }
        }
        this.resumeOwners = Map.copyOf(resumeByKind);
        EnumMap<StageKind, CancellationToCleanupHandoff> byKind =
                new EnumMap<>(StageKind.class);
        for (CancellationToCleanupHandoff handoff : List.copyOf(
                requireNonNull(cancellations, "cancellations is null"))) {
            if (byKind.put(handoff.sourceKind(), handoff) != null) {
                throw new IllegalArgumentException(
                        "duplicate cancellation owner: " + handoff.sourceKind());
            }
        }
        this.cancellations = Map.copyOf(byKind);
    }

    @Override
    public void maintain(Instant now)
    {
        requireNonNull(now, "now is null");
        RuntimeException first = null;
        for (ControlContext context : store.pending()) {
            try {
                maintain(context, now);
            }
            catch (RuntimeException failure) {
                if (first == null) {
                    first = failure;
                }
                else {
                    first.addSuppressed(failure);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }

    private void maintain(ControlContext context, Instant now)
    {
        switch (context.lifecycle()) {
            case PAUSING -> pause(context, now);
            case RESUMING -> resume(context, now);
            case ARCHIVING -> archive(context, now);
            case CANCELING -> cancel(context, now);
            case CLEANING -> cleanup(context, now);
            default -> throw new IllegalStateException(
                    "Task is not awaiting control maintenance");
        }
    }

    private void pause(ControlContext context, Instant now)
    {
        cancelTickets(context, true);
        String barrier = store.ensureBarrier(
                context, TaskManager.QuiescenceReason.PAUSE, now);
        if (!store.satisfyBarrier(context.taskId(), barrier, now)) {
            return;
        }
        TaskManager.PauseEvidence evidence = store.ensurePauseEvidence(
                context, barrier, now);
        controls.completePause(new TaskManager.PauseCompletionCommand(
                command("complete-pause", context, evidence.barrierId()),
                evidence.barrierId(), evidence.stageId(),
                evidence.stageGeneration(), evidence.restoreCheckpoint(),
                evidence.stopEvidenceDigest()));
    }

    private void resume(ControlContext context, Instant now)
    {
        TaskManager.ResumeEvidence evidence = store.ensureResumeEvidence(context, now);
        ResumeHandoff handoff = store.ensureResumeHandoff(context, evidence, now);
        if (!handoff.accepted()) {
            TaskResumeOwner owner = resumeOwners.get(context.stageKind());
            if (owner == null) {
                return;
            }
            handoff = store.acceptResumeHandoff(
                    handoff, owner.accept(handoff.request()), now);
        }
        controls.completeResume(new TaskManager.ResumeCompletionCommand(
                command("complete-resume", context, evidence.reconciliationId()),
                evidence.reconciliationId(), evidence.stageId(),
                evidence.stageGeneration(), evidence.restoreCheckpoint(),
                evidence.reconciliationDigest()));
    }

    private void archive(ControlContext context, Instant now)
    {
        TaskManager.ArchiveEvidence evidence = store.ensureArchiveEvidence(context, now);
        controls.completeArchive(new TaskManager.ArchiveCompletionCommand(
                command("complete-archive", context, evidence.archiveEvidenceId()),
                evidence.archiveEvidenceId(), evidence.stageId(),
                evidence.stageGeneration(), evidence.livenessDigest()));
    }

    private void cancel(ControlContext context, Instant now)
    {
        TerminalAcceptance acceptance = store.ensureTerminalAcceptance(context, now);
        cancelTickets(context, true);
        String barrier = store.ensureBarrier(
                context, TaskManager.QuiescenceReason.CANCEL, now);
        if (!store.satisfyBarrier(context.taskId(), barrier, now)) {
            return;
        }
        CancellationTarget cleanup = store.cancellationTarget(context, acceptance);
        CancellationToCleanupHandoff handoff = cancellations.get(context.stageKind());
        if (handoff == null) {
            throw new IllegalStateException(
                    "No cancellation handoff for " + context.stageKind());
        }
        handoff.accept(new TaskManager.CancellationCommand(
                id("open-canceled-cleanup", acceptance.id()), ACTOR,
                context.taskId(), context.taskEpoch(), context.taskVersion(),
                context.stageId(), context.stageGeneration(), context.stageVersion(),
                barrier, cleanup.stageId(), cleanup.generation()));
    }

    private void cleanup(ControlContext context, Instant now)
    {
        if (context.stageKind() != StageKind.CLEANUP) {
            throw new IllegalStateException("CLEANING Task lacks a Cleanup Stage");
        }
        TerminalAcceptance acceptance = store.ensureTerminalAcceptance(context, now);
        store.ensureCleanupStage(context, acceptance, now);
        cancelTickets(context, false);
        String barrier = store.cleanupBarrier(context).orElseGet(() ->
                store.ensureBarrier(
                        context, TaskManager.QuiescenceReason.CLEANUP, now));
        if (!store.satisfyBarrier(context.taskId(), barrier, now)) {
            return;
        }
        store.ensureCleanupGraph(context, acceptance, barrier, now);
    }

    private void cancelTickets(ControlContext context, boolean includeCleanup)
    {
        store.liveTicketIds(context.taskId(), includeCleanup)
                .forEach(cancellationPort);
    }

    private static TaskManager.Command command(
            String namespace, ControlContext context, String proofId)
    {
        return new TaskManager.Command(
                id(namespace, context.taskId(), proofId), ACTOR,
                context.taskId(), context.taskEpoch(), context.taskVersion());
    }

    private static String id(String namespace, Object... parts)
    {
        StringBuilder value = new StringBuilder(namespace);
        for (Object part : parts) {
            value.append('\u001f').append(part);
        }
        return UUID.nameUUIDFromBytes(
                value.toString().getBytes(StandardCharsets.UTF_8)).toString();
    }
}

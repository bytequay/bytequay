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
package com.bytequay.app.repository.sqlite;

import com.bytequay.app.domain.Actor;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskFile;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskPhaseEvent;
import com.bytequay.app.domain.TaskRecoveryRequest;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.TaskStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static com.bytequay.app.repository.sqlite.SqlitePageRequests.firstPage;
import static java.util.Objects.requireNonNull;

@Component
class SqliteTaskStore
        implements TaskStore
{
    private final TaskJpaRepository tasks;
    private final TaskStatusEventJpaRepository statusEvents;
    private final TaskFileJpaRepository files;
    private final TaskPhaseEventJpaRepository phaseEvents;
    private final ObjectMapper objectMapper;

    SqliteTaskStore(
            TaskJpaRepository tasks,
            TaskStatusEventJpaRepository statusEvents,
            TaskFileJpaRepository files,
            TaskPhaseEventJpaRepository phaseEvents,
            ObjectMapper objectMapper)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.statusEvents = requireNonNull(statusEvents, "statusEvents is null");
        this.files = requireNonNull(files, "files is null");
        this.phaseEvents = requireNonNull(phaseEvents, "phaseEvents is null");
        this.objectMapper = requireNonNull(objectMapper, "objectMapper is null");
    }

    @Override
    @Transactional
    public void saveTask(Task task)
    {
        TaskEntity entity = tasks.findById(task.id()).orElseGet(TaskEntity::new);
        boolean creating = entity.getId() == null;
        if (!creating && !entity.getOrigin().equals(task.origin())) {
            throw new IllegalArgumentException(
                    "task origin is immutable: " + entity.getOrigin() + " -> " + task.origin());
        }
        entity.setId(task.id());
        entity.setThreadId(task.threadId());
        entity.setSeq(task.seq());
        entity.setStatus(task.status().name());
        entity.setBranchName(task.branchName());
        entity.setWorktreePath(task.worktreePath());
        entity.setBaseBranch(task.baseBranch());
        entity.setWorkingDir(task.workingDir());
        entity.setProcessPid(task.processPid());
        entity.setLogPath(task.logPath());
        entity.setPrNumber(task.prNumber());
        entity.setPrState(task.prState());
        entity.setCiState(task.ciState());
        entity.setTaskType(task.taskType());
        entity.setLinkedPrNumber(task.linkedPrNumber());
        entity.setLinkedIssueNumber(task.linkedIssueNumber());
        if (creating) {
            entity.setOrigin(task.origin());
        }
        entity.setCostUsdMilli(task.costUsdMilli());
        entity.setTokensIn(task.tokensIn());
        entity.setTokensOut(task.tokensOut());
        entity.setAgentSessionId(task.agentSessionId());
        entity.setName(task.name());
        entity.setRoleSkill(task.roleSkill());
        entity.setWorkModelJson(WorkModelJson.serialise(objectMapper, task.workModel()));
        entity.setCreatedAtMs(task.createdAt().toEpochMilli());
        entity.setEndedAtMs(Timestamps.epochMilli(task.endedAt()));
        entity.setErrorMessage(task.errorMessage());
        tasks.save(entity);
    }

    @Override
    @Transactional
    public boolean updateStatusIf(String taskId, TaskStatus expected, TaskStatus to)
    {
        return tasks.casStatus(taskId, expected.name(), to.name()) == 1;
    }

    @Override
    @Transactional
    public void appendStatusEvent(
            String taskId, TaskStatus from, TaskStatus to, Actor actor, String reason, Instant occurredAt)
    {
        TaskStatusEventEntity event = new TaskStatusEventEntity();
        event.setTaskId(taskId);
        event.setFromStatus(from.name());
        event.setToStatus(to.name());
        event.setActor(actor.name());
        event.setReason(reason);
        event.setOccurredAtMs(occurredAt.toEpochMilli());
        statusEvents.save(event);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> currentLivenessTurnId(String taskId)
    {
        return tasks.findById(taskId).map(TaskEntity::getCurrentLivenessTurnId);
    }

    @Override
    @Transactional
    public boolean setCurrentLivenessTurnIdIf(String taskId, String expected, String next)
    {
        if (expected == null) {
            return tasks.setLivenessPointerIfUnset(taskId, next) == 1;
        }
        return tasks.casLivenessPointer(taskId, expected, next) == 1;
    }

    @Override
    @Transactional
    public void checkpointPause(String taskId, TaskStatus pausedStatus)
    {
        mutate(taskId, entity -> entity.setPausedStatus(pausedStatus.name()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TaskStatus> pausedStatus(String taskId)
    {
        return tasks.findById(taskId)
                .map(TaskEntity::getPausedStatus)
                .map(TaskStatus::valueOf);
    }

    @Override
    @Transactional
    public void requestResume(String taskId, Instant at)
    {
        mutate(taskId, entity -> entity.setResumeRequestedAtMs(at.toEpochMilli()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> resumeRequestedAt(String taskId)
    {
        return tasks.findById(taskId)
                .map(TaskEntity::getResumeRequestedAtMs)
                .map(Instant::ofEpochMilli);
    }

    @Override
    @Transactional
    public void clearPauseCheckpoint(String taskId)
    {
        mutate(taskId, entity -> {
            entity.setPausedStatus(null);
            entity.setResumeRequestedAtMs(null);
        });
    }

    @Override
    @Transactional
    public void clearProcessPid(String taskId)
    {
        mutate(taskId, entity -> entity.setProcessPid(null));
    }

    @Override
    @Transactional
    public void checkpointRecovery(String taskId, TaskPhase recoveryPhase, String contextJson)
    {
        mutate(taskId, entity -> {
            entity.setRecoveryPhase(recoveryPhase.name());
            entity.setRecoveryContextJson(contextJson);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TaskPhase> recoveryPhase(String taskId)
    {
        return tasks.findById(taskId)
                .map(TaskEntity::getRecoveryPhase)
                .map(TaskPhase::valueOf);
    }

    @Override
    @Transactional
    public void recordRecoveryRequest(
            String taskId, String requestId, String kind, String payloadJson, Instant at)
    {
        mutate(taskId, entity -> {
            entity.setRecoveryRequestId(requestId);
            entity.setRecoveryRequestedKind(kind);
            entity.setRecoveryRequestPayloadJson(payloadJson);
            entity.setRecoveryRequestedAtMs(at.toEpochMilli());
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TaskRecoveryRequest> recoveryRequest(String taskId)
    {
        return tasks.findById(taskId)
                .filter(entity -> entity.getRecoveryRequestId() != null)
                .map(entity -> new TaskRecoveryRequest(
                        entity.getRecoveryRequestId(),
                        entity.getRecoveryRequestedKind(),
                        entity.getRecoveryRequestPayloadJson(),
                        Instant.ofEpochMilli(entity.getRecoveryRequestedAtMs())));
    }

    @Override
    @Transactional
    public void clearRecoveryState(String taskId)
    {
        mutate(taskId, entity -> {
            entity.setRecoveryPhase(null);
            entity.setRecoveryContextJson(null);
            entity.setRecoveryRequestId(null);
            entity.setRecoveryRequestedKind(null);
            entity.setRecoveryRequestPayloadJson(null);
            entity.setRecoveryRequestedAtMs(null);
        });
    }

    @Override
    @Transactional
    public void updateRuntimeFailure(String taskId, Instant endedAt, String errorMessage)
    {
        mutate(taskId, entity -> {
            entity.setEndedAtMs(Timestamps.epochMilli(endedAt));
            entity.setErrorMessage(errorMessage);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public List<Task> listByStatuses(Collection<TaskStatus> statuses, int limit)
    {
        List<String> names = statuses.stream().map(TaskStatus::name).toList();
        return tasks.findByStatusInOrderByCreatedAtMsAsc(names, PageRequest.of(0, limit)).stream()
                .map(this::toTask)
                .toList();
    }

    @Override
    public Optional<Task> findTaskById(String id)
    {
        return tasks.findById(id).map(this::toTask);
    }

    @Override
    public Optional<Task> findTaskByBranch(String branchName)
    {
        if (branchName == null || branchName.isBlank()) {
            return Optional.empty();
        }
        return tasks.findFirstByBranchNameOrderBySeqDesc(branchName).map(this::toTask);
    }

    /** Load a task entity, apply {@code mutator}, and save it — a no-op
     *  when the id is unknown. Backs the column-level updates that
     *  {@code saveTask} deliberately doesn't map (phase, links, pushed_at,
     *  …), so a later full-row save can't clobber them. Callers carry the
     *  {@code @Transactional} boundary. */
    private void mutate(String taskId, Consumer<TaskEntity> mutator)
    {
        tasks.findById(taskId).ifPresent(entity -> {
            mutator.accept(entity);
            tasks.save(entity);
        });
    }

    @Override
    @Transactional
    public void markPushed(String taskId, Instant pushedAt)
    {
        // Load-set-save: saveTask deliberately never maps pushed_at_ms, so
        // editing the live entity is the only path that writes it and no
        // later full-row save can clobber it.
        mutate(taskId, entity -> entity.setPushedAtMs(Timestamps.epochMilli(pushedAt)));
    }

    @Override
    @Transactional
    public void linkPullRequest(String taskId, int prNumber, String prState)
    {
        mutate(taskId, entity -> {
            entity.setPrNumber(prNumber);
            entity.setLinkedPrNumber(prNumber);
            if (prState != null && !prState.isBlank()) {
                entity.setPrState(prState);
            }
        });
    }

    @Override
    @Transactional
    public void updateCiState(String taskId, String ciState)
    {
        mutate(taskId, entity -> entity.setCiState(ciState));
    }

    @Override
    public List<Task> findByLinkedPrNumber(int prNumber)
    {
        return tasks.findByLinkedPrNumber(prNumber).stream()
                .map(this::toTask)
                .toList();
    }

    @Override
    @Transactional
    public void completeTask(String taskId, Instant endedAt)
    {
        mutate(taskId, entity -> {
            entity.setStatus(TaskStatus.COMPLETED.name());
            entity.setEndedAtMs(Timestamps.epochMilli(endedAt));
        });
    }

    @Override
    @Transactional
    public void cancelTask(String taskId, Instant endedAt)
    {
        mutate(taskId, entity -> {
            entity.setStatus(TaskStatus.CANCELED.name());
            entity.setEndedAtMs(Timestamps.epochMilli(endedAt));
        });
    }

    @Override
    @Transactional
    public void remoteCloseTask(String taskId, Instant endedAt)
    {
        mutate(taskId, entity -> {
            entity.setStatus(TaskStatus.REMOTE_CLOSED.name());
            entity.setEndedAtMs(Timestamps.epochMilli(endedAt));
        });
    }

    @Override
    @Transactional
    public void updatePhase(String taskId, TaskPhase phase)
    {
        mutate(taskId, entity -> entity.setPhase(phase.name()));
    }

    @Override
    @Transactional
    public void setOpeningPrompt(String taskId, String openingPrompt)
    {
        mutate(taskId, entity -> entity.setOpeningPrompt(openingPrompt));
    }

    @Override
    @Transactional
    public void appendPhaseEvent(
            String taskId, TaskPhase from, TaskPhase to, Instant at, String reason, Actor actor)
    {
        TaskPhaseEventEntity row = new TaskPhaseEventEntity();
        row.setTaskId(taskId);
        row.setFromPhase(from == null ? null : from.name());
        row.setToPhase(to.name());
        row.setTransitionedAtMs(at.toEpochMilli());
        row.setReason(reason);
        row.setActor(actor == null ? null : actor.name());
        phaseEvents.save(row);
    }

    @Override
    public List<TaskPhaseEvent> listPhaseEvents(String taskId)
    {
        return phaseEvents.findByTaskIdOrderByTransitionedAtMsAsc(taskId).stream()
                .map(SqliteTaskStore::toPhaseEvent)
                .toList();
    }

    @Override
    public int consecutiveAutoPushes(String taskId)
    {
        return tasks.findById(taskId).map(TaskEntity::getConsecutiveAutoPushes).orElse(0);
    }

    @Override
    @Transactional
    public void setConsecutiveAutoPushes(String taskId, int value)
    {
        mutate(taskId, entity -> entity.setConsecutiveAutoPushes(value));
    }

    @Override
    @Transactional
    public boolean markMergeNotificationSentIfUnset(String taskId, Instant at)
    {
        return tasks.setMergeNotificationSentAtIfNull(taskId, at.toEpochMilli()) == 1;
    }

    @Override
    @Transactional
    public void clearMergeNotificationSent(String taskId)
    {
        tasks.clearMergeNotificationSentAt(taskId);
    }

    @Override
    public Optional<Instant> mergeNotificationSentAt(String taskId)
    {
        return tasks.findById(taskId)
                .map(TaskEntity::getMergeNotificationSentAtMs)
                .map(Timestamps::instant);
    }

    @Override
    @Transactional
    public boolean markReadyGateSentIfUnset(String taskId, Instant at)
    {
        return tasks.setReadyGateSentAtIfNull(taskId, at.toEpochMilli()) == 1;
    }

    @Override
    @Transactional
    public void authorizeMerge(String taskId, Instant at)
    {
        tasks.authorizeMerge(taskId, at.toEpochMilli());
    }

    @Override
    @Transactional
    public void clearMergeAuthorization(String taskId)
    {
        tasks.clearMergeAuthorization(taskId);
    }

    @Override
    public boolean isMergeAuthorized(String taskId)
    {
        return tasks.findById(taskId)
                .map(TaskEntity::getMergeAuthorizedAtMs)
                .isPresent();
    }

    @Override
    public int mergeQueueRetries(String taskId)
    {
        return tasks.findById(taskId)
                .map(TaskEntity::getMergeQueueRetries)
                .orElse(0);
    }

    @Override
    @Transactional
    public void setMergeQueueRetries(String taskId, int retries)
    {
        tasks.setMergeQueueRetries(taskId, retries);
    }

    @Override
    @Transactional
    public void setPendingCompletionSummaryTurnId(String taskId, String turnId)
    {
        tasks.setPendingCompletionSummaryTurnId(taskId, turnId);
    }

    @Override
    @Transactional
    public void clearPendingCompletionSummaryTurnId(String taskId)
    {
        tasks.clearPendingCompletionSummaryTurnId(taskId);
    }

    @Override
    public Optional<String> pendingCompletionSummaryTurnId(String taskId)
    {
        return tasks.findById(taskId)
                .map(TaskEntity::getPendingCompletionSummaryTurnId);
    }

    @Override
    public Optional<Task> findTaskByPendingCompletionSummaryTurnId(String turnId)
    {
        return tasks.findByPendingCompletionSummaryTurnId(turnId)
                .map(this::toTask);
    }

    @Override
    public Optional<Task> findActiveTaskByPrRef(String prRef)
    {
        return tasks.findFirstByLinkedPrRefAndPhaseNot(prRef, TaskPhase.COMPLETED.name())
                .map(this::toTask);
    }

    @Override
    @Transactional
    public void linkTaskToPr(String taskId, String prRef)
    {
        mutate(taskId, entity -> entity.setLinkedPrRef(prRef));
    }

    @Override
    public List<Task> findTasksByPrRef(String prRef)
    {
        return tasks.findByLinkedPrRefOrderBySeqAsc(prRef).stream()
                .map(this::toTask)
                .toList();
    }

    private static TaskPhaseEvent toPhaseEvent(TaskPhaseEventEntity e)
    {
        return new TaskPhaseEvent(
                e.getId(),
                e.getTaskId(),
                e.getFromPhase() == null ? null : TaskPhase.valueOf(e.getFromPhase()),
                TaskPhase.valueOf(e.getToPhase()),
                Instant.ofEpochMilli(e.getTransitionedAtMs()),
                e.getReason(),
                e.getActor() == null ? null : Actor.valueOf(e.getActor()));
    }

    @Override
    @Transactional
    public void deleteTask(String id)
    {
        if (!tasks.existsById(id)) {
            return;
        }
        // task_files cascades via the FK ON DELETE CASCADE clause in the
        // migration; no manual delete required.
        tasks.deleteById(id);
    }

    @Override
    public List<Task> listTasksByThread(String threadId)
    {
        return tasks.findByThreadIdOrderBySeqAsc(threadId).stream()
                .map(this::toTask)
                .toList();
    }

    @Override
    public boolean hasActiveTask(String threadId)
    {
        return !activeTasksForThread(threadId).isEmpty();
    }

    @Override
    public boolean isAutoApprove(String taskId)
    {
        return tasks.findById(taskId).map(TaskEntity::isAutoApprove).orElse(false);
    }

    @Override
    @Transactional
    public void setAutoApprove(String taskId, boolean enabled)
    {
        mutate(taskId, entity -> entity.setAutoApprove(enabled));
    }

    @Override
    public boolean isAutoMerge(String taskId)
    {
        return tasks.findById(taskId).map(TaskEntity::isAutoMerge).orElse(false);
    }

    @Override
    @Transactional
    public void setAutoMerge(String taskId, boolean enabled)
    {
        mutate(taskId, entity -> entity.setAutoMerge(enabled));
    }

    @Override
    public int minApprovals(String taskId)
    {
        return tasks.findById(taskId).map(TaskEntity::getMinApprovals).orElse(0);
    }

    @Override
    @Transactional
    public void setMinApprovals(String taskId, int minApprovals)
    {
        mutate(taskId, entity -> entity.setMinApprovals(minApprovals));
    }

    /** Non-terminal tasks for a thread (runtime status not COMPLETED/ERRORED
     *  <em>and</em> dev-lifecycle phase not COMPLETED), latest seq first.
     *  The phase guard matters because a remote merge advances phase to
     *  COMPLETED without flipping the runtime status off IDLE — such a task
     *  is done, not active, and reading status alone would let it masquerade
     *  as an active task. */
    @Override
    public List<Task> activeTasksForThread(String threadId)
    {
        List<String> active = List.of(
                TaskStatus.PENDING.name(),
                TaskStatus.RUNNING.name(),
                TaskStatus.IDLE.name());
        return tasks.findByThreadIdAndStatusInOrderBySeqDesc(threadId, active)
                .stream()
                .map(this::toTask)
                .filter(t -> t.phase() != TaskPhase.COMPLETED)
                .toList();
    }

    @Override
    public Optional<Task> findLatestTaskForThread(String threadId)
    {
        return tasks.findFirstByThreadIdOrderBySeqDesc(threadId)
                .map(this::toTask);
    }

    @Override
    public Optional<Long> maxSeqForThread(String threadId)
    {
        return tasks.findFirstByThreadIdOrderBySeqDesc(threadId).map(TaskEntity::getSeq);
    }

    @Override
    public List<Task> listByStatus(TaskStatus status, int limit)
    {
        return tasks.findByStatusOrderByCreatedAtMsAsc(status.name(), firstPage(limit))
                .stream()
                .map(this::toTask)
                .toList();
    }

    @Override
    public List<Task> listWithLinkedPr(int limit)
    {
        return tasks.findByLinkedPrNumberIsNotNullOrderByCreatedAtMsDesc(firstPage(limit))
                .stream()
                .map(this::toTask)
                .toList();
    }

    @Override
    public List<Task> listByPhases(Collection<TaskPhase> phases, int limit)
    {
        if (phases.isEmpty()) {
            return List.of();
        }
        List<String> names = phases.stream().map(Enum::name).toList();
        return tasks.findByPhaseInOrderByCreatedAtMsDesc(names, firstPage(limit))
                .stream()
                .map(this::toTask)
                .toList();
    }

    @Override
    public List<Task> listByPhaseAndOrigin(TaskPhase phase, String origin)
    {
        return tasks.findByPhaseAndOriginOrderByCreatedAtMsAsc(phase.name(), origin).stream()
                .map(this::toTask)
                .toList();
    }

    @Override
    @Transactional
    public void recordFile(TaskFile file)
    {
        TaskFileEntity.TaskFileKey key =
                new TaskFileEntity.TaskFileKey(file.taskId(), file.path());
        TaskFileEntity entity = files.findById(key).orElseGet(TaskFileEntity::new);
        entity.setId(key);
        entity.setOperation(file.operation());
        entity.setCount(file.count());
        entity.setLinesAdded(file.linesAdded());
        entity.setLinesRemoved(file.linesRemoved());
        entity.setLastTouchedMs(file.lastTouchedAt().toEpochMilli());
        files.save(entity);
    }

    @Override
    public List<TaskFile> listFiles(String taskId)
    {
        return files.findByIdTaskIdOrderByLastTouchedMsDesc(taskId).stream()
                .map(SqliteTaskStore::toFile)
                .toList();
    }

    private Task toTask(TaskEntity e)
    {
        return new Task(
                e.getId(),
                e.getThreadId(),
                e.getSeq(),
                TaskStatus.valueOf(e.getStatus()),
                e.getBranchName(),
                e.getWorktreePath(),
                e.getBaseBranch(),
                e.getWorkingDir(),
                e.getProcessPid(),
                e.getLogPath(),
                e.getPrNumber(),
                e.getPrState(),
                e.getCiState(),
                e.getTaskType(),
                e.getLinkedPrNumber(),
                e.getLinkedIssueNumber(),
                e.getCostUsdMilli(),
                e.getTokensIn(),
                e.getTokensOut(),
                e.getAgentSessionId(),
                Instant.ofEpochMilli(e.getCreatedAtMs()),
                Timestamps.instant(e.getEndedAtMs()),
                e.getErrorMessage(),
                e.getName(),
                e.getRoleSkill(),
                WorkModelJson.deserialise(objectMapper, e.getWorkModelJson()),
                Timestamps.instant(e.getPushedAtMs()),
                parsePhase(e.getPhase()),
                e.getAgendaJson(),
                e.getConsecutiveAutoPushes(),
                e.getLinkedPrRef(),
                e.getOpeningPrompt(),
                e.getOrigin());
    }

    /** Tolerant parse: an unknown / null phase string falls back to
     *  IMPLEMENTING rather than breaking the task load. */
    private static TaskPhase parsePhase(String raw)
    {
        if (raw == null || raw.isBlank()) {
            return TaskPhase.IMPLEMENTING;
        }
        try {
            return TaskPhase.valueOf(raw);
        }
        catch (IllegalArgumentException e) {
            return TaskPhase.IMPLEMENTING;
        }
    }

    private static TaskFile toFile(TaskFileEntity e)
    {
        return new TaskFile(
                e.getId().getTaskId(),
                e.getId().getPath(),
                e.getOperation(),
                e.getCount(),
                e.getLinesAdded(),
                e.getLinesRemoved(),
                Instant.ofEpochMilli(e.getLastTouchedMs()));
    }
}

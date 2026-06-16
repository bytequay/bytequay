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
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.repository.TaskStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static com.bytequay.app.repository.sqlite.SqlitePageRequests.firstPage;
import static java.util.Objects.requireNonNull;

@Component
class SqliteTaskStore
        implements TaskStore
{
    private final TaskJpaRepository tasks;
    private final TaskFileJpaRepository files;
    private final TaskPhaseEventJpaRepository phaseEvents;
    private final ObjectMapper objectMapper;

    SqliteTaskStore(
            TaskJpaRepository tasks,
            TaskFileJpaRepository files,
            TaskPhaseEventJpaRepository phaseEvents,
            ObjectMapper objectMapper)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.files = requireNonNull(files, "files is null");
        this.phaseEvents = requireNonNull(phaseEvents, "phaseEvents is null");
        this.objectMapper = requireNonNull(objectMapper, "objectMapper is null");
    }

    @Override
    @Transactional
    public void saveTask(Task task)
    {
        TaskEntity entity = tasks.findById(task.id()).orElseGet(TaskEntity::new);
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
        entity.setCostUsdMilli(task.costUsdMilli());
        entity.setTokensIn(task.tokensIn());
        entity.setTokensOut(task.tokensOut());
        entity.setAgentSessionId(task.agentSessionId());
        entity.setName(task.name());
        entity.setRoleSkill(task.roleSkill());
        entity.setWorkModelJson(serialiseWorkModel(task.workModel()));
        entity.setCreatedAtMs(task.createdAt().toEpochMilli());
        entity.setEndedAtMs(task.endedAt() == null ? null : task.endedAt().toEpochMilli());
        entity.setErrorMessage(task.errorMessage());
        tasks.save(entity);
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

    @Override
    public boolean isAcceptEdits(String taskId)
    {
        return tasks.findById(taskId).map(TaskEntity::isAcceptEdits).orElse(false);
    }

    @Override
    @Transactional
    public void setAcceptEdits(String taskId, boolean enabled)
    {
        // Load-set-save rather than saveTask(Task): saveTask maps from
        // the domain record, which deliberately has no acceptEdits field,
        // so round-tripping through it would never carry the flag. Editing
        // the live entity keeps every other column untouched.
        TaskEntity entity = tasks.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("no task " + taskId));
        entity.setAcceptEdits(enabled);
        tasks.save(entity);
    }

    @Override
    @Transactional
    public void markPushed(String taskId, Instant pushedAt)
    {
        // Load-set-save like setAcceptEdits: saveTask deliberately never
        // maps pushed_at_ms, so editing the live entity is the only path
        // that writes it and no later full-row save can clobber it.
        tasks.findById(taskId).ifPresent(entity -> {
            entity.setPushedAtMs(pushedAt == null ? null : pushedAt.toEpochMilli());
            tasks.save(entity);
        });
    }

    @Override
    @Transactional
    public void linkPullRequest(String taskId, int prNumber, String prState)
    {
        tasks.findById(taskId).ifPresent(entity -> {
            entity.setPrNumber(prNumber);
            entity.setLinkedPrNumber(prNumber);
            if (prState != null && !prState.isBlank()) {
                entity.setPrState(prState);
            }
            tasks.save(entity);
        });
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
        tasks.findById(taskId).ifPresent(entity -> {
            entity.setStatus(TaskStatus.COMPLETED.name());
            entity.setEndedAtMs(endedAt == null ? null : endedAt.toEpochMilli());
            tasks.save(entity);
        });
    }

    @Override
    @Transactional
    public void updatePhase(String taskId, TaskPhase phase)
    {
        tasks.findById(taskId).ifPresent(entity -> {
            entity.setPhase(phase.name());
            tasks.save(entity);
        });
    }

    @Override
    @Transactional
    public void setOpeningPrompt(String taskId, String openingPrompt)
    {
        tasks.findById(taskId).ifPresent(entity -> {
            entity.setOpeningPrompt(openingPrompt);
            tasks.save(entity);
        });
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
        tasks.findById(taskId).ifPresent(entity -> {
            entity.setConsecutiveAutoPushes(value);
            tasks.save(entity);
        });
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
        tasks.findById(taskId).ifPresent(entity -> {
            entity.setLinkedPrRef(prRef);
            tasks.save(entity);
        });
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
    public Optional<Task> findActiveTaskForThread(String threadId)
    {
        // Non-terminal = anything that isn't COMPLETED or ERRORED.
        // Listed seq-desc so the first row is the latest active task.
        List<String> active = List.of(
                TaskStatus.PENDING.name(),
                TaskStatus.RUNNING.name(),
                TaskStatus.AWAITING.name(),
                TaskStatus.IDLE.name());
        // Also exclude tasks whose dev-lifecycle phase is terminal even when
        // the runtime status lags behind it — a remote merge advances phase
        // to COMPLETED without flipping status off IDLE, and such a task is
        // done, not active. Reading status alone would let it masquerade as
        // the thread's active task and pull trunk turns into its lane.
        return tasks.findByThreadIdAndStatusInOrderBySeqDesc(threadId, active)
                .stream()
                .map(this::toTask)
                .filter(t -> t.phase() != TaskPhase.COMPLETED)
                .findFirst();
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
                e.getEndedAtMs() == null ? null : Instant.ofEpochMilli(e.getEndedAtMs()),
                e.getErrorMessage(),
                e.getName(),
                e.getRoleSkill(),
                deserialiseWorkModel(e.getWorkModelJson()),
                e.getPushedAtMs() == null ? null : Instant.ofEpochMilli(e.getPushedAtMs()),
                parsePhase(e.getPhase()),
                e.getAgendaJson(),
                e.getConsecutiveAutoPushes(),
                e.getLinkedPrRef(),
                e.getOpeningPrompt());
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

    private String serialiseWorkModel(WorkModel m)
    {
        if (m == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(m);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("WorkModel JSON serialise failed", e);
        }
    }

    private WorkModel deserialiseWorkModel(String json)
    {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, WorkModel.class);
        }
        catch (JsonProcessingException e) {
            // Bad row → treat as "no override"; the resolver falls back
            // to the thread / workspace pick. Don't break task load.
            return null;
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

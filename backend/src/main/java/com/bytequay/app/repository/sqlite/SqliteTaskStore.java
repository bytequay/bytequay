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

import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskFile;
import com.bytequay.app.domain.TaskKind;
import com.bytequay.app.domain.TaskMessage;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.TaskStore;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Component
class SqliteTaskStore
        implements TaskStore
{
    private final TaskJpaRepository tasks;
    private final TaskMessageJpaRepository messages;
    private final TaskFileJpaRepository files;

    SqliteTaskStore(
            TaskJpaRepository tasks,
            TaskMessageJpaRepository messages,
            TaskFileJpaRepository files)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.messages = requireNonNull(messages, "messages is null");
        this.files = requireNonNull(files, "files is null");
    }

    @Override
    @Transactional
    public void saveTask(Task task)
    {
        TaskEntity entity = tasks.findById(task.id()).orElseGet(TaskEntity::new);
        entity.setId(task.id());
        entity.setKind(task.kind().name());
        entity.setProvider(task.provider());
        entity.setAgentSessionId(task.agentSessionId());
        entity.setTitle(task.title());
        entity.setStatus(task.status().name());
        entity.setWorkingDir(task.workingDir());
        entity.setBranchName(task.branchName());
        entity.setModel(task.model());
        entity.setCostUsdMilli(task.costUsdMilli());
        entity.setTokensIn(task.tokensIn());
        entity.setTokensOut(task.tokensOut());
        entity.setProcessPid(task.processPid());
        entity.setLogPath(task.logPath());
        entity.setCreatedAtMs(task.createdAt().toEpochMilli());
        entity.setUpdatedAtMs(task.updatedAt().toEpochMilli());
        entity.setEndedAtMs(task.endedAt() == null ? null : task.endedAt().toEpochMilli());
        entity.setErrorMessage(task.errorMessage());
        entity.setMetadataJson(task.metadataJson());
        entity.setGroupId(task.groupId());
        tasks.save(entity);
    }

    @Override
    public Optional<Task> findTaskById(String id)
    {
        return tasks.findById(id).map(SqliteTaskStore::toTask);
    }

    @Override
    public List<Task> listTasksByStatus(TaskStatus status, int limit)
    {
        return tasks.findByStatusOrderByUpdatedAtMsDesc(status.name(), PageRequest.of(0, limit))
                .stream()
                .map(SqliteTaskStore::toTask)
                .toList();
    }

    @Override
    public List<Task> listTasksByGroup(String groupId, int limit)
    {
        return tasks.findByGroupIdOrderByUpdatedAtMsDesc(groupId, PageRequest.of(0, limit))
                .stream()
                .map(SqliteTaskStore::toTask)
                .toList();
    }

    @Override
    @Transactional
    public void unsetGroupOnTasks(String groupId)
    {
        tasks.clearGroupId(groupId);
    }

    @Override
    @Transactional
    public void appendMessage(TaskMessage message)
    {
        TaskMessageEntity entity = new TaskMessageEntity();
        entity.setId(message.id());
        entity.setTaskId(message.taskId());
        entity.setSeq(message.seq());
        entity.setRole(message.role());
        entity.setType(message.type());
        entity.setContentJson(message.contentJson());
        entity.setDurationMs(message.durationMs());
        entity.setTokensIn(message.tokensIn());
        entity.setTokensOut(message.tokensOut());
        entity.setCostUsdMilli(message.costUsdMilli());
        entity.setTsMs(message.ts().toEpochMilli());
        messages.save(entity);
    }

    @Override
    public List<TaskMessage> listMessages(String taskId)
    {
        return messages.findByTaskIdOrderBySeqAsc(taskId).stream()
                .map(SqliteTaskStore::toMessage)
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

    private static Task toTask(TaskEntity e)
    {
        return new Task(
                e.getId(),
                TaskKind.valueOf(e.getKind()),
                e.getProvider(),
                e.getAgentSessionId(),
                e.getTitle(),
                TaskStatus.valueOf(e.getStatus()),
                e.getWorkingDir(),
                e.getBranchName(),
                e.getModel(),
                e.getCostUsdMilli(),
                e.getTokensIn(),
                e.getTokensOut(),
                e.getProcessPid(),
                e.getLogPath(),
                Instant.ofEpochMilli(e.getCreatedAtMs()),
                Instant.ofEpochMilli(e.getUpdatedAtMs()),
                e.getEndedAtMs() == null ? null : Instant.ofEpochMilli(e.getEndedAtMs()),
                e.getErrorMessage(),
                e.getMetadataJson(),
                e.getGroupId());
    }

    private static TaskMessage toMessage(TaskMessageEntity e)
    {
        return new TaskMessage(
                e.getId(),
                e.getTaskId(),
                e.getSeq(),
                e.getRole(),
                e.getType(),
                e.getContentJson(),
                e.getDurationMs(),
                e.getTokensIn(),
                e.getTokensOut(),
                e.getCostUsdMilli(),
                Instant.ofEpochMilli(e.getTsMs()));
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

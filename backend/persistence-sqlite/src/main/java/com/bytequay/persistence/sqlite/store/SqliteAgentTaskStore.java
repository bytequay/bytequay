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
package com.bytequay.persistence.sqlite.store;

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskNotFoundException;
import com.bytequay.agent.core.CleanupState;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.persistence.sqlite.entity.AgentTaskEntity;
import com.bytequay.persistence.sqlite.entity.AgentTurnEntity;
import com.bytequay.persistence.sqlite.repository.AgentApprovalJpaRepository;
import com.bytequay.persistence.sqlite.repository.AgentEventOutboxJpaRepository;
import com.bytequay.persistence.sqlite.repository.AgentInputDeliveryJpaRepository;
import com.bytequay.persistence.sqlite.repository.AgentProcessLaunchJpaRepository;
import com.bytequay.persistence.sqlite.repository.AgentQueuedInputJpaRepository;
import com.bytequay.persistence.sqlite.repository.AgentTaskJpaRepository;
import com.bytequay.persistence.sqlite.repository.AgentTurnJpaRepository;
import com.google.common.collect.ImmutableList;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static com.bytequay.persistence.sqlite.store.StoreWrites.AGENT;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteAgentTaskStore
        implements AgentTaskStore
{
    private static final List<TaskState> TERMINAL_STATES = ImmutableList.of(TaskState.COMPLETED, TaskState.FAILED);
    // These policies have a separate durable workflow row that must reach FINISHED before removal.
    private static final List<AgentPermissionPolicy> WORKFLOW_POLICIES = ImmutableList.of(
            AgentPermissionPolicy.CI_AUTOFIX,
            AgentPermissionPolicy.CHERRY_PICK);

    private final AgentTaskJpaRepository repository;
    private final AgentTurnJpaRepository turns;
    private final AgentApprovalJpaRepository approvals;
    private final AgentQueuedInputJpaRepository queue;
    private final AgentInputDeliveryJpaRepository deliveries;
    private final AgentEventOutboxJpaRepository outbox;
    private final AgentProcessLaunchJpaRepository launches;
    private final JdbcTemplate jdbc;

    public SqliteAgentTaskStore(
            AgentTaskJpaRepository repository,
            AgentTurnJpaRepository turns,
            AgentApprovalJpaRepository approvals,
            AgentQueuedInputJpaRepository queue,
            AgentInputDeliveryJpaRepository deliveries,
            AgentEventOutboxJpaRepository outbox,
            AgentProcessLaunchJpaRepository launches,
            JdbcTemplate jdbc)
    {
        this.repository = requireNonNull(repository, "repository is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.approvals = requireNonNull(approvals, "approvals is null");
        this.queue = requireNonNull(queue, "queue is null");
        this.deliveries = requireNonNull(deliveries, "deliveries is null");
        this.outbox = requireNonNull(outbox, "outbox is null");
        this.launches = requireNonNull(launches, "launches is null");
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    @Override
    @Transactional
    public void insert(AgentTask task)
    {
        requireNonNull(task, "task is null");
        StoreWrites.insert(repository, task.id().toString(), "Task", () -> AgentTaskEntity.fromDomain(task), AGENT);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentTask> find(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");
        return repository.findById(taskId.toString()).map(AgentTaskEntity::toDomain);
    }

    @Override
    @Transactional
    public AgentTask update(AgentTask task)
    {
        requireNonNull(task, "task is null");
        return StoreWrites.update(
                repository, task.id().toString(), "Task", task.version(), AgentTaskEntity::getVersion, entity -> entity.updateFrom(task),
                () -> new AgentTaskNotFoundException(task.id()), AGENT)
                .toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentTask> listRecent(int limit)
    {
        checkLimit(limit);
        return repository.findAllByOrderByUpdatedAtDesc(PageRequest.of(0, limit)).stream()
                .map(AgentTaskEntity::toDomain)
                .collect(toImmutableList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentTask> listRecentInteractive(String workspaceId, int limit)
    {
        requireNonNull(workspaceId, "workspaceId is null");
        checkArgument(!workspaceId.isBlank(), "workspaceId is blank");
        checkLimit(limit);
        return repository.findByWorkspaceIdAndInteractiveTrueOrderByUpdatedAtDesc(workspaceId, PageRequest.of(0, limit)).stream()
                .map(AgentTaskEntity::toDomain)
                .collect(toImmutableList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentTask> listByWorkspace(String workspaceId)
    {
        requireNonNull(workspaceId, "workspaceId is null");
        checkArgument(!workspaceId.isBlank(), "workspaceId is blank");
        return repository.findByWorkspaceIdOrderByCreatedAtAsc(workspaceId).stream()
                .map(AgentTaskEntity::toDomain)
                .collect(toImmutableList());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasWorktreeReference(String workspaceId)
    {
        requireNonNull(workspaceId, "workspaceId is null");
        checkArgument(!workspaceId.isBlank(), "workspaceId is blank");
        return repository.existsByWorkspaceIdAndGeneratedBranchIsNotNull(workspaceId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentTask> listLive()
    {
        return repository.findByStateNotInOrderByCreatedAtAsc(TERMINAL_STATES).stream()
                .map(AgentTaskEntity::toDomain)
                .collect(toImmutableList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentTask> listPendingWorktreeCleanup(AgentPermissionPolicy permissionPolicy, Instant retryBefore)
    {
        requireNonNull(permissionPolicy, "permissionPolicy is null");
        requireNonNull(retryBefore, "retryBefore is null");
        return repository.findPendingWorktreeCleanup(
                        TERMINAL_STATES,
                        permissionPolicy,
                        CleanupState.DELETED,
                        retryBefore).stream()
                .map(AgentTaskEntity::toDomain)
                .collect(toImmutableList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentTask> listCleanupDue(Instant now, Instant retryBefore, int limit)
    {
        requireNonNull(now, "now is null");
        requireNonNull(retryBefore, "retryBefore is null");
        checkLimit(limit);
        return repository.findCleanupDue(
                        TERMINAL_STATES,
                        CleanupState.DELETED,
                        CleanupState.RETRY_PENDING,
                        WORKFLOW_POLICIES,
                        now,
                        retryBefore,
                        PageRequest.of(0, limit)).stream()
                .map(AgentTaskEntity::toDomain)
                .collect(toImmutableList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentTask> listArchiveDue(Instant completedBefore, int limit)
    {
        requireNonNull(completedBefore, "completedBefore is null");
        checkLimit(limit);
        return repository.findArchiveDue(TaskState.COMPLETED, completedBefore, PageRequest.of(0, limit)).stream()
                .map(AgentTaskEntity::toDomain)
                .collect(toImmutableList());
    }

    @Override
    @Transactional
    public void delete(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");
        String id = taskId.toString();
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM branch_rebase WHERE agent_task_id = ? AND terminal_at IS NULL)", Boolean.class, id))) {
            throw new AgentConflictException("Task has an active branch rebase: " + taskId);
        }
        jdbc.update("DELETE FROM branch_rebase WHERE agent_task_id = ?", id);
        jdbc.update("DELETE FROM recent_touch WHERE item_key = ?", "task:" + id);
        jdbc.update("DELETE FROM recent_text WHERE item_key = ?", "task:" + id);
        jdbc.update("DELETE FROM local_review_thread WHERE task_id = ?", id);
        // A launch names its owner, the task or one of its turns; nothing else ties it to the task.
        List<String> owners = Stream.concat(Stream.of(id), turns.findByTaskIdOrderByCreatedAtAsc(id).stream().map(AgentTurnEntity::getId))
                .collect(toImmutableList());
        launches.deleteByOwnerIdIn(owners);
        approvals.deleteByTaskId(id);
        queue.deleteByTaskId(id);
        deliveries.deleteByTaskId(id);
        outbox.deleteByTaskId(id);
        turns.deleteByTaskId(id);
        repository.deleteById(id);
        // Flushed here so a caller inside a wider transaction sees the row gone, as the bulk deletes above already are.
        repository.flush();
    }

    private static void checkLimit(int limit)
    {
        checkArgument(limit > 0, "limit must be positive");
    }
}

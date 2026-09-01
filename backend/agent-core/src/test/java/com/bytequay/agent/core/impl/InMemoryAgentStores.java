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
package com.bytequay.agent.core.impl;

import com.bytequay.agent.core.AgentApproval;
import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentEventOutboxEntry;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskNotFoundException;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnNotFoundException;
import com.bytequay.agent.core.ApprovalDecision;
import com.bytequay.agent.core.ApprovalId;
import com.bytequay.agent.core.CleanupState;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.store.AgentApprovalStore;
import com.bytequay.agent.core.store.AgentEventOutboxStore;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.AgentUnitOfWork;
import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

final class InMemoryAgentStores
        implements AgentTaskStore, AgentTurnStore, AgentApprovalStore, AgentEventOutboxStore, AgentUnitOfWork
{
    private final Map<TaskId, AgentTask> tasks = new HashMap<>();
    private final Map<TurnId, AgentTurn> turns = new HashMap<>();
    private final Map<ApprovalId, AgentApproval> approvals = new LinkedHashMap<>();
    private final Map<EventId, AgentEventOutboxEntry> outbox = new LinkedHashMap<>();
    private RuntimeException taskInsertFailure;
    private boolean persistBeforeTaskInsertFailure;
    private int taskFindCalls;
    private int failingTaskFindCall = -1;
    private RuntimeException taskFindFailure;
    private RuntimeException approvalFindFailure;
    private RuntimeException approvalFindAfterResolveFailure;
    private RuntimeException approvalResolveFailure;
    private TurnState failingTurnUpdateState;
    private RuntimeException turnUpdateFailure;

    @Override
    public <T> T execute(Work<T> work)
    {
        return requireNonNull(work, "work is null").run();
    }

    @Override
    public synchronized void insert(AgentTask task)
    {
        if (taskInsertFailure != null) {
            if (persistBeforeTaskInsertFailure) {
                tasks.putIfAbsent(task.id(), task);
            }
            throw taskInsertFailure;
        }
        if (tasks.putIfAbsent(task.id(), task) != null) {
            throw new AgentConflictException("Task already exists: " + task.id());
        }
    }

    @Override
    public synchronized Optional<AgentTask> find(TaskId taskId)
    {
        taskFindCalls++;
        if (taskFindCalls == failingTaskFindCall) {
            throw taskFindFailure;
        }
        return Optional.ofNullable(tasks.get(taskId));
    }

    @Override
    public synchronized AgentTask update(AgentTask task)
    {
        AgentTask current = tasks.get(task.id());
        if (current == null) {
            throw new AgentTaskNotFoundException(task.id());
        }
        if (current.version() != task.version()) {
            throw new AgentConflictException("Task version changed: " + task.id());
        }
        AgentTask stored = task.withVersion(task.version() + 1);
        tasks.put(task.id(), stored);
        return stored;
    }

    @Override
    public synchronized List<AgentTask> listRecent(int limit)
    {
        return tasks.values().stream()
                .sorted(Comparator.comparing(AgentTask::updatedAt).reversed())
                .limit(limit)
                .collect(toImmutableList());
    }

    @Override
    public synchronized List<AgentTask> listRecentInteractive(String workspaceId, int limit)
    {
        return tasks.values().stream()
                .filter(AgentTask::interactive)
                .filter(task -> task.workspaceId().stream().anyMatch(workspaceId::equals))
                .sorted(Comparator.comparing(AgentTask::updatedAt).reversed())
                .limit(limit)
                .collect(toImmutableList());
    }

    @Override
    public synchronized boolean hasWorktreeReference(String workspaceId)
    {
        return tasks.values().stream()
                .anyMatch(task -> task.taskWorktree().stream()
                        .anyMatch(worktree -> worktree.workspaceId().equals(workspaceId)));
    }

    @Override
    public synchronized List<AgentTask> listLive()
    {
        return tasks.values().stream()
                .filter(task -> !task.state().terminal())
                .sorted(Comparator.comparing(AgentTask::createdAt))
                .collect(toImmutableList());
    }

    @Override
    public synchronized List<AgentTask> listCleanupDue(Instant now, int limit)
    {
        return tasks.values().stream()
                .filter(task -> task.state().terminal())
                .filter(task -> task.cleanupState() != CleanupState.DELETED)
                .filter(task -> task.cleanupState() == CleanupState.RETRY_PENDING
                        || task.deleteAfter().stream().anyMatch(deadline -> !deadline.isAfter(now)))
                .sorted(Comparator.comparing(task -> task.deleteAfter().orElse(Instant.MAX)))
                .limit(limit)
                .collect(toImmutableList());
    }

    @Override
    public synchronized void insert(AgentTurn turn)
    {
        if (turns.containsKey(turn.id())) {
            throw new AgentConflictException("Turn already exists: " + turn.id());
        }
        if (turns.values().stream().anyMatch(existing -> existing.taskId().equals(turn.taskId())
                && existing.idempotencyKey().equals(turn.idempotencyKey()))) {
            throw new AgentConflictException("Turn idempotency key already exists");
        }
        if (turn.state().active() && findActive(turn.taskId()).isPresent()) {
            throw new AgentConflictException("Task already has an active turn");
        }
        turns.put(turn.id(), turn);
    }

    @Override
    public synchronized Optional<AgentTurn> find(TurnId turnId)
    {
        return Optional.ofNullable(turns.get(turnId));
    }

    @Override
    public synchronized Optional<AgentTurn> findByIdempotencyKey(TaskId taskId, String idempotencyKey)
    {
        return turns.values().stream()
                .filter(turn -> turn.taskId().equals(taskId))
                .filter(turn -> turn.idempotencyKey().equals(idempotencyKey))
                .findFirst();
    }

    @Override
    public synchronized Optional<AgentTurn> findActive(TaskId taskId)
    {
        return turns.values().stream()
                .filter(turn -> turn.taskId().equals(taskId))
                .filter(turn -> turn.state().active())
                .findFirst();
    }

    @Override
    public synchronized AgentTurn update(AgentTurn turn)
    {
        if (turn.state() == failingTurnUpdateState) {
            RuntimeException failure = turnUpdateFailure;
            failingTurnUpdateState = null;
            turnUpdateFailure = null;
            throw failure;
        }
        AgentTurn current = turns.get(turn.id());
        if (current == null) {
            throw new AgentTurnNotFoundException(turn.id());
        }
        if (current.version() != turn.version()) {
            throw new AgentConflictException("Turn version changed: " + turn.id());
        }
        if (turn.state().active() && turns.values().stream().anyMatch(existing -> !existing.id().equals(turn.id())
                && existing.taskId().equals(turn.taskId())
                && existing.state().active())) {
            throw new AgentConflictException("Task already has an active turn");
        }
        AgentTurn stored = turn.withVersion(turn.version() + 1);
        turns.put(turn.id(), stored);
        return stored;
    }

    @Override
    public synchronized List<AgentTurn> list(TaskId taskId)
    {
        return turns.values().stream()
                .filter(turn -> turn.taskId().equals(taskId))
                .sorted(Comparator.comparing(AgentTurn::createdAt))
                .collect(toImmutableList());
    }

    @Override
    public synchronized List<AgentTurn> listRecoverable()
    {
        return turns.values().stream()
                .filter(turn -> turn.state().active())
                .sorted(Comparator.comparing(AgentTurn::createdAt))
                .collect(toImmutableList());
    }

    @Override
    public synchronized void insert(AgentApproval approval)
    {
        if (approvals.putIfAbsent(approval.request().id(), approval) != null) {
            throw new AgentConflictException("Approval already exists: " + approval.request().id());
        }
    }

    @Override
    public synchronized Optional<AgentApproval> find(ApprovalId approvalId)
    {
        if (approvalFindFailure != null) {
            RuntimeException failure = approvalFindFailure;
            approvalFindFailure = null;
            throw failure;
        }
        return Optional.ofNullable(approvals.get(approvalId));
    }

    @Override
    public synchronized boolean resolve(
            ApprovalId approvalId,
            ApprovalDecision decision,
            AgentEventPayload.ApprovalOutcome outcome)
    {
        if (approvalResolveFailure != null) {
            RuntimeException failure = approvalResolveFailure;
            approvalResolveFailure = null;
            throw failure;
        }
        AgentApproval approval = approvals.get(approvalId);
        if (approval == null || !approval.pending()) {
            return false;
        }
        approvals.put(approvalId, approval.resolve(decision, outcome));
        if (approvalFindAfterResolveFailure != null) {
            approvalFindFailure = approvalFindAfterResolveFailure;
            approvalFindAfterResolveFailure = null;
        }
        return true;
    }

    @Override
    public synchronized List<AgentApproval> listPending(TurnId turnId)
    {
        return approvals.values().stream()
                .filter(approval -> approval.request().turnId().equals(turnId))
                .filter(AgentApproval::pending)
                .sorted(Comparator.comparing((AgentApproval approval) -> approval.request().requestedAt())
                        .thenComparing(approval -> approval.request().id().toString()))
                .collect(toImmutableList());
    }

    @Override
    public synchronized List<AgentApproval> listPending()
    {
        return approvals.values().stream()
                .filter(AgentApproval::pending)
                .sorted(Comparator.comparing((AgentApproval approval) -> approval.request().requestedAt())
                        .thenComparing(approval -> approval.request().id().toString()))
                .collect(toImmutableList());
    }

    @Override
    public synchronized void insert(AgentEventOutboxEntry entry)
    {
        if (outbox.putIfAbsent(entry.eventId(), entry) != null) {
            throw new AgentConflictException("Event already exists: " + entry.eventId());
        }
    }

    @Override
    public synchronized Optional<AgentEventOutboxEntry> find(EventId eventId)
    {
        return Optional.ofNullable(outbox.get(eventId));
    }

    @Override
    public synchronized List<AgentEventOutboxEntry> listPending(int limit)
    {
        return outbox.values().stream()
                .filter(entry -> entry.sequence().isEmpty())
                .limit(limit)
                .collect(toImmutableList());
    }

    @Override
    public synchronized List<AgentEventOutboxEntry> listPending(TaskId taskId, int limit)
    {
        return outbox.values().stream()
                .filter(entry -> entry.taskId().equals(taskId))
                .filter(entry -> entry.sequence().isEmpty())
                .limit(limit)
                .collect(toImmutableList());
    }

    @Override
    public synchronized boolean hasPending(TaskId taskId)
    {
        return outbox.values().stream()
                .anyMatch(entry -> entry.taskId().equals(taskId) && entry.sequence().isEmpty());
    }

    @Override
    public synchronized void markAppended(EventId eventId, long sequence)
    {
        AgentEventOutboxEntry entry = find(eventId)
                .orElseThrow(() -> new AgentConflictException("Event is absent: " + eventId));
        if (entry.sequence().isPresent() && entry.sequence().orElseThrow() != sequence) {
            throw new AgentConflictException("Event sequence changed: " + eventId);
        }
        outbox.put(eventId, new AgentEventOutboxEntry(
                entry.eventId(),
                entry.taskId(),
                entry.turnId(),
                entry.source(),
                entry.payload(),
                entry.occurredAt(),
                Optional.of(sequence)));
    }

    synchronized List<AgentTask> tasks()
    {
        return ImmutableList.copyOf(tasks.values());
    }

    synchronized List<AgentEventOutboxEntry> outboxEntries()
    {
        return ImmutableList.copyOf(outbox.values());
    }

    synchronized void failTaskInsert(RuntimeException failure, boolean persistBeforeFailure)
    {
        taskInsertFailure = failure;
        persistBeforeTaskInsertFailure = persistBeforeFailure;
    }

    synchronized void failTaskFind(int call, RuntimeException failure)
    {
        failingTaskFindCall = call;
        taskFindFailure = failure;
    }

    synchronized void failApprovalFind(RuntimeException failure)
    {
        approvalFindFailure = failure;
    }

    synchronized void failApprovalFindAfterResolve(RuntimeException failure)
    {
        approvalFindAfterResolveFailure = failure;
    }

    synchronized void failApprovalResolve(RuntimeException failure)
    {
        approvalResolveFailure = failure;
    }

    synchronized void failTurnUpdate(TurnState state, RuntimeException failure)
    {
        failingTurnUpdateState = state;
        turnUpdateFailure = failure;
    }

    synchronized void clearApprovalFindFailure()
    {
        approvalFindFailure = null;
    }
}

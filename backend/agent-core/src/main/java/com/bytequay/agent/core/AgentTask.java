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
package com.bytequay.agent.core;

import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentSessionRef;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static com.bytequay.agent.spi.AgentChecks.checkOptionalText;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

public record AgentTask(
        TaskId id,
        Optional<String> workspaceId,
        boolean interactive,
        String title,
        AgentLaneId laneId,
        RepositoryIdentity repository,
        Path worktree,
        Optional<TaskWorktree> taskWorktree,
        AgentTaskDefaults defaults,
        TaskState state,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Optional<Instant> terminalAt,
        Optional<String> terminalReason,
        Optional<AgentSessionRef> sessionReference,
        TaskMetrics metrics,
        Optional<TaskSummary> summary,
        CleanupState cleanupState,
        Optional<Instant> deleteAfter,
        Optional<String> cleanupFailure)
{
    public AgentTask
    {
        requireNonNull(id, "id is null");
        checkOptionalText(workspaceId, "workspaceId");
        checkArgument(!interactive || workspaceId.isPresent(), "interactive task has no workspaceId");
        checkNotBlank(title, "title");
        requireNonNull(laneId, "laneId is null");
        requireNonNull(repository, "repository is null");
        requireNonNull(worktree, "worktree is null");
        requireNonNull(taskWorktree, "taskWorktree is null");
        requireNonNull(defaults, "defaults is null");
        requireNonNull(state, "state is null");
        requireNonNull(createdAt, "createdAt is null");
        requireNonNull(updatedAt, "updatedAt is null");
        requireNonNull(terminalAt, "terminalAt is null");
        checkOptionalText(terminalReason, "terminalReason");
        requireNonNull(sessionReference, "sessionReference is null");
        requireNonNull(metrics, "metrics is null");
        requireNonNull(summary, "summary is null");
        requireNonNull(cleanupState, "cleanupState is null");
        requireNonNull(deleteAfter, "deleteAfter is null");
        checkOptionalText(cleanupFailure, "cleanupFailure");
        checkArgument(worktree.isAbsolute(), "worktree is not absolute");
        checkArgument(taskWorktree.stream().allMatch(value -> value.taskId().equals(id)),
                "taskWorktree belongs to another task");
        checkArgument(taskWorktree.stream().allMatch(value -> value.physicalRepository().equals(repository)),
                "taskWorktree belongs to another repository");
        checkArgument(taskWorktree.stream().allMatch(value -> value.canonicalWorktree().equals(worktree)),
                "taskWorktree path does not match worktree");
        checkArgument(taskWorktree.stream().allMatch(value -> workspaceId
                        .map(value.workspaceId()::equals)
                        .orElse(false)),
                "taskWorktree belongs to another workspace");
        checkArgument(version >= 0, "version is negative");
        checkArgument(!updatedAt.isBefore(createdAt), "updatedAt is before createdAt");
        checkArgument(sessionReference.stream().allMatch(reference -> reference.laneId().equals(laneId)), "session reference belongs to another lane");
        checkArgument(state.terminal() == terminalAt.isPresent() && state.terminal() == deleteAfter.isPresent(), "terminal timestamps and task state disagree");
        checkArgument((state == TaskState.FAILED) == terminalReason.isPresent(), "terminalReason and task state disagree");
        checkArgument((state == TaskState.COMPLETED) == summary.isPresent(), "summary and task state disagree");
        checkArgument(summary.stream().allMatch(value -> value.metrics().equals(metrics)), "summary metrics and task metrics disagree");
        checkArgument(terminalAt.isEmpty() || deleteAfter.orElseThrow().isAfter(terminalAt.orElseThrow()), "deleteAfter must be after terminalAt");
        checkArgument(state.terminal() || cleanupState == CleanupState.RETAINED, "a live task cannot enter cleanup");
        checkArgument((cleanupState == CleanupState.RETRY_PENDING) == cleanupFailure.isPresent(), "cleanupFailure and cleanupState disagree");
    }

    public static AgentTask created(
            TaskId id,
            Optional<String> workspaceId,
            boolean interactive,
            String title,
            AgentLaneId laneId,
            RepositoryIdentity repository,
            Path worktree,
            AgentTaskDefaults defaults,
            Instant now)
    {
        return new AgentTask(
                id,
                workspaceId,
                interactive,
                title,
                laneId,
                repository,
                worktree,
                Optional.empty(),
                defaults,
                TaskState.CREATED,
                0,
                now,
                now,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                TaskMetrics.empty(),
                Optional.empty(),
                CleanupState.RETAINED,
                Optional.empty(),
                Optional.empty());
    }

    public static AgentTask created(
            TaskId id,
            boolean interactive,
            String title,
            AgentLaneId laneId,
            TaskWorktree taskWorktree,
            AgentTaskDefaults defaults,
            Instant now)
    {
        requireNonNull(taskWorktree, "taskWorktree is null");
        return new AgentTask(
                id,
                Optional.of(taskWorktree.workspaceId()),
                interactive,
                title,
                laneId,
                taskWorktree.physicalRepository(),
                taskWorktree.canonicalWorktree(),
                Optional.of(taskWorktree),
                defaults,
                TaskState.CREATED,
                0,
                now,
                now,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                TaskMetrics.empty(),
                Optional.empty(),
                CleanupState.RETAINED,
                Optional.empty(),
                Optional.empty());
    }

    public static AgentTask created(
            TaskId id,
            Optional<String> workspaceId,
            boolean interactive,
            String title,
            AgentLaneId laneId,
            RepositoryIdentity repository,
            Path worktree,
            AgentPermissionPolicy permissionPolicy,
            Instant now)
    {
        return created(
                id,
                workspaceId,
                interactive,
                title,
                laneId,
                repository,
                worktree,
                AgentTaskDefaults.forPermission(permissionPolicy),
                now);
    }

    public AgentPermissionPolicy permissionPolicy()
    {
        return defaults.permissionPolicy();
    }

    public AgentTask ready(Instant now)
    {
        requireState(TaskState.CREATED);
        return copy(TaskState.READY, version, now, terminalAt, terminalReason, sessionReference, metrics,
                summary, cleanupState, deleteAfter, cleanupFailure);
    }

    public AgentTask activate(Instant now)
    {
        if (state == TaskState.ACTIVE) {
            return this;
        }
        requireState(TaskState.READY);
        return copy(TaskState.ACTIVE, version, now, terminalAt, terminalReason, sessionReference, metrics,
                summary, cleanupState, deleteAfter, cleanupFailure);
    }

    public AgentTask beginCompletion(Instant now)
    {
        if (state == TaskState.COMPLETING) {
            return this;
        }
        checkState(state == TaskState.READY || state == TaskState.ACTIVE, "expected task state READY or ACTIVE but was " + state);
        return copy(TaskState.COMPLETING, version, now, terminalAt, terminalReason, sessionReference, metrics,
                summary, cleanupState, deleteAfter, cleanupFailure);
    }

    public AgentTask complete(TaskSummary completedSummary, Instant completedAt, Instant deletionDeadline)
    {
        requireState(TaskState.COMPLETING);
        requireNonNull(completedSummary, "completedSummary is null");
        requireNonNull(completedAt, "completedAt is null");
        requireNonNull(deletionDeadline, "deletionDeadline is null");
        return copy(
                TaskState.COMPLETED,
                version,
                completedAt,
                Optional.of(completedAt),
                Optional.empty(),
                sessionReference,
                completedSummary.metrics(),
                Optional.of(completedSummary),
                CleanupState.RETAINED,
                Optional.of(deletionDeadline),
                Optional.empty());
    }

    public AgentTask fail(String reason, Instant failedAt, Instant deletionDeadline)
    {
        checkNotBlank(reason, "reason");
        requireNonNull(failedAt, "failedAt is null");
        requireNonNull(deletionDeadline, "deletionDeadline is null");
        if (state.terminal()) {
            checkState(state == TaskState.FAILED && terminalReason.orElseThrow().equals(reason), "task is already terminal");
            return this;
        }
        checkState(state != TaskState.CREATED, "a task must be ready before it can fail durably");
        return copy(
                TaskState.FAILED,
                version,
                failedAt,
                Optional.of(failedAt),
                Optional.of(reason),
                sessionReference,
                metrics,
                Optional.empty(),
                CleanupState.RETAINED,
                Optional.of(deletionDeadline),
                Optional.empty());
    }

    public AgentTask withSessionReference(AgentSessionRef reference, Instant now)
    {
        requireNonNull(reference, "reference is null");
        checkState(!state.terminal(), "cannot update a terminal task session");
        return copy(state, version, now, terminalAt, terminalReason, Optional.of(reference), metrics,
                summary, cleanupState, deleteAfter, cleanupFailure);
    }

    public AgentTask withMetrics(TaskMetrics updatedMetrics, Instant now)
    {
        requireNonNull(updatedMetrics, "updatedMetrics is null");
        return copy(state, version, now, terminalAt, terminalReason, sessionReference, updatedMetrics,
                summary, cleanupState, deleteAfter, cleanupFailure);
    }

    public AgentTask withDefaults(AgentTaskDefaults updatedDefaults, Instant now)
    {
        requireNonNull(updatedDefaults, "updatedDefaults is null");
        requireNonNull(now, "now is null");
        checkState(!state.terminal(), "cannot update defaults for a terminal task");
        checkArgument(!now.isBefore(updatedAt), "now is before the current update time");
        return new AgentTask(
                id,
                workspaceId,
                interactive,
                title,
                laneId,
                repository,
                worktree,
                taskWorktree,
                updatedDefaults,
                state,
                version,
                createdAt,
                now,
                terminalAt,
                terminalReason,
                sessionReference,
                metrics,
                summary,
                cleanupState,
                deleteAfter,
                cleanupFailure);
    }

    public AgentTask withVersion(long updatedVersion)
    {
        return copy(state, updatedVersion, updatedAt, terminalAt, terminalReason, sessionReference, metrics,
                summary, cleanupState, deleteAfter, cleanupFailure);
    }

    public AgentTask withCleanupState(CleanupState updatedState, Optional<String> failure, Instant now)
    {
        requireNonNull(updatedState, "updatedState is null");
        checkOptionalText(failure, "failure");
        checkState(state.terminal(), "cannot clean up a live task");
        checkState(cleanupState.canTransitionTo(updatedState), "invalid cleanup transition: " + cleanupState + " -> " + updatedState);
        return copy(state, version, now, terminalAt, terminalReason, sessionReference, metrics,
                summary, updatedState, deleteAfter, failure);
    }

    private AgentTask copy(
            TaskState updatedState,
            long updatedVersion,
            Instant updateTime,
            Optional<Instant> updatedTerminalAt,
            Optional<String> updatedTerminalReason,
            Optional<AgentSessionRef> updatedSessionReference,
            TaskMetrics updatedMetrics,
            Optional<TaskSummary> updatedSummary,
            CleanupState updatedCleanupState,
            Optional<Instant> updatedDeleteAfter,
            Optional<String> updatedCleanupFailure)
    {
        checkArgument(!updateTime.isBefore(updatedAt), "updateTime is before the current update time");
        return new AgentTask(
                id,
                workspaceId,
                interactive,
                title,
                laneId,
                repository,
                worktree,
                taskWorktree,
                defaults,
                updatedState,
                updatedVersion,
                createdAt,
                updateTime,
                updatedTerminalAt,
                updatedTerminalReason,
                updatedSessionReference,
                updatedMetrics,
                updatedSummary,
                updatedCleanupState,
                updatedDeleteAfter,
                updatedCleanupFailure);
    }

    private void requireState(TaskState expected)
    {
        checkState(state == expected, "expected task state " + expected + " but was " + state);
    }
}

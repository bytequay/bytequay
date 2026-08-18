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
import static java.util.Objects.requireNonNull;

public record AgentTask(
        TaskId id,
        String title,
        AgentLaneId laneId,
        RepositoryIdentity repository,
        Path worktree,
        AgentPermissionPolicy permissionPolicy,
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
        checkNotBlank(title, "title");
        requireNonNull(laneId, "laneId is null");
        requireNonNull(repository, "repository is null");
        requireNonNull(worktree, "worktree is null");
        requireNonNull(permissionPolicy, "permissionPolicy is null");
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
        checkArgument(version >= 0, "version is negative");
        checkArgument(!updatedAt.isBefore(createdAt), "updatedAt is before createdAt");
        checkArgument(sessionReference.stream().allMatch(reference -> reference.laneId().equals(laneId)),
                "session reference belongs to another lane");
        checkArgument(state.terminal() == terminalAt.isPresent() && state.terminal() == deleteAfter.isPresent(),
                "terminal timestamps and task state disagree");
        checkArgument((state == TaskState.FAILED) == terminalReason.isPresent(),
                "terminalReason and task state disagree");
        checkArgument((state == TaskState.COMPLETED) == summary.isPresent(),
                "summary and task state disagree");
        checkArgument(summary.stream().allMatch(value -> value.metrics().equals(metrics)),
                "summary metrics and task metrics disagree");
        checkArgument(terminalAt.isEmpty() || deleteAfter.orElseThrow().isAfter(terminalAt.orElseThrow()),
                "deleteAfter must be after terminalAt");
        checkArgument(state.terminal() || cleanupState == CleanupState.RETAINED,
                "a live task cannot enter cleanup");
        checkArgument((cleanupState == CleanupState.RETRY_PENDING) == cleanupFailure.isPresent(),
                "cleanupFailure and cleanupState disagree");
    }

    public static AgentTask created(
            TaskId id,
            String title,
            AgentLaneId laneId,
            RepositoryIdentity repository,
            Path worktree,
            AgentPermissionPolicy permissionPolicy,
            Instant now)
    {
        return new AgentTask(
                id,
                title,
                laneId,
                repository,
                worktree,
                permissionPolicy,
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
        if (state != TaskState.READY && state != TaskState.ACTIVE) {
            throw new IllegalStateException("expected task state READY or ACTIVE but was " + state);
        }
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
            if (state == TaskState.FAILED && terminalReason.orElseThrow().equals(reason)) {
                return this;
            }
            throw new IllegalStateException("task is already terminal");
        }
        if (state == TaskState.CREATED) {
            throw new IllegalStateException("a task must be ready before it can fail durably");
        }
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
        if (state.terminal()) {
            throw new IllegalStateException("cannot update a terminal task session");
        }
        return copy(state, version, now, terminalAt, terminalReason, Optional.of(reference), metrics,
                summary, cleanupState, deleteAfter, cleanupFailure);
    }

    public AgentTask withMetrics(TaskMetrics updatedMetrics, Instant now)
    {
        requireNonNull(updatedMetrics, "updatedMetrics is null");
        return copy(state, version, now, terminalAt, terminalReason, sessionReference, updatedMetrics,
                summary, cleanupState, deleteAfter, cleanupFailure);
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
        if (!state.terminal()) {
            throw new IllegalStateException("cannot clean up a live task");
        }
        if (!cleanupState.canTransitionTo(updatedState)) {
            throw new IllegalStateException("invalid cleanup transition: " + cleanupState + " -> " + updatedState);
        }
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
                title,
                laneId,
                repository,
                worktree,
                permissionPolicy,
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
        if (state != expected) {
            throw new IllegalStateException("expected task state " + expected + " but was " + state);
        }
    }
}

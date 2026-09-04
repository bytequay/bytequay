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
        Optional<String> creationFingerprint,
        String title,
        TitleSource titleSource,
        AgentLaneId laneId,
        RepositoryIdentity repository,
        Path worktree,
        Optional<TaskWorktree> taskWorktree,
        AgentTaskDefaults defaults,
        TaskState state,
        Optional<WorktreeMutationClaim> worktreeMutationClaim,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Optional<Instant> terminalAt,
        Optional<String> terminalReason,
        Optional<AgentSessionRef> sessionReference,
        TaskMetrics metrics,
        Optional<TaskSummary> summary,
        Optional<Instant> worktreeRemovedAt,
        CleanupState cleanupState,
        Optional<Instant> deleteAfter,
        Optional<String> cleanupFailure)
{
    public AgentTask
    {
        requireNonNull(id, "id is null");
        checkOptionalText(workspaceId, "workspaceId");
        checkArgument(!interactive || workspaceId.isPresent(), "interactive task has no workspaceId");
        checkOptionalText(creationFingerprint, "creationFingerprint");
        checkNotBlank(title, "title");
        requireNonNull(titleSource, "titleSource is null");
        requireNonNull(laneId, "laneId is null");
        requireNonNull(repository, "repository is null");
        requireNonNull(worktree, "worktree is null");
        requireNonNull(taskWorktree, "taskWorktree is null");
        requireNonNull(defaults, "defaults is null");
        requireNonNull(state, "state is null");
        requireNonNull(worktreeMutationClaim, "worktreeMutationClaim is null");
        requireNonNull(createdAt, "createdAt is null");
        requireNonNull(updatedAt, "updatedAt is null");
        requireNonNull(terminalAt, "terminalAt is null");
        checkOptionalText(terminalReason, "terminalReason");
        requireNonNull(sessionReference, "sessionReference is null");
        requireNonNull(metrics, "metrics is null");
        requireNonNull(summary, "summary is null");
        requireNonNull(worktreeRemovedAt, "worktreeRemovedAt is null");
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
        checkArgument(worktreeRemovedAt.isEmpty() || state.terminal(), "a live task cannot have a removed worktree");
        checkArgument(state.terminal() || cleanupState == CleanupState.RETAINED, "a live task cannot enter cleanup");
        checkArgument(!state.terminal() || worktreeMutationClaim.isEmpty(), "a terminal task cannot retain a worktree mutation claim");
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
                Optional.empty(),
                title,
                TitleSource.USER,
                laneId,
                repository,
                worktree,
                Optional.empty(),
                defaults,
                TaskState.OPEN,
                Optional.empty(),
                0,
                now,
                now,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                TaskMetrics.empty(),
                Optional.empty(),
                Optional.empty(),
                CleanupState.RETAINED,
                Optional.empty(),
                Optional.empty());
    }

    public static AgentTask created(
            TaskId id,
            boolean interactive,
            String title,
            TitleSource titleSource,
            AgentLaneId laneId,
            TaskWorktree taskWorktree,
            AgentTaskDefaults defaults,
            Instant now)
    {
        return created(id, interactive, Optional.empty(), title, titleSource, laneId, taskWorktree, defaults, now);
    }

    public static AgentTask created(
            TaskId id,
            boolean interactive,
            Optional<String> creationFingerprint,
            String title,
            TitleSource titleSource,
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
                creationFingerprint,
                title,
                titleSource,
                laneId,
                taskWorktree.physicalRepository(),
                taskWorktree.canonicalWorktree(),
                Optional.of(taskWorktree),
                defaults,
                TaskState.OPEN,
                Optional.empty(),
                0,
                now,
                now,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                TaskMetrics.empty(),
                Optional.empty(),
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

    public AgentTask complete(TaskSummary completedSummary, Instant completedAt, Instant deletionDeadline)
    {
        checkState(worktreeMutationClaim.isEmpty(), "task has a worktree mutation claim");
        return completed(completedSummary, completedAt, deletionDeadline);
    }

    public AgentTask complete(
            TaskSummary completedSummary,
            Instant completedAt,
            Instant deletionDeadline,
            WorktreeMutationClaim claim)
    {
        requireNonNull(claim, "claim is null");
        checkState(worktreeMutationClaim.equals(Optional.of(claim)), "worktree mutation claim owner changed");
        return completed(completedSummary, completedAt, deletionDeadline);
    }

    private AgentTask completed(
            TaskSummary completedSummary,
            Instant completedAt,
            Instant deletionDeadline)
    {
        requireState(TaskState.OPEN);
        requireNonNull(completedSummary, "completedSummary is null");
        requireNonNull(completedAt, "completedAt is null");
        requireNonNull(deletionDeadline, "deletionDeadline is null");
        return copy(
                TaskState.COMPLETED,
                Optional.empty(),
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
        checkState(worktreeMutationClaim.isEmpty(), "task has a worktree mutation claim");
        return failed(reason, failedAt, deletionDeadline);
    }

    /**
     * Fails a task still holding the claim of a turn whose work could not be shown stopped.
     *
     * <p>Guarded the way completion is: the caller names the claim it is discarding, so a task
     * cannot lose one by accident. The worktree is retained rather than cleaned, because the reason
     * this operation exists is that something may still have been writing to it.
     *
     * @param reason why the task is being failed
     * @param failedAt when it was failed
     * @param deletionDeadline when the retained worktree may be reclaimed
     * @param claim the held claim being discarded
     * @return the failed task
     */
    public AgentTask fail(String reason, Instant failedAt, Instant deletionDeadline, WorktreeMutationClaim claim)
    {
        requireNonNull(claim, "claim is null");
        checkState(
                worktreeMutationClaim.isEmpty() || worktreeMutationClaim.equals(Optional.of(claim)),
                "worktree mutation claim owner changed");
        return failed(reason, failedAt, deletionDeadline);
    }

    private AgentTask failed(String reason, Instant failedAt, Instant deletionDeadline)
    {
        checkNotBlank(reason, "reason");
        requireNonNull(failedAt, "failedAt is null");
        requireNonNull(deletionDeadline, "deletionDeadline is null");
        if (state.terminal()) {
            checkState(state == TaskState.FAILED && terminalReason.orElseThrow().equals(reason), "task is already terminal");
            return this;
        }
        requireState(TaskState.OPEN);
        return copy(
                TaskState.FAILED,
                Optional.empty(),
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
        return copy(state, worktreeMutationClaim, version, now, terminalAt, terminalReason, Optional.of(reference), metrics,
                summary, cleanupState, deleteAfter, cleanupFailure);
    }

    public AgentTask withMetrics(TaskMetrics updatedMetrics, Instant now)
    {
        requireNonNull(updatedMetrics, "updatedMetrics is null");
        return copy(state, worktreeMutationClaim, version, now, terminalAt, terminalReason, sessionReference, updatedMetrics,
                summary, cleanupState, deleteAfter, cleanupFailure);
    }

    /**
     * Replaces the title. A person's choice is final; the agent may only propose over a
     * provisional title, so a proposal that arrives after a rename changes nothing.
     */
    public AgentTask withTitle(String updatedTitle, TitleSource source, Instant now)
    {
        checkNotBlank(updatedTitle, "updatedTitle");
        requireNonNull(source, "source is null");
        requireNonNull(now, "now is null");
        checkArgument(source != TitleSource.PROVISIONAL, "a title cannot become provisional again");
        checkState(source == TitleSource.USER || titleSource == TitleSource.PROVISIONAL,
                "only a provisional title accepts a proposal");
        checkArgument(!now.isBefore(updatedAt), "now is before the current update time");
        return new AgentTask(
                id,
                workspaceId,
                interactive,
                creationFingerprint,
                updatedTitle,
                source,
                laneId,
                repository,
                worktree,
                taskWorktree,
                defaults,
                state,
                worktreeMutationClaim,
                version,
                createdAt,
                now,
                terminalAt,
                terminalReason,
                sessionReference,
                metrics,
                summary,
                worktreeRemovedAt,
                cleanupState,
                deleteAfter,
                cleanupFailure);
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
                creationFingerprint,
                title,
                titleSource,
                laneId,
                repository,
                worktree,
                taskWorktree,
                updatedDefaults,
                state,
                worktreeMutationClaim,
                version,
                createdAt,
                now,
                terminalAt,
                terminalReason,
                sessionReference,
                metrics,
                summary,
                worktreeRemovedAt,
                cleanupState,
                deleteAfter,
                cleanupFailure);
    }

    public AgentTask withVersion(long updatedVersion)
    {
        return copy(state, worktreeMutationClaim, updatedVersion, updatedAt, terminalAt, terminalReason, sessionReference, metrics,
                summary, cleanupState, deleteAfter, cleanupFailure);
    }

    /**
     * The same task, written again so its version moves.
     *
     * <p>A queue write goes through this: enqueue and Complete both update the task row, so
     * whichever commits second sees the version it was told and conflicts, and the queue can never
     * gain a live row after the task has closed.
     */
    public AgentTask withUpdatedAt(Instant now)
    {
        requireNonNull(now, "now is null");
        requireState(TaskState.OPEN);
        return copy(state, worktreeMutationClaim, version, now, terminalAt, terminalReason, sessionReference, metrics,
                summary, cleanupState, deleteAfter, cleanupFailure);
    }

    public AgentTask withCleanupState(CleanupState updatedState, Optional<String> failure, Instant now)
    {
        requireNonNull(updatedState, "updatedState is null");
        checkOptionalText(failure, "failure");
        checkState(state.terminal(), "cannot clean up a live task");
        checkState(cleanupState.canTransitionTo(updatedState), "invalid cleanup transition: " + cleanupState + " -> " + updatedState);
        return copy(state, worktreeMutationClaim, version, now, terminalAt, terminalReason, sessionReference, metrics,
                summary, updatedState, deleteAfter, failure);
    }

    public AgentTask withWorktreeRemovedAt(Instant removedAt)
    {
        requireNonNull(removedAt, "removedAt is null");
        checkState(state.terminal(), "cannot remove a live task worktree");
        checkArgument(!removedAt.isBefore(updatedAt), "removedAt is before the current update time");
        if (worktreeRemovedAt.isPresent()) {
            return this;
        }
        return new AgentTask(
                id,
                workspaceId,
                interactive,
                creationFingerprint,
                title,
                titleSource,
                laneId,
                repository,
                worktree,
                taskWorktree,
                defaults,
                state,
                worktreeMutationClaim,
                version,
                createdAt,
                removedAt,
                terminalAt,
                terminalReason,
                sessionReference,
                metrics,
                summary,
                Optional.of(removedAt),
                cleanupState,
                deleteAfter,
                cleanupFailure);
    }

    public AgentTask withWorktreeCleanupAttemptAt(Instant attemptedAt)
    {
        requireNonNull(attemptedAt, "attemptedAt is null");
        checkState(state.terminal(), "cannot clean up a live task worktree");
        checkState(worktreeRemovedAt.isEmpty(), "task worktree is already removed");
        checkArgument(!attemptedAt.isBefore(updatedAt), "attemptedAt is before the current update time");
        return copy(state, worktreeMutationClaim, version, attemptedAt, terminalAt, terminalReason, sessionReference, metrics,
                summary, cleanupState, deleteAfter, cleanupFailure);
    }

    public AgentTask claimWorktreeMutation(WorktreeMutationClaim claim, Instant now)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(now, "now is null");
        requireState(TaskState.OPEN);
        checkState(worktreeMutationClaim.isEmpty(), "task already has a worktree mutation claim");
        return copy(state, Optional.of(claim), version, now, terminalAt, terminalReason, sessionReference, metrics,
                summary, cleanupState, deleteAfter, cleanupFailure);
    }

    public AgentTask releaseWorktreeMutation(WorktreeMutationClaim claim, Instant now)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(now, "now is null");
        requireState(TaskState.OPEN);
        checkState(worktreeMutationClaim.equals(Optional.of(claim)), "worktree mutation claim owner changed");
        return copy(state, Optional.empty(), version, now, terminalAt, terminalReason, sessionReference, metrics,
                summary, cleanupState, deleteAfter, cleanupFailure);
    }

    private AgentTask copy(
            TaskState updatedState,
            Optional<WorktreeMutationClaim> updatedWorktreeMutationClaim,
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
                creationFingerprint,
                title,
                titleSource,
                laneId,
                repository,
                worktree,
                taskWorktree,
                defaults,
                updatedState,
                updatedWorktreeMutationClaim,
                updatedVersion,
                createdAt,
                updateTime,
                updatedTerminalAt,
                updatedTerminalReason,
                updatedSessionReference,
                updatedMetrics,
                updatedSummary,
                worktreeRemovedAt,
                updatedCleanupState,
                updatedDeleteAfter,
                updatedCleanupFailure);
    }

    private void requireState(TaskState expected)
    {
        checkState(state == expected, "expected task state " + expected + " but was " + state);
    }
}

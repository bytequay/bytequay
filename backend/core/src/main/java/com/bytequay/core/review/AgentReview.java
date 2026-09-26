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
package com.bytequay.core.review;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * One private review of a pull request or local development task: the workflow that owns an agent task, its runs, its
 * findings, and its discussions until the user ends it.
 *
 * @param observedHeadSha the newest source head ByteQuay has seen; working-copy catch-up and explicit review updates use this target
 * @param worktreeHeadSha the last head ByteQuay aligned, retained independently of the assessed run to recognize private local commits
 */
public record AgentReview(
        String id,
        String workspaceId,
        String repo,
        OptionalInt number,
        OptionalLong pullRequestId,
        String taskId,
        Optional<String> sourceTaskId,
        State state,
        boolean followUpPaused,
        Optional<String> latestCompletedRunId,
        Optional<String> observedHeadSha,
        Optional<String> worktreeHeadSha,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Optional<Instant> closedAt)
{
    public AgentReview
    {
        requireNonNull(id, "id is null");
        requireNonNull(workspaceId, "workspaceId is null");
        requireNonNull(repo, "repo is null");
        requireNonNull(number, "number is null");
        checkArgument(number.isEmpty() || number.getAsInt() > 0, "number is not positive");
        requireNonNull(sourceTaskId, "sourceTaskId is null");
        checkArgument(number.isPresent() || sourceTaskId.isPresent(), "review source is missing");
        requireNonNull(pullRequestId, "pullRequestId is null");
        requireNonNull(taskId, "taskId is null");
        requireNonNull(state, "state is null");
        requireNonNull(latestCompletedRunId, "latestCompletedRunId is null");
        requireNonNull(observedHeadSha, "observedHeadSha is null");
        requireNonNull(worktreeHeadSha, "worktreeHeadSha is null");
        checkArgument(version >= 0, "version is negative");
        requireNonNull(createdAt, "createdAt is null");
        requireNonNull(updatedAt, "updatedAt is null");
        requireNonNull(closedAt, "closedAt is null");
        checkArgument((state == State.CLOSED) == closedAt.isPresent(), "closedAt and state disagree");
    }

    public AgentReview withVersion(long updatedVersion)
    {
        return new AgentReview(
                id,
                workspaceId,
                repo,
                number,
                pullRequestId,
                taskId,
                sourceTaskId,
                state,
                followUpPaused,
                latestCompletedRunId,
                observedHeadSha,
                worktreeHeadSha,
                updatedVersion,
                createdAt,
                updatedAt,
                closedAt);
    }

    public AgentReview withFollowUpPaused(boolean paused, Instant now)
    {
        return new AgentReview(id, workspaceId, repo, number, pullRequestId, taskId, sourceTaskId, state, paused, latestCompletedRunId, observedHeadSha, worktreeHeadSha,
                version, createdAt, now, closedAt);
    }

    public AgentReview withPullRequest(int pullNumber, OptionalLong storedId, Instant now)
    {
        checkArgument(number.isEmpty() || number.getAsInt() == pullNumber, "review pull request cannot change");
        return new AgentReview(id, workspaceId, repo, OptionalInt.of(pullNumber), storedId, taskId, sourceTaskId, state, followUpPaused,
                latestCompletedRunId, observedHeadSha, worktreeHeadSha, version, createdAt, now, closedAt);
    }

    public AgentReview withObservedHead(String headSha, Instant now)
    {
        return new AgentReview(
                id,
                workspaceId,
                repo,
                number,
                pullRequestId,
                taskId,
                sourceTaskId,
                state,
                followUpPaused,
                latestCompletedRunId,
                Optional.of(headSha),
                worktreeHeadSha,
                version,
                createdAt,
                now,
                closedAt);
    }

    public AgentReview withLatestCompletedRun(String runId, Instant now)
    {
        return new AgentReview(id, workspaceId, repo, number, pullRequestId, taskId, sourceTaskId, state, followUpPaused, Optional.of(runId), observedHeadSha, worktreeHeadSha,
                version, createdAt, now, closedAt);
    }

    /** Last head ByteQuay aligned, independent of the retained review scope and any private local commits. */
    public AgentReview withWorktreeHead(String headSha, Instant now)
    {
        return new AgentReview(id, workspaceId, repo, number, pullRequestId, taskId, sourceTaskId, state, followUpPaused, latestCompletedRunId,
                observedHeadSha, Optional.of(headSha), version, createdAt, now, closedAt);
    }

    public AgentReview closed(Instant now)
    {
        return new AgentReview(
                id,
                workspaceId,
                repo,
                number,
                pullRequestId,
                taskId,
                sourceTaskId,
                State.CLOSED,
                true,
                latestCompletedRunId,
                observedHeadSha,
                worktreeHeadSha,
                version,
                createdAt,
                now,
                Optional.of(now));
    }

    public enum State
    {
        OPEN,
        CLOSED
    }
}

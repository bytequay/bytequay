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
import java.util.OptionalLong;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * One private review of one pull request: the workflow that owns an agent task, its runs, its
 * findings, and its discussions until the user ends it.
 *
 * @param observedHeadSha the newest remote head ByteQuay has seen for the pull request, which is
 *        the head a later run reviews; coalesced, so only the newest pending head is ever scheduled
 */
public record AgentReview(
        String id,
        String workspaceId,
        String repo,
        int number,
        OptionalLong pullRequestId,
        String taskId,
        State state,
        boolean followUpPaused,
        Optional<String> latestCompletedRunId,
        Optional<String> observedHeadSha,
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
        checkArgument(number > 0, "number is not positive");
        requireNonNull(pullRequestId, "pullRequestId is null");
        requireNonNull(taskId, "taskId is null");
        requireNonNull(state, "state is null");
        requireNonNull(latestCompletedRunId, "latestCompletedRunId is null");
        requireNonNull(observedHeadSha, "observedHeadSha is null");
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
                state,
                followUpPaused,
                latestCompletedRunId,
                observedHeadSha,
                updatedVersion,
                createdAt,
                updatedAt,
                closedAt);
    }

    public AgentReview withFollowUpPaused(boolean paused, Instant now)
    {
        return new AgentReview(id, workspaceId, repo, number, pullRequestId, taskId, state, paused, latestCompletedRunId, observedHeadSha, version, createdAt, now, closedAt);
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
                state,
                followUpPaused,
                latestCompletedRunId,
                Optional.of(headSha),
                version,
                createdAt,
                now,
                closedAt);
    }

    public AgentReview withLatestCompletedRun(String runId, Instant now)
    {
        return new AgentReview(id, workspaceId, repo, number, pullRequestId, taskId, state, followUpPaused, Optional.of(runId), observedHeadSha, version, createdAt, now, closedAt);
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
                State.CLOSED,
                true,
                latestCompletedRunId,
                observedHeadSha,
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

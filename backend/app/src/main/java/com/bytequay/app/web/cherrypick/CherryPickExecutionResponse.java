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
package com.bytequay.app.web.cherrypick;

import com.bytequay.core.cherrypick.CherryPickExecution;
import com.bytequay.core.cherrypick.CherryPickItem;
import com.bytequay.core.cherrypick.CherryPickLifecycle;
import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Execution response.
 *
 * <p>{@code result} is the durable answer per commit, and the current step
 * explains only what is still pending. The lifecycle carries the stages after
 * applying, and {@code agentTaskId} names the conversation that resolves,
 * builds and rebases.
 */
public record CherryPickExecutionResponse(
        String executionId,
        String workspaceId,
        String repository,
        Optional<String> sourceRepository,
        String workBranch,
        String localTargetBranch,
        String targetBranch,
        String status,
        Optional<String> currentStep,
        Optional<String> pauseReason,
        Optional<String> finishReason,
        Optional<String> summary,
        String initialTargetSha,
        String currentTargetSha,
        Optional<Integer> currentPosition,
        Optional<PullRequestDraftResponse> pullRequestDraft,
        Optional<DeliveryResponse> delivery,
        List<ItemResponse> items,
        Optional<String> agentTaskId,
        CherryPickLifecycle lifecycle,
        Instant createdAt,
        Instant updatedAt,
        Optional<Instant> terminalAt)
{
    public CherryPickExecutionResponse
    {
        requireNonNull(executionId, "executionId is null");
        requireNonNull(workspaceId, "workspaceId is null");
        requireNonNull(repository, "repository is null");
        requireNonNull(sourceRepository, "sourceRepository is null");
        requireNonNull(workBranch, "workBranch is null");
        requireNonNull(localTargetBranch, "localTargetBranch is null");
        requireNonNull(targetBranch, "targetBranch is null");
        requireNonNull(status, "status is null");
        requireNonNull(currentStep, "currentStep is null");
        requireNonNull(pauseReason, "pauseReason is null");
        requireNonNull(finishReason, "finishReason is null");
        requireNonNull(summary, "summary is null");
        requireNonNull(initialTargetSha, "initialTargetSha is null");
        requireNonNull(currentTargetSha, "currentTargetSha is null");
        requireNonNull(currentPosition, "currentPosition is null");
        requireNonNull(pullRequestDraft, "pullRequestDraft is null");
        requireNonNull(delivery, "delivery is null");
        items = ImmutableList.copyOf(requireNonNull(items, "items is null"));
        requireNonNull(agentTaskId, "agentTaskId is null");
        requireNonNull(lifecycle, "lifecycle is null");
        requireNonNull(createdAt, "createdAt is null");
        requireNonNull(updatedAt, "updatedAt is null");
        requireNonNull(terminalAt, "terminalAt is null");
    }

    public static CherryPickExecutionResponse of(CherryPickExecution execution)
    {
        return new CherryPickExecutionResponse(
                execution.id().toString(),
                execution.workspace().workspaceId(),
                execution.workspace().repository().fullName(),
                execution.workspace().sourceRepository().map(RepoRef::fullName),
                execution.workspace().workBranch(),
                execution.workspace().localTargetBranch(),
                execution.workspace().targetBranch(),
                execution.status().name(),
                execution.currentStep().map(Enum::name),
                execution.pauseReason().map(Enum::name),
                execution.finishReason().map(Enum::name),
                execution.summary(),
                execution.range().initialTargetSha(),
                execution.currentTargetSha(),
                execution.cursor().map(CherryPickItem::position),
                execution.pullRequestDraft().map(PullRequestDraftResponse::of),
                execution.delivery().map(DeliveryResponse::of),
                execution.items().stream().map(ItemResponse::of).collect(toImmutableList()),
                execution.agentTaskId(),
                execution.lifecycle(),
                execution.createdAt(),
                execution.updatedAt(),
                execution.terminalAt());
    }

    /** Where the work branch was pushed and the pull request it opened. */
    public record DeliveryResponse(String pushRemote, String head, String sha, Optional<Long> pullRequestId, Optional<Integer> pullRequestNumber, Optional<String> pullRequestUrl)
    {
        public DeliveryResponse
        {
            requireNonNull(pushRemote, "pushRemote is null");
            requireNonNull(head, "head is null");
            requireNonNull(sha, "sha is null");
            requireNonNull(pullRequestId, "pullRequestId is null");
            requireNonNull(pullRequestNumber, "pullRequestNumber is null");
            requireNonNull(pullRequestUrl, "pullRequestUrl is null");
        }

        private static DeliveryResponse of(CherryPickExecution.Delivery delivery)
        {
            return new DeliveryResponse(delivery.pushRemote(), delivery.head(), delivery.sha(), delivery.pullRequestId(), delivery.pullRequestNumber(), delivery.pullRequestUrl());
        }
    }

    /** Pull-request copy saved at start for the draft the execution opens. */
    public record PullRequestDraftResponse(String title, String body)
    {
        public PullRequestDraftResponse
        {
            requireNonNull(title, "title is null");
            requireNonNull(body, "body is null");
        }

        private static PullRequestDraftResponse of(CherryPickExecution.PullRequestDraft draft)
        {
            return new PullRequestDraftResponse(draft.title(), draft.body());
        }
    }

    /** One plan item and its one final result. */
    public record ItemResponse(int position, String sourceSha, String title, String result, Optional<String> targetSha, Optional<Integer> pullRequestNumber)
    {
        public ItemResponse
        {
            requireNonNull(sourceSha, "sourceSha is null");
            requireNonNull(title, "title is null");
            requireNonNull(result, "result is null");
            requireNonNull(targetSha, "targetSha is null");
            requireNonNull(pullRequestNumber, "pullRequestNumber is null");
        }

        public static ItemResponse of(CherryPickItem item)
        {
            return new ItemResponse(
                    item.position(),
                    item.sourceSha(),
                    item.title(),
                    item.result().name(),
                    item.targetSha(),
                    item.pullRequest().map(CherryPickItem.PullRequestRefView::number));
        }
    }
}

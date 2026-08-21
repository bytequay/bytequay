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

import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Execution response.
 *
 * <p>Deliberately small: {@code result} is the durable answer per commit, and
 * the current step explains only what is still pending.
 */
public record CherryPickExecutionResponse(
        String executionId,
        Optional<String> workspaceId,
        String repository,
        String workBranch,
        String localTargetBranch,
        String status,
        Optional<String> currentStep,
        Optional<String> pauseReason,
        Optional<String> finishReason,
        Optional<String> summary,
        String initialTargetSha,
        String currentTargetSha,
        Optional<Integer> currentPosition,
        Optional<PullRequestDraftResponse> pullRequestDraft,
        List<ItemResponse> items)
{
    public CherryPickExecutionResponse
    {
        requireNonNull(executionId, "executionId is null");
        requireNonNull(workspaceId, "workspaceId is null");
        requireNonNull(repository, "repository is null");
        requireNonNull(workBranch, "workBranch is null");
        requireNonNull(localTargetBranch, "localTargetBranch is null");
        requireNonNull(status, "status is null");
        requireNonNull(currentStep, "currentStep is null");
        requireNonNull(pauseReason, "pauseReason is null");
        requireNonNull(finishReason, "finishReason is null");
        requireNonNull(summary, "summary is null");
        requireNonNull(initialTargetSha, "initialTargetSha is null");
        requireNonNull(currentTargetSha, "currentTargetSha is null");
        requireNonNull(currentPosition, "currentPosition is null");
        requireNonNull(pullRequestDraft, "pullRequestDraft is null");
        items = List.copyOf(requireNonNull(items, "items is null"));
    }

    public static CherryPickExecutionResponse of(CherryPickExecution execution)
    {
        return new CherryPickExecutionResponse(
                execution.id().toString(),
                execution.workspace().workspaceId(),
                execution.workspace().repository().fullName(),
                execution.workspace().workBranch(),
                execution.workspace().localTargetBranch(),
                execution.status().name(),
                execution.currentStep().map(Enum::name),
                execution.pauseReason().map(Enum::name),
                execution.finishReason().map(Enum::name),
                execution.summary(),
                execution.range().initialTargetSha(),
                execution.currentTargetSha(),
                execution.cursor().map(CherryPickItem::position),
                execution.pullRequestDraft().map(PullRequestDraftResponse::of),
                execution.items().stream().map(ItemResponse::of).collect(toImmutableList()));
    }

    /** Pull-request copy saved for the later explicit push and create actions. */
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
    public record ItemResponse(
            int position,
            String sourceSha,
            String title,
            String result,
            Optional<String> targetSha,
            Optional<Integer> pullRequestNumber)
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

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
package com.bytequay.app.workspace;

import com.bytequay.core.cherrypick.CherryPickExecutionId;
import com.bytequay.core.domain.RepoRef;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Explicit remote-delivery boundary for completed workspace cherry-pick executions. */
public interface WorkspacePullRequestService
{
    /**
     * Reads delivery state and the current write permission without changing local or remote state.
     *
     * @param workspaceId workspace that owns the execution
     * @param executionId completed cherry-pick execution
     * @return permission, routing, and any already-recorded push or pull request
     */
    DeliveryStatus getStatus(String workspaceId, CherryPickExecutionId executionId);

    /**
     * Pushes the generated branch to the persisted writable {@code origin} without force.
     *
     * <p>Direct and fork workspaces both push to their selected repository at {@code origin};
     * ByteQuay never falls back to another remote.</p>
     *
     * @param command owning workspace, completed execution, and explicit confirmation
     * @return persisted push identity; a repeated confirmation returns the existing result
     */
    PushResult push(PushCommand command);

    /**
     * Creates a pull request by delegating to the existing authenticated PR write client.
     *
     * <p>A direct workspace targets its selected repository. A fork uses its selected fork as
     * the head and its one persisted upstream as the base repository.</p>
     *
     * @param command owning workspace, pushed execution, and a second explicit confirmation
     * @return persisted GitHub pull-request identity; a repeat returns the existing result
     */
    PullRequestResult createPullRequest(PullRequestCommand command);

    record PushCommand(String workspaceId, CherryPickExecutionId executionId, boolean confirmed)
    {
        public PushCommand
        {
            requireNonNull(workspaceId, "workspaceId is null");
            requireNonNull(executionId, "executionId is null");
        }
    }

    record PullRequestCommand(String workspaceId, CherryPickExecutionId executionId, boolean confirmed)
    {
        public PullRequestCommand
        {
            requireNonNull(workspaceId, "workspaceId is null");
            requireNonNull(executionId, "executionId is null");
        }
    }

    record PushResult(String remote, String head, String sha)
    {
        public PushResult
        {
            requireText(remote, "remote");
            requireText(head, "head");
            requireText(sha, "sha");
        }
    }

    record PullRequestResult(
            long providerId,
            int number,
            String url,
            RepoRef baseRepository,
            String baseBranch,
            String head)
    {
        public PullRequestResult
        {
            checkArgument(providerId > 0, "providerId is not positive");
            checkArgument(number > 0, "number is not positive");
            requireText(url, "url");
            requireNonNull(baseRepository, "baseRepository is null");
            requireText(baseBranch, "baseBranch");
            requireText(head, "head");
        }
    }

    record DeliveryStatus(
            boolean pushWritable,
            Optional<String> disabledReason,
            RepoRef baseRepository,
            String baseBranch,
            String head,
            Optional<PushResult> push,
            Optional<PullRequestResult> pullRequest)
    {
        public DeliveryStatus
        {
            requireNonNull(disabledReason, "disabledReason is null");
            requireNonNull(baseRepository, "baseRepository is null");
            requireText(baseBranch, "baseBranch");
            requireText(head, "head");
            requireNonNull(push, "push is null");
            requireNonNull(pullRequest, "pullRequest is null");
            checkArgument(pushWritable || disabledReason.isPresent(),
                    "non-writable delivery has no reason");
            checkArgument(pullRequest.isEmpty() || push.isPresent(),
                    "pull request has no recorded push");
        }
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        checkArgument(!value.isBlank(), name + " is blank");
    }
}

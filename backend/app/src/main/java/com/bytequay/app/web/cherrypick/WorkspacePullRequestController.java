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

import com.bytequay.app.workspace.WorkspacePullRequestService;
import com.bytequay.app.workspace.WorkspacePullRequestService.DeliveryStatus;
import com.bytequay.app.workspace.WorkspacePullRequestService.PullRequestCommand;
import com.bytequay.app.workspace.WorkspacePullRequestService.PullRequestResult;
import com.bytequay.app.workspace.WorkspacePullRequestService.PushCommand;
import com.bytequay.app.workspace.WorkspacePullRequestService.PushResult;
import com.bytequay.core.cherrypick.CherryPickExecutionId;
import com.bytequay.core.cherrypick.CherryPickInvalidRequestException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static java.util.Objects.requireNonNull;

/** Explicit push and pull-request endpoints for completed workspace executions. */
@RestController
public final class WorkspacePullRequestController
{
    private final WorkspacePullRequestService pullRequests;

    public WorkspacePullRequestController(WorkspacePullRequestService pullRequests)
    {
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
    }

    @GetMapping("/api/workspaces/{workspaceId}/cherry-pick/executions/{executionId}/delivery")
    public DeliveryStatus getStatus(
            @PathVariable String workspaceId,
            @PathVariable String executionId)
    {
        return pullRequests.getStatus(workspaceId, executionId(executionId));
    }

    @PostMapping("/api/workspaces/{workspaceId}/cherry-pick/executions/{executionId}/push")
    public PushResult push(
            @PathVariable String workspaceId,
            @PathVariable String executionId,
            @RequestBody WorkspaceDeliveryRequest request)
    {
        requireNonNull(request, "request is null");
        return pullRequests.push(new PushCommand(workspaceId, executionId(executionId), request.confirmed()));
    }

    @PostMapping("/api/workspaces/{workspaceId}/cherry-pick/executions/{executionId}/pull-request")
    public PullRequestResult createPullRequest(
            @PathVariable String workspaceId,
            @PathVariable String executionId,
            @RequestBody WorkspaceDeliveryRequest request)
    {
        requireNonNull(request, "request is null");
        return pullRequests.createPullRequest(
                new PullRequestCommand(workspaceId, executionId(executionId), request.confirmed()));
    }

    private static CherryPickExecutionId executionId(String value)
    {
        try {
            return CherryPickExecutionId.valueOf(value);
        }
        catch (IllegalArgumentException failure) {
            throw new CherryPickInvalidRequestException("executionId is not a valid identifier");
        }
    }
}

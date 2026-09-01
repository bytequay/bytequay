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

import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.app.cherrypick.CherryPickExecutionService;
import com.bytequay.app.cherrypick.StartCherryPickExecutionCommand;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.cherrypick.CherryPickExecution.PullRequestDraft;
import com.bytequay.core.cherrypick.CherryPickExecutionId;
import com.bytequay.core.cherrypick.CherryPickInvalidRequestException;
import com.google.common.collect.ImmutableList;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Execution endpoints, behind the desktop session filter like every other. */
@RestController
public class CherryPickExecutionController
{
    private final WorkspaceRepositoryService workspaces;
    private final CherryPickExecutionService executions;

    public CherryPickExecutionController(
            WorkspaceRepositoryService workspaces,
            CherryPickExecutionService executions)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.executions = requireNonNull(executions, "executions is null");
    }

    @PostMapping("/api/workspaces/{workspaceId}/cherry-pick/executions")
    @ResponseStatus(HttpStatus.CREATED)
    public CherryPickExecutionResponse start(
            @PathVariable String workspaceId,
            @RequestBody CherryPickExecutionRequest request)
    {
        WorkspaceRepositoryContext context = workspaces.requireReadyRepository(workspaceId);
        return CherryPickExecutionResponse.of(executions.start(
                new StartCherryPickExecutionCommand(
                        text(request.previewId(), "previewId"),
                        text(request.expectedSourceBaseSha(), "expectedSourceBaseSha"),
                        text(request.expectedSourceTipSha(), "expectedSourceTipSha"),
                        text(request.expectedTargetSha(), "expectedTargetSha"),
                        ImmutableList.copyOf(request.userSkippedSourceShas()),
                        text(request.laneId(), "laneId"),
                        text(request.idempotencyKey(), "idempotencyKey"),
                        new PullRequestDraft(
                                text(request.pullRequestTitle(), "pullRequestTitle"),
                                request.pullRequestBody())),
                workspaceId,
                context.repository(),
                RepositoryIdentity.valueOf(context.selectedRepository().fullName())));
    }

    @GetMapping("/api/cherry-pick-ranges/executions")
    public List<CherryPickExecutionResponse> list(@RequestParam(defaultValue = "20") int limit)
    {
        return executions.list(limit).stream()
                .map(CherryPickExecutionResponse::of)
                .collect(toImmutableList());
    }

    @GetMapping("/api/cherry-pick-ranges/executions/{executionId}")
    public CherryPickExecutionResponse get(@PathVariable String executionId)
    {
        return CherryPickExecutionResponse.of(executions.get(executionId(executionId)));
    }

    @PostMapping("/api/cherry-pick-ranges/executions/{executionId}/messages")
    public CherryPickExecutionResponse sendMessage(
            @PathVariable String executionId,
            @RequestBody CherryPickMessageRequest request)
    {
        return CherryPickExecutionResponse.of(executions.sendMessage(
                executionId(executionId), text(request.message(), "message")));
    }

    @PostMapping("/api/workspaces/{workspaceId}/cherry-pick/executions/{executionId}/cancel")
    public CherryPickExecutionResponse cancel(
            @PathVariable String workspaceId,
            @PathVariable String executionId)
    {
        return CherryPickExecutionResponse.of(executions.cancel(executionId(executionId), workspaceId));
    }

    private static CherryPickExecutionId executionId(String value)
    {
        try {
            return CherryPickExecutionId.valueOf(value);
        }
        catch (IllegalArgumentException e) {
            throw new CherryPickInvalidRequestException("executionId is not a valid identifier");
        }
    }

    private static String text(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new CherryPickInvalidRequestException(name + " is blank");
        }
        return value;
    }
}

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
package com.bytequay.app.web;

import com.bytequay.app.flow.runtime.NewFlowAgentPermissions;
import com.bytequay.app.flow.runtime.NewFlowAgentPermissions.PendingApproval;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * The run page's view of agent tool-approval questions: list what a run is
 * asking, answer one question. An answer for a question that already closed
 * (timed out, or the turn ended) is a 404 rather than a silent success.
 */
@RestController
public final class NewFlowAgentPermissionController
{
    private final NewFlowAgentPermissions permissions;

    public NewFlowAgentPermissionController(
            NewFlowAgentPermissions permissions)
    {
        this.permissions = requireNonNull(permissions, "permissions is null");
    }

    @GetMapping("/api/new-flow/runs/{runId}/permissions")
    public List<PendingApproval> pending(@PathVariable String runId)
    {
        if (runId == null || runId.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "runId is required");
        }
        return permissions.pending(runId);
    }

    @PostMapping("/api/new-flow/permissions/{approvalId}")
    public void answer(
            @PathVariable String approvalId,
            @RequestBody AnswerBody body)
    {
        if (body == null || body.allow() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "allow is required");
        }
        if (!permissions.answer(approvalId, body.allow())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "no such pending approval");
        }
    }

    public record AnswerBody(Boolean allow) {}
}

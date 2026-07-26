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

import com.bytequay.app.service.learning.DirectoryScopeService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.bytequay.app.web.RequestValidation.requireBody;
import static java.util.Objects.requireNonNull;

/** Approval-first API for history-derived code areas. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/directory-scopes")
public class DirectoryScopeController
{
    private final DirectoryScopeService scopes;

    public DirectoryScopeController(DirectoryScopeService scopes)
    {
        this.scopes = requireNonNull(scopes, "scopes is null");
    }

    @GetMapping("/suggestions")
    public DirectoryScopeService.Overview suggestions(@PathVariable String workspaceId)
    {
        return scopes.suggestions(workspaceId);
    }

    @PostMapping("/decisions")
    public DirectoryScopeService.Decision decide(
            @PathVariable String workspaceId,
            @RequestBody DecisionBody body)
    {
        body = requireBody(body);
        return scopes.decide(workspaceId, body.path(), body.decision());
    }

    @PutMapping("/threads/{threadId}")
    public DirectoryScopeService.Assignment assign(
            @PathVariable String workspaceId,
            @PathVariable String threadId,
            @RequestBody AssignmentBody body)
    {
        body = requireBody(body);
        return scopes.assign(workspaceId, threadId, body.path());
    }

    @DeleteMapping("/threads/{threadId}")
    public void clear(
            @PathVariable String workspaceId,
            @PathVariable String threadId)
    {
        scopes.clearAssignment(workspaceId, threadId);
    }

    public record DecisionBody(String path, String decision) {}

    public record AssignmentBody(String path) {}
}

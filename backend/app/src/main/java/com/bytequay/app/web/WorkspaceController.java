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

import com.bytequay.app.workspace.WatchRepositoryCommand;
import com.bytequay.app.workspace.WorkspaceDetails;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.app.workspace.WorkspaceSummary;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController
{
    private final WorkspaceRepositoryService workspaces;

    public WorkspaceController(WorkspaceRepositoryService workspaces)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
    }

    @PostMapping
    public WorkspaceSummary watch(@RequestBody WatchRequest request)
    {
        requireNonNull(request, "body is required");
        return WorkspaceSummary.of(workspaces.watch(new WatchRepositoryCommand(
                request.owner(), request.repo(), Optional.ofNullable(request.name()))));
    }

    @GetMapping
    public List<WorkspaceSummary> list()
    {
        return workspaces.listWorkspaces();
    }

    @GetMapping("/{workspaceId}")
    public WorkspaceDetails get(@PathVariable String workspaceId)
    {
        return workspaces.getWorkspace(workspaceId);
    }

    @PostMapping("/{workspaceId}/sync")
    public WorkspaceSummary sync(@PathVariable String workspaceId)
    {
        return WorkspaceSummary.of(workspaces.requestSync(workspaceId));
    }

    @DeleteMapping("/{workspaceId}")
    public Map<String, String> unwatch(@PathVariable String workspaceId)
    {
        workspaces.unwatch(workspaceId);
        return Map.of("result", "removed");
    }

    public record WatchRequest(String owner, String repo, String name) {}
}

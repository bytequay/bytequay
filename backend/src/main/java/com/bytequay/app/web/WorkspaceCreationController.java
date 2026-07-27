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

import com.bytequay.app.beans.workspace.WorkspaceCreationDto;
import com.bytequay.app.service.workspaces.WorkspaceCreationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static java.util.Objects.requireNonNull;

@RestController
@RequestMapping("/api/workspace-creations")
public class WorkspaceCreationController
{
    private final WorkspaceCreationService creations;

    public WorkspaceCreationController(WorkspaceCreationService creations)
    {
        this.creations = requireNonNull(creations, "creations is null");
    }

    @PostMapping
    public WorkspaceCreationDto create(@RequestBody CreateRequest request)
    {
        requireNonNull(request, "request is null");
        return creations.create(
                request.owner(), request.repo(), request.writeMode(),
                request.existingForkRepo());
    }

    @GetMapping
    public List<WorkspaceCreationDto> list()
    {
        return creations.list();
    }

    @GetMapping("/{id}")
    public WorkspaceCreationDto get(@PathVariable String id)
    {
        return creations.require(id);
    }

    @PostMapping("/{id}/retry")
    public WorkspaceCreationDto retry(@PathVariable String id)
    {
        return creations.retry(id);
    }

    public record CreateRequest(
            String owner,
            String repo,
            String writeMode,
            String existingForkRepo) {}
}

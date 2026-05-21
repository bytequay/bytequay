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

import com.bytequay.app.domain.Workspace;
import com.bytequay.app.domain.WorkspaceRepo;
import com.bytequay.app.service.workspaces.WorkspaceService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * REST surface for the Workspace tier. v1 only has one workspace
 * ({@code ws-default}), but the URLs are workspace-id-shaped so the
 * multi-workspace switcher in a later phase doesn't need to re-route.
 */
@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController
{
    private final WorkspaceService workspaces;

    public WorkspaceController(WorkspaceService workspaces)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
    }

    @GetMapping
    public List<Workspace> list()
    {
        return workspaces.list();
    }

    @GetMapping("/{id}")
    public Workspace get(@PathVariable String id)
    {
        return workspaces.require(id);
    }

    @GetMapping("/{id}/memory")
    public Map<String, String> getMemory(@PathVariable String id)
    {
        return Map.of("memoryMd", workspaces.getMemory(id));
    }

    @PutMapping("/{id}/memory")
    public Workspace setMemory(@PathVariable String id, @RequestBody MemoryBody body)
    {
        return workspaces.setMemory(id, body.memoryMd() == null ? "" : body.memoryMd());
    }

    @GetMapping("/{id}/repos")
    public List<WorkspaceRepo> listRepos(@PathVariable String id)
    {
        return workspaces.listRepos(id);
    }

    @PostMapping("/{id}/repos")
    public WorkspaceRepo addRepo(@PathVariable String id, @RequestBody RepoAttachBody body)
    {
        return workspaces.addRepo(id, body.repoFullName(), body.defaultBaseBranch());
    }

    @DeleteMapping("/{id}/repos/{owner}/{repo}")
    public void removeRepo(@PathVariable String id,
            @PathVariable String owner, @PathVariable String repo)
    {
        workspaces.removeRepo(id, owner + "/" + repo);
    }

    @PutMapping("/{id}/repos/{owner}/{repo}/default-base-branch")
    public WorkspaceRepo setDefaultBaseBranch(@PathVariable String id,
            @PathVariable String owner, @PathVariable String repo,
            @RequestBody DefaultBaseBranchBody body)
    {
        return workspaces.setDefaultBaseBranch(id, owner + "/" + repo, body.defaultBaseBranch());
    }

    public record MemoryBody(String memoryMd) {}

    public record RepoAttachBody(String repoFullName, String defaultBaseBranch) {}

    public record DefaultBaseBranchBody(String defaultBaseBranch) {}
}

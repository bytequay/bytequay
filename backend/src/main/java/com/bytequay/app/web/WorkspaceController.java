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
import com.bytequay.app.domain.WorkspaceMemoryProposal;
import com.bytequay.app.domain.WorkspaceRepo;
import com.bytequay.app.service.workspaces.WorkspaceMemoryDistiller;
import com.bytequay.app.service.workspaces.WorkspaceMemoryProposalService;
import com.bytequay.app.service.workspaces.WorkspaceService;
import org.springframework.http.ResponseEntity;
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
    private final WorkspaceMemoryDistiller distiller;
    private final WorkspaceMemoryProposalService proposals;

    public WorkspaceController(
            WorkspaceService workspaces,
            WorkspaceMemoryDistiller distiller,
            WorkspaceMemoryProposalService proposals)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.distiller = requireNonNull(distiller, "distiller is null");
        this.proposals = requireNonNull(proposals, "proposals is null");
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

    /** Force a fresh distillation pass of the workspace memory from
     *  the active Thread Overalls. The scheduled job runs every 30
     *  minutes; this endpoint is the "do it now" trigger users
     *  reach for after configuring the Anthropic key or after a
     *  heavy day's worth of thread activity. Returns the upserted
     *  proposal, or 204 when there was nothing to fold in (no
     *  Overalls yet, scratch workspace, or proposed body identical
     *  to current memory). The user confirms via
     *  {@link #applyMemoryProposal} before anything lands in
     *  {@code memory_md}. */
    @PostMapping("/{id}/memory/distill")
    public ResponseEntity<WorkspaceMemoryProposal> distillMemory(@PathVariable String id)
    {
        return distiller.distill(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** Return the pending memory proposal for this workspace, or 204
     *  when there isn't one. The frontend polls this to drive the
     *  banner in WorkspaceMemoryPage. */
    @GetMapping("/{id}/memory/proposal")
    public ResponseEntity<WorkspaceMemoryProposal> getMemoryProposal(@PathVariable String id)
    {
        return proposals.find(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** Confirm the pending proposal — write its body back to
     *  {@code memory_md} and clear the row. 409 when the workspace's
     *  memory has been hand-edited since the proposal was generated
     *  (drift check), so a stale proposal can't clobber the edit. */
    @PostMapping("/{id}/memory/proposal/apply")
    public Workspace applyMemoryProposal(@PathVariable String id)
    {
        return proposals.apply(id);
    }

    /** Drop the pending proposal without writing anything. 404 when
     *  no proposal exists; the UI should only render Discard when
     *  GET .../proposal returns 200. */
    @PostMapping("/{id}/memory/proposal/discard")
    public void discardMemoryProposal(@PathVariable String id)
    {
        proposals.discard(id);
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

    /** Flip the headless auto-fix opt-in on or off for one repo.
     *  Off by default per CLAUDE.md — only when this is explicitly
     *  enabled will the automation coordinator spawn a CLI agent
     *  against a failing-CI candidate. */
    @PutMapping("/{id}/repos/{owner}/{repo}/auto-fix-enabled")
    public WorkspaceRepo setAutoFixEnabled(@PathVariable String id,
            @PathVariable String owner, @PathVariable String repo,
            @RequestBody AutoFixEnabledBody body)
    {
        return workspaces.setAutoFixEnabled(id, owner + "/" + repo, body.autoFixEnabled());
    }

    public record MemoryBody(String memoryMd) {}

    public record RepoAttachBody(String repoFullName, String defaultBaseBranch) {}

    public record DefaultBaseBranchBody(String defaultBaseBranch) {}

    public record AutoFixEnabledBody(boolean autoFixEnabled) {}
}

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
package com.bytequay.app.service.workspaces;

import com.bytequay.app.domain.Workspace;
import com.bytequay.app.domain.WorkspaceRepo;
import com.bytequay.app.repository.WorkspaceStore;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Service surface for the Workspace tier. Owns the workspace memory
 * (a small markdown blob loaded into every thread's context), the
 * list of repos attached to each workspace, and per-(workspace, repo)
 * defaults used by ship-and-continue (notably the merge-target
 * branch).
 *
 * <p>v1 keeps a single ambient workspace ({@code ws-default} named
 * "ByteQuay"). Multi-workspace creation lands later; this service
 * already accepts the workspace id everywhere so the multi-workspace
 * path is just a routing problem.
 */
@Service
public class WorkspaceService
{
    /**
     * Id of the ambient workspace seeded by V73. The frontend treats
     * this as "the workspace" until multi-workspace switching ships.
     */
    public static final String DEFAULT_WORKSPACE_ID = "ws-default";

    /**
     * Soft upper bound on the workspace memory size — loaded into
     * every thread, so growth has a 1:N cost. Distillation keeps the
     * blob well under this; manual edits get a 4× hard limit so the
     * user can paste a large block while distillation re-condenses it.
     */
    public static final int MEMORY_MD_TARGET_CHARS = 8_000;
    public static final int MEMORY_MD_HARD_CAP_CHARS = 32_000;

    private final WorkspaceStore store;

    public WorkspaceService(WorkspaceStore store)
    {
        this.store = requireNonNull(store, "store is null");
    }

    public List<Workspace> list()
    {
        return store.listWorkspaces();
    }

    public Workspace require(String workspaceId)
    {
        return store.findWorkspaceById(workspaceId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no workspace: " + workspaceId));
    }

    public String getMemory(String workspaceId)
    {
        return require(workspaceId).memoryMd();
    }

    /**
     * Writes the markdown body wholesale. Rejects oversized payloads
     * outright; distillation is responsible for keeping the memory
     * within the soft target.
     */
    public Workspace setMemory(String workspaceId, String memoryMd)
    {
        requireNonNull(memoryMd, "memoryMd is null");
        if (memoryMd.length() > MEMORY_MD_HARD_CAP_CHARS) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(413),
                    "workspace memory exceeds hard cap of " + MEMORY_MD_HARD_CAP_CHARS + " chars");
        }
        Workspace current = require(workspaceId);
        Workspace next = new Workspace(
                current.id(), current.name(), memoryMd,
                current.isScratch(), current.createdAt(), Instant.now());
        store.saveWorkspace(next);
        return next;
    }

    public List<WorkspaceRepo> listRepos(String workspaceId)
    {
        require(workspaceId);
        return store.listRepos(workspaceId);
    }

    /**
     * Attach a repo to the workspace. Idempotent on
     * {@code (workspaceId, repoFullName)}.
     */
    public WorkspaceRepo addRepo(String workspaceId, String repoFullName, String defaultBaseBranch)
    {
        require(workspaceId);
        requireNonNull(repoFullName, "repoFullName is null");
        WorkspaceRepo repo = new WorkspaceRepo(
                workspaceId, repoFullName.trim(),
                trimToNull(defaultBaseBranch), Instant.now());
        store.addRepo(repo);
        return repo;
    }

    public void removeRepo(String workspaceId, String repoFullName)
    {
        require(workspaceId);
        store.removeRepo(workspaceId, repoFullName);
    }

    /**
     * Resolve the per-repo merge-target branch for ship-and-continue.
     * Used as the "from main" base when cutting the next task. Returns
     * empty when no override is configured; the caller then falls back
     * to {@code git defaultBranch}.
     */
    public Optional<String> findDefaultBaseBranch(String workspaceId, String repoFullName)
    {
        return store.findRepo(workspaceId, repoFullName)
                .map(WorkspaceRepo::defaultBaseBranch)
                .filter(s -> s != null && !s.isBlank());
    }

    public WorkspaceRepo setDefaultBaseBranch(String workspaceId, String repoFullName, String branch)
    {
        require(workspaceId);
        String normalised = trimToNull(branch);
        store.setDefaultBaseBranch(workspaceId, repoFullName, normalised);
        return store.findRepo(workspaceId, repoFullName)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404),
                        repoFullName + " not attached to workspace " + workspaceId));
    }

    private static String trimToNull(String s)
    {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}

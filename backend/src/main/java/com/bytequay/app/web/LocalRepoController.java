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

import com.bytequay.app.domain.LocalBranch;
import com.bytequay.app.domain.LocalRepoStatus;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.local.LocalRepoService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * REST surface for the Repos page. Reads only — clone / locate / push
 * land in follow-up commits once the list view ships.
 */
@RestController
@RequestMapping("/api/repos/local")
public class LocalRepoController
{
    private final LocalRepoService localRepoService;
    private final WatchedRepoStore watchedRepoStore;

    public LocalRepoController(LocalRepoService localRepoService, WatchedRepoStore watchedRepoStore)
    {
        this.localRepoService = requireNonNull(localRepoService, "localRepoService is null");
        this.watchedRepoStore = requireNonNull(watchedRepoStore, "watchedRepoStore is null");
    }

    /**
     * GET /api/repos/local — one row per watched repo with its
     * local-clone state. Drives the cards on the Repos page.
     */
    @GetMapping
    public List<LocalRepoStatus> listAll()
    {
        return localRepoService.listAll();
    }

    /**
     * PUT /api/repos/local/{owner}/{repo}/path — record a local
     * working-copy path against a watched repo. Pass an empty string
     * to unmap. The clone / locate-existing flows on the Repos page
     * call this once the user picks a destination.
     */
    @PutMapping("/{owner}/{repo}/path")
    public void setLocalClonePath(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo,
            @RequestBody PathRequest body)
    {
        String path = body == null || body.path() == null || body.path().isBlank() ? null : body.path();
        watchedRepoStore.setLocalClonePath(owner, repo, path);
    }

    /**
     * GET /api/repos/local/{owner}/{repo}/default-clone-path —
     * suggested destination the modal pre-fills for the Clone-fresh
     * flow. Computed from the user's home dir; sent down rather than
     * computed on the renderer so we keep the path-shape decision
     * server-side.
     */
    @GetMapping("/{owner}/{repo}/default-clone-path")
    public DefaultClonePathResponse defaultClonePath(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo)
    {
        return new DefaultClonePathResponse(LocalRepoService.defaultClonePath(owner, repo).toString());
    }

    /**
     * POST /api/repos/local/{owner}/{repo}/clone — clones the
     * watched repo's GitHub URL into {@code body.destination} and
     * records the path. Synchronous; big repos can take minutes, so
     * the renderer shows a "Cloning…" state while this is in flight.
     */
    @PostMapping("/{owner}/{repo}/clone")
    public LocalRepoStatus clone(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo,
            @RequestBody CloneRequest body)
    {
        if (body == null || body.destination() == null || body.destination().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "destination is required");
        }
        try {
            return localRepoService.cloneFresh(owner, repo, Path.of(body.destination()));
        }
        catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "clone interrupted");
        }
    }

    /**
     * POST /api/repos/local/{owner}/{repo}/locate — register an
     * existing local working tree as the watched repo's clone. The
     * service verifies the folder is a git working tree whose origin
     * matches the watched repo; mismatches surface as 400 with a
     * humane message the modal can render inline.
     */
    @PostMapping("/{owner}/{repo}/locate")
    public LocalRepoStatus locate(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo,
            @RequestBody PathRequest body)
    {
        if (body == null || body.path() == null || body.path().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path is required");
        }
        try {
            return localRepoService.locateExisting(owner, repo, Path.of(body.path()));
        }
        catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "locate interrupted");
        }
    }

    /**
     * GET /api/repos/local/{owner}/{repo}/branches — local branches
     * of the mapped working tree, with metadata that drives the
     * branches kanban (current flag, last-commit time, upstream
     * tracking, ahead/behind counts).
     */
    @GetMapping("/{owner}/{repo}/branches")
    public List<LocalBranch> listBranches(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo)
    {
        try {
            return localRepoService.listBranches(owner, repo);
        }
        catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
        catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "branch listing interrupted");
        }
    }

    /**
     * POST /api/repos/local/{owner}/{repo}/fetch — runs
     * {@code git fetch --all --prune}. Returns the refreshed status
     * row so the UI can update without a separate list refetch.
     */
    @PostMapping("/{owner}/{repo}/fetch")
    public LocalRepoStatus fetch(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo)
    {
        return runGitOperation(() -> localRepoService.fetch(owner, repo));
    }

    /**
     * POST /api/repos/local/{owner}/{repo}/pull — runs a
     * fast-forward-only pull on the current branch. Diverging
     * histories return 409 with git's stderr verbatim.
     */
    @PostMapping("/{owner}/{repo}/pull")
    public LocalRepoStatus pull(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo)
    {
        return runGitOperation(() -> localRepoService.pull(owner, repo));
    }

    /**
     * POST /api/repos/local/{owner}/{repo}/push — pushes the current
     * branch. First-time pushes auto-set tracking via
     * {@code -u origin HEAD}. Non-fast-forward pushes return 409
     * with git's stderr verbatim.
     */
    @PostMapping("/{owner}/{repo}/push")
    public LocalRepoStatus push(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo)
    {
        return runGitOperation(() -> localRepoService.push(owner, repo));
    }

    /**
     * POST /api/repos/local/{owner}/{repo}/branches — creates a new
     * branch from {@code body.base} (or current HEAD when omitted)
     * and switches to it. Returns 400 on missing/blank name, 409
     * when git refuses (e.g. branch already exists).
     */
    @PostMapping("/{owner}/{repo}/branches")
    public LocalRepoStatus createBranch(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo,
            @RequestBody CreateBranchRequest body)
    {
        if (body == null || body.name() == null || body.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        return runGitOperation(() -> localRepoService.createBranch(owner, repo, body.name(), body.base()));
    }

    private LocalRepoStatus runGitOperation(GitOp op)
    {
        try {
            return op.call();
        }
        catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
        catch (GitRunner.GitCommandException e) {
            // Non-zero git exit (most commonly: not a fast-forward,
            // network failure, auth required). 409 conveys "the op
            // is well-formed but git refused" — let the UI render
            // git's stderr inline.
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.stderr().strip());
        }
        catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "git operation interrupted");
        }
    }

    @FunctionalInterface
    private interface GitOp
    {
        LocalRepoStatus call()
                throws IOException, InterruptedException;
    }

    public record PathRequest(String path) {}
    public record CloneRequest(String destination) {}
    public record DefaultClonePathResponse(String defaultPath) {}
    public record CreateBranchRequest(String name, String base) {}
}

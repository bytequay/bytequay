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

import com.bytequay.app.domain.LocalActivityEntry;
import com.bytequay.app.domain.LocalBranch;
import com.bytequay.app.domain.LocalCommit;
import com.bytequay.app.domain.LocalRepoStatus;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.local.LocalRepoService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final PatResolver patResolver;

    public LocalRepoController(
            LocalRepoService localRepoService,
            WatchedRepoStore watchedRepoStore,
            PatResolver patResolver)
    {
        this.localRepoService = requireNonNull(localRepoService, "localRepoService is null");
        this.watchedRepoStore = requireNonNull(watchedRepoStore, "watchedRepoStore is null");
        this.patResolver = requireNonNull(patResolver, "patResolver is null");
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
     * GET /api/repos/local/{owner}/{repo}/commits — recent commits on
     * {@code revision} (default HEAD). {@code limit} is capped server-
     * side so a runaway request can't ask {@code git log} for the
     * entire history of a million-commit repo.
     */
    @GetMapping("/{owner}/{repo}/commits")
    public List<LocalCommit> listCommits(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo,
            @RequestParam(name = "revision", required = false) String revision,
            @RequestParam(name = "limit", required = false, defaultValue = "100") int limit)
    {
        // Cap matches what the Commits tab UI scrolls without paging.
        // Bump together when paging lands.
        int capped = Math.min(Math.max(limit, 1), 500);
        try {
            return localRepoService.listCommits(owner, repo, revision, capped);
        }
        catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
        catch (GitRunner.GitCommandException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.stderr().strip());
        }
        catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "commit listing interrupted");
        }
    }

    /**
     * GET /api/repos/local/{owner}/{repo}/activity — recent reflog
     * entries (HEAD-mutating events: commits, checkouts, merges,
     * pulls, rebases). {@code limit} is server-capped.
     */
    @GetMapping("/{owner}/{repo}/activity")
    public List<LocalActivityEntry> listActivity(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo,
            @RequestParam(name = "limit", required = false, defaultValue = "100") int limit)
    {
        int capped = Math.min(Math.max(limit, 1), 500);
        try {
            return localRepoService.listActivity(owner, repo, capped);
        }
        catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
        catch (GitRunner.GitCommandException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.stderr().strip());
        }
        catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "activity listing interrupted");
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
     * POST /api/repos/local/{owner}/{repo}/push-force —
     * {@code git push --force-with-lease}. Refuses to act unless the
     * request body includes {@code "confirmed": true}, so a missing
     * confirmation reads as a 400 rather than silently rewriting
     * remote history.
     */
    @PostMapping("/{owner}/{repo}/push-force")
    public LocalRepoStatus pushForce(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo,
            @RequestBody(required = false) ForcePushRequest body)
    {
        if (body == null || !body.confirmed()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Force-with-lease push requires explicit user confirmation");
        }
        return runGitOperation(() -> localRepoService.pushForceWithLease(owner, repo));
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

    /**
     * POST /api/repos/local/{owner}/{repo}/branches/switch —
     * switches HEAD to an existing local branch. 409 if the working
     * tree has uncommitted changes that conflict with the target.
     */
    @PostMapping("/{owner}/{repo}/branches/switch")
    public LocalRepoStatus switchBranch(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo,
            @RequestBody SwitchBranchRequest body)
    {
        if (body == null || body.name() == null || body.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        return runGitOperation(() -> localRepoService.switchBranch(owner, repo, body.name()));
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

    /**
     * POST /api/repos/local/{owner}/{repo}/pull-requests — opens a
     * pull request on github.com against the watched repo, with the
     * local clone's HEAD branch as the source. Honors the upstream
     * remote name recorded by the locate flow: fork-based clones
     * push as {@code "<forkOwner>:<branch>"}, direct clones push as
     * a bare branch name.
     */
    @PostMapping("/{owner}/{repo}/pull-requests")
    public CreatePrResponse createPullRequest(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo,
            @RequestBody CreatePrRequest body)
    {
        if (body == null || body.title() == null || body.title().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title is required");
        }
        String pat = patResolver.resolve(owner + "/" + repo);
        try {
            PullRequest created = localRepoService.createPullRequest(
                    pat, owner, repo, body.title(), body.body(),
                    body.base(), body.draft());
            return new CreatePrResponse(created.number(), created.htmlUrl());
        }
        catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
        catch (GitRunner.GitCommandException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.stderr().strip());
        }
        catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "PR creation interrupted");
        }
    }

    /**
     * DELETE /api/repos/local/{owner}/{repo}/branches — deletes the
     * named local branches. The current branch is always refused;
     * everything else is allowed (cleanup classification is advisory
     * for the UI, not a hard gate). When {@code deleteRemote} is
     * true, also runs {@code git push origin --delete <branch>} for
     * any deleted branch that has an upstream tracking ref.
     *
     * Per-card delete from the UI sends a single name; the same
     * endpoint supports multi-name input for any future bulk caller.
     */
    @DeleteMapping("/{owner}/{repo}/branches")
    public DeleteBranchesResponse deleteBranches(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo,
            @RequestBody DeleteBranchesRequest body)
    {
        if (body == null || body.names() == null || body.names().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "names is required");
        }
        try {
            return new DeleteBranchesResponse(
                    localRepoService.deleteBranches(owner, repo, body.names(), body.deleteRemote()));
        }
        catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
        catch (GitRunner.GitCommandException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.stderr().strip());
        }
        catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "delete interrupted");
        }
    }

    public record PathRequest(String path) {}
    public record CloneRequest(String destination) {}
    public record DefaultClonePathResponse(String defaultPath) {}
    public record CreateBranchRequest(String name, String base) {}
    public record SwitchBranchRequest(String name) {}
    public record ForcePushRequest(boolean confirmed) {}
    public record DeleteBranchesRequest(List<String> names, boolean deleteRemote) {}
    public record DeleteBranchesResponse(List<String> deleted) {}
    public record CreatePrRequest(String title, String body, String base, boolean draft) {}
    public record CreatePrResponse(int number, String htmlUrl) {}
}

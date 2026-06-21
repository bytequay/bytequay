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
import com.bytequay.app.domain.LocalCommitDetail;
import com.bytequay.app.domain.LocalCommitFile;
import com.bytequay.app.domain.LocalFileDiff;
import com.bytequay.app.domain.LocalMergeBase;
import com.bytequay.app.domain.LocalRepoStatus;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestDraft;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.local.LocalRepoService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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

import static com.bytequay.app.utils.StringInputUtil.requireNotBlank;
import static com.bytequay.app.web.RequestValidation.requireBody;
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

    public LocalRepoController(
            LocalRepoService localRepoService,
            WatchedRepoStore watchedRepoStore)
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
        CloneRequest request = requireBody(body);
        requireNotBlank(request.destination(), "destination is required");
        try {
            return localRepoService.cloneFresh(owner, repo, Path.of(request.destination()));
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
        PathRequest request = requireBody(body);
        requireNotBlank(request.path(), "path is required");
        try {
            return localRepoService.locateExisting(owner, repo, Path.of(request.path()));
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
        return runLocalRepoOperation(
                () -> localRepoService.listBranches(owner, repo),
                "branch listing interrupted");
    }

    /**
     * GET /api/repos/local/{owner}/{repo}/conflict-paths?prNumber=N&baseRef=main
     * — enumerates the file paths in conflict between a PR's head and
     * its base, using {@code git merge-tree --name-only}. Drives the
     * "⚠ Conflict (N files)" pill on the PR detail page; gracefully
     * degrades (returns {@code available: false} + a reason token)
     * when the repo isn't locally cloned or the fetch fails, so the
     * pill can stay as a plain "open on GitHub" link in that case.
     *
     * <p>Always 200 so the frontend has a single non-error path to
     * branch on; failure modes are encoded in the response body.
     */
    @GetMapping("/{owner}/{repo}/conflict-paths")
    public LocalRepoService.MergeConflictPaths conflictPaths(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo,
            @RequestParam("prNumber") int prNumber,
            @RequestParam("baseRef") String baseRef)
    {
        return localRepoService.listMergeConflictPaths(owner, repo, prNumber, baseRef);
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
        return runLocalRepoOperation(
                () -> localRepoService.listCommits(owner, repo, revision, capped),
                "commit listing interrupted");
    }

    /**
     * GET /api/repos/local/{owner}/{repo}/commits/{sha}/files —
     * lists every file touched by a single commit, with status and
     * line counts. Powers the middle pane of the Commits tab.
     */
    @GetMapping("/{owner}/{repo}/commits/{sha}/files")
    public List<LocalCommitFile> commitFiles(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo,
            @PathVariable("sha") String sha)
    {
        return runLocalRepoOperation(
                () -> localRepoService.commitFiles(owner, repo, sha),
                "commit-files listing interrupted");
    }

    /**
     * GET /api/repos/local/{owner}/{repo}/working-tree/files —
     * working-tree files (uncommitted: staged + unstaged + untracked)
     * via git status --porcelain. Powers the Commits tab's "Changes"
     * mode middle pane.
     */
    @GetMapping("/{owner}/{repo}/working-tree/files")
    public List<LocalCommitFile> workingTreeFiles(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo)
    {
        return runLocalRepoOperation(
                () -> localRepoService.workingTreeFiles(owner, repo),
                "working-tree status interrupted");
    }

    /**
     * GET /api/repos/local/{owner}/{repo}/working-tree/diff?path= —
     * working-tree diff for one file (git diff HEAD -- path, with
     * an untracked-file fallback). Powers the Commits tab's
     * "Changes" mode right pane.
     */
    @GetMapping("/{owner}/{repo}/working-tree/diff")
    public LocalFileDiff workingTreeFileDiff(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo,
            @RequestParam("path") String filePath)
    {
        return runLocalRepoOperation(
                () -> localRepoService.workingTreeFileDiff(owner, repo, filePath),
                "working-tree diff fetch interrupted");
    }

    /**
     * GET /api/repos/local/{owner}/{repo}/commits/{sha}/detail —
     * subject + body of a single commit. Lazy-fetched when the user
     * selects a commit in the Commits tab so the patch-detail card
     * can show the full message.
     */
    @GetMapping("/{owner}/{repo}/commits/{sha}/detail")
    public LocalCommitDetail commitDetail(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo,
            @PathVariable("sha") String sha)
    {
        return runLocalRepoOperation(
                () -> localRepoService.commitDetail(owner, repo, sha),
                "commit-detail fetch interrupted");
    }

    /**
     * GET /api/repos/local/{owner}/{repo}/commits/{sha}/diff?path= —
     * unified diff for a single file at this commit. Drives the right
     * pane of the Commits tab.
     */
    @GetMapping("/{owner}/{repo}/commits/{sha}/diff")
    public LocalFileDiff commitFileDiff(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo,
            @PathVariable("sha") String sha,
            @RequestParam("path") String filePath)
    {
        return runLocalRepoOperation(
                () -> localRepoService.commitFileDiff(owner, repo, sha, filePath),
                "commit-diff fetch interrupted");
    }

    /**
     * GET /api/repos/local/{owner}/{repo}/range/files?base=&head= —
     * file list for the diff between two refs. Used by the Commits
     * tab's compare-branches mode. Both refs may be branch names
     * or shas; falls through origin/<name> on missing local refs.
     */
    @GetMapping("/{owner}/{repo}/range/files")
    public List<LocalCommitFile> rangeFiles(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo,
            @RequestParam("base") String base,
            @RequestParam("head") String head)
    {
        return runLocalRepoOperation(
                () -> localRepoService.rangeFiles(owner, repo, base, head),
                "range-files lookup interrupted");
    }

    /**
     * GET /api/repos/local/{owner}/{repo}/range/diff?base=&head=&path=
     * — unified diff for one file between two refs (git diff
     * base..head -- path). Used by the Commits tab's compare-branches
     * mode. Differs from /commits-range/diff in that there's no ^
     * shift — branch refs aren't shas, so ^ would point at the
     * wrong commit.
     */
    @GetMapping("/{owner}/{repo}/range/diff")
    public LocalFileDiff rangeFileDiff(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo,
            @RequestParam("base") String base,
            @RequestParam("head") String head,
            @RequestParam("path") String filePath)
    {
        return runLocalRepoOperation(
                () -> localRepoService.rangeFileDiff(owner, repo, base, head, filePath),
                "range diff fetch interrupted");
    }

    /**
     * GET /api/repos/local/{owner}/{repo}/commits-range/diff
     *     ?oldest=&newest=&path= — unified diff for one file across
     * the commit range {@code oldest^..newest}. Used by the Commits
     * tab when more than one commit is selected.
     */
    @GetMapping("/{owner}/{repo}/commits-range/diff")
    public LocalFileDiff commitRangeFileDiff(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo,
            @RequestParam("oldest") String oldestSha,
            @RequestParam("newest") String newestSha,
            @RequestParam("path") String filePath)
    {
        return runLocalRepoOperation(
                () -> localRepoService.commitRangeFileDiff(owner, repo, oldestSha, newestSha, filePath),
                "commit range-diff fetch interrupted");
    }

    /**
     * GET /api/repos/local/{owner}/{repo}/merge-base?branch=&base= —
     * merge-base sha of {@code branch} and {@code base}. {@code base}
     * is optional; when omitted, falls back to the repo's default
     * branch. Returns {@code {sha:null,base:null}} when no common
     * ancestor exists, so the UI can quietly skip the divider.
     */
    @GetMapping("/{owner}/{repo}/merge-base")
    public LocalMergeBase mergeBase(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo,
            @RequestParam("branch") String branch,
            @RequestParam(name = "base", required = false) String base)
    {
        return runLocalRepoOperation(
                () -> localRepoService.mergeBase(owner, repo, branch, base),
                "merge-base lookup interrupted");
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
        return runLocalRepoOperation(
                () -> localRepoService.listActivity(owner, repo, capped),
                "activity listing interrupted");
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
        return runLocalRepoOperation(
                () -> localRepoService.fetch(owner, repo),
                "git operation interrupted");
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
        return runLocalRepoOperation(
                () -> localRepoService.pull(owner, repo),
                "git operation interrupted");
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
        return runLocalRepoOperation(
                () -> localRepoService.push(owner, repo),
                "git operation interrupted");
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
        return runLocalRepoOperation(
                () -> localRepoService.pushForceWithLease(owner, repo),
                "git operation interrupted");
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
        CreateBranchRequest request = requireBody(body);
        requireNotBlank(request.name(), "name is required");
        return runLocalRepoOperation(
                () -> localRepoService.createBranch(owner, repo, request.name(), request.base()),
                "git operation interrupted");
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
        SwitchBranchRequest request = requireBody(body);
        requireNotBlank(request.name(), "name is required");
        return runLocalRepoOperation(
                () -> localRepoService.switchBranch(owner, repo, request.name()),
                "git operation interrupted");
    }

    /**
     * POST /api/repos/local/{owner}/{repo}/branches/checkout-remote —
     * fetches a branch from origin and switches to it. Used by
     * IN_REVIEW phantom cards to materialize a PR's head branch
     * locally on demand. 409 surfaces git's stderr (no such ref on
     * origin, dirty tree, etc.) so the modal can show it inline.
     */
    @PostMapping("/{owner}/{repo}/branches/checkout-remote")
    public LocalRepoStatus checkoutRemoteBranch(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo,
            @RequestBody SwitchBranchRequest body)
    {
        SwitchBranchRequest request = requireBody(body);
        requireNotBlank(request.name(), "name is required");
        return runLocalRepoOperation(
                () -> localRepoService.checkoutRemoteBranch(owner, repo, request.name()),
                "git operation interrupted");
    }

    private <T> T runLocalRepoOperation(LocalRepoOp<T> op, String interruptedMessage)
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
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, interruptedMessage);
        }
    }

    @FunctionalInterface
    private interface LocalRepoOp<T>
    {
        T call()
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
        CreatePrRequest request = requireBody(body);
        requireNotBlank(request.title(), "title is required");
        return runLocalRepoOperation(() -> {
            PullRequest created = localRepoService.createPullRequest(
                    owner, repo, request.title(), request.body(),
                    request.base(), request.draft());
            return new CreatePrResponse(created.number(), created.htmlUrl());
        }, "PR creation interrupted");
    }

    /**
     * POST /api/repos/local/{owner}/{repo}/pull-requests/draft —
     * asks the active LLM to draft a title + description from the
     * diff between the current branch and {@code body.base}. The
     * client fills the response into the Open-PR form so the user
     * can hand-edit before submitting.
     */
    @PostMapping("/{owner}/{repo}/pull-requests/draft")
    public PullRequestDraft draftPullRequest(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo,
            @RequestBody DraftPrRequest body)
    {
        try {
            return localRepoService.draftPullRequestWithAi(
                    owner, repo,
                    body == null ? null : body.base(),
                    body == null ? null : body.head());
        }
        catch (IllegalStateException e) {
            // Covers: HEAD detached, no diff, no API key configured,
            // model JSON malformed. CONFLICT carries the message
            // straight to the modal.
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        catch (GitRunner.GitCommandException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.stderr().strip());
        }
        catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "draft interrupted");
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
        return runLocalRepoOperation(
                () -> new DeleteBranchesResponse(
                        localRepoService.deleteBranches(owner, repo, body.names(), body.deleteRemote())),
                "delete interrupted");
    }

    /**
     * PATCH /api/repos/local/{owner}/{repo}/view-focus — persists the
     * user's choice of commits-tab focus for the repo detail page.
     * Body: {@code {"viewFocus": "fork"}} or
     * {@code {"viewFocus": "upstream"}}. Returns the refreshed status
     * row. No git or GitHub calls — pure local DB write.
     */
    @PatchMapping("/{owner}/{repo}/view-focus")
    public LocalRepoStatus setViewFocus(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo,
            @RequestBody ViewFocusRequest body)
    {
        if (body == null || body.viewFocus() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "viewFocus is required");
        }
        try {
            return localRepoService.setViewFocus(owner, repo, body.viewFocus());
        }
        catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    public record PathRequest(String path) {}
    public record CloneRequest(String destination) {}
    public record ViewFocusRequest(String viewFocus) {}
    public record DefaultClonePathResponse(String defaultPath) {}
    public record CreateBranchRequest(String name, String base) {}
    public record SwitchBranchRequest(String name) {}
    public record ForcePushRequest(boolean confirmed) {}
    public record DeleteBranchesRequest(List<String> names, boolean deleteRemote) {}
    public record DeleteBranchesResponse(List<String> deleted) {}
    public record CreatePrRequest(String title, String body, String base, boolean draft) {}
    public record CreatePrResponse(int number, String htmlUrl) {}
    public record DraftPrRequest(String base, String head) {}
}

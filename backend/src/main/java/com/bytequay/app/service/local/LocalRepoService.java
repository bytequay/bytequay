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
package com.bytequay.app.service.local;

import com.bytequay.app.domain.CreatePullRequestCommand;
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
import com.bytequay.app.domain.RepoMeta;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.PullRequestStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.ai.LlmReviewerRegistry;
import com.bytequay.app.service.codegraph.CodeGraphUpdateCoordinator;
import com.bytequay.app.service.credentials.PatResolver;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Drives the Repos page. Joins the watched-repos table with the
 * working-tree state of each managed clone, returning one
 * {@link LocalRepoStatus} per watched repo.
 */
@Service
public class LocalRepoService
{
    private static final Logger log = LoggerFactory.getLogger(LocalRepoService.class);
    private static final String UPSTREAM_REMOTE = "upstream";
    private static final Duration FORK_READY_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration FORK_READY_POLL = Duration.ofSeconds(2);

    public enum WriteMode
    {
        FORK,
        DIRECT;

        public static WriteMode parse(String raw)
        {
            if (raw == null || raw.isBlank()) {
                return FORK;
            }
            return WriteMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        }
    }

    public record ManagedClonePlan(
            String viewerLogin,
            boolean directAvailable,
            boolean forkAvailable,
            WriteMode defaultWriteMode,
            String destination) {}

    public record PreparedClone(
            Path path,
            String upstreamRemoteName) {}

    private final WatchedRepoStore watchedRepoStore;
    private final GitRunner gitRunner;
    private final PullRequestRepository gitHub;
    private final PullRequestStore pullRequestStore;
    private final LlmReviewerRegistry llmReviewerRegistry;
    private final PatResolver patResolver;
    private final CodeGraphUpdateCoordinator codeGraph;

    @Autowired
    public LocalRepoService(
            WatchedRepoStore watchedRepoStore,
            GitRunner gitRunner,
            PullRequestRepository gitHub,
            PullRequestStore pullRequestStore,
            LlmReviewerRegistry llmReviewerRegistry,
            PatResolver patResolver,
            CodeGraphUpdateCoordinator codeGraph)
    {
        this.watchedRepoStore = requireNonNull(watchedRepoStore, "watchedRepoStore is null");
        this.gitRunner = requireNonNull(gitRunner, "gitRunner is null");
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.pullRequestStore = requireNonNull(pullRequestStore, "pullRequestStore is null");
        this.llmReviewerRegistry = requireNonNull(llmReviewerRegistry, "llmReviewerRegistry is null");
        this.patResolver = requireNonNull(patResolver, "patResolver is null");
        this.codeGraph = requireNonNull(codeGraph, "codeGraph is null");
    }

    public LocalRepoService(
            WatchedRepoStore watchedRepoStore,
            GitRunner gitRunner,
            PullRequestRepository gitHub,
            PullRequestStore pullRequestStore,
            LlmReviewerRegistry llmReviewerRegistry,
            PatResolver patResolver)
    {
        this(watchedRepoStore, gitRunner, gitHub, pullRequestStore,
                llmReviewerRegistry, patResolver, CodeGraphUpdateCoordinator.disabled());
    }

    /**
     * Returns the local-repo status for every watched repo. Cheap
     * rows without a clone are returned immediately; cloned rows run a small
     * batch of git commands (status --porcelain, rev-parse HEAD)
     * which can take ~50ms each on a large working tree.
     */
    public List<LocalRepoStatus> listAll()
    {
        if (!gitRunner.isAvailable()) {
            return watchedRepoStore.findAll().stream()
                    .map(LocalRepoService::gitUnavailable)
                    .collect(toImmutableList());
        }
        return watchedRepoStore.findAll().stream()
                .map(this::statusOf)
                .collect(toImmutableList());
    }

    private LocalRepoStatus statusOf(WatchedRepo repo)
    {
        String viewFocus = LocalRepoStatus.resolveViewFocus(repo.viewFocus(), repo.upstreamRemoteName());
        if (repo.localClonePath() == null) {
            return LocalRepoStatus.unmapped(repo.owner(), repo.repo());
        }
        Path path = Path.of(repo.localClonePath());
        if (!Files.isDirectory(path)) {
            return new LocalRepoStatus(repo.owner(), repo.repo(), repo.localClonePath(),
                    LocalRepoStatus.State.MISSING, null, null,
                    "Working copy not found at " + path, repo.upstreamRemoteName(), null, viewFocus);
        }
        if (!gitRunner.isGitWorkingTree(path)) {
            return new LocalRepoStatus(repo.owner(), repo.repo(), repo.localClonePath(),
                    LocalRepoStatus.State.MISSING, null, null,
                    "Path is not a git working tree", repo.upstreamRemoteName(), null, viewFocus);
        }
        try {
            int dirty = gitRunner.countDirtyFiles(path);
            String branch = gitRunner.currentBranch(path);
            // origin/HEAD lookup — null on shallow clones / repos
            // without a configured default. Cheap symbolic-ref read.
            String defaultBranch = gitRunner.defaultBranch(path).orElse(null);
            LocalRepoStatus.State state = dirty == 0 ? LocalRepoStatus.State.CLEAN
                    : LocalRepoStatus.State.MODIFIED;
            return new LocalRepoStatus(repo.owner(), repo.repo(), repo.localClonePath(),
                    state, branch, dirty, null, repo.upstreamRemoteName(), defaultBranch, viewFocus);
        }
        catch (GitRunner.GitCommandException e) {
            log.warn("git failed on {}: {}", path, e.getMessage());
            return new LocalRepoStatus(repo.owner(), repo.repo(), repo.localClonePath(),
                    LocalRepoStatus.State.ERROR, null, null,
                    e.stderr().strip(), repo.upstreamRemoteName(), null, viewFocus);
        }
        catch (IOException e) {
            log.warn("git invocation failed on {}", path, e);
            return new LocalRepoStatus(repo.owner(), repo.repo(), repo.localClonePath(),
                    LocalRepoStatus.State.ERROR, null, null,
                    e.getMessage(), repo.upstreamRemoteName(), null, viewFocus);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("git invocation failed on {}", path, e);
            return new LocalRepoStatus(repo.owner(), repo.repo(), repo.localClonePath(),
                    LocalRepoStatus.State.ERROR, null, null,
                    e.getMessage(), repo.upstreamRemoteName(), null, viewFocus);
        }
    }

    private static LocalRepoStatus gitUnavailable(WatchedRepo repo)
    {
        return new LocalRepoStatus(repo.owner(), repo.repo(), repo.localClonePath(),
                LocalRepoStatus.State.GIT_UNAVAILABLE, null, null,
                "git not found on PATH — install Xcode Command Line Tools",
                repo.upstreamRemoteName(), null,
                LocalRepoStatus.resolveViewFocus(repo.viewFocus(), repo.upstreamRemoteName()));
    }

    /**
     * App-managed destination for the repo:
     * {@code ~/Library/Application Support/ByteQuay/repos/{owner}/{repo}}.
     */
    public static Path defaultClonePath(String owner, String repo)
    {
        String home = System.getProperty("user.home");
        return Path.of(home, "Library", "Application Support", "ByteQuay", "repos", owner, repo);
    }

    public ManagedClonePlan managedClonePlan(String owner, String repo)
    {
        requireNonNull(owner, "owner is null");
        requireNonNull(repo, "repo is null");
        String pat = patResolver.resolve(owner + "/" + repo);
        String viewer = gitHub.fetchUserProfile(pat).login();
        boolean directAvailable = gitHub.fetchViewerCanWrite(pat, RepoRef.of(owner, repo));
        boolean forkAvailable = !owner.equalsIgnoreCase(viewer);
        WriteMode defaultMode = forkAvailable ? WriteMode.FORK : WriteMode.DIRECT;
        return new ManagedClonePlan(
                viewer,
                directAvailable,
                forkAvailable,
                defaultMode,
                defaultClonePath(owner, repo).toString());
    }

    /**
     * Creates a ByteQuay-managed clone for a watched repo. The watched
     * repo is always {@code owner/repo}; {@code writeMode} decides
     * where branches are pushed:
     * <ul>
     *   <li>DIRECT: origin is {@code owner/repo}; requires push access.</li>
     *   <li>FORK: origin is {@code viewer/repo}, upstream is {@code owner/repo}.</li>
     * </ul>
     */
    public LocalRepoStatus cloneManaged(String owner, String repo, WriteMode writeMode)
            throws IOException, InterruptedException
    {
        requireNonNull(owner, "owner is null");
        requireNonNull(repo, "repo is null");
        WriteMode mode = requireNonNull(writeMode, "writeMode is null");
        Path destination = defaultClonePath(owner, repo);
        Optional<WatchedRepo> existing = watchedRepoStore.find(owner, repo);
        if (existing.isPresent()
                && existing.get().localClonePath() != null
                && Files.isDirectory(Path.of(existing.get().localClonePath()))) {
            LocalRepoStatus status = statusOf(existing.get());
            if (status.state() == LocalRepoStatus.State.CLEAN
                    || status.state() == LocalRepoStatus.State.MODIFIED) {
                return status;
            }
        }
        PreparedClone prepared = recoverPreparedClone(
                owner, repo, mode, destination)
                .orElse(null);
        if (prepared == null) {
            Path cloneDestination = recoveryDestination(destination);
            prepared = prepareManagedClone(
                    owner, repo, mode, cloneDestination);
        }
        ensureWatched(owner, repo);
        activatePreparedClone(owner, repo, prepared);
        return statusOf(refreshWatchedRepo(owner, repo));
    }

    /**
     * Adopts a checkout that finished cloning before the creation operation
     * could persist its mapping. A partial or unrelated directory is never
     * deleted; the caller clones into a fresh sibling instead.
     */
    private Optional<PreparedClone> recoverPreparedClone(
            String owner,
            String repo,
            WriteMode mode,
            Path destination)
            throws IOException, InterruptedException
    {
        if (!gitRunner.isGitWorkingTree(destination)) {
            return Optional.empty();
        }
        List<GitRunner.Remote> remotes = gitRunner.listRemotes(destination);
        GitRunner.Remote origin = remotes.stream()
                .filter(remote -> "origin".equals(remote.name()))
                .findFirst()
                .orElse(null);
        if (origin == null) {
            return Optional.empty();
        }

        String pat = patResolver.resolve(owner + "/" + repo);
        RepoRef watched = RepoRef.of(owner, repo);
        if (mode == WriteMode.DIRECT) {
            if (!LocalRepoRemote.remoteMatchesRepo(origin.url(), owner, repo)
                    || !gitHub.fetchViewerCanWrite(pat, watched)) {
                return Optional.empty();
            }
            return Optional.of(new PreparedClone(
                    destination.toAbsolutePath().normalize(), null));
        }

        String viewer = gitHub.fetchUserProfile(pat).login();
        if (owner.equalsIgnoreCase(viewer)
                || !LocalRepoRemote.remoteMatchesRepo(
                        origin.url(), viewer, repo)) {
            return Optional.empty();
        }
        String upstream = remotes.stream()
                .filter(remote -> LocalRepoRemote.remoteMatchesRepo(
                        remote.url(), owner, repo))
                .map(GitRunner.Remote::name)
                .findFirst()
                .orElse(null);
        if (upstream == null) {
            boolean upstreamNameAvailable = remotes.stream()
                    .noneMatch(remote -> UPSTREAM_REMOTE.equals(remote.name()));
            if (!upstreamNameAvailable) {
                return Optional.empty();
            }
            gitRunner.addRemote(
                    destination, UPSTREAM_REMOTE, githubCloneUrl(owner, repo));
            gitRunner.fetchRemote(destination, UPSTREAM_REMOTE);
            try {
                gitRunner.setRemoteHead(destination, UPSTREAM_REMOTE);
            }
            catch (GitRunner.GitCommandException e) {
                log.warn("Could not set {}/HEAD for {}: {}",
                        UPSTREAM_REMOTE, destination, e.getMessage());
            }
            upstream = UPSTREAM_REMOTE;
        }
        return Optional.of(new PreparedClone(
                destination.toAbsolutePath().normalize(), upstream));
    }

    private static Path recoveryDestination(Path destination)
            throws IOException
    {
        if (!Files.exists(destination)) {
            return destination;
        }
        if (Files.isDirectory(destination)) {
            try (Stream<Path> entries = Files.list(destination)) {
                if (entries.findAny().isEmpty()) {
                    return destination;
                }
            }
        }
        String suffix = Long.toUnsignedString(System.nanoTime(), 36);
        return destination.resolveSibling(
                destination.getFileName() + ".recovery-" + suffix);
    }

    /**
     * Clones and verifies a replacement checkout without changing the live
     * workspace mapping. The caller can safely leave the current checkout in
     * use until {@link #activatePreparedClone} performs the one-row swap.
     */
    public PreparedClone prepareManagedClone(
            String owner,
            String repo,
            WriteMode writeMode,
            Path destination)
            throws IOException, InterruptedException
    {
        requireNonNull(owner, "owner is null");
        requireNonNull(repo, "repo is null");
        WriteMode mode = requireNonNull(writeMode, "writeMode is null");
        requireEmptyDestination(destination);
        String pat = patResolver.resolve(owner + "/" + repo);
        RepoRef watched = RepoRef.of(owner, repo);
        String viewer = gitHub.fetchUserProfile(pat).login();
        String cloneOwner;
        String upstreamRemoteName = null;

        if (mode == WriteMode.DIRECT) {
            if (!gitHub.fetchViewerCanWrite(pat, watched)) {
                throw new IllegalStateException("No write access to " + owner + "/" + repo
                        + ". Use fork mode instead.");
            }
            cloneOwner = owner;
        }
        else {
            if (owner.equalsIgnoreCase(viewer)) {
                throw new IllegalStateException(owner + "/" + repo
                        + " is owned by the current user; use direct mode.");
            }
            ensureForkReady(pat, viewer, watched);
            cloneOwner = viewer;
            upstreamRemoteName = UPSTREAM_REMOTE;
        }

        String cloneUrl = githubCloneUrl(cloneOwner, repo);
        log.info("Cloning managed repo {} via {} -> {}", watched.fullName(), mode, destination);
        gitRunner.clone(cloneUrl, destination);
        if (upstreamRemoteName != null) {
            gitRunner.addRemote(destination, upstreamRemoteName, githubCloneUrl(owner, repo));
            gitRunner.fetchRemote(destination, upstreamRemoteName);
            try {
                gitRunner.setRemoteHead(destination, upstreamRemoteName);
            }
            catch (GitRunner.GitCommandException e) {
                log.warn("Could not set {}/HEAD for {}: {}", upstreamRemoteName, destination, e.getMessage());
            }
        }
        if (!gitRunner.isGitWorkingTree(destination)) {
            throw new IllegalStateException(
                    "Replacement clone could not be verified at " + destination);
        }
        return new PreparedClone(destination.toAbsolutePath().normalize(),
                upstreamRemoteName);
    }

    /** Makes a previously verified checkout live in one persisted update. */
    public LocalRepoStatus activatePreparedClone(
            String owner,
            String repo,
            PreparedClone prepared)
    {
        requireNonNull(prepared, "prepared is null");
        watchedRepoStore.replaceClone(
                owner,
                repo,
                prepared.path().toString(),
                prepared.upstreamRemoteName());
        codeGraph.requestRefreshAsync(prepared.path(), "repo-cloned");
        return statusOf(refreshWatchedRepo(owner, repo));
    }

    public Optional<PreparedClone> verifiedPreparedClone(
            Path path,
            String upstreamRemoteName)
    {
        if (path == null || !gitRunner.isGitWorkingTree(path)) {
            return Optional.empty();
        }
        return Optional.of(new PreparedClone(
                path.toAbsolutePath().normalize(),
                upstreamRemoteName));
    }

    private void ensureForkReady(String pat, String viewer, RepoRef watched)
            throws InterruptedException
    {
        if (findExpectedFork(pat, viewer, watched).isPresent()) {
            return;
        }
        gitHub.createFork(pat, watched);
        Instant deadline = Instant.now().plus(FORK_READY_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            Thread.sleep(FORK_READY_POLL.toMillis());
            if (findExpectedFork(pat, viewer, watched).isPresent()) {
                return;
            }
        }
        throw new IllegalStateException("Fork " + viewer + "/" + watched.repo()
                + " was not ready after " + FORK_READY_TIMEOUT.toSeconds() + " seconds.");
    }

    private Optional<RepoMeta> findExpectedFork(String pat, String viewer, RepoRef watched)
    {
        Optional<RepoMeta> fork = gitHub.findRepoMeta(pat, RepoRef.of(viewer, watched.repo()));
        if (fork.isEmpty()) {
            return Optional.empty();
        }
        RepoMeta meta = fork.get();
        if (watched.owner().equalsIgnoreCase(meta.parentOwner())
                && watched.repo().equalsIgnoreCase(meta.parentName())) {
            return fork;
        }
        throw new IllegalStateException(viewer + "/" + watched.repo()
                + " already exists but is not a fork of " + watched.fullName() + ".");
    }

    private static void requireEmptyDestination(Path destination)
            throws IOException
    {
        requireNonNull(destination, "destination is null");
        if (Files.exists(destination) && Files.isDirectory(destination)) {
            try (Stream<Path> entries = Files.list(destination)) {
                if (entries.findAny().isPresent()) {
                    throw new IllegalStateException("Destination is not empty: " + destination);
                }
            }
        }
    }

    private static String githubCloneUrl(String owner, String repo)
    {
        return "https://github.com/" + owner + "/" + repo + ".git";
    }

    /**
     * Creates the watched-repo row if it does not exist yet. A watched
     * repo must always carry a managed clone, so the only way one is born
     * is through the managed-clone flow that lands its path in the same call.
     * Idempotent — an existing row is left untouched.
     */
    private void ensureWatched(String owner, String repo)
    {
        if (watchedRepoStore.find(owner, repo).isEmpty()) {
            watchedRepoStore.add(owner, repo);
        }
    }

    private WatchedRepo refreshWatchedRepo(String owner, String repo)
    {
        return watchedRepoStore.find(owner, repo)
                .orElseThrow(() -> new IllegalStateException(owner + "/" + repo + " is not watched"));
    }

    /**
     * Persists the user's choice of commits-tab focus for the repo
     * detail page. {@code viewFocus} must be {@code "fork"} or
     * {@code "upstream"}. Returns the refreshed status row so the UI
     * can re-render from the response without a follow-up fetch.
     */
    public LocalRepoStatus setViewFocus(String owner, String repo, String viewFocus)
    {
        if (!"fork".equals(viewFocus) && !"upstream".equals(viewFocus)) {
            throw new IllegalArgumentException("viewFocus must be 'fork' or 'upstream', got: " + viewFocus);
        }
        watchedRepoStore.setViewFocus(owner, repo, viewFocus);
        return statusOf(refreshWatchedRepo(owner, repo));
    }

    /**
     * Runs `git fetch --all --prune` against the watched repo's
     * local clone. Returns the refreshed status row so the caller
     * doesn't need to re-list (counts may have shifted).
     */
    public LocalRepoStatus fetch(String owner, String repo)
            throws IOException, InterruptedException
    {
        Path path = clonePathOrThrow(owner, repo);
        gitRunner.fetch(path);
        codeGraph.requestRefreshAsync(path, "repo-fetch");
        return statusOf(refreshWatchedRepo(owner, repo));
    }

    /**
     * Fast-forward pull on the current branch. Returns the refreshed
     * status row. Surfaces git's stderr as an
     * {@link GitRunner.GitCommandException} when the pull would not
     * be fast-forward — the controller maps that to a 409 so the UI
     * can show "needs rebase" inline.
     */
    public LocalRepoStatus pull(String owner, String repo)
            throws IOException, InterruptedException
    {
        Path path = clonePathOrThrow(owner, repo);
        gitRunner.pullFastForward(path);
        codeGraph.ensureFreshSync(path, "repo-pull");
        return statusOf(refreshWatchedRepo(owner, repo));
    }

    /**
     * Pushes the current branch upstream. First-time pushes (no
     * tracking ref yet) are auto-set up via {@code -u origin HEAD}.
     * Non-fast-forward pushes fail loudly; the caller can retry via
     * {@link #pushForceWithLease} after confirming with the user.
     */
    public LocalRepoStatus push(String owner, String repo)
            throws IOException, InterruptedException
    {
        Path path = clonePathOrThrow(owner, repo);
        gitRunner.push(path);
        return statusOf(refreshWatchedRepo(owner, repo));
    }

    /**
     * {@code git push --force-with-lease} on the current branch.
     * Caller must have already obtained explicit user confirmation —
     * this method does not check; the controller's request body
     * carries a {@code confirmed} flag that gates entry.
     */
    public LocalRepoStatus pushForceWithLease(String owner, String repo)
            throws IOException, InterruptedException
    {
        Path path = clonePathOrThrow(owner, repo);
        gitRunner.pushForceWithLease(path);
        return statusOf(refreshWatchedRepo(owner, repo));
    }

    /**
     * Creates {@code branchName} from {@code baseRef} (or current
     * HEAD when null) and switches to it. Returns the refreshed
     * status row so the UI can update the current-branch chip
     * without a re-list.
     */
    public LocalRepoStatus createBranch(String owner, String repo, String branchName, String baseRef)
            throws IOException, InterruptedException
    {
        Path path = clonePathOrThrow(owner, repo);
        if (branchName == null || branchName.isBlank()) {
            throw new IllegalArgumentException("Branch name is required");
        }
        gitRunner.createBranch(path, branchName.trim(), baseRef);
        codeGraph.ensureFreshSync(path, "repo-create-branch");
        return statusOf(refreshWatchedRepo(owner, repo));
    }

    /**
     * Switches HEAD to {@code branchName}. The branch must already
     * exist locally — use {@link #createBranch} for new branches.
     * Returns the refreshed status row so the UI can update the
     * current-branch chip without a re-list.
     */
    public LocalRepoStatus switchBranch(String owner, String repo, String branchName)
            throws IOException, InterruptedException
    {
        Path path = clonePathOrThrow(owner, repo);
        if (branchName == null || branchName.isBlank()) {
            throw new IllegalArgumentException("Branch name is required");
        }
        gitRunner.switchBranch(path, branchName.trim());
        codeGraph.ensureFreshSync(path, "repo-switch-branch");
        return statusOf(refreshWatchedRepo(owner, repo));
    }

    /**
     * Materializes a remote-only branch into a local tracking branch
     * and switches HEAD to it. Used for IN_REVIEW phantoms whose head
     * ref isn't checked out in this clone — typically a branch the
     * user pushed from another machine.
     */
    public LocalRepoStatus checkoutRemoteBranch(String owner, String repo, String branchName)
            throws IOException, InterruptedException
    {
        Path path = clonePathOrThrow(owner, repo);
        if (branchName == null || branchName.isBlank()) {
            throw new IllegalArgumentException("Branch name is required");
        }
        gitRunner.checkoutRemoteBranch(path, branchName.trim());
        codeGraph.ensureFreshSync(path, "repo-checkout-remote-branch");
        return statusOf(refreshWatchedRepo(owner, repo));
    }

    /**
     * Deletes the named local branches via {@code git branch -D},
     * optionally also pushing a delete to the remote
     * ({@code git push <remote> --delete <branch>}) for any branch
     * that has an upstream configured. The current branch is never
     * deletable; everything else is. Cleanup classification is
     * advisory for the UI — this method does not gate on it.
     *
     * Returns the names that were actually deleted locally; the
     * caller can compare with the input list to surface any that
     * were skipped (currently only the current branch).
     */
    public List<String> deleteBranches(
            String owner,
            String repo,
            List<String> names,
            boolean deleteRemote)
            throws IOException, InterruptedException
    {
        Path path = clonePathOrThrow(owner, repo);
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        // Re-list branches so we authorize against current state, not
        // a stale UI snapshot. The current-branch filter below is the
        // only hard gate left — deleting HEAD is always refused.
        List<LocalBranch> current = listBranches(owner, repo);
        Set<String> nonCurrentNames = current.stream()
                .filter(b -> !b.isCurrent())
                .map(LocalBranch::name)
                .collect(Collectors.toUnmodifiableSet());
        List<String> approved = names.stream()
                .filter(nonCurrentNames::contains)
                .collect(toImmutableList());
        if (approved.isEmpty()) {
            return List.of();
        }
        if (deleteRemote) {
            // Only the branches that actually have an upstream get a
            // remote delete pushed — the others are local-only and
            // there's nothing to delete remotely. Push origin --delete
            // first so a local delete failure doesn't strand the
            // remote tip with no local pointer to it.
            Set<String> withUpstream = current.stream()
                    .filter(b -> approved.contains(b.name()) && b.hasUpstream())
                    .map(LocalBranch::name)
                    .collect(Collectors.toUnmodifiableSet());
            for (String name : withUpstream) {
                // We push to "origin" because that's where the user's
                // tracking branch lives in both direct-clone and
                // fork-based workflows (origin = watched repo or fork
                // respectively). The upstream remote name on the
                // watched repo is for PR creation, not for deletes.
                gitRunner.deleteRemoteBranch(path, "origin", name);
            }
        }
        gitRunner.deleteBranches(path, approved);
        return approved;
    }

    private Path clonePathOrThrow(String owner, String repo)
    {
        WatchedRepo watched = refreshWatchedRepo(owner, repo);
        if (watched.localClonePath() == null) {
            throw new IllegalStateException(owner + "/" + repo + " has no local clone mapped");
        }
        return Path.of(watched.localClonePath());
    }

    /**
     * Enumerates the file paths that conflict between the PR's head
     * and its base branch. Uses the local clone as a virtual workbench:
     *
     * <ol>
     *   <li>Fetch {@code pull/{N}/head} + the base branch into a
     *       non-user-visible refs namespace
     *       ({@code refs/bytequay/pr/{N}/{head,base}}).</li>
     *   <li>Run {@code git merge-tree --write-tree --name-only --no-messages}
     *       against the two tips; the path lines after the merged-tree
     *       OID are exactly the files in conflict.</li>
     * </ol>
     *
     * <p>Returns a {@link MergeConflictPaths} carrying both the list +
     * an {@code available} flag so the renderer can distinguish "PR
     * has no conflicts" from "we can't tell because the repo isn't
     * cloned locally yet". The unavailable case is not an error — the
     * top-level conflict pill still links out to github.com's editor
     * regardless.
     */
    public MergeConflictPaths listMergeConflictPaths(String owner, String repo, int prNumber, String baseRef)
    {
        WatchedRepo watched = refreshWatchedRepo(owner, repo);
        if (watched.localClonePath() == null) {
            return new MergeConflictPaths(false, "no_local_clone", ImmutableList.of());
        }
        if (baseRef == null || baseRef.isBlank()) {
            return new MergeConflictPaths(false, "no_base_ref", ImmutableList.of());
        }
        if (prNumber <= 0) {
            return new MergeConflictPaths(false, "invalid_pr_number", ImmutableList.of());
        }
        Path path = Path.of(watched.localClonePath());
        try {
            gitRunner.fetchPrRefs(path, prNumber, baseRef);
        }
        catch (GitRunner.GitCommandException | IOException e) {
            log.warn("Could not fetch PR refs for {}/{}#{}: {}", owner, repo, prNumber, e.getMessage());
            return new MergeConflictPaths(false, "fetch_failed", ImmutableList.of());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Could not fetch PR refs for {}/{}#{}: {}", owner, repo, prNumber, e.getMessage());
            return new MergeConflictPaths(false, "fetch_failed", ImmutableList.of());
        }
        try {
            List<String> paths = gitRunner.listMergeConflictPaths(
                    path,
                    GitRunner.headRef(prNumber),
                    GitRunner.baseRef(prNumber));
            return new MergeConflictPaths(true, null, paths);
        }
        catch (IOException e) {
            log.warn("merge-tree failed for {}/{}#{}: {}", owner, repo, prNumber, e.getMessage());
            return new MergeConflictPaths(false, "merge_tree_failed", ImmutableList.of());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("merge-tree failed for {}/{}#{}: {}", owner, repo, prNumber, e.getMessage());
            return new MergeConflictPaths(false, "merge_tree_failed", ImmutableList.of());
        }
    }

    /**
     * Result envelope for {@link #listMergeConflictPaths}.
     *
     * @param available true when the merge-tree probe ran successfully — the
     *                  paths list is then definitive (empty = no conflicts).
     * @param reason    when {@code !available}, a stable token the
     *                  renderer can localise: {@code no_local_clone},
     *                  {@code no_base_ref}, {@code invalid_pr_number},
     *                  {@code fetch_failed}, or {@code merge_tree_failed}.
     * @param paths     file paths reported as conflicting by git.
     */
    public record MergeConflictPaths(boolean available, String reason, List<String> paths) {}

    /**
     * Returns the branches of a watched repo's local clone, mapped
     * into LocalBranch records ready for the kanban renderer. Throws
     * IllegalStateException when the repo isn't mapped — the caller
     * is expected to gate on the status row first.
     */
    public List<LocalBranch> listBranches(String owner, String repo)
            throws IOException, InterruptedException
    {
        WatchedRepo watched = refreshWatchedRepo(owner, repo);
        if (watched.localClonePath() == null) {
            throw new IllegalStateException(owner + "/" + repo + " has no local clone mapped");
        }
        Path path = Path.of(watched.localClonePath());
        // Look up linked PRs for this repo once, in a single query,
        // and pass the map down — beats firing one lookup per branch
        // for repos with hundreds of refs. Read off the list-PR table
        // (V42 captured head_ref there) so the IN REVIEW column
        // populates from the list sync, not just after a per-PR
        // detail fetch.
        Map<String, Integer> prByHeadRef = pullRequestStore
                .openPrNumbersByHeadRef(owner + "/" + repo);
        // Default branch resolved once so the per-branch commit-count
        // calls all measure against the same base. Empty when origin
        // has no HEAD ref (shallow / locally-created repo) — every
        // branch's commitCount stays null in that case.
        Optional<String> defaultBranch = gitRunner.defaultBranch(path);
        List<LocalBranch> local = gitRunner.listBranches(path).stream()
                .map(ref -> toLocalBranch(ref, prByHeadRef, path, defaultBranch))
                .collect(toImmutableList());
        // Synthesize entries for PRs whose head ref isn't checked out
        // locally — typically a branch the user pushed from another
        // machine. Without this, IN_REVIEW would silently miss PRs
        // until the user manually `git switch`es each one.
        Set<String> localNames = local.stream()
                .map(LocalBranch::name)
                .collect(Collectors.toUnmodifiableSet());
        List<LocalBranch> remoteOnly = prByHeadRef.entrySet().stream()
                .filter(e -> !localNames.contains(e.getKey()))
                .map(e -> remoteOnlyBranch(e.getKey(), e.getValue()))
                .collect(toImmutableList());
        return ImmutableList.<LocalBranch>builder()
                .addAll(local)
                .addAll(remoteOnly)
                .build();
    }

    /** Phantom IN_REVIEW entry for a PR whose head branch hasn't been
     *  checked out locally. ahead/behind/cleanup all stay null —
     *  there's no local history to compare. */
    private static LocalBranch remoteOnlyBranch(String name, int prNumber)
    {
        return new LocalBranch(
                name,
                /* isCurrent */ false,
                /* lastCommitAt */ null,
                /* hasUpstream */ false,
                /* ahead */ null,
                /* behind */ null,
                /* linkedPrNumber */ prNumber,
                /* cleanupReason */ null,
                /* commitCount */ null,
                /* rebasePreview */ null,
                /* remoteOnly */ true);
    }

    /**
     * Returns the most recent commits on {@code revision} (or HEAD
     * when null/blank) of the watched repo's local clone. The cap
     * mirrors what the Commits tab renders without paging — small
     * enough that {@code git log} stays sub-100ms even on big repos.
     */
    public List<LocalCommit> listCommits(String owner, String repo, String revision, int limit)
            throws IOException, InterruptedException
    {
        Path path = clonePathOrThrow(owner, repo);
        // Remote-only branches (PRs pushed from another machine, no
        // local checkout) only exist at refs/remotes/origin/<name>;
        // fall back so the Commits tab still works for them.
        String resolved = resolveLogRevision(path, revision);
        return gitRunner.listCommits(path, resolved, limit).stream()
                .map(LocalRepoService::toLocalCommit)
                .collect(toImmutableList());
    }

    /**
     * Merge-base of {@code branch} and {@code base} — the sha where
     * {@code branch} branched off. {@code base} is optional; when
     * blank, falls back to the repo's default branch (origin/HEAD).
     * Returns null when neither side resolves or when the histories
     * are unrelated, so the UI can quietly skip the "branched from"
     * divider rather than error out.
     */
    public LocalMergeBase mergeBase(String owner, String repo, String branch, String base)
            throws IOException, InterruptedException
    {
        Path path = clonePathOrThrow(owner, repo);
        String resolvedBranch = resolveLogRevision(path, branch);
        String resolvedBase = (base == null || base.isBlank())
                ? gitRunner.defaultBranch(path).orElse(null)
                : resolveLogRevision(path, base);
        if (resolvedBase == null) {
            return new LocalMergeBase(null, null);
        }
        Optional<String> sha = gitRunner.mergeBase(path, resolvedBranch, resolvedBase);
        return new LocalMergeBase(sha.orElse(null), resolvedBase);
    }

    public BranchComparison compareBranches(
            String owner,
            String repo,
            String branch,
            String base)
            throws IOException, InterruptedException
    {
        Path path = clonePathOrThrow(owner, repo);
        String resolvedBranch = resolveLogRevision(path, branch);
        String resolvedBase = base == null || base.isBlank()
                ? gitRunner.defaultBranch(path)
                        .orElseThrow(() -> new IllegalStateException(
                                "repository has no resolvable default branch"))
                : resolveLogRevision(path, base);
        String forkPoint = gitRunner.mergeBase(
                        path, resolvedBranch, resolvedBase)
                .orElse(resolvedBase);
        List<LocalCommit> commits = gitRunner
                .listCommits(path, forkPoint + ".." + resolvedBranch, 500)
                .stream()
                .map(LocalRepoService::toLocalCommit)
                .collect(toImmutableList());
        List<LocalCommitFile> files = gitRunner
                .rangeFiles(path, forkPoint, resolvedBranch)
                .stream()
                .map(file -> new LocalCommitFile(
                        file.path(),
                        file.status(),
                        file.additions(),
                        file.deletions()))
                .collect(toImmutableList());
        return new BranchComparison(
                branch, resolvedBranch, resolvedBase, forkPoint,
                commits, files);
    }

    public record BranchComparison(
            String branch,
            String resolvedBranch,
            String base,
            String mergeBase,
            List<LocalCommit> commits,
            List<LocalCommitFile> files) {}

    private String resolveLogRevision(Path workingDir, String requested)
            throws IOException, InterruptedException
    {
        if (requested == null || requested.isBlank()) {
            return requested;
        }
        if (gitRunner.refExists(workingDir, requested)) {
            return requested;
        }
        String originForm = "origin/" + requested;
        if (gitRunner.refExists(workingDir, originForm)) {
            return originForm;
        }
        throw new IllegalStateException(
                "Couldn't resolve branch '" + requested + "' locally — fetch first, "
                        + "or check out the branch.");
    }

    private static LocalCommit toLocalCommit(GitRunner.CommitEntry e)
    {
        return new LocalCommit(
                e.sha(),
                e.shortSha(),
                e.subject(),
                e.authorName(),
                e.authorEmail(),
                parseIsoOrNull(e.authoredAt()));
    }

    /**
     * Files touched by {@code sha}. Powers the middle pane of the
     * Commits tab (file tree the user picks from to load a per-file
     * diff into the right pane).
     */
    public List<LocalCommitFile> commitFiles(String owner, String repo, String sha)
            throws IOException, InterruptedException
    {
        Path path = clonePathOrThrow(owner, repo);
        return gitRunner.commitFiles(path, sha).stream()
                .map(f -> new LocalCommitFile(f.path(), f.status(), f.additions(), f.deletions()))
                .collect(toImmutableList());
    }

    /**
     * One file's unified diff at {@code sha}. Capped at
     * {@link #FILE_DIFF_MAX_BYTES} so a giant change (e.g. a generated
     * vendor file) doesn't blow up the renderer; the truncated flag
     * lets the UI flag it inline.
     */
    public LocalFileDiff commitFileDiff(String owner, String repo, String sha, String filePath)
            throws IOException, InterruptedException
    {
        Path path = clonePathOrThrow(owner, repo);
        String patch = gitRunner.commitFileDiff(path, sha, filePath, FILE_DIFF_MAX_BYTES);
        boolean truncated = patch.contains("(diff truncated at ");
        return new LocalFileDiff(filePath, patch, truncated);
    }

    /**
     * Files in the working tree that differ from HEAD — uncommitted
     * changes (staged + unstaged + untracked). Powers the Commits
     * tab's "Changes" mode. Returns the same shape as
     * {@link #commitFiles(String, String, String)} so the same
     * file-tree pane renders both, just with the working tree as
     * the source.
     */
    public List<LocalCommitFile> workingTreeFiles(String owner, String repo)
            throws IOException, InterruptedException
    {
        Path path = clonePathOrThrow(owner, repo);
        return gitRunner.workingTreeFiles(path).stream()
                // additions/deletions stay 0 — the porcelain status doesn't
                // carry line counts and computing them per-file would
                // require a per-file diff, which we already do lazily on
                // selection. The Files-changed list just shows the path.
                .map(f -> new LocalCommitFile(f.path(), f.status(), 0, 0))
                .collect(toImmutableList());
    }

    /**
     * Working-tree diff for one file — git diff HEAD -- path, with
     * an untracked-file fallback. Drives the right pane of the
     * Commits tab in Changes mode.
     */
    public LocalFileDiff workingTreeFileDiff(String owner, String repo, String filePath)
            throws IOException, InterruptedException
    {
        Path path = clonePathOrThrow(owner, repo);
        String patch = gitRunner.workingTreeFileDiff(path, filePath, FILE_DIFF_MAX_BYTES);
        boolean truncated = patch.contains("(diff truncated at ");
        return new LocalFileDiff(filePath, patch, truncated);
    }

    /**
     * Subject + body of one commit. Lazy-fetched when a commit is
     * selected in the Commits tab so the listCommits response stays
     * small. Throws on unresolvable shas instead of returning empty
     * so the caller surfaces a clear error rather than a blank card.
     */
    public LocalCommitDetail commitDetail(String owner, String repo, String sha)
            throws IOException, InterruptedException
    {
        Path path = clonePathOrThrow(owner, repo);
        return gitRunner.commitDetail(path, sha)
                .map(e -> new LocalCommitDetail(e.sha(), e.subject(), e.body()))
                .orElseThrow(() -> new IllegalStateException(
                        "Commit '" + sha + "' not found in this clone."));
    }

    /**
     * Files that differ between {@code base} and {@code head} —
     * used by the Commits tab's compare-branches mode. The base /
     * head args may be raw shas, branch names, or any ref git
     * accepts; both go through the same origin/<name> fallback as
     * the listCommits flow when the bare name doesn't resolve.
     */
    public List<LocalCommitFile> rangeFiles(String owner, String repo, String base, String head)
            throws IOException, InterruptedException
    {
        Path path = clonePathOrThrow(owner, repo);
        String resolvedBase = resolveLogRevision(path, base);
        String resolvedHead = resolveLogRevision(path, head);
        return gitRunner.rangeFiles(path, resolvedBase, resolvedHead).stream()
                .map(f -> new LocalCommitFile(f.path(), f.status(), f.additions(), f.deletions()))
                .collect(toImmutableList());
    }

    /**
     * Per-file unified diff between two refs — counterpart to
     * {@link #rangeFiles}. Same args, same shape as
     * {@link #commitFileDiff}. Used by the Commits tab's
     * compare-branches mode; differs from
     * {@link #commitRangeFileDiff} in that there's no {@code ^}
     * shift on the base (branch refs aren't shas, so {@code ^}
     * would point at the wrong commit).
     */
    public LocalFileDiff rangeFileDiff(
            String owner, String repo, String base, String head, String filePath)
            throws IOException, InterruptedException
    {
        Path path = clonePathOrThrow(owner, repo);
        String resolvedBase = resolveLogRevision(path, base);
        String resolvedHead = resolveLogRevision(path, head);
        String patch = gitRunner.rangeFileDiff(
                path, resolvedBase, resolvedHead, filePath, FILE_DIFF_MAX_BYTES);
        boolean truncated = patch.contains("(diff truncated at ");
        return new LocalFileDiff(filePath, patch, truncated);
    }

    /**
     * One file's unified diff across a commit range — used by the
     * Commits tab when the user has selected more than one commit.
     * The {@code oldestSha} and {@code newestSha} args are the
     * boundary commits in the user's selection (chronological order);
     * the underlying {@code git diff} runs against
     * {@code oldestSha^..newestSha} so the patch captures every
     * change those commits introduced. Note: a sparse selection
     * (gaps between commits) over-includes the un-selected middle
     * commits — git can't produce a "just-these-commits" diff
     * directly. Truncation matches {@link #commitFileDiff}.
     */
    public LocalFileDiff commitRangeFileDiff(
            String owner, String repo, String oldestSha, String newestSha, String filePath)
            throws IOException, InterruptedException
    {
        Path path = clonePathOrThrow(owner, repo);
        String patch = gitRunner.rangeFileDiff(
                path, oldestSha + "^", newestSha, filePath, FILE_DIFF_MAX_BYTES);
        boolean truncated = patch.contains("(diff truncated at ");
        return new LocalFileDiff(filePath, patch, truncated);
    }

    private static final int FILE_DIFF_MAX_BYTES = 250_000;

    /**
     * Opens a pull request on github.com against the watched repo,
     * with the local clone's head as the source. For fork-based
     * clones the head is rendered as {@code "<forkOwner>:<branch>"}
     * (the cross-fork form GitHub requires); direct clones use a
     * bare branch name.
     *
     * Returns the created PR domain object so the caller can plumb
     * the new number into the UI without an extra round-trip.
     */
    public PullRequest createPullRequest(
            String owner,
            String repo,
            String title,
            String bodyText,
            String base,
            boolean draft)
            throws IOException, InterruptedException
    {
        String pat = patResolver.resolve(owner + "/" + repo);
        Path path = clonePathOrThrow(owner, repo);
        WatchedRepo watched = refreshWatchedRepo(owner, repo);
        String headBranch = gitRunner.currentBranch(path);
        if (headBranch == null) {
            throw new IllegalStateException("HEAD is detached — switch to a branch before opening a PR");
        }
        String headRef = headBranch;
        if (watched.upstreamRemoteName() != null) {
            // Fork-based: GitHub needs "<forkOwner>:<branch>" so the
            // server can resolve the cross-repo ref.
            String forkOwner = forkOwnerFromOrigin(path);
            if (forkOwner == null) {
                throw new IllegalStateException("Couldn't read origin remote to determine fork owner");
            }
            headRef = forkOwner + ":" + headBranch;
        }
        String resolvedBase = base != null && !base.isBlank() ? base.trim() : "main";
        CreatePullRequestCommand command = new CreatePullRequestCommand(
                headRef,
                resolvedBase,
                title.trim(),
                bodyText == null || bodyText.isBlank() ? Optional.empty() : Optional.of(bodyText),
                Optional.of(draft),
                Optional.empty());
        return gitHub.createPullRequest(pat, RepoRef.of(owner, repo), command);
    }

    /**
     * Asks the active LLM provider to draft a PR title + description
     * from the diff between the current branch and {@code baseBranch}.
     * Reads the repo's {@code PULL_REQUEST_TEMPLATE.md} (when present)
     * and includes it in the prompt so the description respects the
     * team's section structure.
     *
     * <p>Diff is capped at {@link #DIFF_MAX_BYTES} so a giant PR doesn't
     * overflow the prompt budget — the cap is enforced server-side
     * with a clear marker so the AI knows the input is incomplete.
     */
    public PullRequestDraft draftPullRequestWithAi(
            String owner,
            String repo,
            String baseBranch,
            String headBranchOverride)
            throws IOException, InterruptedException
    {
        Path path = clonePathOrThrow(owner, repo);
        // Frontend supplies the head when the user lazy-selected a
        // branch via a card click — at that point HEAD on disk still
        // points at the previously-checked-out branch. Falling back
        // to currentBranch covers the legacy callers (and the case
        // where no card was clicked).
        String headBranch = headBranchOverride != null && !headBranchOverride.isBlank()
                ? headBranchOverride.trim()
                : gitRunner.currentBranch(path);
        if (headBranch == null) {
            throw new IllegalStateException("HEAD is detached — switch to a branch before drafting.");
        }
        String requestedBase = baseBranch != null && !baseBranch.isBlank() ? baseBranch.trim() : "main";
        if (headBranch.equals(requestedBase)) {
            throw new IllegalStateException(
                    "Head and base are both '" + headBranch + "' — switch to a feature branch first.");
        }
        String diffBase = resolveDiffBase(path, requestedBase);
        String diff = gitRunner.diff(path, diffBase, headBranch, DIFF_MAX_BYTES);
        String template = readPrTemplate(path).orElse(null);
        // The model sees the human-friendly base name (what the PR
        // will target on GitHub); the diff was computed against
        // whatever ref git could resolve locally (often origin/<base>
        // when the user hasn't checked out the base branch).
        return llmReviewerRegistry.active()
                .draftPullRequest(headBranch, requestedBase, diff, template);
    }

    /**
     * Picks a ref git can actually diff against. Tries the bare name
     * first (matches when the user has a local branch by that name),
     * then {@code origin/<name>} (the common case where {@code main}
     * exists only as a remote-tracking ref). Throws when neither
     * resolves so the user gets a clear error instead of git's
     * ambiguous-revision message.
     */
    private String resolveDiffBase(Path workingDir, String requestedBase)
            throws IOException, InterruptedException
    {
        if (gitRunner.refExists(workingDir, requestedBase)) {
            return requestedBase;
        }
        String originForm = "origin/" + requestedBase;
        if (gitRunner.refExists(workingDir, originForm)) {
            return originForm;
        }
        throw new IllegalStateException(
                "Couldn't resolve base '" + requestedBase + "' locally — fetch first, "
                        + "or pick a base that exists in this clone.");
    }

    private static final int DIFF_MAX_BYTES = 60_000;
    private static final int PR_TEMPLATE_MAX_BYTES = 16_000;
    private static final List<String> PR_TEMPLATE_PATHS = List.of(
            ".github/PULL_REQUEST_TEMPLATE.md",
            ".github/pull_request_template.md",
            "PULL_REQUEST_TEMPLATE.md",
            "pull_request_template.md",
            "docs/PULL_REQUEST_TEMPLATE.md",
            "docs/pull_request_template.md");

    /**
     * Walks {@link #PR_TEMPLATE_PATHS} in order and returns the first
     * existing template's content (capped to {@link #PR_TEMPLATE_MAX_BYTES}).
     * Multi-template repos ({@code .github/PULL_REQUEST_TEMPLATE/}
     * directory) aren't picked up here — surfacing those would require
     * a UI to let the user choose which template to use; deferred.
     */
    private static Optional<String> readPrTemplate(Path workingDir)
            throws IOException
    {
        for (String relative : PR_TEMPLATE_PATHS) {
            Path candidate = workingDir.resolve(relative);
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            byte[] bytes = Files.readAllBytes(candidate);
            int len = Math.min(bytes.length, PR_TEMPLATE_MAX_BYTES);
            return Optional.of(new String(bytes, 0, len, StandardCharsets.UTF_8));
        }
        return Optional.empty();
    }

    /**
     * Reads the origin remote URL and extracts the GitHub owner
     * segment. Used to render the cross-fork {@code "<owner>:<branch>"}
     * head ref for fork-based PR creation. Returns null if the origin
     * isn't a github.com URL we can parse.
     */
    private String forkOwnerFromOrigin(Path workingDir)
            throws IOException, InterruptedException
    {
        String url = gitRunner.originUrl(workingDir);
        if (url == null) {
            return null;
        }
        return LocalRepoRemote.parseGithubOwner(url);
    }

    /**
     * Recent {@code git reflog} entries for the watched repo's clone,
     * mapped into {@link LocalActivityEntry} for the Activity tab.
     */
    public List<LocalActivityEntry> listActivity(String owner, String repo, int limit)
            throws IOException, InterruptedException
    {
        Path path = clonePathOrThrow(owner, repo);
        return gitRunner.listReflog(path, limit).stream()
                .map(LocalRepoService::toActivityEntry)
                .collect(toImmutableList());
    }

    private static LocalActivityEntry toActivityEntry(GitRunner.ReflogEntry e)
    {
        return new LocalActivityEntry(
                e.sha(),
                e.shortSha(),
                e.selector(),
                classifyReflogSubject(e.subject()),
                e.subject(),
                parseIsoOrNull(e.reflogAt()));
    }

    /**
     * Classifies a reflog subject into one of our known event kinds.
     * Subjects look like {@code "commit: WIP"}, {@code "checkout: moving
     * from X to Y"}, {@code "pull: Fast-forward"}, etc. We match on
     * the colon-prefixed verb because git localizes nothing in the
     * reflog — these prefixes are stable across versions.
     */
    static LocalActivityEntry.Kind classifyReflogSubject(String subject)
    {
        if (subject == null || subject.isEmpty()) {
            return LocalActivityEntry.Kind.UNKNOWN;
        }
        int colon = subject.indexOf(':');
        String prefix = (colon < 0 ? subject : subject.substring(0, colon))
                .trim()
                .toLowerCase(Locale.ROOT);
        return switch (prefix) {
            case "commit", "commit (initial)", "commit (amend)" -> LocalActivityEntry.Kind.COMMIT;
            case "checkout" -> LocalActivityEntry.Kind.CHECKOUT;
            case "merge" -> LocalActivityEntry.Kind.MERGE;
            case "pull" -> LocalActivityEntry.Kind.PULL;
            case "push" -> LocalActivityEntry.Kind.PUSH;
            case "rebase", "rebase (start)", "rebase (pick)", "rebase (continue)",
                 "rebase (finish)", "rebase -i (start)", "rebase -i (finish)" ->
                    LocalActivityEntry.Kind.REBASE;
            case "reset" -> LocalActivityEntry.Kind.RESET;
            case "branch" -> LocalActivityEntry.Kind.BRANCH;
            default -> {
                // git's reflog uses two prefix shapes:
                //   - bare verb: "commit:", "checkout:", "pull:"
                //   - verb + arg: "merge feat/foo:", "rebase -i (start):"
                // The switch above catches the bare forms; here we
                // fall back to startsWith so the verb+arg forms still
                // classify correctly.
                if (prefix.startsWith("commit")) {
                    yield LocalActivityEntry.Kind.COMMIT;
                }
                if (prefix.startsWith("rebase")) {
                    yield LocalActivityEntry.Kind.REBASE;
                }
                if (prefix.startsWith("merge")) {
                    yield LocalActivityEntry.Kind.MERGE;
                }
                if (prefix.startsWith("pull")) {
                    yield LocalActivityEntry.Kind.PULL;
                }
                if (prefix.startsWith("push")) {
                    yield LocalActivityEntry.Kind.PUSH;
                }
                yield LocalActivityEntry.Kind.UNKNOWN;
            }
        };
    }

    /** Pulls "ahead 5, behind 2" or "[ahead 5]" out of git's
     *  upstream:track field, which uses bracketed prose for the
     *  default-format string. */
    private static final Pattern AHEAD_RE = Pattern.compile("ahead (\\d+)");
    private static final Pattern BEHIND_RE = Pattern.compile("behind (\\d+)");

    /** Idle threshold for the never-pushed cleanup heuristic. Long
     *  enough that an experiment the user is genuinely still iterating
     *  on doesn't end up flagged. */
    private static final Duration IDLE_THRESHOLD = Duration.ofDays(90);

    private LocalBranch toLocalBranch(
            GitRunner.BranchRef ref,
            Map<String, Integer> prByHeadRef,
            Path workingDir,
            Optional<String> defaultBranch)
    {
        Instant when = parseIsoOrNull(ref.committerDate());
        boolean hasUpstream = !ref.upstream().isEmpty();
        boolean upstreamGone = hasUpstream && ref.upstreamTrack().contains("gone");
        Integer ahead = null;
        Integer behind = null;
        if (hasUpstream && !upstreamGone) {
            Matcher a = AHEAD_RE.matcher(ref.upstreamTrack());
            Matcher b = BEHIND_RE.matcher(ref.upstreamTrack());
            ahead = a.find() ? Integer.parseInt(a.group(1)) : 0;
            behind = b.find() ? Integer.parseInt(b.group(1)) : 0;
        }
        LocalBranch.CleanupReason cleanupReason = classifyCleanup(when, hasUpstream, upstreamGone);
        // Best-effort PR link. The map is built from pr_detail rows,
        // so PRs whose detail isn't synced yet won't show up here —
        // the column stays empty for those branches until the user
        // opens the PR (which triggers the detail sync).
        Integer linkedPrNumber = prByHeadRef.get(ref.name());
        Integer commitCount = resolveCommitCount(workingDir, ref.name(), defaultBranch);
        LocalBranch.RebasePreview rebasePreview = resolveRebasePreview(
                workingDir, ref, hasUpstream, upstreamGone, behind, commitCount, defaultBranch);
        return new LocalBranch(ref.name(), ref.isCurrent(), when, hasUpstream,
                ahead, behind, linkedPrNumber, cleanupReason, commitCount, rebasePreview,
                /* remoteOnly */ false);
    }

    /**
     * Picks a rebase target and asks merge-tree what it'd cost.
     * Two paths:
     *   - upstream branches that are behind their tracking ref get
     *     previewed against the upstream (the natural "rebase before
     *     push" target);
     *   - branches without upstream that have unique commits vs the
     *     default get previewed against the default (the "rebase
     *     before opening a PR" target).
     * Branches that wouldn't trigger a rebase in practice (nothing
     * behind / no unique commits / no resolvable target) get null
     * and the card stays unannotated.
     */
    private LocalBranch.RebasePreview resolveRebasePreview(
            Path workingDir,
            GitRunner.BranchRef ref,
            boolean hasUpstream,
            boolean upstreamGone,
            Integer behind,
            Integer commitCount,
            Optional<String> defaultBranch)
    {
        String base;
        if (hasUpstream && !upstreamGone && behind != null && behind > 0) {
            base = stripRefsRemotes(ref.upstream());
        }
        else if (!hasUpstream && commitCount != null && commitCount > 0
                && defaultBranch.isPresent() && !defaultBranch.get().equals(ref.name())) {
            base = defaultBranch.get();
        }
        else {
            return null;
        }
        try {
            GitRunner.RebaseOutcome outcome = gitRunner.rebasePreview(workingDir, ref.name(), base);
            return switch (outcome) {
                case CLEAN -> LocalBranch.RebasePreview.CLEAN;
                case CONFLICTS -> LocalBranch.RebasePreview.CONFLICTS;
                case UNKNOWN -> LocalBranch.RebasePreview.UNKNOWN;
            };
        }
        catch (IOException e) {
            return LocalBranch.RebasePreview.UNKNOWN;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Turns {@code refs/remotes/origin/foo} (the form
     *  {@code for-each-ref %(upstream)} emits) into the short
     *  {@code origin/foo} merge-tree expects. */
    private static String stripRefsRemotes(String ref)
    {
        String prefix = "refs/remotes/";
        if (ref.startsWith(prefix)) {
            return ref.substring(prefix.length());
        }
        return ref;
    }

    /**
     * Counts commits on {@code branch} that aren't on the repo's
     * default base. Skips the default branch itself (vs-itself is
     * always zero and reads as misleading) and swallows IO errors —
     * a missing count just hides the chip rather than failing the
     * whole branches list.
     */
    private Integer resolveCommitCount(
            Path workingDir, String branch, Optional<String> defaultBranch)
    {
        if (defaultBranch.isEmpty() || defaultBranch.get().equals(branch)) {
            return null;
        }
        try {
            return gitRunner.commitCountUniqueTo(workingDir, branch, defaultBranch.get());
        }
        catch (IOException e) {
            return null;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    static LocalBranch.CleanupReason classifyCleanup(
            Instant lastCommitAt, boolean hasUpstream, boolean upstreamGone)
    {
        if (upstreamGone) {
            return LocalBranch.CleanupReason.REMOTE_GONE;
        }
        if (!hasUpstream && lastCommitAt != null
                && Duration.between(lastCommitAt, Instant.now()).compareTo(IDLE_THRESHOLD) > 0) {
            return LocalBranch.CleanupReason.IDLE_NEVER_PUSHED;
        }
        return null;
    }

    private static Instant parseIsoOrNull(String iso)
    {
        if (iso == null || iso.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(iso);
        }
        catch (DateTimeParseException e) {
            return null;
        }
    }
}

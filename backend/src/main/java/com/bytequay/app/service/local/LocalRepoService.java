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

import com.bytequay.app.domain.LocalBranch;
import com.bytequay.app.domain.LocalRepoStatus;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.WatchedRepoStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Drives the Repos page. Joins the watched-repos table with the
 * working-tree state of each mapped clone, returning one
 * {@link LocalRepoStatus} per watched repo.
 */
@Service
public class LocalRepoService
{
    private static final Logger log = LoggerFactory.getLogger(LocalRepoService.class);

    private final WatchedRepoStore watchedRepoStore;
    private final GitRunner gitRunner;

    public LocalRepoService(WatchedRepoStore watchedRepoStore, GitRunner gitRunner)
    {
        this.watchedRepoStore = requireNonNull(watchedRepoStore, "watchedRepoStore is null");
        this.gitRunner = requireNonNull(gitRunner, "gitRunner is null");
    }

    /**
     * Returns the local-repo status for every watched repo. Cheap
     * unmapped rows are returned immediately; mapped rows run a small
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
        if (repo.localClonePath() == null) {
            return LocalRepoStatus.unmapped(repo.owner(), repo.repo());
        }
        Path path = Path.of(repo.localClonePath());
        if (!Files.isDirectory(path)) {
            return new LocalRepoStatus(repo.owner(), repo.repo(), repo.localClonePath(),
                    LocalRepoStatus.State.MISSING, null, null,
                    "Working copy not found at " + path);
        }
        if (!gitRunner.isGitWorkingTree(path)) {
            return new LocalRepoStatus(repo.owner(), repo.repo(), repo.localClonePath(),
                    LocalRepoStatus.State.MISSING, null, null,
                    "Path is not a git working tree");
        }
        try {
            int dirty = gitRunner.countDirtyFiles(path);
            String branch = gitRunner.currentBranch(path);
            LocalRepoStatus.State state = dirty == 0 ? LocalRepoStatus.State.CLEAN
                    : LocalRepoStatus.State.MODIFIED;
            return new LocalRepoStatus(repo.owner(), repo.repo(), repo.localClonePath(),
                    state, branch, dirty, null);
        }
        catch (GitRunner.GitCommandException e) {
            log.warn("git failed on {}: {}", path, e.getMessage());
            return new LocalRepoStatus(repo.owner(), repo.repo(), repo.localClonePath(),
                    LocalRepoStatus.State.ERROR, null, null, e.stderr().strip());
        }
        catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("git invocation failed on {}", path, e);
            return new LocalRepoStatus(repo.owner(), repo.repo(), repo.localClonePath(),
                    LocalRepoStatus.State.ERROR, null, null, e.getMessage());
        }
    }

    private static LocalRepoStatus gitUnavailable(WatchedRepo repo)
    {
        return new LocalRepoStatus(repo.owner(), repo.repo(), repo.localClonePath(),
                LocalRepoStatus.State.GIT_UNAVAILABLE, null, null,
                "git not found on PATH — install Xcode Command Line Tools");
    }

    /**
     * Default destination for the Clone-fresh flow:
     * {@code ~/Library/Application Support/ByteQuay/repos/{owner}/{repo}}.
     * The user can override at clone time via the modal's `Change…`
     * action; this is the value the modal pre-fills.
     */
    public static Path defaultClonePath(String owner, String repo)
    {
        String home = System.getProperty("user.home");
        return Path.of(home, "Library", "Application Support", "ByteQuay", "repos", owner, repo);
    }

    /**
     * Runs `git clone` against the GitHub URL of {@code owner/repo}
     * into {@code destination}, then records the destination on the
     * watched repo. Throws {@link IllegalStateException} if the
     * destination already exists with content (refuse to clobber).
     */
    public LocalRepoStatus cloneFresh(String owner, String repo, Path destination)
            throws IOException, InterruptedException
    {
        requireNonNull(owner, "owner is null");
        requireNonNull(repo, "repo is null");
        requireNonNull(destination, "destination is null");
        if (Files.exists(destination) && Files.isDirectory(destination)) {
            try (Stream<Path> entries = Files.list(destination)) {
                if (entries.findAny().isPresent()) {
                    throw new IllegalStateException("Destination is not empty: " + destination);
                }
            }
        }
        String url = "https://github.com/" + owner + "/" + repo + ".git";
        log.info("Cloning {} → {}", url, destination);
        gitRunner.clone(url, destination);
        watchedRepoStore.setLocalClonePath(owner, repo, destination.toString());
        return statusOf(refreshWatchedRepo(owner, repo));
    }

    /**
     * Verifies the user-picked folder is a git working tree whose
     * `origin` remote points at the watched repo, then records the
     * path. Throws {@link IllegalArgumentException} on mismatch — the
     * controller surfaces the message verbatim so the modal can show
     * "wrong remote: …" inline.
     */
    public LocalRepoStatus locateExisting(String owner, String repo, Path path)
            throws IOException, InterruptedException
    {
        requireNonNull(owner, "owner is null");
        requireNonNull(repo, "repo is null");
        requireNonNull(path, "path is null");
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("Not a directory: " + path);
        }
        if (!gitRunner.isGitWorkingTree(path)) {
            throw new IllegalArgumentException("Not a git working tree: " + path);
        }
        String remote = gitRunner.originUrl(path);
        if (remote == null) {
            throw new IllegalArgumentException("No `origin` remote configured at " + path);
        }
        if (!remoteMatchesRepo(remote, owner, repo)) {
            throw new IllegalArgumentException(
                    "Remote `origin` is " + remote + " — expected " + owner + "/" + repo);
        }
        watchedRepoStore.setLocalClonePath(owner, repo, path.toString());
        return statusOf(refreshWatchedRepo(owner, repo));
    }

    private WatchedRepo refreshWatchedRepo(String owner, String repo)
    {
        return watchedRepoStore.find(owner, repo)
                .orElseThrow(() -> new IllegalStateException(owner + "/" + repo + " is not watched"));
    }

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
        return gitRunner.listBranches(path).stream()
                .map(LocalRepoService::toLocalBranch)
                .collect(toImmutableList());
    }

    /** Pulls "ahead 5, behind 2" or "[ahead 5]" out of git's
     *  upstream:track field, which uses bracketed prose for the
     *  default-format string. */
    private static final Pattern AHEAD_RE = Pattern.compile("ahead (\\d+)");
    private static final Pattern BEHIND_RE = Pattern.compile("behind (\\d+)");

    private static LocalBranch toLocalBranch(GitRunner.BranchRef ref)
    {
        Instant when = parseIsoOrNull(ref.committerDate());
        boolean hasUpstream = !ref.upstream().isEmpty();
        Integer ahead = null;
        Integer behind = null;
        if (hasUpstream) {
            Matcher a = AHEAD_RE.matcher(ref.upstreamTrack());
            Matcher b = BEHIND_RE.matcher(ref.upstreamTrack());
            ahead = a.find() ? Integer.parseInt(a.group(1)) : 0;
            behind = b.find() ? Integer.parseInt(b.group(1)) : 0;
        }
        // linkedPrNumber stays null for now — populating it requires a
        // join against PR head refs, which the list-page sync doesn't
        // capture today. The IN REVIEW column will be empty until that
        // join lands; deliberately deferred to keep this slice tight.
        return new LocalBranch(ref.name(), ref.isCurrent(), when, hasUpstream, ahead, behind, null);
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

    /**
     * Tolerates the four shapes GitHub publishes for the same repo:
     * {@code git@github.com:owner/repo.git}, {@code https://github.com/owner/repo.git},
     * {@code https://github.com/owner/repo}, {@code github.com/owner/repo}.
     * The trailing {@code .git} is also optional. We don't accept other
     * hosts — those are forks or mirrors and shouldn't be confused
     * with the watched github.com repo.
     */
    static boolean remoteMatchesRepo(String remoteUrl, String owner, String repo)
    {
        String cleaned = remoteUrl.trim();
        if (cleaned.endsWith(".git")) {
            cleaned = cleaned.substring(0, cleaned.length() - 4);
        }
        String wantPath = owner + "/" + repo;
        // SSH form: git@github.com:owner/repo
        if (cleaned.startsWith("git@github.com:")) {
            return cleaned.substring("git@github.com:".length()).equalsIgnoreCase(wantPath);
        }
        // HTTPS / git protocol: ...github.com/owner/repo
        int idx = cleaned.toLowerCase(Locale.ROOT).indexOf("github.com/");
        if (idx < 0) {
            return false;
        }
        return cleaned.substring(idx + "github.com/".length()).equalsIgnoreCase(wantPath);
    }
}

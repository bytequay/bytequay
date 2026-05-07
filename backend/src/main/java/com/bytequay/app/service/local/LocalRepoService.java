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

import com.bytequay.app.domain.LocalActivityEntry;
import com.bytequay.app.domain.LocalBranch;
import com.bytequay.app.domain.LocalCommit;
import com.bytequay.app.domain.LocalRepoStatus;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.WatchedRepoStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
                    "Working copy not found at " + path, repo.upstreamRemoteName());
        }
        if (!gitRunner.isGitWorkingTree(path)) {
            return new LocalRepoStatus(repo.owner(), repo.repo(), repo.localClonePath(),
                    LocalRepoStatus.State.MISSING, null, null,
                    "Path is not a git working tree", repo.upstreamRemoteName());
        }
        try {
            int dirty = gitRunner.countDirtyFiles(path);
            String branch = gitRunner.currentBranch(path);
            LocalRepoStatus.State state = dirty == 0 ? LocalRepoStatus.State.CLEAN
                    : LocalRepoStatus.State.MODIFIED;
            return new LocalRepoStatus(repo.owner(), repo.repo(), repo.localClonePath(),
                    state, branch, dirty, null, repo.upstreamRemoteName());
        }
        catch (GitRunner.GitCommandException e) {
            log.warn("git failed on {}: {}", path, e.getMessage());
            return new LocalRepoStatus(repo.owner(), repo.repo(), repo.localClonePath(),
                    LocalRepoStatus.State.ERROR, null, null,
                    e.stderr().strip(), repo.upstreamRemoteName());
        }
        catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("git invocation failed on {}", path, e);
            return new LocalRepoStatus(repo.owner(), repo.repo(), repo.localClonePath(),
                    LocalRepoStatus.State.ERROR, null, null,
                    e.getMessage(), repo.upstreamRemoteName());
        }
    }

    private static LocalRepoStatus gitUnavailable(WatchedRepo repo)
    {
        return new LocalRepoStatus(repo.owner(), repo.repo(), repo.localClonePath(),
                LocalRepoStatus.State.GIT_UNAVAILABLE, null, null,
                "git not found on PATH — install Xcode Command Line Tools",
                repo.upstreamRemoteName());
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
        // Direct clone — origin already points at the watched repo,
        // so there is no separate "upstream" remote to track.
        watchedRepoStore.setUpstreamRemoteName(owner, repo, null);
        return statusOf(refreshWatchedRepo(owner, repo));
    }

    /**
     * Verifies the user-picked folder is a git working tree with at
     * least one remote pointing at the watched repo, then records
     * the path. Tolerates the fork-based OSS workflow:
     * {@code origin} = user's fork, {@code upstream} = watched repo
     * — both layouts are accepted as long as ANY remote matches.
     * Throws {@link IllegalArgumentException} on mismatch with the
     * remote list embedded in the message so the modal can show it
     * inline.
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
        List<GitRunner.Remote> remotes = gitRunner.listRemotes(path);
        if (remotes.isEmpty()) {
            throw new IllegalArgumentException("No remotes configured at " + path);
        }
        String upstreamRemoteName = pickUpstreamRemoteName(remotes, owner, repo);
        if (upstreamRemoteName == null && remotes.stream().noneMatch(r -> remoteMatchesRepo(r.url(), owner, repo))) {
            String summary = remotes.stream()
                    .map(r -> r.name() + " " + redactCredentials(r.url()))
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("(none)");
            throw new IllegalArgumentException(
                    "No remote points at " + owner + "/" + repo + ". Found: " + summary);
        }
        watchedRepoStore.setLocalClonePath(owner, repo, path.toString());
        watchedRepoStore.setUpstreamRemoteName(owner, repo, upstreamRemoteName);
        return statusOf(refreshWatchedRepo(owner, repo));
    }

    /**
     * Picks the remote that ByteQuay should treat as the "upstream"
     * (i.e. the watched repo) when this clone is fork-based:
     *
     * <ul>
     *   <li>If origin points at the watched repo → return null. The
     *       clone is direct, so there's no separate upstream concept
     *       and ByteQuay should leave the column unset.</li>
     *   <li>If origin points elsewhere but another remote points at
     *       the watched repo → return that remote's name. The user's
     *       fork-based workflow has origin = fork, upstream = watched
     *       repo, and we record "upstream" (or whatever they named it)
     *       so Create-PR knows which remote to push the head ref to
     *       and which repo to open the PR against.</li>
     *   <li>If no remote matches → return null and let the caller
     *       reject the locate.</li>
     * </ul>
     */
    static String pickUpstreamRemoteName(List<GitRunner.Remote> remotes, String owner, String repo)
    {
        GitRunner.Remote origin = remotes.stream()
                .filter(r -> "origin".equals(r.name()))
                .findFirst()
                .orElse(null);
        if (origin != null && remoteMatchesRepo(origin.url(), owner, repo)) {
            return null;
        }
        return remotes.stream()
                .filter(r -> remoteMatchesRepo(r.url(), owner, repo))
                .map(GitRunner.Remote::name)
                .findFirst()
                .orElse(null);
    }

    /**
     * Strips embedded credentials (PAT or username:password) from a
     * git URL so they don't leak into the error message we show the
     * user. {@code https://ghp_xxx@github.com/foo/bar.git} →
     * {@code https://github.com/foo/bar.git}.
     */
    static String redactCredentials(String url)
    {
        // Match scheme://[creds@]rest. Only redact if there's actually
        // a creds segment — preserve "git@github.com:foo/bar" SSH
        // form which has a literal "git@" that's not a credential.
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) {
            return url;
        }
        int at = url.indexOf('@', schemeEnd + 3);
        if (at < 0) {
            return url;
        }
        return url.substring(0, schemeEnd + 3) + url.substring(at + 1);
    }

    private WatchedRepo refreshWatchedRepo(String owner, String repo)
    {
        return watchedRepoStore.find(owner, repo)
                .orElseThrow(() -> new IllegalStateException(owner + "/" + repo + " is not watched"));
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
        return statusOf(refreshWatchedRepo(owner, repo));
    }

    /**
     * Deletes the named local branches, but only if they're cleanup
     * candidates per {@link #classifyCleanup}. The check is server-
     * side authoritative: even if the UI somehow surfaces a Delete
     * action on a non-cleanup branch, the request will be rejected.
     * The current branch is never deletable.
     *
     * Returns the names that were actually deleted; the caller can
     * compare with the input list to surface any that were skipped.
     */
    public List<String> deleteCleanupBranches(String owner, String repo, List<String> names)
            throws IOException, InterruptedException
    {
        Path path = clonePathOrThrow(owner, repo);
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        // Re-list branches so we authorize against current state, not
        // a stale UI snapshot. A branch the UI thought was idle but
        // that was just pushed gets refused here.
        List<LocalBranch> current = listBranches(owner, repo);
        Set<String> byName = current.stream()
                .filter(b -> b.cleanupReason() != null && !b.isCurrent())
                .map(LocalBranch::name)
                .collect(Collectors.toUnmodifiableSet());
        List<String> approved = names.stream()
                .filter(byName::contains)
                .collect(toImmutableList());
        if (approved.isEmpty()) {
            return List.of();
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
        return gitRunner.listCommits(path, revision, limit).stream()
                .map(LocalRepoService::toLocalCommit)
                .collect(toImmutableList());
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
                parseIsoOrNull(e.authoredAt()));
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

    private static LocalBranch toLocalBranch(GitRunner.BranchRef ref)
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
        // linkedPrNumber stays null for now — populating it requires a
        // join against PR head refs, which the list-page sync doesn't
        // capture today. The IN REVIEW column will be empty until that
        // join lands; deliberately deferred to keep this slice tight.
        return new LocalBranch(ref.name(), ref.isCurrent(), when, hasUpstream,
                ahead, behind, null, cleanupReason);
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

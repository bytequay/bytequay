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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.local.GitRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Objects.requireNonNull;

/**
 * Manages per-task git worktrees so multiple agents can edit the same
 * repo in parallel without colliding on the working tree or the index.
 *
 * <p>For each new task:
 * <ol>
 *   <li>Slugify the task title for the branch name.</li>
 *   <li>Compute the worktree path as
 *       {@code <repo-root>/.worktrees/<task-id>/} per the model doc.</li>
 *   <li>Make sure {@code .worktrees/} is in {@code .git/info/exclude}
 *       (per-repo, not committed) so the directory doesn't show up in
 *       the main worktree's {@code git status}.</li>
 *   <li>Resolve the base ref (callers can override; default is the
 *       repo's default branch, falling back to whatever's checked out
 *       in the main worktree).</li>
 *   <li>Run {@code git worktree add -b <branchName> <path> <baseRef>}
 *       which creates the branch and checks it out in one step.</li>
 * </ol>
 *
 * <p>If any step fails, {@link #create} returns {@link Optional#empty()}
 * and the caller falls back to running the agent in the main checkout.
 * That keeps worktree isolation opt-in in practice — failures don't
 * block thread creation.
 */
@Service
public class WorktreeService
{
    private static final Logger log = LoggerFactory.getLogger(WorktreeService.class);

    /** Directory worktrees live under inside each repo. The model doc
     *  ({@code docs/mockups/workspace-thread-task-design.md}) names this
     *  exact path: {@code <repo>/.worktrees/<task-id>/}. */
    static final String WORKTREE_ROOT_REL = ".worktrees";
    /** Dir name (under {@link #WORKTREE_ROOT_REL}) of the shared trunk
     *  planning worktree. Distinct from any task id, so the task-orphan
     *  sweeper never mistakes it for a task worktree. */
    static final String PLANNING_WORKTREE_REL = "_planning";

    /** Branch-name prefix for dev branches. The branch is named for the
     *  task's purpose ({@code dev/<title-slug>}) — short and readable —
     *  with a numeric suffix added only on collision. The worktree dir
     *  still carries the full task id, so the two need not match. */
    static final String DEV_BRANCH_PREFIX = "dev/";

    /** Hard cap on slug length so worktree paths and branch names stay
     *  human-readable. */
    static final int SLUG_MAX_CHARS = 32;

    /** Upper bound on the collision-dedupe suffix ({@code -2 … -N}). Far
     *  beyond any real number of same-title branches; a backstop so a
     *  pathological repo can't spin the loop forever. */
    private static final int MAX_BRANCH_DEDUPE = 50;

    /** Per-worktree infra directory ({@code core.hooksPath}-style scope),
     *  kept separate from {@code .git/hooks/}. No hook is installed here
     *  any more — the constant remains so the stage-all exclusion and the
     *  infra-path guard keep skipping it if a stale dir is ever present. */
    static final String HOOK_DIR_REL = ".bytequay-hooks";

    private final GitRunner git;
    private final WatchedRepoStore watchedRepos;
    /** Per-clone monitors so concurrent trunk turns serialise their
     *  fetch + reset of the shared planning worktree. */
    private final ConcurrentHashMap<String, Object> planningLocks = new ConcurrentHashMap<>();

    public WorktreeService(GitRunner git, WatchedRepoStore watchedRepos)
    {
        this.git = requireNonNull(git, "git is null");
        this.watchedRepos = requireNonNull(watchedRepos, "watchedRepos is null");
    }

    /**
     * Creates the worktree + branch for a new task. Returns the handle
     * on success, empty if the working dir isn't usable as a git repo
     * or any git step failed — callers fall back to the main checkout.
     *
     * <p>The on-disk dir is named for the task id (one worktree per
     * task), while the branch keeps a slugged form of the title for
     * readability.
     */
    public Optional<WorktreeHandle> create(Path repoRoot, String taskId, String title)
    {
        requireNonNull(repoRoot, "repoRoot is null");
        requireNonNull(taskId, "taskId is null");
        if (!git.isAvailable()) {
            log.debug("git binary unavailable; skipping worktree for task {}", taskId);
            return Optional.empty();
        }
        try {
            String slug = slugify(title);
            Path worktreePath = repoRoot
                    .resolve(WORKTREE_ROOT_REL)
                    .resolve(taskId)
                    .toAbsolutePath()
                    .normalize();
            // Name the branch for the task's purpose, not its id. The id
            // is the fallback only when the title yields no usable slug.
            String branchName = uniqueDevBranch(repoRoot, slug.isEmpty() ? taskId : slug);
            String baseRef = resolveBaseRef(repoRoot);
            if (baseRef == null) {
                log.info("No base ref resolvable in {}; skipping worktree for task {}",
                        repoRoot, taskId);
                return Optional.empty();
            }
            appendToGitInfoExclude(repoRoot);
            git.worktreeAdd(repoRoot, worktreePath, branchName, baseRef);
            // Pin the exact base commit the worktree was cut from, so the
            // task's diff is a fixed base..HEAD rather than a re-guessed
            // branch name on every request.
            String baseCommit = git.resolveCommitSha(repoRoot, baseRef).orElse(null);
            log.info("Created worktree at {} on branch {} (from {} @ {}) for task {}",
                    worktreePath, branchName, baseRef, baseCommit, taskId);
            return Optional.of(new WorktreeHandle(worktreePath, branchName, baseCommit));
        }
        catch (IOException e) {
            log.warn("Worktree create failed for task {} in {}: {}",
                    taskId, repoRoot, e.getMessage());
            return Optional.empty();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Worktree create interrupted for task {}", taskId);
            return Optional.empty();
        }
        catch (RuntimeException e) {
            // requireSuccess() throws RuntimeException-derived for non-zero exit.
            log.warn("Worktree create rejected by git for task {}: {}",
                    taskId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Removes the worktree and deletes its branch. Best-effort: errors
     * are logged and swallowed so a delete that races with a manual
     * {@code rm -rf} on the worktree doesn't 500 the controller.
     */
    public void remove(Path repoRoot, String worktreePath, String localBranch)
    {
        if (repoRoot == null || worktreePath == null || worktreePath.isBlank()) {
            return;
        }
        try {
            git.worktreeRemove(repoRoot, Path.of(worktreePath));
        }
        catch (IOException | RuntimeException e) {
            log.warn("Worktree remove failed for {}: {}", worktreePath, e.getMessage());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Worktree remove interrupted for {}", worktreePath);
            return;
        }
        if (localBranch == null || localBranch.isBlank()) {
            return;
        }
        try {
            git.deleteBranches(repoRoot, List.of(localBranch));
        }
        catch (IOException | RuntimeException e) {
            log.warn("Branch delete failed for {}: {}", localBranch, e.getMessage());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Branch delete interrupted for {}", localBranch);
        }
    }

    /**
     * Reap a finished task's worktree and branch. No-op for a task that
     * has no worktree (already shipped/reaped) or whose clone root is
     * unknown. Best-effort — failures are logged and never propagate, so
     * a completion or cancel path never fails on a dead worktree.
     */
    public void reap(Task task)
    {
        if (task.worktreePath() == null || task.workingDir() == null) {
            return;
        }
        try {
            remove(Path.of(task.workingDir()), task.worktreePath(), task.branchName());
            // Symmetric with the create log — a completed task's cleanup is
            // otherwise silent, so there's no signal it ran or what it freed
            // (per-step failures still surface as warns inside remove()).
            log.info("Reaped worktree {} + branch {} for task {}",
                    task.worktreePath(), task.branchName(), task.id());
        }
        catch (RuntimeException e) {
            log.warn("worktree reap for completed task {} failed: {}", task.id(), e.getMessage());
        }
    }

    /**
     * Delete a merged task's branch from the remote — the post-merge cleanup
     * GitHub's own "automatically delete head branches" setting performs, done
     * here for repos that don't have it on. Call only once the PR has merged:
     * deleting the head branch of a still-open PR would close it. No-op when
     * the branch never reached the remote ({@code pushedAt} null) or the clone
     * root / branch is unknown. Best-effort — a branch GitHub already
     * auto-deleted (push --delete → "remote ref does not exist") or a transient
     * network error is logged, never propagated, so a merge never fails on
     * cleanup.
     */
    public void deleteRemoteBranch(Task task)
    {
        if (task.pushedAt() == null || task.workingDir() == null
                || task.branchName() == null || task.branchName().isBlank()) {
            return;
        }
        try {
            git.deleteRemoteBranch(Path.of(task.workingDir()), "origin", task.branchName());
            log.info("Deleted remote branch {} for merged task {}", task.branchName(), task.id());
        }
        catch (IOException | RuntimeException e) {
            log.info("Remote branch delete for task {} skipped: {}", task.id(), e.getMessage());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Remote branch delete for task {} interrupted", task.id());
        }
    }

    /**
     * A {@code dev/<base>} branch name, unsuffixed when free and suffixed
     * {@code -2 / -3 / …} only when an earlier task already took the bare
     * name. Keeps the common case short and readable
     * ({@code dev/fix-login}) while staying unique across threads that
     * happen to share a title slug. The {@code ^{commit}} probe in
     * {@link GitRunner#refExists} matches only real branches, so this
     * never collides with a tag or remote ref.
     */
    private String uniqueDevBranch(Path repoRoot, String base)
            throws IOException, InterruptedException
    {
        String head = DEV_BRANCH_PREFIX + base;
        String candidate = head;
        for (int n = 2; n <= MAX_BRANCH_DEDUPE && git.refExists(repoRoot, candidate); n++) {
            candidate = head + "-" + n;
        }
        return candidate;
    }

    /**
     * Normalises a task title into a worktree-safe slug. Lowercase,
     * non-alphanumeric runs collapsed to single dashes, leading/
     * trailing dashes stripped, truncated to {@link #SLUG_MAX_CHARS}.
     * Empty input returns an empty string; the caller uses the task id
     * alone in that case.
     */
    static String slugify(String raw)
    {
        if (raw == null) {
            return "";
        }
        String lowered = raw.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(Math.min(lowered.length(), SLUG_MAX_CHARS));
        boolean lastWasDash = true;
        for (int i = 0; i < lowered.length() && out.length() < SLUG_MAX_CHARS; i++) {
            char c = lowered.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                out.append(c);
                lastWasDash = false;
            }
            else if (c == '\'' || c == '\u2019') {
                // Apostrophes vanish rather than splitting a word, so
                // "let's" -> "lets", not "let-s".
                continue;
            }
            else if (!lastWasDash && out.length() > 0) {
                out.append('-');
                lastWasDash = true;
            }
        }
        // Strip trailing dash if the last appended char was one.
        while (out.length() > 0 && out.charAt(out.length() - 1) == '-') {
            out.deleteCharAt(out.length() - 1);
        }
        return out.toString();
    }

    /**
     * Picks the ref a new task branch is cut from — always a
     * <b>remote-tracking ref</b>, fetched fresh, so the worktree starts
     * from the latest <em>published</em> trunk rather than a local
     * checkout that may have raced ahead with un-pushed commits.
     *
     * <p>The base remote is the {@code upstreamRemoteName} for a
     * <b>fork-based clone</b> (branch off {@code upstream/master}, open the
     * PR against it) and {@code origin} for a <b>direct clone</b> (branch
     * off {@code origin/<default>}). Either way the remote is fetched first
     * so the ref is current, and the returned value is the remote-tracking
     * ref itself — branching from the bare local {@code main} would fold any
     * un-pushed local commits into the task branch, and from there into its
     * PR. Mirrors {@link #resolvePlanningBaseRef}.
     *
     * <p>When the remote is unreachable or has no resolvable {@code HEAD}
     * (offline first run, never-fetched clone) we fall back to the local
     * default branch, then the currently checked-out branch, so creation
     * still works without a reachable remote.
     */
    private String resolveBaseRef(Path repoRoot)
            throws IOException, InterruptedException
    {
        String remote = baseRemoteName(repoRoot);
        try {
            git.fetchRemote(repoRoot, remote);
        }
        catch (IOException | RuntimeException e) {
            log.warn("Fetch of {} in {} failed ({}); branching from last-known {}/HEAD",
                    remote, repoRoot, e.getMessage(), remote);
        }
        Optional<String> remoteDefault = git.defaultBranch(repoRoot, remote);
        if (remoteDefault.isPresent() && !remoteDefault.get().isBlank()) {
            return remote + "/" + remoteDefault.get();
        }
        Optional<String> localDefault = git.defaultBranch(repoRoot);
        if (localDefault.isPresent() && !localDefault.get().isBlank()) {
            return localDefault.get();
        }
        String current = git.currentBranch(repoRoot);
        if (current != null && !current.isBlank()) {
            return current;
        }
        return null;
    }

    /**
     * The remote a task worktree branches from: the upstream remote for a
     * fork-based clone (a watched repo whose {@code upstreamRemoteName} names
     * the remote pointing at the upstream repo), {@code origin} otherwise.
     */
    private String baseRemoteName(Path repoRoot)
    {
        WatchedRepo repo = watchedRepoFor(repoRoot).orElse(null);
        if (repo != null && repo.upstreamRemoteName() != null && !repo.upstreamRemoteName().isBlank()) {
            return repo.upstreamRemoteName();
        }
        return "origin";
    }

    /**
     * The bare branch name a PR off {@code repoRoot} should target — the
     * upstream's default branch for a fork-based clone (e.g. {@code
     * master} for a trinodb/trino fork), else the local clone's default
     * branch ({@code origin/HEAD}), falling back to {@code main}. This is
     * the PR-base counterpart to {@link #resolveBaseRef} (which yields the
     * remote-tracking ref a worktree branches from); both agree on the
     * branch for a fork — {@code upstream/master} to branch from, {@code
     * master} to target. No fetch here — task creation cuts the worktree
     * (which fetches the upstream) immediately before this is read.
     */
    public String resolveBaseBranchName(Path repoRoot)
    {
        try {
            WatchedRepo repo = watchedRepoFor(repoRoot).orElse(null);
            if (repo != null && repo.upstreamRemoteName() != null && !repo.upstreamRemoteName().isBlank()) {
                Optional<String> upstreamDefault = git.defaultBranch(repoRoot, repo.upstreamRemoteName());
                if (upstreamDefault.isPresent() && !upstreamDefault.get().isBlank()) {
                    return upstreamDefault.get();
                }
            }
            return git.defaultBranch(repoRoot).filter(b -> !b.isBlank()).orElse("main");
        }
        catch (IOException | RuntimeException e) {
            log.warn("Resolving base branch for {} failed ({}); defaulting to main", repoRoot, e.getMessage());
            return "main";
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "main";
        }
    }

    /** The watched repo whose local clone is {@code repoRoot}, if any. */
    private Optional<WatchedRepo> watchedRepoFor(Path repoRoot)
    {
        return watchedRepos.findAll().stream()
                .filter(r -> r.localClonePath() != null
                        && !r.localClonePath().isBlank()
                        && Path.of(r.localClonePath()).equals(repoRoot))
                .findFirst();
    }

    /**
     * Ensures a shared, read-only <b>planning worktree</b> for {@code
     * repoRoot}, checked out detached at the up-to-date base ref —
     * {@code upstream/master} for a fork-based clone, {@code
     * origin/<default>} for a direct clone. Fetches the base remote, then
     * creates the worktree (first call) or hard-resets it to the fresh
     * ref (subsequent calls). The trunk planning session runs here so its
     * code search reflects the latest base instead of whatever branch the
     * user's main checkout happens to be on — without disturbing that
     * checkout.
     *
     * <p>One worktree per clone at {@code .worktrees/_planning}, shared
     * across all trunk threads rooted in that clone (planning is
     * read-only). Serialised per clone so concurrent trunk turns don't
     * race on the fetch/reset. Best-effort: returns empty on any failure
     * (no git, unresolvable base, add failed) so the caller can fall back
     * to the clone root. Not a task worktree, so the orphan sweeper —
     * which only reaps task rows — leaves it alone.
     */
    public Optional<PlanningSync> ensurePlanningWorktree(Path repoRoot)
    {
        requireNonNull(repoRoot, "repoRoot is null");
        if (!git.isAvailable()) {
            return Optional.empty();
        }
        Path planningPath = repoRoot.resolve(WORKTREE_ROOT_REL).resolve(PLANNING_WORKTREE_REL)
                .toAbsolutePath().normalize();
        synchronized (planningLockFor(repoRoot)) {
            try {
                String baseRef = resolvePlanningBaseRef(repoRoot);
                if (baseRef == null) {
                    return Optional.empty();
                }
                if (Files.isDirectory(planningPath)) {
                    git.resetHard(planningPath, baseRef);
                }
                else {
                    appendToGitInfoExclude(repoRoot);
                    git.worktreeAddDetached(repoRoot, planningPath, baseRef);
                }
                return Optional.of(new PlanningSync(planningPath, baseRef));
            }
            catch (IOException | RuntimeException e) {
                log.warn("Planning worktree for {} unavailable ({}); trunk will use the clone root",
                        repoRoot, e.getMessage());
                return Optional.empty();
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
    }

    /**
     * The {@code <remote>/<default>} remote-tracking ref the planning
     * worktree should track, after fetching that remote. Upstream remote
     * for a fork, {@code origin} otherwise. Null when no default branch
     * resolves. A fetch failure (offline) is tolerated — we reset to the
     * last-known ref.
     */
    private String resolvePlanningBaseRef(Path repoRoot)
            throws IOException, InterruptedException
    {
        WatchedRepo repo = watchedRepoFor(repoRoot).orElse(null);
        String remote = repo != null
                && repo.upstreamRemoteName() != null
                && !repo.upstreamRemoteName().isBlank()
                ? repo.upstreamRemoteName()
                : "origin";
        try {
            git.fetchRemote(repoRoot, remote);
        }
        catch (IOException | RuntimeException e) {
            log.warn("Fetch of {} in {} failed ({}); planning from last-known {}/HEAD",
                    remote, repoRoot, e.getMessage(), remote);
        }
        Optional<String> branch = git.defaultBranch(repoRoot, remote);
        return branch.filter(b -> !b.isBlank()).map(b -> remote + "/" + b).orElse(null);
    }

    private Object planningLockFor(Path repoRoot)
    {
        return planningLocks.computeIfAbsent(repoRoot.toString(), k -> new Object());
    }

    /**
     * Adds {@code /.worktrees/} to {@code .git/info/exclude} if it's
     * not already there. Per-repo, not committed — the user's
     * {@code .gitignore} stays untouched. The previous layout added
     * {@code /.bytequay/} for older installs; we leave that marker
     * alone (the directory may still exist with leftover worktrees).
     */
    private static void appendToGitInfoExclude(Path repoRoot)
            throws IOException
    {
        Path excludePath = repoRoot.resolve(".git").resolve("info").resolve("exclude");
        if (!Files.isDirectory(excludePath.getParent())) {
            // Not a standard git layout (submodule or worktree checkout).
            // Skip — the subsequent worktree-add surfaces a clearer error
            // if the repo really is broken.
            return;
        }
        String marker = "/.worktrees/";
        if (Files.exists(excludePath)) {
            String body = Files.readString(excludePath, StandardCharsets.UTF_8);
            for (String line : body.split("\\R", -1)) {
                String trimmed = line.trim();
                if (trimmed.equals(marker) || trimmed.equals(".worktrees/")) {
                    return;
                }
            }
        }
        String append = (Files.exists(excludePath) && Files.size(excludePath) > 0 ? "\n" : "")
                + "# Added by ByteQuay — per-task worktrees live here.\n"
                + marker + "\n";
        Files.writeString(excludePath, append, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Outcome of a successful {@link #create} call. */
    /**
     * A freshly-cut task worktree.
     *
     * @param baseCommit the SHA {@code worktreePath} was cut from (the base
     *     ref resolved at create time), or null if it couldn't be resolved.
     */
    public record WorktreeHandle(Path worktreePath, String branchName, String baseCommit) {}

    /** Outcome of {@link #ensurePlanningWorktree}: the planning worktree's
     *  path and the base ref it was synced to (e.g. {@code upstream/master}). */
    public record PlanningSync(Path worktree, String baseRef) {}
}

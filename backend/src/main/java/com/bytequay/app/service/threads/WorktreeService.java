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

import static java.util.Objects.requireNonNull;

/**
 * Manages per-thread git worktrees so multiple agents can edit the same
 * repo in parallel without colliding on the working tree or the index.
 *
 * <p>For each new coding thread:
 * <ol>
 *   <li>Slugify the thread title.</li>
 *   <li>Compute the worktree path as
 *       {@code <repo-root>/.bytequay/worktrees/dev/<sessionId>-<slug>/}.</li>
 *   <li>Make sure {@code .bytequay/} is in {@code .git/info/exclude}
 *       (per-repo, not committed) so the directory doesn't show up in
 *       the main worktree's {@code git status}.</li>
 *   <li>Pick a base ref via {@link GitRunner#defaultBranch}, falling
 *       back to {@link GitRunner#currentBranch} if the default can't
 *       be resolved.</li>
 *   <li>Run {@code git worktree add -b <branchName> <path> <baseRef>}
 *       which creates the branch and checks it out in one step.</li>
 * </ol>
 *
 * <p>If any step fails (working dir isn't a git repo, base ref can't be
 * resolved, disk full, etc.), {@link #create} returns
 * {@link Optional#empty()} and the caller falls back to running the agent
 * directly in the main checkout. This keeps the worktree feature
 * opt-in in practice: threads for which it fails still work, they just
 * don't get isolation.
 */
@Service
public class WorktreeService
{
    private static final Logger log = LoggerFactory.getLogger(WorktreeService.class);

    /** Directory the worktrees live under (per repo). Matches the path
     *  layout described in docs/mockups/tasks/task-development.md. */
    static final String WORKTREE_ROOT_REL = ".bytequay/worktrees";
    /** Sub-tree under {@link #WORKTREE_ROOT_REL} for coding threads. Other
     *  kinds (future "generic") will get their own sibling directory. */
    static final String DEV_SUBDIR = "dev";
    /** Branch-name prefix for coding threads; same shape as the on-disk
     *  directory tree so the branch name and worktree path mirror. */
    static final String DEV_BRANCH_PREFIX = "dev/";
    /** Hard cap on slug length so worktree paths and branch names stay
     *  human-readable. */
    static final int SLUG_MAX_CHARS = 32;

    private final GitRunner git;

    public WorktreeService(GitRunner git)
    {
        this.git = requireNonNull(git, "git is null");
    }

    /**
     * Creates the worktree + branch for a new thread. Returns the
     * handle on success, empty if the working dir isn't usable as a
     * git repo or any git step failed — caller falls back to the
     * main checkout.
     */
    public Optional<WorktreeHandle> create(Path repoRoot, String sessionId, String title)
    {
        requireNonNull(repoRoot, "repoRoot is null");
        requireNonNull(sessionId, "sessionId is null");
        if (!git.isAvailable()) {
            log.debug("git binary unavailable; skipping worktree for session {}", sessionId);
            return Optional.empty();
        }
        try {
            String slug = slugify(title);
            String suffix = sessionId + (slug.isEmpty() ? "" : "-" + slug);
            Path worktreePath = repoRoot
                    .resolve(WORKTREE_ROOT_REL)
                    .resolve(DEV_SUBDIR)
                    .resolve(suffix)
                    .toAbsolutePath()
                    .normalize();
            String branchName = DEV_BRANCH_PREFIX + suffix;
            String baseRef = resolveBaseRef(repoRoot);
            if (baseRef == null) {
                log.info("No base ref resolvable in {}; skipping worktree for session {}",
                        repoRoot, sessionId);
                return Optional.empty();
            }
            appendToGitInfoExclude(repoRoot);
            git.worktreeAdd(repoRoot, worktreePath, branchName, baseRef);
            log.info("Created worktree at {} on branch {} (from {}) for session {}",
                    worktreePath, branchName, baseRef, sessionId);
            return Optional.of(new WorktreeHandle(worktreePath, branchName));
        }
        catch (IOException e) {
            log.warn("Worktree create failed for session {} in {}: {}",
                    sessionId, repoRoot, e.getMessage());
            return Optional.empty();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Worktree create interrupted for session {}", sessionId);
            return Optional.empty();
        }
        catch (RuntimeException e) {
            // requireSuccess() throws RuntimeException-derived for non-zero exit.
            log.warn("Worktree create rejected by git for session {}: {}",
                    sessionId, e.getMessage());
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
     * Normalises a thread title into a worktree-safe slug. Lowercase,
     * non-alphanumeric runs collapsed to single dashes, leading/
     * trailing dashes stripped, truncated to {@link #SLUG_MAX_CHARS}
     * so the resulting path stays a reasonable length. Empty input
     * (or input that slugifies to nothing) returns an empty string;
     * the caller uses the session id alone in that case.
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
     * Picks a sensible base ref to branch the new worktree from. Tries
     * {@link GitRunner#defaultBranch} first (e.g. {@code main} /
     * {@code master} from the {@code origin/HEAD} symref); falls back
     * to whatever branch is currently checked out in the main repo.
     */
    private String resolveBaseRef(Path repoRoot)
            throws IOException, InterruptedException
    {
        Optional<String> defaultBranch = git.defaultBranch(repoRoot);
        if (defaultBranch.isPresent() && !defaultBranch.get().isBlank()) {
            return defaultBranch.get();
        }
        String current = git.currentBranch(repoRoot);
        if (current != null && !current.isBlank()) {
            return current;
        }
        return null;
    }

    /**
     * Adds {@code /.bytequay/} to {@code .git/info/exclude} if it's not
     * already there. Per-repo, not committed — the user's
     * {@code .gitignore} stays untouched. Best-effort: failures are
     * surfaced as IOExceptions so the caller can decide whether to
     * abort the worktree create or proceed.
     */
    private static void appendToGitInfoExclude(Path repoRoot)
            throws IOException
    {
        Path excludePath = repoRoot.resolve(".git").resolve("info").resolve("exclude");
        if (!Files.isDirectory(excludePath.getParent())) {
            // Not a standard git layout (e.g. a submodule or worktree
            // checkout). Skip — the worktree-add will surface a clearer
            // error if the repo really is broken.
            return;
        }
        String marker = "/.bytequay/";
        if (Files.exists(excludePath)) {
            String body = Files.readString(excludePath, StandardCharsets.UTF_8);
            for (String line : body.split("\\R", -1)) {
                if (line.trim().equals(marker) || line.trim().equals(".bytequay/")) {
                    return;
                }
            }
        }
        String append = (Files.exists(excludePath) && Files.size(excludePath) > 0 ? "\n" : "")
                + "# Added by ByteQuay — per-thread worktrees live here.\n"
                + marker + "\n";
        Files.writeString(excludePath, append, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Outcome of a successful {@link #create} call. */
    public record WorktreeHandle(Path worktreePath, String branchName) {}
}

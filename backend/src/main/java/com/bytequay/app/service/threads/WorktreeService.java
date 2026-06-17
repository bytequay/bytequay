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

    public WorktreeService(GitRunner git)
    {
        this.git = requireNonNull(git, "git is null");
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
            log.info("Created worktree at {} on branch {} (from {}) for task {}",
                    worktreePath, branchName, baseRef, taskId);
            return Optional.of(new WorktreeHandle(worktreePath, branchName));
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
        }
        catch (RuntimeException e) {
            log.warn("worktree reap for completed task {} failed: {}", task.id(), e.getMessage());
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
     * Picks a sensible base ref. Tries {@link GitRunner#defaultBranch}
     * first (from the {@code origin/HEAD} symref); falls back to the
     * branch checked out in the main repo.
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
    public record WorktreeHandle(Path worktreePath, String branchName) {}
}

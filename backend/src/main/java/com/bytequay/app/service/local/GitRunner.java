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

import com.bytequay.app.domain.RepoRef;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * Thin wrapper around the system {@code git} binary. We shell out
 * (rather than embedding JGit) so the user's existing gitconfig, SSH
 * keys, signing keys, and credential helper apply to ByteQuay
 * automatically — see project_jgit_fallback.md for the rationale.
 *
 * Operations are synchronous and timeout-bounded; the caller is
 * responsible for off-thread invocation when the latency would block
 * the request thread.
 */
@Component
public class GitRunner
{
    private static final long DEFAULT_TIMEOUT_SECONDS = 30;

    // Runtime-computed control-byte separators. Embedding them as
    // string literals (e.g. "\0" or with literal US) trips checkstyle's
    // lexer
    // because Java resolves Unicode escapes before lexical analysis,
    // putting actual control bytes in the source. Computing here
    // keeps the source plain ASCII.
    private static final String NUL_SEP = String.valueOf((char) 0);
    private static final String US_SEP = String.valueOf((char) 0x1F);
    private static final String RS_SEP = String.valueOf((char) 0x1E);

    /**
     * Returns true iff `git --version` succeeded — used as a startup
     * probe so the rest of the local-repo stack can short-circuit
     * with a friendly "install git" message when the binary is
     * missing.
     */
    public boolean isAvailable()
    {
        try {
            GitResult result = run(List.of("git", "--version"), null, 5);
            return result.exitCode() == 0;
        }
        catch (IOException e) {
            return false;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Runs {@code git status --porcelain} in {@code workingDir} and
     * returns the count of reported lines. Each line in the porcelain
     * output represents one changed file (modified, added, untracked,
     * or staged), so the count is the dirty-file pill the UI renders.
     */
    public int countDirtyFiles(Path workingDir)
            throws IOException, InterruptedException
    {
        GitResult result = run(List.of("git", "status", "--porcelain"), workingDir);
        result.requireSuccess();
        if (result.stdout().isEmpty()) {
            return 0;
        }
        // --porcelain emits one line per entry, lines have no trailing
        // newline mid-buffer but the final line does. Split on \n and
        // drop empties so an empty buffer doesn't read as one entry.
        return (int) result.stdout().lines().filter(s -> !s.isEmpty()).count();
    }

    /**
     * True if the working tree or index has anything {@code git status
     * --porcelain} would report (unstaged, staged, or untracked).
     * The ship-and-continue path uses this to decide whether to auto-
     * stage and commit before pushing.
     */
    public boolean hasUncommittedChanges(Path workingDir)
            throws IOException, InterruptedException
    {
        GitResult result = run(List.of("git", "status", "--porcelain"), workingDir);
        result.requireSuccess();
        return !result.stdout().isBlank();
    }

    /**
     * Raw status stream for callers that need a stable checkout
     * fingerprint. {@code -z} avoids ambiguous quoting, and
     * {@code --untracked-files=all} expands untracked directories so
     * a new source file inside one participates in the fingerprint.
     */
    public String statusPorcelainZ(Path workingDir)
            throws IOException, InterruptedException
    {
        GitResult result = run(
                List.of("git", "status", "--porcelain=v1", "-z", "--untracked-files=all"),
                workingDir);
        result.requireSuccess();
        return result.stdout();
    }

    /**
     * {@code git add -A} on the working dir — stages all unstaged
     * changes and untracked files. Pairs with {@link #commit} when
     * the agent finished editing but never committed.
     */
    public void stageAll(Path workingDir)
            throws IOException, InterruptedException
    {
        stageAll(workingDir, List.of());
    }

    /**
     * Like {@link #stageAll(Path)} but skips the given paths via
     * {@code :(exclude)} pathspecs, so app-managed files never enter a
     * commit even though they sit in the worktree. Nothing is written to
     * git's config or exclude files — the exclusion lives only in the
     * staging command we run.
     */
    public void stageAll(Path workingDir, List<String> excludePaths)
            throws IOException, InterruptedException
    {
        requireNonNull(excludePaths, "excludePaths is null");
        List<String> args = new ArrayList<>(List.of("git", "add", "-A"));
        if (!excludePaths.isEmpty()) {
            // A positive pathspec ('.') is required for the excludes to
            // subtract from; without it git would stage nothing.
            args.add("--");
            args.add(".");
            for (String path : excludePaths) {
                args.add(":(exclude)" + path);
            }
        }
        run(args, workingDir).requireSuccess();
    }

    /**
     * {@code git commit -m <message>}; returns the new HEAD sha, or
     * empty when there was nothing staged (which git reports as a
     * non-zero exit with "nothing to commit" in stderr — we treat
     * that as success-with-nothing-to-do).
     */
    public Optional<String> commit(Path workingDir, String message)
            throws IOException, InterruptedException
    {
        requireNonNull(message, "message is null");
        GitResult result = run(
                List.of("git", "commit", "-m", message),
                workingDir);
        if (result.exitCode() != 0) {
            String stderr = result.stderr() == null ? "" : result.stderr();
            if (stderr.contains("nothing to commit")
                    || stderr.contains("no changes added to commit")) {
                return Optional.empty();
            }
            result.requireSuccess();
        }
        GitResult head = run(List.of("git", "rev-parse", "HEAD"), workingDir);
        head.requireSuccess();
        return Optional.of(head.stdout().strip());
    }

    /**
     * Creates an empty commit ({@code git commit --allow-empty}) and
     * returns its SHA. Used to re-trigger a push-driven CI run (e.g. to
     * shake out a flaky failure) without changing any files.
     */
    public String commitEmpty(Path workingDir, String message)
            throws IOException, InterruptedException
    {
        requireNonNull(message, "message is null");
        run(List.of("git", "commit", "--allow-empty", "-m", message), workingDir).requireSuccess();
        GitResult head = run(List.of("git", "rev-parse", "HEAD"), workingDir);
        head.requireSuccess();
        return head.stdout().strip();
    }

    /**
     * Returns the HEAD commit SHA of {@code workingDir}. Used by the
     * post-ship CI-fix loop to target a re-run of the failed checks at
     * the exact commit the task's branch was pushed at.
     */
    public String headSha(Path workingDir)
            throws IOException, InterruptedException
    {
        GitResult result = run(List.of("git", "rev-parse", "HEAD"), workingDir);
        result.requireSuccess();
        return result.stdout().strip();
    }

    /** True when both checkouts share the same git object and ref store. */
    public boolean sharesCommonDirectory(Path first, Path second)
            throws IOException, InterruptedException
    {
        requireNonNull(first, "first is null");
        requireNonNull(second, "second is null");
        return gitCommonDirectory(first).equals(gitCommonDirectory(second));
    }

    private Path gitCommonDirectory(Path workingDir)
            throws IOException, InterruptedException
    {
        GitResult result = run(List.of("git", "rev-parse", "--git-common-dir"), workingDir);
        result.requireSuccess();
        Path path = Path.of(result.stdout().strip());
        if (!path.isAbsolute()) {
            path = workingDir.resolve(path);
        }
        return path.toRealPath();
    }

    /** Read one branch head directly from a remote without changing local
     * refs. Push-saga crash recovery uses this to distinguish "the push
     * happened but its stamp was lost" from an effect that still needs I/O. */
    public Optional<String> remoteHeadSha(Path workingDir, String remote, String branch)
            throws IOException, InterruptedException
    {
        requireNonNull(remote, "remote is null");
        requireNonNull(branch, "branch is null");
        GitResult result = run(
                List.of("git", "ls-remote", "--heads", remote, "refs/heads/" + branch),
                workingDir);
        result.requireSuccess();
        String output = result.stdout().strip();
        if (output.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(output.split("\\s+", 2)[0]);
    }

    /**
     * Resolves the local exclude file for this checkout. Linked
     * worktrees have a {@code .git} file instead of a directory, so
     * callers must ask git rather than assuming {@code .git/info/exclude}
     * under the working tree.
     */
    public Path gitInfoExcludePath(Path workingDir)
            throws IOException, InterruptedException
    {
        GitResult result = run(List.of("git", "rev-parse", "--git-path", "info/exclude"), workingDir);
        result.requireSuccess();
        Path path = Path.of(result.stdout().strip());
        if (!path.isAbsolute()) {
            path = workingDir.resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    /**
     * Returns the current branch name, or null if HEAD is detached
     * (e.g. the user checked out a tag or specific commit). Powers
     * the branch chip on the repo detail header.
     */
    public String currentBranch(Path workingDir)
            throws IOException, InterruptedException
    {
        GitResult result = run(List.of("git", "rev-parse", "--abbrev-ref", "HEAD"), workingDir);
        result.requireSuccess();
        String branch = result.stdout().strip();
        // git emits the literal "HEAD" when the working tree is in
        // detached-HEAD mode; treat that as "no branch".
        return branch.isEmpty() || "HEAD".equals(branch) ? null : branch;
    }

    /**
     * Returns durable Git control-state markers which mean the checkout is
     * still inside a multi-step mutation. Clean porcelain and an exact HEAD
     * are not sufficient while any of these markers remains.
     */
    public List<String> inProgressOperations(Path workingDir)
            throws IOException, InterruptedException
    {
        requireNonNull(workingDir, "workingDir is null");
        List<String> active = new ArrayList<>();
        addDirectoryMarker(workingDir, active, "rebase-merge");
        addDirectoryMarker(workingDir, active, "rebase-apply");
        addFileMarker(workingDir, active, "REBASE_HEAD");
        addDirectoryMarker(workingDir, active, "sequencer");
        addFileMarker(workingDir, active, "MERGE_HEAD");
        addFileMarker(workingDir, active, "MERGE_AUTOSTASH");
        addFileMarker(workingDir, active, "AUTO_MERGE");
        addFileMarker(workingDir, active, "CHERRY_PICK_HEAD");
        addFileMarker(workingDir, active, "REVERT_HEAD");
        addFileMarker(workingDir, active, "BISECT_START");
        addFileMarker(workingDir, active, "BISECT_HEAD");
        addFileMarker(workingDir, active, "BISECT_NAMES");
        addFileMarker(workingDir, active, "BISECT_LOG");
        addFileMarker(workingDir, active, "BISECT_RUN");
        addFileMarker(workingDir, active, "BISECT_TERMS");
        addFileMarker(workingDir, active, "BISECT_EXPECTED_REV");
        addFileMarker(workingDir, active, "BISECT_ANCESTORS_OK");
        addRefNamespaceMarker(workingDir, active, "refs/bisect");
        return List.copyOf(active);
    }

    /**
     * Aborts one recognized multi-step Git mutation under the caller's
     * explicit quarantine-repair authority. Ambiguous or foreign control
     * state is left untouched and fails closed.
     */
    public boolean abortInProgressOperationForRepair(Path workingDir)
            throws IOException, InterruptedException
    {
        List<String> active = inProgressOperations(workingDir);
        if (active.isEmpty()) {
            return false;
        }
        List<String> command;
        boolean rebaseMerge = active.contains("rebase-merge");
        boolean rebaseApply = active.contains("rebase-apply");
        if (rebaseMerge || rebaseApply) {
            if (rebaseMerge && rebaseApply) {
                throw new IllegalStateException(
                        "Git has ambiguous in-progress operation state: " + active);
            }
            requireOnly(
                    active,
                    rebaseMerge ? "rebase-merge" : "rebase-apply",
                    "REBASE_HEAD",
                    "AUTO_MERGE");
            if (rebaseApply
                    && Files.isRegularFile(
                            gitPath(workingDir, "rebase-apply/applying"))) {
                command = List.of("git", "am", "--abort");
            }
            else {
                command = List.of("git", "rebase", "--abort");
            }
        }
        else if (active.contains("CHERRY_PICK_HEAD")) {
            requireOnly(active, "CHERRY_PICK_HEAD", "sequencer", "AUTO_MERGE");
            command = List.of("git", "cherry-pick", "--abort");
        }
        else if (active.contains("REVERT_HEAD")) {
            requireOnly(active, "REVERT_HEAD", "sequencer", "AUTO_MERGE");
            command = List.of("git", "revert", "--abort");
        }
        else if (active.contains("MERGE_HEAD")) {
            requireOnly(active, "MERGE_HEAD", "MERGE_AUTOSTASH", "AUTO_MERGE");
            command = List.of("git", "merge", "--abort");
        }
        else if (active.contains("BISECT_START")) {
            requireOnly(
                    active,
                    "BISECT_START",
                    "BISECT_HEAD",
                    "BISECT_NAMES",
                    "BISECT_LOG",
                    "BISECT_RUN",
                    "BISECT_TERMS",
                    "BISECT_EXPECTED_REV",
                    "BISECT_ANCESTORS_OK",
                    "refs/bisect");
            command = List.of("git", "bisect", "reset");
        }
        else if (active.equals(List.of("sequencer"))) {
            String first = Files.readAllLines(
                            gitPath(workingDir, "sequencer/todo"),
                            StandardCharsets.UTF_8)
                    .stream()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Git sequencer has no operation identity"));
            if (first.startsWith("pick ")) {
                command = List.of("git", "cherry-pick", "--abort");
            }
            else if (first.startsWith("revert ")) {
                command = List.of("git", "revert", "--abort");
            }
            else {
                throw new IllegalStateException(
                        "Git sequencer operation is not recognized");
            }
        }
        else {
            throw new IllegalStateException(
                    "Git has ambiguous in-progress operation state: " + active);
        }
        run(command, workingDir, 60).requireSuccess();
        if (active.contains("AUTO_MERGE")
                && Files.isRegularFile(gitPath(workingDir, "AUTO_MERGE"))) {
            run(List.of("git", "update-ref", "-d", "AUTO_MERGE"), workingDir)
                    .requireSuccess();
        }
        List<String> remaining = inProgressOperations(workingDir);
        if (!remaining.isEmpty()) {
            throw new IllegalStateException(
                    "Git operation abort left control state: " + remaining);
        }
        return true;
    }

    private static void requireOnly(List<String> active, String... allowed)
    {
        if (!Set.of(allowed).containsAll(active)) {
            throw new IllegalStateException(
                    "Git has ambiguous in-progress operation state: " + active);
        }
    }

    private void addDirectoryMarker(
            Path workingDir, List<String> active, String marker)
            throws IOException, InterruptedException
    {
        if (Files.isDirectory(gitPath(workingDir, marker))) {
            active.add(marker);
        }
    }

    private void addFileMarker(
            Path workingDir, List<String> active, String marker)
            throws IOException, InterruptedException
    {
        if (Files.isRegularFile(gitPath(workingDir, marker))) {
            active.add(marker);
        }
    }

    private void addRefNamespaceMarker(
            Path workingDir, List<String> active, String namespace)
            throws IOException, InterruptedException
    {
        GitResult result = run(
                List.of("git", "for-each-ref", "--format=%(refname)", namespace),
                workingDir);
        result.requireSuccess();
        if (!result.stdout().isBlank()) {
            active.add(namespace);
        }
    }

    /**
     * Runs {@code git fetch --all --prune} in {@code workingDir} —
     * pulls every remote's refs, drops dead remote-tracking branches.
     * The fetched data updates ahead/behind counts on the next
     * branches-list call. Network-bound; 5-minute cap covers slow
     * pulls without indefinitely hanging.
     */
    public void fetch(Path workingDir)
            throws IOException, InterruptedException
    {
        GitResult result = run(
                List.of("git", "fetch", "--all", "--prune"),
                workingDir,
                300);
        result.requireSuccess();
    }

    /**
     * Fetches a single named remote — {@code git fetch --prune
     * <remote>}. Used before cutting a worktree off a fork's upstream
     * so {@code <remote>/HEAD} reflects the latest upstream tip rather
     * than whatever was last pulled. Narrower (and faster) than {@link
     * #fetch}'s {@code --all} when only one remote matters.
     */
    public void fetchRemote(Path workingDir, String remote)
            throws IOException, InterruptedException
    {
        requireNonNull(remote, "remote is null");
        run(List.of("git", "fetch", "--prune", remote), workingDir, 300)
                .requireSuccess();
    }

    /**
     * Copies the selected commit objects from another local checkout without
     * adding or changing a remote. Workspace relations point at independent
     * clones, so the fork must receive these objects before it can cherry-pick
     * an upstream SHA.
     */
    public void fetchObjects(Path workingDir, Path sourceRepository, List<String> commits)
            throws IOException, InterruptedException
    {
        requireNonNull(sourceRepository, "sourceRepository is null");
        requireNonNull(commits, "commits is null");
        if (commits.isEmpty()) {
            return;
        }
        List<String> args = new ArrayList<>(List.of(
                "git", "fetch", "--no-tags", sourceRepository.toString()));
        args.addAll(commits);
        run(args, workingDir, 300).requireSuccess();
    }

    /**
     * Runs {@code git pull --ff-only} — fast-forward only, no merge
     * commits. If the local branch has diverged from upstream the
     * pull fails loudly; resolving the divergence is explicitly out
     * of scope for the local-repo MVP (see local-repo-design.md —
     * conflict resolution is delegated to the user's IDE).
     */
    public void pullFastForward(Path workingDir)
            throws IOException, InterruptedException
    {
        GitResult result = run(
                List.of("git", "pull", "--ff-only"),
                workingDir,
                300);
        result.requireSuccess();
    }

    /**
     * Creates {@code branchName} starting from {@code baseRef} (or
     * the current HEAD when null) and switches to it. The fast,
     * single-command path is {@code git switch -c <name> [<base>]}.
     * Throws on conflicting names — git's "already exists" stderr
     * surfaces verbatim through the controller's 409 mapping.
     */
    public void createBranch(Path workingDir, String branchName, String baseRef)
            throws IOException, InterruptedException
    {
        requireNonNull(branchName, "branchName is null");
        List<String> args = new ArrayList<>(List.of("git", "switch", "-c", branchName));
        if (baseRef != null && !baseRef.isBlank()) {
            args.add(baseRef);
        }
        run(args, workingDir).requireSuccess();
    }

    /** Uses git's own ref-name validator instead of duplicating its rules. */
    public boolean isValidBranchName(String branchName)
            throws IOException, InterruptedException
    {
        if (branchName == null || branchName.isBlank()) {
            return false;
        }
        return run(List.of("git", "check-ref-format", "--branch", branchName), null, 5)
                .exitCode() == 0;
    }

    /**
     * Switches HEAD to {@code branchName}. Equivalent to
     * {@code git switch <branch>}. Fails if the working tree has
     * uncommitted changes that would conflict — git's stderr
     * surfaces verbatim through the controller's 409 mapping so the
     * UI can prompt the user to stash or commit.
     */
    public void switchBranch(Path workingDir, String branchName)
            throws IOException, InterruptedException
    {
        requireNonNull(branchName, "branchName is null");
        run(List.of("git", "switch", branchName), workingDir).requireSuccess();
    }

    /**
     * Materializes a branch the user has on a remote but not in the
     * local clone — fetches the ref from origin, then runs
     * {@code git switch} which auto-creates a tracking branch when
     * exactly one remote-tracking ref matches. Used when the user
     * picks an IN_REVIEW PR whose head branch hasn't been checked
     * out locally (e.g. they pushed it from another machine).
     */
    public void checkoutRemoteBranch(Path workingDir, String branchName)
            throws IOException, InterruptedException
    {
        requireNonNull(branchName, "branchName is null");
        // 5-minute fetch cap — covers slow networks without hanging
        // the request thread indefinitely.
        run(List.of("git", "fetch", "origin", branchName), workingDir, 300).requireSuccess();
        run(List.of("git", "switch", branchName), workingDir).requireSuccess();
    }

    /**
     * Deletes the named local branches in a single {@code git branch
     * -D} invocation. {@code -D} is the unconditional form (no merged
     * check) — the cleanup column already encodes the safety filter
     * server-side, so a second client-side check would just refuse
     * branches the user explicitly chose. Authorization that they
     * belong in cleanup happens in the service layer; this method
     * is the dumb executor.
     */
    public void deleteBranches(Path workingDir, List<String> names)
            throws IOException, InterruptedException
    {
        requireNonNull(names, "names is null");
        if (names.isEmpty()) {
            return;
        }
        List<String> args = new ArrayList<>(List.of("git", "branch", "-D"));
        args.addAll(names);
        run(args, workingDir).requireSuccess();
    }

    /**
     * Creates a linked worktree rooted at {@code worktreePath} with a
     * new branch {@code branchName} pointing at {@code baseRef}. The
     * branch ref is created as part of the same git command — there's
     * no separate {@code createBranch} call needed.
     *
     * <p>Equivalent to {@code git worktree add -b <branchName>
     * <worktreePath> <baseRef>}. Fails (with git's stderr surfaced) if
     * the branch already exists, the path already exists, or the base
     * ref doesn't resolve.
     *
     * @param mainRepoDir the main repo's working directory (the
     *                    worktree command runs from here so it knows
     *                    which .git store to register the new worktree
     *                    under)
     * @param worktreePath absolute path of the new worktree's directory
     * @param branchName name of the new branch to create
     * @param baseRef the ref to branch from (e.g. {@code "main"} or
     *                {@code "upstream/master"})
     */
    public void worktreeAdd(Path mainRepoDir, Path worktreePath, String branchName, String baseRef)
            throws IOException, InterruptedException
    {
        requireNonNull(worktreePath, "worktreePath is null");
        requireNonNull(branchName, "branchName is null");
        requireNonNull(baseRef, "baseRef is null");
        run(List.of("git", "worktree", "add",
                        "-b", branchName,
                        worktreePath.toString(),
                        baseRef),
                mainRepoDir)
                .requireSuccess();
    }

    /** Re-attaches a pre-existing branch after a process stopped between
     * creating the branch and durably recording the linked worktree. */
    public void worktreeAddExisting(Path mainRepoDir, Path worktreePath, String branchName)
            throws IOException, InterruptedException
    {
        requireNonNull(worktreePath, "worktreePath is null");
        requireNonNull(branchName, "branchName is null");
        run(List.of("git", "worktree", "add", worktreePath.toString(), branchName),
                mainRepoDir)
                .requireSuccess();
    }

    /** Drops registrations whose checkout directory no longer exists. */
    public void worktreePrune(Path mainRepoDir)
            throws IOException, InterruptedException
    {
        run(List.of("git", "worktree", "prune"), mainRepoDir).requireSuccess();
    }

    /**
     * Adds a <b>detached</b> worktree at {@code baseRef} — {@code git
     * worktree add --detach <path> <ref>}. No branch is created, so the
     * worktree is a throwaway checkout that can be hard-reset to a moving
     * ref on each use. Used for the trunk's read-only planning worktree,
     * which tracks {@code upstream/master} (or {@code origin/main}) without
     * holding a branch that would collide with task worktrees.
     */
    public void worktreeAddDetached(Path mainRepoDir, Path worktreePath, String baseRef)
            throws IOException, InterruptedException
    {
        requireNonNull(worktreePath, "worktreePath is null");
        requireNonNull(baseRef, "baseRef is null");
        run(List.of("git", "worktree", "add", "--detach",
                        worktreePath.toString(),
                        baseRef),
                mainRepoDir)
                .requireSuccess();
    }

    /**
     * Hard-resets {@code workingDir} to {@code ref} — {@code git reset
     * --hard <ref>}. Discards any local changes in that working tree.
     * Only safe on worktrees ByteQuay owns (e.g. the planning worktree);
     * never call it on the user's own checkout.
     */
    public void resetHard(Path workingDir, String ref)
            throws IOException, InterruptedException
    {
        requireNonNull(ref, "ref is null");
        run(List.of("git", "reset", "--hard", ref), workingDir).requireSuccess();
    }

    /**
     * Removes untracked files from a ByteQuay-owned worktree after a rejected
     * writer attempt. App-managed paths can be retained explicitly; ignored
     * files are not touched.
     */
    public void cleanUntracked(Path workingDir, List<String> excludePaths)
            throws IOException, InterruptedException
    {
        requireNonNull(workingDir, "workingDir is null");
        requireNonNull(excludePaths, "excludePaths is null");
        List<String> args = new ArrayList<>(List.of("git", "clean", "-f", "-d"));
        for (String path : excludePaths) {
            requireNonNull(path, "exclude path is null");
            if (path.isBlank()) {
                throw new IllegalArgumentException("exclude path is blank");
            }
            args.add("-e");
            args.add(path.endsWith("/") ? path : path + "/");
        }
        run(args, workingDir).requireSuccess();
    }

    /**
     * Sets a single git config value in {@code workingDir} —
     * {@code git config <key> <value>}. Scope is the local config of
     * the working dir, which for a linked worktree means the per-
     * worktree {@code .git/config.worktree} (not the shared main
     * repo config). Used to wire {@code core.hooksPath} so the
     * worktree picks up ByteQuay's per-task hook directory without
     * touching the main repo's hooks.
     */
    public void setConfig(Path workingDir, String key, String value)
            throws IOException, InterruptedException
    {
        requireNonNull(key, "key is null");
        requireNonNull(value, "value is null");
        run(List.of("git", "config", key, value), workingDir).requireSuccess();
    }

    /**
     * Removes the worktree at {@code worktreePath}. Uses {@code --force}
     * to handle the case where the worktree has uncommitted changes
     * (we own the worktree's lifecycle, so the dirty-tree safety check
     * doesn't apply). Does not delete the branch — pair with
     * {@link #deleteBranches} when removing the linked dev branch too.
     *
     * <p>Idempotent if {@code git worktree prune} has already cleared
     * the worktree's metadata: stderr surfaces, callers decide whether
     * to log-and-continue.
     */
    public void worktreeRemove(Path mainRepoDir, Path worktreePath)
            throws IOException, InterruptedException
    {
        requireNonNull(worktreePath, "worktreePath is null");
        run(List.of("git", "worktree", "remove", "--force", worktreePath.toString()),
                mainRepoDir)
                .requireSuccess();
    }

    /**
     * Applies commits in caller-supplied order and deliberately leaves a
     * failed cherry-pick in progress so an attached recovery agent can resolve
     * it in the isolated worktree.
     */
    public CherryPickOutcome cherryPick(
            Path workingDir,
            List<String> commits)
            throws IOException, InterruptedException
    {
        return cherryPick(workingDir, commits, false);
    }

    /**
     * @param recordOrigin pass {@code -x} so git appends
     *         {@code (cherry picked from commit <sha>)} to each message. The line
     *         survives a conflict resolved through {@code --continue}, because the
     *         sequencer keeps the flag for the whole run.
     */
    public CherryPickOutcome cherryPick(
            Path workingDir,
            List<String> commits,
            boolean recordOrigin)
            throws IOException, InterruptedException
    {
        requireNonNull(commits, "commits is null");
        int applied = 0;
        for (String commit : commits) {
            requireNonNull(commit, "commit is null");
            List<String> argv = recordOrigin
                    ? List.of("git", "cherry-pick", "-x", commit)
                    : List.of("git", "cherry-pick", commit);
            GitResult result = run(
                    argv,
                    workingDir,
                    300);
            if (result.exitCode() != 0) {
                GitResult unresolved = run(
                        List.of("git", "diff", "--name-only",
                                "--diff-filter=U", "-z"),
                        workingDir,
                        30);
                List<String> paths = unresolved.exitCode() == 0
                        ? Arrays.stream(unresolved.stdout().split(NUL_SEP, -1))
                                .filter(path -> !path.isBlank())
                                .toList()
                        : List.of();
                String detail = result.stderr().isBlank()
                        ? result.stdout().strip()
                        : result.stderr().strip();
                return new CherryPickOutcome(
                        false, applied, commit, paths, detail);
            }
            applied++;
        }
        return new CherryPickOutcome(
                true, applied, null, List.of(), null);
    }

    /** True when this worktree contains a stopped cherry-pick. */
    public boolean cherryPickInProgress(Path workingDir)
            throws IOException, InterruptedException
    {
        GitResult result = run(
                List.of("git", "rev-parse", "--verify", "--quiet", "CHERRY_PICK_HEAD"),
                workingDir,
                15);
        return result.exitCode() == 0;
    }

    /** Paths which still have unresolved index stages. */
    public List<String> unresolvedPaths(Path workingDir)
            throws IOException, InterruptedException
    {
        GitResult result = run(
                List.of("git", "diff", "--name-only", "--diff-filter=U", "-z"),
                workingDir,
                30);
        result.requireSuccess();
        return Arrays.stream(result.stdout().split(NUL_SEP, -1))
                .filter(path -> !path.isBlank())
                .toList();
    }

    /** Continues a human-resolved cherry-pick without opening an editor. */
    public CherryPickOutcome continueCherryPick(Path workingDir)
            throws IOException, InterruptedException
    {
        List<String> unresolved = unresolvedPaths(workingDir);
        if (!unresolved.isEmpty()) {
            return new CherryPickOutcome(
                    false, 0, null, unresolved,
                    "resolve every conflict before continuing");
        }
        GitResult result = run(
                List.of("git", "-c", "core.editor=true", "cherry-pick", "--continue"),
                workingDir,
                300);
        if (result.exitCode() == 0) {
            return new CherryPickOutcome(true, 1, null, List.of(), null);
        }
        String detail = result.stderr().isBlank()
                ? result.stdout().strip()
                : result.stderr().strip();
        return new CherryPickOutcome(
                false, 0, null, unresolvedPaths(workingDir), detail);
    }

    public record CherryPickOutcome(
            boolean complete,
            int appliedCount,
            String stoppedAt,
            List<String> conflictPaths,
            String message) {}

    /**
     * {@code git push <remote> --delete <branch>} — removes the branch
     * from the remote. Used by the per-card delete affordance when
     * the user opts to delete the remote copy alongside the local one.
     * Network-bound; 5-minute cap matches push.
     */
    public void deleteRemoteBranch(Path workingDir, String remoteName, String branchName)
            throws IOException, InterruptedException
    {
        requireNonNull(remoteName, "remoteName is null");
        requireNonNull(branchName, "branchName is null");
        run(List.of("git", "push", remoteName, "--delete", branchName), workingDir, 300)
                .requireSuccess();
    }

    /**
     * True iff git can resolve {@code ref} in {@code workingDir} —
     * tag, branch, remote-tracking ref, SHA, anything {@code git
     * rev-parse --verify} accepts. Cheap probe used to fall back from
     * a bare base name to its remote-tracking equivalent when the
     * user hasn't checked out a local branch by that name.
     */
    public boolean refExists(Path workingDir, String ref)
            throws IOException, InterruptedException
    {
        requireNonNull(ref, "ref is null");
        GitResult result = run(
                List.of("git", "rev-parse", "--verify", "--quiet", ref + "^{commit}"),
                workingDir,
                5);
        return result.exitCode() == 0;
    }

    /** Read one file from an immutable commit without checking it out or
     * changing the user's working tree. Used by standalone PR reviews when
     * the watched clone already contains the reviewed GitHub SHA. */
    public String fileAtRef(Path workingDir, String ref, String path)
            throws IOException, InterruptedException
    {
        requireNonNull(ref, "ref is null");
        requireNonNull(path, "path is null");
        // A ref is concatenated into a single argv token below, so a leading "-" is
        // parsed by git as an option rather than a revision. `git show --output=<file>`
        // is a perfectly good file writer, which would make this read-only helper a
        // write primitive for any caller that forwards an untrusted ref.
        if (ref.isBlank() || ref.startsWith("-")) {
            throw new IllegalArgumentException("ref must not be blank or start with '-'");
        }
        Path relative = Path.of(path).normalize();
        if (relative.isAbsolute() || relative.startsWith("..")) {
            throw new IllegalArgumentException("path escapes repository");
        }
        GitResult result = run(
                List.of("git", "show", ref + ":" + relative.toString().replace('\\', '/')),
                workingDir,
                30);
        result.requireSuccess();
        return result.stdout();
    }

    /** Find bounded literal references in the exact reviewed commit. This
     * is intentionally read-only: no fetch, checkout, or worktree mutation. */
    public List<String> grepAtRef(Path workingDir, String ref, String query, int limit)
            throws IOException, InterruptedException
    {
        requireNonNull(ref, "ref is null");
        requireNonNull(query, "query is null");
        if (query.isBlank() || limit <= 0) {
            return List.of();
        }
        GitResult result = run(
                List.of("git", "grep", "-n", "-F", "-m", "5", "-e", query, ref, "--"),
                workingDir,
                30);
        if (result.exitCode() == 1) {
            return List.of();
        }
        result.requireSuccess();
        return result.stdout().lines().limit(limit).toList();
    }

    /** Resolve {@code ref} to the full commit SHA it points at, or empty when
     *  it doesn't resolve. Used to pin a task's base at cut time so the diff
     *  is a fixed {@code base..HEAD} instead of a re-guessed branch name. */
    public Optional<String> resolveCommitSha(Path workingDir, String ref)
            throws IOException, InterruptedException
    {
        requireNonNull(ref, "ref is null");
        GitResult result = run(
                List.of("git", "rev-parse", "--verify", "--quiet", ref + "^{commit}"),
                workingDir,
                5);
        if (result.exitCode() != 0) {
            return Optional.empty();
        }
        String sha = result.stdout().strip();
        return sha.isEmpty() ? Optional.empty() : Optional.of(sha);
    }

    /**
     * Returns the unified diff for what {@code headRef} adds on top of
     * its merge-base with {@code baseRef} — same set of changes
     * GitHub renders on a PR ({@code git diff base...head}, three
     * dots). Truncated to {@code maxBytes} so a giant PR doesn't
     * blow up the AI prompt budget; truncation is indicated by an
     * inline marker so the caller (and the model) knows the data is
     * incomplete.
     *
     * <p>Long-running branches against a stale local base would
     * produce huge two-dot diffs (mainline drift dominates the real
     * changes); three-dot scopes the diff to the branch's commits
     * and matches what the user expects from "the PR diff".
     */
    public String diff(Path workingDir, String baseRef, String headRef, int maxBytes)
            throws IOException, InterruptedException
    {
        requireNonNull(baseRef, "baseRef is null");
        requireNonNull(headRef, "headRef is null");
        GitResult result = run(
                List.of("git", "diff", baseRef + "..." + headRef),
                workingDir,
                180);
        result.requireSuccess();
        String stdout = result.stdout();
        if (maxBytes > 0 && stdout.length() > maxBytes) {
            return stdout.substring(0, maxBytes)
                    + "\n\n... (diff truncated at " + maxBytes + " bytes; "
                    + (stdout.length() - maxBytes) + " more bytes omitted)\n";
        }
        return stdout;
    }

    /**
     * Plain {@code git push} on the current branch. With no upstream
     * tracking ref configured we add {@code -u origin <branch>} so
     * the push lands the branch on the user's fork (origin) and
     * sets it up to track from then on.
     */
    public void push(Path workingDir)
            throws IOException, InterruptedException
    {
        runPush(workingDir, false);
    }

    /**
     * {@code git push --force-with-lease} — overwrites the remote
     * branch only if its tip still matches what the local clone last
     * saw. That guard rejects the push when someone else pushed in
     * between, so the user doesn't blow away a teammate's commit by
     * accident. Strictly safer than {@code --force}; we never expose
     * plain force-push. The caller is expected to gate this behind
     * an explicit confirmation in the UI.
     */
    public void pushForceWithLease(Path workingDir)
            throws IOException, InterruptedException
    {
        runPush(workingDir, true);
    }

    private void runPush(Path workingDir, boolean forceWithLease)
            throws IOException, InterruptedException
    {
        // Always push the current branch to `origin` under the same name and
        // set tracking there — never rely on the branch's own upstream. A fork
        // task branch is cut from `upstream/master`, so its upstream is
        // `upstream/master` (a different remote AND a different name); a bare
        // `git push` then fails push.default=simple's name check ("the upstream
        // branch ... does not match the name of your current branch"). Pushing
        // `origin HEAD` lands the branch on the fork (or, for a directly-owned
        // clone, on its own origin) and is correct for both layouts. The PR is
        // opened against the merge target (upstream/master for a fork, the
        // default branch for a direct clone) separately.
        List<String> args = forceWithLease
                ? List.of("git", "push", "--force-with-lease", "-u", "origin", "HEAD")
                : List.of("git", "push", "-u", "origin", "HEAD");
        GitResult result = run(args, workingDir, 300);
        // A first force-push has no remote-tracking ref for the lease to
        // compare against — retry without the lease (still to origin HEAD).
        if (result.exitCode() != 0
                && forceWithLease
                && (result.stderr().contains("has no upstream branch")
                        || result.stderr().contains("stale info")
                        || result.stderr().contains("no upstream"))) {
            run(List.of("git", "push", "-u", "origin", "HEAD"), workingDir, 300)
                    .requireSuccess();
            return;
        }
        result.requireSuccess();
    }

    /**
     * Lists every local branch with metadata in a single
     * {@code git for-each-ref} invocation. The callback gets one
     * {@link BranchRef} per branch — name, last-commit timestamp,
     * upstream-tracking ref, ahead/behind counts. Caller joins
     * against the watched PR table to fill in linkedPrNumber.
     *
     * Output format is delimiter-separated rather than one git call
     * per branch — N branches in O(1) processes is critical for
     * repos like trino with hundreds of long-lived branches.
     */
    public List<BranchRef> listBranches(Path workingDir)
            throws IOException, InterruptedException
    {
        // Field separator: ASCII Unit Separator (0x1F). Branch names
        // can technically contain anything except control chars and
        // a few special tokens, so a normal char would risk false
        // splits. \x1f is one byte and effectively never appears in
        // branch names or shortlog output.
        String fmt = "%(refname:short)"
                + "%(committerdate:iso-strict)"
                + "%(upstream)"
                + "%(upstream:track)"
                + "%(HEAD)";
        GitResult result = run(
                List.of("git", "for-each-ref", "--format=" + fmt, "refs/heads"),
                workingDir);
        result.requireSuccess();
        return result.stdout().lines()
                .filter(line -> !line.isEmpty())
                .map(GitRunner::parseBranchRow)
                .toList();
    }

    private static BranchRef parseBranchRow(String line)
    {
        String[] parts = line.split("", -1);
        // Defensive: a malformed row ends up with fewer fields. Treat
        // missing fields as empty so we don't crash listing the whole
        // repo because one ref had a weird format.
        String name = parts.length > 0 ? parts[0] : "";
        String date = parts.length > 1 ? parts[1] : "";
        String upstream = parts.length > 2 ? parts[2] : "";
        String track = parts.length > 3 ? parts[3] : "";
        String head = parts.length > 4 ? parts[4] : "";
        return new BranchRef(name, date, upstream, track, "*".equals(head));
    }

    public record BranchRef(String name, String committerDate, String upstream,
                             String upstreamTrack, boolean isCurrent) {}

    /**
     * Short names of every remote-tracking branch of {@code remote} —
     * {@code upstream/master}, {@code upstream/release-1}, and so on.
     * The remote's symbolic {@code HEAD} is excluded: it is an alias for
     * one of the branches already listed, not a branch of its own.
     */
    public List<String> listRemoteBranches(Path workingDir, String remote)
            throws IOException, InterruptedException
    {
        requireNonNull(remote, "remote is null");
        GitResult result = run(
                List.of("git", "for-each-ref", "--format=%(refname:short)",
                        "refs/remotes/" + remote),
                workingDir);
        result.requireSuccess();
        return result.stdout().lines()
                .map(String::strip)
                .filter(name -> !name.isEmpty() && !name.equals(remote + "/HEAD"))
                .toList();
    }

    /**
     * Returns the local clone's idea of the upstream's default branch,
     * read from {@code refs/remotes/origin/HEAD}. Returns
     * {@link Optional#empty()} when origin/HEAD isn't set — happens
     * after a shallow clone or in repos created locally without an
     * origin push.
     */
    public Optional<String> defaultBranch(Path workingDir)
            throws IOException, InterruptedException
    {
        return defaultBranch(workingDir, "origin");
    }

    /**
     * Default branch of a named remote — read from {@code
     * refs/remotes/<remote>/HEAD}. {@link #defaultBranch(Path)} is the
     * {@code origin} case; a fork-based clone resolves its upstream's
     * default (e.g. {@code master}) by passing the upstream remote name
     * so a worktree branches off the right base instead of the fork's.
     * Empty when the remote has no recorded HEAD (never fetched, or a
     * bare mirror without a symbolic HEAD).
     */
    public Optional<String> defaultBranch(Path workingDir, String remote)
            throws IOException, InterruptedException
    {
        requireNonNull(remote, "remote is null");
        GitResult result = run(
                List.of("git", "symbolic-ref", "--short", "refs/remotes/" + remote + "/HEAD"),
                workingDir,
                5);
        if (result.exitCode() != 0) {
            return Optional.empty();
        }
        // symbolic-ref --short returns "<remote>/main"; strip the remote
        // prefix to get the bare branch name the user types.
        String full = result.stdout().strip();
        int slash = full.indexOf('/');
        if (slash < 0 || slash == full.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(full.substring(slash + 1));
    }

    /**
     * Virtual merge of {@code head} onto {@code base} via
     * {@code git merge-tree --write-tree}. Returns {@link RebaseOutcome#CLEAN}
     * when the merge applies without conflicts (exit 0),
     * {@link RebaseOutcome#CONFLICTS} when git reports conflicting paths
     * (exit 1), and {@link RebaseOutcome#UNKNOWN} for any other
     * failure (unresolvable ref, ancient git, etc).
     *
     * Conflict-on-merge is a close proxy for conflict-on-rebase: a
     * proper rebase replays each commit against the new tip while a
     * merge unifies the whole change set, so they can disagree on
     * exotic histories. For the kanban's "will this rebase be
     * painful?" hint that's accurate enough.
     */
    public RebaseOutcome rebasePreview(Path workingDir, String head, String base)
            throws IOException, InterruptedException
    {
        requireNonNull(head, "head is null");
        requireNonNull(base, "base is null");
        GitResult result = run(
                List.of("git", "merge-tree", "--write-tree", "--no-messages", base, head),
                workingDir,
                30);
        return switch (result.exitCode()) {
            case 0 -> RebaseOutcome.CLEAN;
            case 1 -> RebaseOutcome.CONFLICTS;
            default -> RebaseOutcome.UNKNOWN;
        };
    }

    /**
     * Enumerates the file paths that would conflict if {@code head} was
     * merged onto {@code base}. Same {@code merge-tree --write-tree}
     * machinery as {@link #rebasePreview}, but with {@code --name-only}
     * so the conflict-info section is a flat list of paths instead of
     * the verbose {@code (mode, oid, stage, name)} tuples.
     *
     * <p>Output format from git:
     * <pre>
     *   &lt;merged-tree-OID&gt;
     *   path/with/conflict.txt
     *   another/conflicting/path.java
     * </pre>
     * The first line is always the OID; we drop it and return what's
     * left. Returns an empty list when the merge is clean (exit 0).
     * Returns an empty list on {@code merge-tree}-unsupported git
     * versions or unresolvable refs too — the caller should rely on
     * {@code mergeableState} from GitHub for the "does a conflict
     * exist at all" question; this method only enumerates names when
     * one already does.
     */
    public List<String> listMergeConflictPaths(Path workingDir, String head, String base)
            throws IOException, InterruptedException
    {
        requireNonNull(head, "head is null");
        requireNonNull(base, "base is null");
        GitResult result = run(
                List.of("git", "merge-tree", "--write-tree", "--name-only", "--no-messages", base, head),
                workingDir,
                30);
        if (result.exitCode() == 0 || result.exitCode() == 1) {
            String stdout = result.stdout();
            if (stdout == null || stdout.isEmpty()) {
                return ImmutableList.of();
            }
            String[] lines = stdout.split("\n", -1);
            // First line is the merged-tree OID; rest are paths. Filter
            // blanks (trailing newline → empty tail) so a clean tree
            // (one OID line, no paths) returns [] cleanly.
            ImmutableList.Builder<String> out = ImmutableList.builder();
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i].trim();
                if (!line.isEmpty()) {
                    out.add(line);
                }
            }
            return out.build();
        }
        return ImmutableList.of();
    }

    /**
     * Actually rebases the current branch onto {@code base} — a real
     * mutation, unlike {@link #rebasePreview}'s dry run. Callers are
     * expected to check {@link #rebasePreview} is {@link
     * RebaseOutcome#CLEAN} first; if the real rebase still fails (a race —
     * base moved between the preview and this call, say), it's aborted
     * immediately so the worktree never sits mid-rebase, and the original
     * {@link GitCommandException} propagates.
     */
    public void rebase(Path workingDir, String base)
            throws IOException, InterruptedException
    {
        requireNonNull(base, "base is null");
        GitResult result = run(List.of("git", "rebase", base), workingDir, 120);
        if (result.exitCode() != 0) {
            run(List.of("git", "rebase", "--abort"), workingDir, 30);
            result.requireSuccess();
        }
    }

    /**
     * Applies a real rebase and distinguishes an actual content conflict from
     * every other Git failure. Conflict paths are captured before aborting, and
     * a conflict is returned only after the clean source HEAD is restored.
     * Unknown or infrastructure failures still throw.
     */
    public RebaseApplyResult rebaseAndClassify(Path workingDir, String base)
            throws IOException, InterruptedException
    {
        requireNonNull(base, "base is null");
        String sourceHead = headSha(workingDir);
        if (!statusPorcelainZ(workingDir).isEmpty()) {
            throw new IllegalStateException(
                    "Cannot apply an exact rebase to a dirty worktree");
        }
        GitResult result = run(List.of("git", "rebase", base), workingDir, 120);
        if (result.exitCode() == 0) {
            return RebaseApplyResult.success();
        }

        List<String> conflicts;
        try {
            conflicts = unresolvedPaths(workingDir);
        }
        catch (IOException | InterruptedException | RuntimeException failure) {
            try {
                restoreRebaseSource(workingDir, sourceHead);
            }
            catch (IOException | InterruptedException | RuntimeException restoreFailure) {
                failure.addSuppressed(restoreFailure);
            }
            throw failure;
        }
        restoreRebaseSource(workingDir, sourceHead);
        if (!conflicts.isEmpty()) {
            return RebaseApplyResult.conflict(conflicts);
        }
        result.requireSuccess();
        throw new IllegalStateException("Unreachable rebase result");
    }

    private void restoreRebaseSource(Path workingDir, String sourceHead)
            throws IOException, InterruptedException
    {
        GitResult abort = run(
                List.of("git", "rebase", "--abort"), workingDir, 30);
        boolean restored = sourceHead.equals(headSha(workingDir))
                && statusPorcelainZ(workingDir).isEmpty();
        if (restored) {
            return;
        }
        abort.requireSuccess();
        throw new IllegalStateException(
                "Git rebase abort did not restore the exact source");
    }

    /**
     * Aborts only the in-progress rebase that exactly belongs to the supplied
     * immutable operation. A foreign or malformed rebase is left untouched.
     * The return value is false when no rebase is in progress.
     */
    public boolean abortExactRebase(
            Path workingDir,
            String branch,
            String sourceHead,
            String targetBase)
            throws IOException, InterruptedException
    {
        requireNonNull(workingDir, "workingDir is null");
        requireNonNull(branch, "branch is null");
        requireNonNull(sourceHead, "sourceHead is null");
        requireNonNull(targetBase, "targetBase is null");
        Path mergeState = gitPath(workingDir, "rebase-merge");
        Path applyState = gitPath(workingDir, "rebase-apply");
        boolean mergeActive = Files.isDirectory(mergeState);
        boolean applyActive = Files.isDirectory(applyState);
        if (!mergeActive && !applyActive) {
            return false;
        }
        if (mergeActive && applyActive) {
            throw new IllegalStateException(
                    "Git has multiple in-progress rebase states");
        }

        Path state = mergeActive ? mergeState : applyState;
        String original = requireRebaseMetadata(state, "orig-head");
        String headName = requireRebaseMetadata(state, "head-name");
        String onto = requireRebaseMetadata(state, "onto");
        if (!sourceHead.equals(original)
                || !("refs/heads/" + branch).equals(headName)
                || !targetBase.equals(onto)) {
            throw new IllegalStateException(
                    "In-progress rebase does not match the exact operation");
        }

        GitResult abort = run(
                List.of("git", "rebase", "--abort"), workingDir, 30);
        abort.requireSuccess();
        if (Files.isDirectory(mergeState) || Files.isDirectory(applyState)
                || !branch.equals(currentBranch(workingDir))
                || !sourceHead.equals(headSha(workingDir))
                || !statusPorcelainZ(workingDir).isEmpty()) {
            throw new IllegalStateException(
                    "Git rebase abort did not restore the exact operation source");
        }
        return true;
    }

    private Path gitPath(Path workingDir, String name)
            throws IOException, InterruptedException
    {
        GitResult result = run(
                List.of("git", "rev-parse", "--git-path", name), workingDir);
        result.requireSuccess();
        Path path = Path.of(result.stdout().strip());
        if (!path.isAbsolute()) {
            path = workingDir.resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    private static String requireRebaseMetadata(Path state, String name)
            throws IOException
    {
        Path path = state.resolve(name);
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException(
                    "In-progress rebase lacks exact " + name + " metadata");
        }
        String value = Files.readString(path, StandardCharsets.UTF_8).strip();
        if (value.isEmpty()) {
            throw new IllegalStateException(
                    "In-progress rebase has blank " + name + " metadata");
        }
        return value;
    }

    public record RebaseApplyResult(
            boolean rebased,
            List<String> conflictPaths)
    {
        public RebaseApplyResult
        {
            conflictPaths = List.copyOf(requireNonNull(
                    conflictPaths, "conflictPaths is null"));
            if (rebased == !conflictPaths.isEmpty()
                    || conflictPaths.stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException(
                        "Rebase result must be either rebased or exact conflicts");
            }
        }

        public static RebaseApplyResult success()
        {
            return new RebaseApplyResult(true, List.of());
        }

        public static RebaseApplyResult conflict(List<String> conflictPaths)
        {
            return new RebaseApplyResult(false, conflictPaths);
        }
    }

    /**
     * Replays {@code base..HEAD} using an explicit todo list instead of
     * whatever {@code git rebase -i} would have generated. Both editors
     * are neutralised: {@code sequence.editor} just copies our file over
     * git's todo, and {@code core.editor} is {@code true}(1) so a
     * {@code reword}/conflict can never block on a terminal that isn't
     * there. The caller owns failure handling — this returns the raw
     * result rather than throwing so an aborting caller can read stderr.
     */
    public GitResult rebaseWithTodo(Path workingDir, String base, Path todoFile)
            throws IOException, InterruptedException
    {
        requireNonNull(base, "base is null");
        requireNonNull(todoFile, "todoFile is null");
        return run(
                List.of("git",
                        "-c", "core.editor=true",
                        "-c", "sequence.editor=cp '" + todoFile + "'",
                        "rebase", "-i", base),
                workingDir,
                300);
    }

    /**
     * Publishes a branch whose history was just rewritten. Unlike
     * {@link #pushForceWithLease} this NEVER retries without the lease:
     * a stale lease means the remote moved while the user was editing,
     * and overwriting it anyway is precisely what the lease exists to
     * prevent. Returns the raw result so the caller can surface git's
     * own rejection message.
     */
    public GitResult pushRewrittenBranch(Path workingDir)
            throws IOException, InterruptedException
    {
        return run(
                List.of("git", "push", "--force-with-lease", "-u", "origin", "HEAD"),
                workingDir,
                300);
    }

    /**
     * Publishes one rewritten branch under an explicit named lease. The
     * expected remote SHA is part of the command itself, so neither a stale
     * tracking ref nor a missing upstream can weaken the authorization. There
     * is deliberately no plain-push fallback.
     */
    public GitResult pushRewrittenBranch(
            Path workingDir, String branch, String expectedRemoteSha)
            throws IOException, InterruptedException
    {
        requireNonNull(branch, "branch is null");
        requireNonNull(expectedRemoteSha, "expectedRemoteSha is null");
        if (branch.isBlank() || expectedRemoteSha.isBlank()) {
            throw new IllegalArgumentException(
                    "Rewritten branch and exact lease SHA are required");
        }
        String ref = "refs/heads/" + branch;
        return run(
                List.of("git", "push",
                        "--force-with-lease=" + ref + ":" + expectedRemoteSha,
                        "-u", "origin", "HEAD:" + ref),
                workingDir,
                300);
    }

    /** Best-effort {@code git rebase --abort}; a no-op when no rebase is
     *  in progress, so callers can invoke it unconditionally on failure. */
    public void rebaseAbort(Path workingDir)
            throws IOException, InterruptedException
    {
        run(List.of("git", "rebase", "--abort"), workingDir, 60);
    }

    /**
     * Added/deleted line totals per commit on {@code revision}, keyed by
     * full sha. One {@code git log --numstat} pass rather than a
     * {@code git show} per row — the Commits list renders these on every
     * row, so N round-trips would dominate the page load.
     *
     * <p>Merge commits report no numstat by default and come back as
     * 0/0; binary files report {@code -} and are counted as 0.
     */
    public Map<String, LineStats> commitLineStats(
            Path workingDir, String revision, int limit, int skip)
            throws IOException, InterruptedException
    {
        if (limit <= 0) {
            return Map.of();
        }
        List<String> args = new ArrayList<>(List.of(
                "git", "log",
                "--max-count=" + limit,
                "--skip=" + Math.max(skip, 0),
                "--numstat",
                "--format=" + RS_SEP + "%H"));
        if (revision != null && !revision.isBlank()) {
            args.add(revision);
        }
        GitResult result = run(args, workingDir);
        result.requireSuccess();
        Map<String, LineStats> stats = new LinkedHashMap<>();
        for (String record : result.stdout().split(RS_SEP, -1)) {
            if (record.isBlank()) {
                continue;
            }
            String[] lines = record.split("\n", -1);
            String sha = lines[0].strip();
            if (sha.isEmpty()) {
                continue;
            }
            int additions = 0;
            int deletions = 0;
            for (int i = 1; i < lines.length; i++) {
                String[] columns = lines[i].split("\t", 3);
                if (columns.length < 3) {
                    continue;
                }
                additions += parseNumstat(columns[0]);
                deletions += parseNumstat(columns[1]);
            }
            stats.put(sha, new LineStats(additions, deletions));
        }
        return Map.copyOf(stats);
    }

    private static int parseNumstat(String cell)
    {
        try {
            return Integer.parseInt(cell.strip());
        }
        catch (NumberFormatException binaryOrEmpty) {
            return 0;
        }
    }

    /**
     * Added-line total for {@code fromSha..toSha}, summed across every file.
     * One {@code git diff --numstat} pass; binary files report {@code -} and
     * count as 0.
     *
     * <p>Additions only. Callers sizing a change to decide whether it is worth
     * acting on want the volume of new code, not the churn: a commit that only
     * deletes has nothing to review.
     */
    public int addedLines(Path workingDir, String fromSha, String toSha)
            throws IOException, InterruptedException
    {
        if (fromSha == null || toSha == null || fromSha.equals(toSha)) {
            return 0;
        }
        GitResult result = run(
                List.of("git", "diff", "--numstat", fromSha + ".." + toSha),
                workingDir);
        result.requireSuccess();
        int additions = 0;
        for (String line : result.stdout().split("\n", -1)) {
            String[] columns = line.split("\t", 3);
            if (columns.length < 3) {
                continue;
            }
            additions += parseNumstat(columns[0]);
        }
        return additions;
    }

    public record LineStats(int additions, int deletions) {}

    /**
     * Message bodies (everything after the subject line) per commit on
     * {@code revision}, keyed by full sha. Separate from
     * {@link #listCommits} so the ordinary Commits list keeps paying
     * only for subjects; the rewrite editor needs every body up front
     * because a reword must preserve the body it didn't touch.
     */
    public Map<String, String> commitBodies(
            Path workingDir, String revision, int limit, int skip)
            throws IOException, InterruptedException
    {
        if (limit <= 0) {
            return Map.of();
        }
        List<String> args = new ArrayList<>(List.of(
                "git", "log",
                "--max-count=" + limit,
                "--skip=" + Math.max(skip, 0),
                "-z",
                "--pretty=format:%H" + US_SEP + "%b"));
        if (revision != null && !revision.isBlank()) {
            args.add(revision);
        }
        GitResult result = run(args, workingDir);
        result.requireSuccess();
        Map<String, String> bodies = new LinkedHashMap<>();
        for (String record : result.stdout().split(NUL_SEP, -1)) {
            if (record.isEmpty()) {
                continue;
            }
            String[] parts = record.split(US_SEP, 2);
            if (parts.length < 2) {
                continue;
            }
            bodies.put(parts[0].strip(), parts[1].strip());
        }
        return Map.copyOf(bodies);
    }

    /**
     * Full shas on {@code branch} that {@code trackingRef} does not
     * contain — i.e. the commits still safe to rewrite without a force
     * push. Returns every listed commit when {@code trackingRef} is null
     * (branch never pushed), which is the conservative reading: nothing
     * is published, so nothing needs the force warning.
     */
    public Set<String> unpushedShas(Path workingDir, String branch, String trackingRef)
            throws IOException, InterruptedException
    {
        requireNonNull(branch, "branch is null");
        if (trackingRef == null) {
            trackingRef = "";
        }
        List<String> args = trackingRef.isBlank()
                ? List.of("git", "rev-list", branch)
                : List.of("git", "rev-list", branch, "^" + trackingRef);
        GitResult result = run(args, workingDir);
        result.requireSuccess();
        return result.stdout().lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * The remote-tracking ref {@code branch} publishes to — its
     * configured upstream, falling back to {@code origin/<branch>} when
     * no upstream is set but the ref exists anyway. Empty when the
     * branch has never been pushed.
     */
    public Optional<String> trackingRef(Path workingDir, String branch)
            throws IOException, InterruptedException
    {
        requireNonNull(branch, "branch is null");
        GitResult upstream = run(
                List.of("git", "rev-parse", "--abbrev-ref", branch + "@{upstream}"),
                workingDir,
                10);
        if (upstream.exitCode() == 0 && !upstream.stdout().isBlank()) {
            return Optional.of(upstream.stdout().strip());
        }
        String fallback = "origin/" + branch;
        return refExists(workingDir, fallback) ? Optional.of(fallback) : Optional.empty();
    }

    /**
     * Fetches the GitHub-magic {@code pull/{N}/head} ref + the PR's
     * base branch into a non-user-visible refs namespace
     * ({@code refs/bytequay/pr/{N}/{head,base}}) so the merge-tree call
     * has both tips locally without disturbing the user's branches or
     * tracking refs. Single {@code git fetch} so we pay one network
     * round-trip per PR.
     *
     * <p>{@code pull/N/head} is published by GitHub on the upstream
     * repo for both same-repo and cross-fork PRs — no fork-handling
     * needed here.
     *
     * <p>Throws on fetch failure (network down, auth missing, PR
     * deleted, etc.). The caller is expected to {@code try/catch} and
     * surface a graceful no-data state to the UI.
     */
    public void fetchPrRefs(Path workingDir, int prNumber, String baseRef)
            throws IOException, InterruptedException
    {
        fetchPrRefs(workingDir, "origin", prNumber, baseRef);
    }

    /** Fetches the same private PR refs from a specific configured remote. */
    public void fetchPrRefs(Path workingDir, String remote, int prNumber, String baseRef)
            throws IOException, InterruptedException
    {
        requireNonNull(remote, "remote is null");
        requireNonNull(baseRef, "baseRef is null");
        if (prNumber <= 0) {
            throw new IllegalArgumentException("prNumber must be positive, got " + prNumber);
        }
        String headRefspec = "pull/" + prNumber + "/head:refs/bytequay/pr/" + prNumber + "/head";
        String baseRefspec = baseRef + ":refs/bytequay/pr/" + prNumber + "/base";
        run(
                List.of("git", "fetch", "--no-tags", "--quiet", remote, headRefspec, baseRefspec),
                workingDir,
                120)
                .requireSuccess();
    }

    /** Refspec namespace ref for the head side of a fetched PR. Mirror
     *  this constant on the caller so it doesn't drift if we ever move
     *  off the {@code refs/bytequay/pr/} prefix. */
    public static String headRef(int prNumber)
    {
        return "refs/bytequay/pr/" + prNumber + "/head";
    }

    /** See {@link #headRef}. */
    public static String baseRef(int prNumber)
    {
        return "refs/bytequay/pr/" + prNumber + "/base";
    }

    public enum RebaseOutcome
    {
        CLEAN,
        CONFLICTS,
        UNKNOWN
    }

    /**
     * Counts commits reachable from {@code branch} that aren't
     * reachable from {@code base} — the work unique to this branch.
     * Same shape as the upstream "ahead" count, but vs the default
     * branch instead of the tracking ref. Returns null when either
     * ref is unresolvable so a missing default doesn't blow up the
     * whole listBranches call.
     */
    public Integer commitCountUniqueTo(Path workingDir, String branch, String base)
            throws IOException, InterruptedException
    {
        requireNonNull(branch, "branch is null");
        requireNonNull(base, "base is null");
        GitResult result = run(
                List.of("git", "rev-list", "--count", branch, "^" + base),
                workingDir,
                15);
        if (result.exitCode() != 0) {
            return null;
        }
        try {
            return Integer.parseInt(result.stdout().strip());
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Proves the constrained history shape used by a base-owned CI repair:
     * one new repair commit directly on {@code base}, followed by the exact
     * original Task patch series in the same order.
     */
    public boolean preservesBaseRepairHistory(
            Path workingDir,
            String base,
            String originalHead,
            String repairedHead)
            throws IOException, InterruptedException
    {
        List<String> original = commitShasInRange(workingDir, base, originalHead);
        List<String> repaired = commitShasInRange(workingDir, base, repairedHead);
        if (original.isEmpty() || repaired.size() != original.size() + 1) {
            return false;
        }
        String repairCommit = repaired.getFirst();
        GitResult parent = run(
                List.of("git", "rev-list", "--parents", "-n", "1",
                        repairCommit),
                workingDir, 15);
        String[] parentParts = parent.exitCode() == 0
                ? parent.stdout().strip().split("\\s+") : new String[0];
        if (parentParts.length != 2 || !base.equals(parentParts[1])) {
            return false;
        }
        GitResult rangeDiff = run(
                List.of("git", "range-diff", "--no-color",
                        base + ".." + originalHead,
                        repairCommit + ".." + repairedHead),
                workingDir, 30);
        if (rangeDiff.exitCode() != 0) {
            return false;
        }
        int position = 1;
        for (String line : rangeDiff.stdout().lines().toList()) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.strip().split("\\s+");
            String ordinal = position + ":";
            if (parts.length < 5 || !ordinal.equals(parts[0])
                    || !"=".equals(parts[2])
                    || !ordinal.equals(parts[3])) {
                return false;
            }
            position++;
        }
        return position == original.size() + 1;
    }

    /**
     * Full commit SHAs in {@code base..head}, oldest first. Unlike the
     * presentation-oriented commit-list methods, an unresolvable range is an
     * error: lifecycle code uses this as an exact history proof.
     */
    public List<String> commitShasInRange(
            Path workingDir, String base, String head)
            throws IOException, InterruptedException
    {
        requireNonNull(base, "base is null");
        requireNonNull(head, "head is null");
        GitResult result = run(
                List.of("git", "rev-list", "--reverse", head, "^" + base),
                workingDir, 15);
        result.requireSuccess();
        return result.stdout().lines()
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    /** Direct parents of one exact commit, in Git's stored order. */
    public List<String> commitParentShas(Path workingDir, String commit)
            throws IOException, InterruptedException
    {
        requireNonNull(commit, "commit is null");
        GitResult result = run(
                List.of("git", "rev-list", "--parents", "-n", "1", commit),
                workingDir, 15);
        result.requireSuccess();
        String[] fields = result.stdout().strip().split("\\s+");
        if (fields.length == 0 || fields[0].isBlank()) {
            throw new IllegalStateException("commit has no identity: " + commit);
        }
        return Arrays.stream(fields).skip(1).toList();
    }

    /** Immutable tree object named by one commit. */
    public String commitTreeSha(Path workingDir, String commit)
            throws IOException, InterruptedException
    {
        requireNonNull(commit, "commit is null");
        GitResult result = run(
                List.of("git", "rev-parse", "--verify", commit + "^{tree}"),
                workingDir, 15);
        result.requireSuccess();
        return result.stdout().strip();
    }

    /**
     * Git's stable patch identity for one non-empty commit. The patch is fed
     * through a temporary file so no shell pipeline or platform quoting is
     * involved.
     */
    public String stablePatchId(Path workingDir, String commit)
            throws IOException, InterruptedException
    {
        requireNonNull(commit, "commit is null");
        GitResult patch = run(
                List.of("git", "show", "--pretty=format:", "--binary",
                        "--no-ext-diff", commit),
                workingDir, 30);
        patch.requireSuccess();
        Path input = Files.createTempFile("bytequay-patch-id-", ".diff");
        try {
            Files.writeString(input, patch.stdout(), StandardCharsets.UTF_8);
            GitResult result = run(
                    List.of("git", "patch-id", "--stable"),
                    workingDir, 30, input);
            result.requireSuccess();
            String[] fields = result.stdout().strip().split("\\s+");
            if (fields.length < 1 || !fields[0].matches("[0-9a-f]{40,64}")) {
                throw new IllegalStateException(
                        "commit has no stable patch id: " + commit);
            }
            return fields[0];
        }
        finally {
            Files.deleteIfExists(input);
        }
    }

    /**
     * Best-common-ancestor sha of {@code branch} and {@code base} —
     * git's notion of a branch point. Returns {@link Optional#empty()}
     * when there's no common ancestor (truly unrelated histories) or
     * when either ref doesn't resolve. The Commits tab uses this to
     * place a "branched from <base>" divider after the matching row in
     * the per-branch commit list.
     */
    /**
     * Subject + body of one commit ({@code git log -1 sha}). Used by
     * the Commits-tab patch-detail card; kept off the listCommits
     * response so a 100-row commit list doesn't pay for the body
     * text on every branch load. Returns {@link Optional#empty()}
     * when the sha doesn't resolve.
     *
     * <p>Output is one record using {@link #US_SEP} between subject
     * and body — letting body contain anything except NUL.
     */
    /**
     * Lists files in the working tree that differ from HEAD —
     * uncommitted changes (staged + unstaged + untracked). Powers
     * the Commits tab's "Changes" mode. We use {@code --porcelain=v1
     * -z} so paths with spaces / unicode round-trip cleanly.
     *
     * <p>Each porcelain v1 record is two status chars + space + path,
     * NUL-terminated. We surface a single {@code WorkingTreeFile}
     * per path — when both staged and unstaged columns are populated
     * we keep the staged status, since that's what would land on
     * the next commit.
     */
    public List<WorkingTreeFile> workingTreeFiles(Path workingDir)
            throws IOException, InterruptedException
    {
        GitResult result = run(
                List.of("git", "status", "--porcelain=v1", "-z"),
                workingDir,
                15);
        result.requireSuccess();
        String stdout = result.stdout();
        if (stdout.isEmpty()) {
            return ImmutableList.of();
        }
        ImmutableList.Builder<WorkingTreeFile> out = ImmutableList.builder();
        // Records are NUL-separated. For renames, porcelain v1 emits
        // `R<sp>old<NUL>new` — handle by consuming an extra token.
        String[] records = stdout.split(NUL_SEP, -1);
        for (int i = 0; i < records.length; i++) {
            String rec = records[i];
            if (rec.length() < 4) {
                // skip empties + the trailing ""
                continue;
            }
            char staged = rec.charAt(0);
            char unstaged = rec.charAt(1);
            String path = rec.substring(3);
            // Pick the most informative status: staged wins, fall
            // back to unstaged. Untracked is "??" — collapse to "A".
            char status;
            if (staged == '?' && unstaged == '?') {
                status = 'A';
            }
            else if (staged != ' ' && staged != '?') {
                status = staged;
            }
            else {
                status = unstaged;
            }
            // Renames consume a second NUL-separated token (the new path).
            if (status == 'R' || status == 'C') {
                if (i + 1 < records.length) {
                    path = records[i + 1];
                    i++;
                }
            }
            out.add(new WorkingTreeFile(path, String.valueOf(status)));
        }
        return out.build();
    }

    public record WorkingTreeFile(String path, String status) {}

    /**
     * Unified diff for one file in the working tree —
     * {@code git diff HEAD -- <path>} so the patch reflects both
     * staged and unstaged changes against the last commit.
     * Untracked files appear as "added" without an existing blob;
     * we fall back to {@code git diff --no-index /dev/null path}
     * for those so the user still sees the new file's content.
     * Truncation matches {@link #commitFileDiff}.
     */
    public String workingTreeFileDiff(Path workingDir, String path, int maxBytes)
            throws IOException, InterruptedException
    {
        requireNonNull(path, "path is null");
        // Try HEAD-relative diff first. For untracked files git-diff
        // returns nothing; fall back to a no-index diff against
        // /dev/null so the user still sees the new content.
        GitResult result = run(
                List.of("git", "diff", "HEAD", "--", path),
                workingDir,
                30);
        result.requireSuccess();
        String stdout = result.stdout();
        if (stdout.isBlank()) {
            // Probably untracked. /dev/null on macOS works the same as Linux.
            GitResult untracked = run(
                    List.of("git", "diff", "--no-index", "--", "/dev/null", path),
                    workingDir,
                    30);
            // git diff --no-index returns 1 when files differ — that's "success" for our purposes.
            if (untracked.exitCode() == 0 || untracked.exitCode() == 1) {
                stdout = untracked.stdout();
            }
        }
        if (maxBytes > 0 && stdout.length() > maxBytes) {
            return stdout.substring(0, maxBytes)
                    + "\n\n... (diff truncated at " + maxBytes + " bytes; "
                    + (stdout.length() - maxBytes) + " more bytes omitted)\n";
        }
        return stdout;
    }

    public Optional<CommitDetailEntry> commitDetail(Path workingDir, String sha)
            throws IOException, InterruptedException
    {
        requireNonNull(sha, "sha is null");
        String fmt = "%H" + US_SEP + "%s" + US_SEP + "%b";
        GitResult result = run(
                List.of("git", "log", "-1", "-z", "--pretty=format:" + fmt, sha),
                workingDir,
                15);
        if (result.exitCode() != 0) {
            return Optional.empty();
        }
        String stdout = result.stdout();
        // -z on -1 still emits a trailing NUL; strip it before splitting.
        if (stdout.endsWith(NUL_SEP)) {
            stdout = stdout.substring(0, stdout.length() - 1);
        }
        String[] parts = stdout.split(US_SEP, -1);
        if (parts.length < 3) {
            return Optional.empty();
        }
        return Optional.of(new CommitDetailEntry(parts[0], parts[1], parts[2]));
    }

    public record CommitDetailEntry(String sha, String subject, String body) {}

    public Optional<String> mergeBase(Path workingDir, String branch, String base)
            throws IOException, InterruptedException
    {
        requireNonNull(branch, "branch is null");
        requireNonNull(base, "base is null");
        GitResult result = run(
                List.of("git", "merge-base", branch, base),
                workingDir,
                15);
        if (result.exitCode() != 0) {
            return Optional.empty();
        }
        String sha = result.stdout().strip();
        return sha.isEmpty() ? Optional.empty() : Optional.of(sha);
    }

    /**
     * Walks {@code git log} on {@code revision} (or HEAD when null)
     * and returns up to {@code limit} commits, newest first. Powers
     * the Commits tab. We use {@code -z} so each record is
     * NUL-terminated, which lets commit subjects safely contain any
     * byte except NUL — that includes embedded newlines and our
     * field-separator (US, 0x1F) without escaping logic.
     *
     * Bounded latency: {@code git log} on a 100k-commit repo with a
     * limit of a few hundred completes in milliseconds, so the
     * default 30s timeout is fine. Larger ranges should page rather
     * than raising the cap.
     */
    public List<CommitEntry> listCommits(Path workingDir, String revision, int limit)
            throws IOException, InterruptedException
    {
        return listCommits(workingDir, revision, limit, 0);
    }

    /** As {@link #listCommits(Path, String, int)}, skipping the newest
     *  {@code skip} commits — how the Commits list pages backwards. */
    public List<CommitEntry> listCommits(Path workingDir, String revision, int limit, int skip)
            throws IOException, InterruptedException
    {
        if (limit <= 0) {
            return List.of();
        }
        // %H full sha, %h abbreviated, %an/%ae author, %aI ISO-8601
        // strict authored timestamp, %s subject. Body deferred until
        // a "Commit details" drill-in lands.
        String fmt = "%H" + US_SEP + "%h" + US_SEP + "%an" + US_SEP
                + "%ae" + US_SEP + "%aI" + US_SEP + "%s";
        List<String> args = new ArrayList<>(List.of(
                "git", "log",
                "--max-count=" + limit,
                "--skip=" + Math.max(skip, 0),
                "-z",
                "--pretty=format:" + fmt));
        if (revision != null && !revision.isBlank()) {
            args.add(revision);
        }
        GitResult result = run(args, workingDir);
        result.requireSuccess();
        String stdout = result.stdout();
        if (stdout.isEmpty()) {
            return List.of();
        }
        List<CommitEntry> entries = new ArrayList<>();
        // -z emits records separated by a single NUL with no trailing
        // NUL on the final record.
        for (String record : stdout.split(NUL_SEP, -1)) {
            if (record.isEmpty()) {
                continue;
            }
            String[] parts = record.split(US_SEP, -1);
            if (parts.length < 6) {
                continue;
            }
            entries.add(new CommitEntry(
                    parts[0], parts[1], parts[2], parts[3], parts[4], parts[5]));
        }
        return List.copyOf(entries);
    }

    public record CommitEntry(
            String sha,
            String shortSha,
            String authorName,
            String authorEmail,
            String authoredAt,
            String subject) {}

    /** Commit-list projection with exact tag decorations. */
    public List<DecoratedCommitEntry> listDecoratedCommits(
            Path workingDir,
            String revision,
            int limit)
            throws IOException, InterruptedException
    {
        if (limit <= 0) {
            return List.of();
        }
        String fmt = "%H" + US_SEP + "%h" + US_SEP + "%an" + US_SEP
                + "%ae" + US_SEP + "%aI" + US_SEP + "%s" + US_SEP
                + "%D";
        List<String> args = new ArrayList<>(List.of(
                "git", "log", "--max-count=" + limit, "-z",
                "--pretty=format:" + fmt));
        if (revision != null && !revision.isBlank()) {
            args.add(revision);
        }
        GitResult result = run(args, workingDir);
        result.requireSuccess();
        List<DecoratedCommitEntry> entries = new ArrayList<>();
        for (String record : result.stdout().split(NUL_SEP, -1)) {
            if (record.isEmpty()) {
                continue;
            }
            String[] parts = record.split(US_SEP, -1);
            if (parts.length < 7) {
                continue;
            }
            List<String> tags = Arrays.stream(parts[6].split(", "))
                    .map(String::strip)
                    .filter(decoration -> decoration.startsWith("tag: "))
                    .map(decoration -> decoration.substring("tag: ".length()))
                    .toList();
            entries.add(new DecoratedCommitEntry(
                    parts[0], parts[1], parts[2], parts[3], parts[4],
                    parts[5], tags));
        }
        return List.copyOf(entries);
    }

    public record DecoratedCommitEntry(
            String sha,
            String shortSha,
            String authorName,
            String authorEmail,
            String authoredAt,
            String subject,
            List<String> tags) {}

    public int countCommits(Path workingDir, String revision)
            throws IOException, InterruptedException
    {
        requireNonNull(revision, "revision is null");
        GitResult result = run(
                List.of("git", "rev-list", "--count", revision),
                workingDir,
                60);
        result.requireSuccess();
        return Integer.parseInt(result.stdout().strip());
    }

    /**
     * Like {@link #listCommits} but scoped to commits authored after
     * {@code since}. Drives the Tasks "Commits" tab so we only surface
     * what the AI session produced during its lifetime — anything older
     * is unrelated history. Uses {@code --since} in ISO-8601 form;
     * git interprets it as author date, which matches what we want
     * (we care about when the commit was made, not when an old commit
     * was rewritten).
     */
    public List<CommitEntry> listCommitsSince(Path workingDir, Instant since, int limit)
            throws IOException, InterruptedException
    {
        if (limit <= 0) {
            return List.of();
        }
        requireNonNull(since, "since is null");
        String fmt = "%H" + US_SEP + "%h" + US_SEP + "%an" + US_SEP
                + "%ae" + US_SEP + "%aI" + US_SEP + "%s";
        List<String> args = new ArrayList<>(List.of(
                "git", "log",
                "--max-count=" + limit,
                "--since=" + since.toString(),
                "-z",
                "--pretty=format:" + fmt));
        GitResult result = run(args, workingDir);
        result.requireSuccess();
        String stdout = result.stdout();
        if (stdout.isEmpty()) {
            return List.of();
        }
        List<CommitEntry> entries = new ArrayList<>();
        for (String record : stdout.split(NUL_SEP, -1)) {
            if (record.isEmpty()) {
                continue;
            }
            String[] parts = record.split(US_SEP, -1);
            if (parts.length < 6) {
                continue;
            }
            entries.add(new CommitEntry(
                    parts[0], parts[1], parts[2], parts[3], parts[4], parts[5]));
        }
        return List.copyOf(entries);
    }

    /**
     * Lists the commits a task branch has ADDED on top of its base —
     * {@code git log <base>..HEAD}. This is what "commits on this task"
     * means: only the work the task authored, not the base branch's
     * history the worktree was cut from. A time-based filter over-includes
     * here, because commits landed on the base branch (by other work)
     * during the task's lifetime are recent but aren't the task's.
     *
     * <p>Returns no commits when {@code base} is blank or unresolvable
     * (e.g. the base ref is gone) rather than failing the page.
     */
    public List<CommitEntry> listCommitsAhead(Path workingDir, String base, int limit)
            throws IOException, InterruptedException
    {
        if (limit <= 0 || base == null || base.isBlank()) {
            return List.of();
        }
        String fmt = "%H" + US_SEP + "%h" + US_SEP + "%an" + US_SEP
                + "%ae" + US_SEP + "%aI" + US_SEP + "%s";
        List<String> args = new ArrayList<>(List.of(
                "git", "log",
                "--max-count=" + limit,
                base.trim() + "..HEAD",
                "-z",
                "--pretty=format:" + fmt));
        GitResult result = run(args, workingDir);
        if (result.exitCode() != 0) {
            // An unresolvable base (deleted ref, detached state) shouldn't
            // 500 the Commits panel — just show nothing task-specific.
            return List.of();
        }
        String stdout = result.stdout();
        if (stdout.isEmpty()) {
            return List.of();
        }
        List<CommitEntry> entries = new ArrayList<>();
        for (String record : stdout.split(NUL_SEP, -1)) {
            if (record.isEmpty()) {
                continue;
            }
            String[] parts = record.split(US_SEP, -1);
            if (parts.length < 6) {
                continue;
            }
            entries.add(new CommitEntry(
                    parts[0], parts[1], parts[2], parts[3], parts[4], parts[5]));
        }
        return List.copyOf(entries);
    }

    /**
     * Resolve the ref to diff/list a branch's own commits against. A
     * configured base branch name is only that — a name; in a fresh worktree
     * it may exist only as a remote-tracking ref ({@code origin/main}) and
     * not locally, and a purely local ref can go stale (never fast-forwarded)
     * while {@code origin/<base>} moves on, silently sweeping in commits that
     * already landed upstream by other work. We probe the configured base,
     * its remote-tracking variants, the detected default branch, and a few
     * common fallbacks, then pick whichever resolvable candidate's merge-base
     * is CLOSEST to {@code HEAD} (fewest commits in {@code base..HEAD}) —
     * the tightest base is the branch's real fork point, immune to any one
     * candidate ref having drifted stale. Every caller that lists or diffs
     * "this branch's own commits" must route through this one resolver
     * rather than trusting a configured base name verbatim, or the two call
     * sites silently disagree (one over-includes upstream history).
     *
     * @return the resolved merge-base ref/sha, or null when nothing resolves
     */
    public String resolveCommitBase(Path workingDir, String configuredBaseBranch)
            throws IOException, InterruptedException
    {
        List<String> candidates = new ArrayList<>();
        if (configuredBaseBranch != null && !configuredBaseBranch.isBlank()) {
            String c = configuredBaseBranch.trim();
            candidates.add(c);
            candidates.add("origin/" + c);
            candidates.add("upstream/" + c);
        }
        defaultBranch(workingDir).ifPresent(b -> {
            candidates.add(b);
            candidates.add("origin/" + b);
            candidates.add("upstream/" + b);
        });
        candidates.addAll(List.of(
                "main", "origin/main", "upstream/main",
                "master", "origin/master", "upstream/master"));
        String best = null;
        int bestCount = Integer.MAX_VALUE;
        String firstResolvable = null;
        for (String ref : candidates) {
            if (!refExists(workingDir, ref)) {
                continue;
            }
            String mergeBase = mergeBase(workingDir, "HEAD", ref).orElse(ref);
            if (firstResolvable == null) {
                firstResolvable = mergeBase;
            }
            Integer count = commitCountUniqueTo(workingDir, "HEAD", mergeBase);
            if (count != null && count < bestCount) {
                bestCount = count;
                best = mergeBase;
            }
        }
        return best != null ? best : firstResolvable;
    }

    /**
     * Lists every file touched by a single commit, with status and
     * line counts. Powers the middle pane of the Commits tab.
     *
     * <p>Combined {@code --name-status} + {@code --numstat} pass: one
     * git invocation, two record formats. {@code --format=} suppresses
     * the commit header so we only get file-change lines. Binary files
     * appear with a {@code -}/{@code -} count which we map to -1.
     */
    public List<CommitFileChange> commitFiles(Path workingDir, String sha)
            throws IOException, InterruptedException
    {
        requireNonNull(sha, "sha is null");
        // Two passes are simpler than parsing the combined --raw output.
        // numstat → additions/deletions; name-status → A/M/D/R/C/T flag.
        // Both are O(touched files) so the cost is negligible.
        GitResult numstat = run(
                List.of("git", "show", "--numstat", "--format=", sha),
                workingDir);
        numstat.requireSuccess();
        Map<String, int[]> counts = new HashMap<>();
        for (String line : Splitter.on('\n').split(numstat.stdout())) {
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\t", 3);
            if (parts.length < 3) {
                continue;
            }
            int adds = "-".equals(parts[0]) ? -1 : parseIntSafe(parts[0]);
            int dels = "-".equals(parts[1]) ? -1 : parseIntSafe(parts[1]);
            counts.put(parts[2], new int[] {adds, dels});
        }
        GitResult nameStatus = run(
                List.of("git", "show", "--name-status", "--format=", sha),
                workingDir);
        nameStatus.requireSuccess();
        List<CommitFileChange> files = new ArrayList<>();
        for (String line : Splitter.on('\n').split(nameStatus.stdout())) {
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\t", 3);
            if (parts.length < 2) {
                continue;
            }
            // Renames/copies emit "R<score>\t<old>\t<new>" — the path
            // we surface is the new one (where the change lives now).
            String status = parts[0].substring(0, 1);
            String path = parts.length == 3 ? parts[2] : parts[1];
            int[] count = counts.getOrDefault(path, new int[] {0, 0});
            files.add(new CommitFileChange(path, status, count[0], count[1]));
        }
        return List.copyOf(files);
    }

    private static int parseIntSafe(String s)
    {
        try {
            return Integer.parseInt(s);
        }
        catch (NumberFormatException e) {
            return 0;
        }
    }

    public record CommitFileChange(String path, String status, int additions, int deletions) {}

    /**
     * Returns the unified diff for one file at a specific commit
     * ({@code git show <sha> -- <path>}), truncated to {@code maxBytes}
     * with an inline marker so a giant patch doesn't blow up the
     * renderer. Pipe-drain in {@link #run} keeps git from blocking on
     * a full stdout buffer for large diffs.
     */
    public String commitFileDiff(Path workingDir, String sha, String path, int maxBytes)
            throws IOException, InterruptedException
    {
        requireNonNull(sha, "sha is null");
        requireNonNull(path, "path is null");
        // --format= suppresses the commit message header; -- separates
        // the path from the revision so weirdly-named files don't get
        // misread as refs.
        GitResult result = run(
                List.of("git", "show", "--format=", sha, "--", path),
                workingDir,
                60);
        result.requireSuccess();
        String stdout = result.stdout();
        if (maxBytes > 0 && stdout.length() > maxBytes) {
            return stdout.substring(0, maxBytes)
                    + "\n\n... (diff truncated at " + maxBytes + " bytes; "
                    + (stdout.length() - maxBytes) + " more bytes omitted)\n";
        }
        return stdout;
    }

    /**
     * Unified diff for one file across a commit range
     * ({@code git diff <base>..<head> -- <path>}). Used by the branch
     * Commits tab when the user has selected more than one commit —
     * the caller passes the parent of the oldest selected commit
     * ({@code <oldestSelected>^}) as {@code base} and the newest
     * selected commit as {@code head}, so the resulting patch
     * captures every change those commits introduced as one unified
     * diff. Truncation behavior matches {@link #commitFileDiff}.
     */
    /**
     * Files that differ between {@code base} and {@code head}
     * ({@code git diff --name-status -z base..head}). Returns the
     * same shape as {@link #commitFiles} but for a range —
     * additions/deletions are 0 (we'd need a second pass with
     * {@code --numstat} to fill them in; per-file diffs already
     * carry the line counts when the user drills in). Used by the
     * Commits tab's compare-branches mode.
     */
    public List<CommitFileChange> rangeFiles(Path workingDir, String base, String head)
            throws IOException, InterruptedException
    {
        requireNonNull(base, "base is null");
        requireNonNull(head, "head is null");
        GitResult result = run(
                List.of("git", "diff", "--name-status", "-z", base + ".." + head),
                workingDir,
                30);
        result.requireSuccess();
        return parseNameStatusZ(result.stdout());
    }

    /**
     * Files changed between {@code base} and the current <em>working tree</em>
     * — committed <strong>and</strong> uncommitted, including new untracked
     * files. Unlike {@link #rangeFiles} ({@code base..HEAD}, committed only),
     * this shows everything a task has changed even before it commits, so the
     * diff view isn't blank when the agent edited but never committed.
     */
    public List<CommitFileChange> effectiveFiles(Path workingDir, String base)
            throws IOException, InterruptedException
    {
        requireNonNull(base, "base is null");
        // Tracked changes vs base: committed (base..HEAD) + working-tree edits.
        GitResult tracked = run(
                List.of("git", "diff", "--name-status", "-z", base), workingDir, 30);
        tracked.requireSuccess();
        ImmutableList.Builder<CommitFileChange> out = ImmutableList.builder();
        out.addAll(parseNameStatusZ(tracked.stdout()));
        // Untracked new files — git-diff ignores them; list them as adds. The
        // two sets are disjoint (tracked vs untracked), so no dedup is needed.
        GitResult untracked = run(
                List.of("git", "ls-files", "--others", "--exclude-standard", "-z"), workingDir, 30);
        untracked.requireSuccess();
        for (String path : untracked.stdout().split(NUL_SEP, -1)) {
            if (!path.isEmpty()) {
                out.add(new CommitFileChange(path, "A", 0, 0));
            }
        }
        return out.build();
    }

    /** Parse {@code git diff --name-status -z} output into file changes.
     *  {@code -z} splits records by NUL; renames/copies are
     *  {@code R<score><NUL>old<NUL>new} (three tokens). */
    private static List<CommitFileChange> parseNameStatusZ(String stdout)
    {
        if (stdout.isEmpty()) {
            return ImmutableList.of();
        }
        ImmutableList.Builder<CommitFileChange> out = ImmutableList.builder();
        String[] parts = stdout.split(NUL_SEP, -1);
        for (int i = 0; i < parts.length; i++) {
            String tok = parts[i];
            if (tok.isEmpty()) {
                continue;
            }
            char status = tok.charAt(0);
            String path;
            if ((status == 'R' || status == 'C') && i + 2 < parts.length) {
                i++;
                path = parts[i + 1];
                i++;
            }
            else {
                if (i + 1 >= parts.length) {
                    continue;
                }
                path = parts[i + 1];
                i++;
            }
            out.add(new CommitFileChange(path, String.valueOf(status), 0, 0));
        }
        return out.build();
    }

    public String rangeFileDiff(Path workingDir, String base, String head, String path, int maxBytes)
            throws IOException, InterruptedException
    {
        requireNonNull(base, "base is null");
        requireNonNull(head, "head is null");
        requireNonNull(path, "path is null");
        GitResult result = run(
                List.of("git", "diff", base + ".." + head, "--", path),
                workingDir,
                60);
        result.requireSuccess();
        String stdout = result.stdout();
        if (maxBytes > 0 && stdout.length() > maxBytes) {
            return stdout.substring(0, maxBytes)
                    + "\n\n... (diff truncated at " + maxBytes + " bytes; "
                    + (stdout.length() - maxBytes) + " more bytes omitted)\n";
        }
        return stdout;
    }

    /**
     * Unified diff for one file between {@code base} and the current working
     * tree (committed + uncommitted). For an untracked new file — which
     * {@code git diff base} ignores — falls back to a {@code --no-index} diff
     * against {@code /dev/null} so its full content shows as an add. Pairs
     * with {@link #effectiveFiles}.
     */
    public String effectiveFileDiff(Path workingDir, String base, String path, int maxBytes)
            throws IOException, InterruptedException
    {
        requireNonNull(base, "base is null");
        requireNonNull(path, "path is null");
        GitResult result = run(List.of("git", "diff", base, "--", path), workingDir, 60);
        String stdout = result.exitCode() == 0 ? result.stdout() : "";
        if (stdout.isBlank()) {
            GitResult untracked = run(
                    List.of("git", "diff", "--no-index", "--", "/dev/null", path), workingDir, 60);
            // --no-index exits 1 when the files differ — that's the diff we want.
            if (untracked.exitCode() == 0 || untracked.exitCode() == 1) {
                stdout = untracked.stdout();
            }
        }
        if (maxBytes > 0 && stdout.length() > maxBytes) {
            return stdout.substring(0, maxBytes)
                    + "\n\n... (diff truncated at " + maxBytes + " bytes; "
                    + (stdout.length() - maxBytes) + " more bytes omitted)\n";
        }
        return stdout;
    }

    /**
     * Walks {@code git reflog} and returns up to {@code limit}
     * recent entries. The reflog records every move HEAD makes —
     * commits, checkouts, merges, pulls, rebases — so it's the
     * right primitive for an "activity in this clone" feed.
     *
     * Output shape mirrors {@link #listCommits}: NUL between records,
     * {@link #US_SEP} between fields. {@code %gs} is the reflog
     * subject ("commit:", "checkout:", "merge:"). {@code %gd} is
     * requested with {@code --date=iso-strict} so it carries the
     * reflog event's own timestamp ({@code HEAD@{<iso date>}}) rather
     * than the pointed-to commit's author date — a reset/checkout to
     * an old commit must show "just now", not the old commit's date.
     * The numeric selector ({@code HEAD@{0}}) is reconstructed from
     * each record's position since {@code --date} repurposes {@code
     * %gd}'s own numeric form.
     */
    public List<ReflogEntry> listReflog(Path workingDir, int limit)
            throws IOException, InterruptedException
    {
        if (limit <= 0) {
            return List.of();
        }
        String fmt = "%H" + US_SEP + "%h" + US_SEP + "%gd" + US_SEP + "%gs";
        List<String> args = List.of(
                "git", "reflog",
                "--max-count=" + limit,
                "--date=iso-strict",
                "-z",
                "--pretty=format:" + fmt);
        GitResult result = run(args, workingDir);
        result.requireSuccess();
        String stdout = result.stdout();
        if (stdout.isEmpty()) {
            return List.of();
        }
        List<ReflogEntry> entries = new ArrayList<>();
        int index = 0;
        for (String record : stdout.split(NUL_SEP, -1)) {
            if (record.isEmpty()) {
                continue;
            }
            String[] parts = record.split(US_SEP, -1);
            if (parts.length < 4) {
                continue;
            }
            entries.add(new ReflogEntry(
                    parts[0], parts[1], "HEAD@{" + index + "}", parts[3], reflogDate(parts[2])));
            index++;
        }
        return List.copyOf(entries);
    }

    /**
     * Extracts the ISO timestamp {@code --date=iso-strict} embeds in
     * {@code %gd}'s {@code HEAD@{<date>}} form.
     */
    private static String reflogDate(String gd)
    {
        int open = gd.indexOf('{');
        int close = gd.lastIndexOf('}');
        return open >= 0 && close > open ? gd.substring(open + 1, close) : null;
    }

    /**
     * Local reflog entry.
     *
     * @param selector relative selector git uses to address this entry.
     * @param subject human-readable reflog description.
     * @param reflogAt timestamp the reflog event (HEAD move) itself happened.
     */
    public record ReflogEntry(
            String sha,
            String shortSha,
            String selector,
            String subject,
            String reflogAt) {}

    /**
     * Lists all configured remotes for the working tree at
     * {@code path} as (name, fetch-URL) pairs. Used by fork-aware
     * local repo flows where {@code origin} is the user's fork and
     * {@code upstream} is the watched repo.
     */
    public List<Remote> listRemotes(Path workingDir)
            throws IOException, InterruptedException
    {
        GitResult result = run(List.of("git", "remote", "-v"), workingDir, 5);
        if (result.exitCode() != 0) {
            return List.of();
        }
        List<Remote> remotes = new ArrayList<>();
        for (String line : (Iterable<String>) result.stdout().lines()::iterator) {
            // git remote -v emits two lines per remote — one (fetch),
            // one (push). Format: "<name>\t<url> (fetch|push)". We
            // only need the fetch URLs; push URLs differ rarely and
            // aren't relevant to the locate match.
            if (!line.endsWith("(fetch)")) {
                continue;
            }
            String[] tabParts = line.split("\t", 2);
            if (tabParts.length != 2) {
                continue;
            }
            String name = tabParts[0].trim();
            String url = tabParts[1].replace("(fetch)", "").trim();
            if (!name.isEmpty() && !url.isEmpty()) {
                remotes.add(new Remote(name, url));
            }
        }
        return List.copyOf(remotes);
    }

    public record Remote(String name, String url) {}

    /**
     * Adds a named remote to a freshly-managed clone. Used by fork-mode
     * clones where {@code origin} is the user's fork and {@code upstream}
     * points at the watched repo.
     */
    public void addRemote(Path workingDir, String name, String url)
            throws IOException, InterruptedException
    {
        requireNonNull(name, "name is null");
        requireNonNull(url, "url is null");
        run(List.of("git", "remote", "add", name, url), workingDir).requireSuccess();
    }

    /**
     * Best-effort setup of refs/remotes/{remote}/HEAD after adding a
     * non-origin remote. Without this, callers that ask for the remote's
     * default branch may fall back to origin's default.
     */
    public void setRemoteHead(Path workingDir, String remote)
            throws IOException, InterruptedException
    {
        requireNonNull(remote, "remote is null");
        run(List.of("git", "remote", "set-head", remote, "-a"), workingDir, 30).requireSuccess();
    }

    /**
     * Owner segment of a remote's URL — {@code "trinodb"} for
     * {@code git@github.com:trinodb/trino.git} or
     * {@code https://github.com/trinodb/trino}. Empty when the remote is
     * unknown or the URL isn't a recognised {@code owner/repo} form. Used
     * to form a cross-fork PR head ({@code <fork-owner>:<branch>}) from
     * the fork clone's {@code origin} owner.
     */
    public Optional<String> remoteOwner(Path workingDir, String remote)
            throws IOException, InterruptedException
    {
        requireNonNull(remote, "remote is null");
        GitResult result = run(List.of("git", "remote", "get-url", remote), workingDir, 5);
        if (result.exitCode() != 0) {
            return Optional.empty();
        }
        return parseRepoOwner(result.stdout().strip());
    }

    /** Parses the {@code owner} from a git remote URL across the common
     *  forms: {@code https://host/owner/repo(.git)},
     *  {@code ssh://git@host/owner/repo(.git)}, and the scp-like
     *  {@code git@host:owner/repo(.git)}. Package-private for unit tests. */
    static Optional<String> parseRepoOwner(String url)
    {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        String s = url.strip();
        if (s.endsWith(".git")) {
            s = s.substring(0, s.length() - 4);
        }
        String path;
        if (s.contains("://")) {
            String rest = s.substring(s.indexOf("://") + 3);
            int slash = rest.indexOf('/');
            path = slash < 0 ? "" : rest.substring(slash + 1);
        }
        else if (s.contains(":")) {
            // scp-like host:owner/repo — the path is after the colon.
            path = s.substring(s.indexOf(':') + 1);
        }
        else {
            path = s;
        }
        String[] parts = path.split("/");
        if (parts.length >= 2) {
            String owner = parts[parts.length - 2].trim();
            return owner.isEmpty() ? Optional.empty() : Optional.of(owner);
        }
        return Optional.empty();
    }

    /**
     * {@code owner/repo} slug of a remote's URL — the local-PR push needs the
     * full {@link RepoRef} (owner AND name) to open the PR, whereas
     * {@link #remoteOwner} yields only the owner for the cross-fork head.
     * Empty when the remote is unknown or the URL isn't a recognised form.
     */
    public Optional<RepoRef> remoteSlug(Path workingDir, String remote)
            throws IOException, InterruptedException
    {
        requireNonNull(remote, "remote is null");
        GitResult result = run(List.of("git", "remote", "get-url", remote), workingDir, 5);
        if (result.exitCode() != 0) {
            return Optional.empty();
        }
        return parseRepoSlug(result.stdout().strip());
    }

    /** Parses {@code owner/repo} from a git remote URL across the same forms
     *  {@link #parseRepoOwner} handles. Package-private for unit tests. */
    static Optional<RepoRef> parseRepoSlug(String url)
    {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        String s = url.strip();
        if (s.endsWith(".git")) {
            s = s.substring(0, s.length() - 4);
        }
        String path;
        if (s.contains("://")) {
            String rest = s.substring(s.indexOf("://") + 3);
            int slash = rest.indexOf('/');
            path = slash < 0 ? "" : rest.substring(slash + 1);
        }
        else if (s.contains(":")) {
            path = s.substring(s.indexOf(':') + 1);
        }
        else {
            path = s;
        }
        String[] parts = path.split("/");
        if (parts.length >= 2) {
            String owner = parts[parts.length - 2].trim();
            String repo = parts[parts.length - 1].trim();
            if (!owner.isEmpty() && !repo.isEmpty()) {
                return Optional.of(new RepoRef(owner, repo));
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the URL of {@code remote.origin.url}, or null if no
     * origin is configured. Kept for callers that explicitly need
     * the origin (vs. any remote); locate uses {@link #listRemotes}
     * instead so it tolerates fork-based clones.
     */
    public String originUrl(Path workingDir)
            throws IOException, InterruptedException
    {
        GitResult result = run(List.of("git", "config", "--get", "remote.origin.url"), workingDir, 5);
        if (result.exitCode() != 0) {
            return null;
        }
        String url = result.stdout().strip();
        return url.isEmpty() ? null : url;
    }

    /**
     * Runs {@code git clone} and waits for completion. Big repos
     * (trino, kubernetes, …) routinely take several minutes; the
     * timeout is bumped accordingly. Real progress streaming via
     * git's --progress and a long-running IPC channel is a follow-up
     * — for now the caller blocks on this, and the UI shows a
     * "Cloning…" state.
     */
    public void clone(String url, Path destination)
            throws IOException, InterruptedException
    {
        requireNonNull(url, "url is null");
        requireNonNull(destination, "destination is null");
        Files.createDirectories(destination.getParent());
        // 30-minute cap. A clone that doesn't finish in 30 minutes is
        // almost certainly stuck on auth or DNS — better to fail and
        // surface the stderr than to hang the IPC indefinitely.
        GitResult result = run(
                List.of("git", "clone", url, destination.toString()),
                null,
                1800);
        result.requireSuccess();
    }

    /**
     * True iff {@code path} is the root of a git working tree (or
     * inside one). Used to validate the user-picked folder in the
     * "Locate existing" flow before we record it on the watched repo.
     */
    public boolean isGitWorkingTree(Path path)
    {
        if (path == null || !Files.isDirectory(path)) {
            return false;
        }
        try {
            GitResult result = run(List.of("git", "rev-parse", "--is-inside-work-tree"), path, 5);
            return result.exitCode() == 0 && "true".equals(result.stdout().strip());
        }
        catch (IOException e) {
            return false;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private GitResult run(List<String> args, Path workingDir)
            throws IOException, InterruptedException
    {
        return run(args, workingDir, DEFAULT_TIMEOUT_SECONDS);
    }

    private GitResult run(List<String> args, Path workingDir, long timeoutSeconds)
            throws IOException, InterruptedException
    {
        return run(args, workingDir, timeoutSeconds, null);
    }

    private GitResult run(
            List<String> args,
            Path workingDir,
            long timeoutSeconds,
            Path standardInput)
            throws IOException, InterruptedException
    {
        requireNonNull(args, "args is null");
        ProcessBuilder pb = new ProcessBuilder(args);
        if (workingDir != null) {
            pb.directory(workingDir.toFile());
        }
        if (standardInput != null) {
            pb.redirectInput(standardInput.toFile());
        }
        // Force English output regardless of the user's LANG so we can
        // pattern-match error strings reliably.
        pb.environment().put("LC_ALL", "C");
        // Refuse interactive credential prompts — if `git push` over
        // HTTPS would block on a username/password tty, we want it to
        // fail fast instead of hanging the request thread.
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process process = pb.start();
        // Drain stdout/stderr on background threads while git runs.
        // Without this, a command that writes more than the pipe
        // buffer (~64KB on macOS) before reading anything will block
        // git on its next write — the parent isn't reading yet —
        // and waitFor times out even though the work would have
        // finished in milliseconds. `git diff` on a large branch
        // hits this routinely. Virtual threads keep the cost
        // negligible for the common small-output case.
        Thread stdoutDrain = Thread.ofVirtual().start(
                () -> drainSilently(process.getInputStream()));
        Thread stderrDrain = Thread.ofVirtual().start(
                () -> drainSilently(process.getErrorStream()));
        try {
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                stopProcessTree(process, stdoutDrain, stderrDrain);
                throw new IOException(
                        "git " + args + " timed out after " + timeoutSeconds + "s");
            }
            // join() lets the drainers finish copying anything still in
            // flight after git exited; bounded by 5s in case a drainer
            // somehow gets stuck (shouldn't, but cheap insurance).
            stdoutDrain.join(5_000);
            stderrDrain.join(5_000);
        }
        catch (InterruptedException interrupted) {
            stopProcessTree(process, stdoutDrain, stderrDrain);
            throw interrupted;
        }
        String stdout = bufferedOutput.remove(stdoutDrain);
        String stderr = bufferedOutput.remove(stderrDrain);
        return new GitResult(
                process.exitValue(),
                stdout == null ? "" : stdout,
                stderr == null ? "" : stderr,
                ImmutableList.copyOf(args));
    }

    private void stopProcessTree(
            Process process,
            Thread stdoutDrain,
            Thread stderrDrain)
    {
        List<ProcessHandle> descendants;
        try {
            descendants = process.descendants().toList();
        }
        catch (RuntimeException unavailable) {
            // Some macOS sandboxes deny the sysctl used by descendants().
            // The exact process must still be stopped on cancellation.
            descendants = List.of();
        }
        process.destroy();
        descendants.reversed().forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) {
            process.destroyForcibly();
        }
        stdoutDrain.interrupt();
        stderrDrain.interrupt();
        bufferedOutput.remove(stdoutDrain);
        bufferedOutput.remove(stderrDrain);
    }

    /** Per-thread capture of what a drainer read. ConcurrentHashMap
     *  because the drainer thread writes the result and the caller
     *  thread reads it after join — no shared mutability across
     *  invocations since drainers are one-shot. */
    private final ConcurrentHashMap<Thread, String> bufferedOutput = new ConcurrentHashMap<>();

    private void drainSilently(InputStream in)
    {
        try (in) {
            byte[] bytes = in.readAllBytes();
            if (!Thread.currentThread().isInterrupted()) {
                bufferedOutput.put(
                        Thread.currentThread(),
                        new String(bytes, StandardCharsets.UTF_8));
            }
        }
        catch (IOException e) {
            // Process killed or stream closed — leave the entry
            // unset; run() treats absent as empty.
        }
    }

    public record GitResult(int exitCode, String stdout, String stderr, List<String> args)
    {
        public void requireSuccess()
        {
            if (exitCode != 0) {
                throw new GitCommandException(args, exitCode, stderr);
            }
        }
    }

    public static class GitCommandException
            extends RuntimeException
    {
        private final int exitCode;
        private final String stderr;

        GitCommandException(List<String> args, int exitCode, String stderr)
        {
            super("git " + args + " exited " + exitCode + ": " + stderr.strip());
            this.exitCode = exitCode;
            this.stderr = stderr;
        }

        public int exitCode() { return exitCode; }
        public String stderr() { return stderr; }
    }
}

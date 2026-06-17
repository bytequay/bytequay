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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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
        List<String> args = forceWithLease
                ? List.of("git", "push", "--force-with-lease")
                : List.of("git", "push");
        GitResult result = run(args, workingDir, 300);
        // Exit code 128 + "fatal: The current branch ... has no
        // upstream branch" is the most common first-push case —
        // fall back to `git push -u origin HEAD` so it works
        // without forcing the user to set tracking up by hand.
        // --force-with-lease has nothing to compare against on a
        // first push, so plain `-u origin HEAD` is the right fallback
        // for both modes.
        if (result.exitCode() != 0
                && result.stderr().contains("has no upstream branch")) {
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
     * Returns the local clone's idea of the upstream's default branch,
     * read from {@code refs/remotes/origin/HEAD}. Returns
     * {@link Optional#empty()} when origin/HEAD isn't set — happens
     * after a shallow clone or in repos created locally without an
     * origin push.
     */
    public Optional<String> defaultBranch(Path workingDir)
            throws IOException, InterruptedException
    {
        GitResult result = run(
                List.of("git", "symbolic-ref", "--short", "refs/remotes/origin/HEAD"),
                workingDir,
                5);
        if (result.exitCode() != 0) {
            return Optional.empty();
        }
        // symbolic-ref --short returns "origin/main"; strip the remote
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
        requireNonNull(baseRef, "baseRef is null");
        if (prNumber <= 0) {
            throw new IllegalArgumentException("prNumber must be positive, got " + prNumber);
        }
        String headRefspec = "pull/" + prNumber + "/head:refs/bytequay/pr/" + prNumber + "/head";
        String baseRefspec = baseRef + ":refs/bytequay/pr/" + prNumber + "/base";
        run(
                List.of("git", "fetch", "--no-tags", "--quiet", "origin", headRefspec, baseRefspec),
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
        String stdout = result.stdout();
        if (stdout.isEmpty()) {
            return ImmutableList.of();
        }
        ImmutableList.Builder<CommitFileChange> out = ImmutableList.builder();
        // -z splits records by NUL. For renames/copies the format is
        // R<score><NUL>old<NUL>new — three NUL-separated tokens.
        String[] parts = stdout.split(NUL_SEP, -1);
        for (int i = 0; i < parts.length; i++) {
            String tok = parts[i];
            if (tok.isEmpty()) {
                continue;
            }
            char status = tok.charAt(0);
            String path;
            if ((status == 'R' || status == 'C') && i + 2 < parts.length) {
                // R/C entries take both an old path (skipped) and a new path.
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
     * Walks {@code git reflog} and returns up to {@code limit}
     * recent entries. The reflog records every move HEAD makes —
     * commits, checkouts, merges, pulls, rebases — so it's the
     * right primitive for an "activity in this clone" feed.
     *
     * Output shape mirrors {@link #listCommits}: NUL between records,
     * {@link #US_SEP} between fields. {@code %gs} is the reflog
     * subject ("commit:", "checkout:", "merge:") and {@code %gd} is
     * the reflog selector ({@code HEAD@{0}}).
     */
    public List<ReflogEntry> listReflog(Path workingDir, int limit)
            throws IOException, InterruptedException
    {
        if (limit <= 0) {
            return List.of();
        }
        String fmt = "%H" + US_SEP + "%h" + US_SEP + "%gd" + US_SEP
                + "%gs" + US_SEP + "%aI";
        List<String> args = List.of(
                "git", "reflog",
                "--max-count=" + limit,
                "-z",
                "--pretty=format:" + fmt);
        GitResult result = run(args, workingDir);
        result.requireSuccess();
        String stdout = result.stdout();
        if (stdout.isEmpty()) {
            return List.of();
        }
        List<ReflogEntry> entries = new ArrayList<>();
        for (String record : stdout.split(NUL_SEP, -1)) {
            if (record.isEmpty()) {
                continue;
            }
            String[] parts = record.split(US_SEP, -1);
            if (parts.length < 5) {
                continue;
            }
            entries.add(new ReflogEntry(
                    parts[0], parts[1], parts[2], parts[3], parts[4]));
        }
        return List.copyOf(entries);
    }

    /**
     * Local reflog entry.
     *
     * @param selector relative selector git uses to address this entry.
     * @param subject human-readable reflog description.
     * @param authoredAt author timestamp of the commit the entry points at.
     */
    public record ReflogEntry(
            String sha,
            String shortSha,
            String selector,
            String subject,
            String authoredAt) {}

    /**
     * Lists all configured remotes for the working tree at
     * {@code path} as (name, fetch-URL) pairs. Powers the
     * Locate-existing flow: a fork-based clone has both
     * {@code origin} (the user's fork) and {@code upstream} (the
     * watched repo), and we need to accept the clone if either
     * matches — checking only origin would refuse the standard OSS
     * contribution layout.
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
        requireNonNull(args, "args is null");
        ProcessBuilder pb = new ProcessBuilder(args);
        if (workingDir != null) {
            pb.directory(workingDir.toFile());
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
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            stdoutDrain.interrupt();
            stderrDrain.interrupt();
            throw new IOException("git " + args + " timed out after " + timeoutSeconds + "s");
        }
        // join() lets the drainers finish copying anything still in
        // flight after git exited; bounded by 5s in case a drainer
        // somehow gets stuck (shouldn't, but cheap insurance).
        stdoutDrain.join(5_000);
        stderrDrain.join(5_000);
        String stdout = bufferedOutput.remove(stdoutDrain);
        String stderr = bufferedOutput.remove(stderrDrain);
        return new GitResult(
                process.exitValue(),
                stdout == null ? "" : stdout,
                stderr == null ? "" : stderr,
                ImmutableList.copyOf(args));
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
            bufferedOutput.put(Thread.currentThread(), new String(bytes, StandardCharsets.UTF_8));
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

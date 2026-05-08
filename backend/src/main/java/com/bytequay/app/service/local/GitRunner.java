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

import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
        catch (IOException | InterruptedException e) {
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
     * Returns the unified diff between {@code baseRef} and {@code headRef}
     * ({@code git diff base..head}), truncated to {@code maxBytes} so a
     * giant PR doesn't blow up the AI prompt budget. Truncation is
     * indicated by an inline marker so the caller (and the model) knows
     * the data is incomplete.
     */
    public String diff(Path workingDir, String baseRef, String headRef, int maxBytes)
            throws IOException, InterruptedException
    {
        requireNonNull(baseRef, "baseRef is null");
        requireNonNull(headRef, "headRef is null");
        GitResult result = run(
                List.of("git", "diff", baseRef + ".." + headRef),
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

    public record ReflogEntry(
            String sha,
            String shortSha,
            /** {@code HEAD@{0}}, {@code HEAD@{1}}, … — the relative
             *  selector git uses to address this entry. */
            String selector,
            /** Human-readable description: "commit: …", "checkout: from
             *  X to Y", "merge: …", "pull: Fast-forward", etc. */
            String subject,
            /** Author timestamp of the commit the entry points at. */
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
        catch (IOException | InterruptedException e) {
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
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("git " + args + " timed out after " + timeoutSeconds + "s");
        }
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new GitResult(process.exitValue(), stdout, stderr, ImmutableList.copyOf(args));
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

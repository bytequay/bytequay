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
package com.bytequay.app.ci;

import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.git.GitCommandRunner.Result;
import com.bytequay.core.ci.CiAutofixRun.Authorization;
import com.bytequay.core.ci.CiAutofixRun.PushJournal;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static com.bytequay.app.ci.CiPushResult.Outcome.REJECTED;
import static com.bytequay.app.ci.CiPushResult.Outcome.RETRYABLE;
import static com.bytequay.app.ci.CiPushResult.Outcome.STALE;
import static com.bytequay.app.ci.CiPushResult.Outcome.UNCERTAIN;
import static java.util.Objects.requireNonNull;

@Service
public final class CiGitOperations
        implements CiGitService
{
    private static final String FIXUP_PREFIX = "fixup! ";
    private static final String REMOTE = "origin";
    private static final int MAXIMUM_DIAGNOSTIC_CHARACTERS = 8 * 1024;
    private static final int MAXIMUM_CHANGED_PATHS = 1000;

    private final GitCommandRunner runner;
    private final Clock clock;

    CiGitOperations(GitCommandRunner runner, Clock clock)
    {
        this.runner = requireNonNull(runner, "runner is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public CiBaseSynchronization synchronizeBase(SynchronizeCiBaseCommand command)
    {
        requireNonNull(command, "command is null");
        try {
            Path worktree = validateWorktree(command.worktree(), command.repository(), command.headRef());
            requireCleanAndResolved(worktree);
            requireValidBranch(worktree, command.baseRef());
            requireValidBranch(worktree, command.localBaseBranch());
            if (!hasCommit(worktree, command.expectedBaseSha())) {
                Result fetched = runner.run(worktree, ImmutableList.of(
                        "fetch",
                        "--no-tags",
                        "--no-recurse-submodules",
                        "--no-write-fetch-head",
                        REMOTE,
                        "refs/heads/" + command.baseRef()));
                if (!fetched.successful() || !hasCommit(worktree, command.expectedBaseSha())) {
                    throw problem("The exact pull request base SHA is unavailable after fetch");
                }
            }
            Result updated = runner.run(
                    worktree,
                    "update-ref",
                    "refs/heads/" + command.localBaseBranch(),
                    command.expectedBaseSha());
            if (!updated.successful()) {
                throw problem("The private local base branch could not be updated");
            }
            return CiBaseSynchronization.ready(command.localBaseBranch());
        }
        catch (GitProblem problem) {
            return CiBaseSynchronization.invalid(problem.getMessage());
        }
    }

    @Override
    public boolean removeSynchronizedBase(SynchronizeCiBaseCommand command)
    {
        requireNonNull(command, "command is null");
        try {
            Path worktree = validateWorktree(command.worktree(), command.repository(), command.headRef());
            requireValidBranch(worktree, command.localBaseBranch());
            Result current = runner.run(
                    worktree,
                    "rev-parse",
                    "--verify",
                    "--quiet",
                    "refs/heads/" + command.localBaseBranch());
            if (!current.successful()) {
                return true;
            }
            return runner.run(
                    worktree,
                    "update-ref",
                    "-d",
                    "refs/heads/" + command.localBaseBranch()).successful();
        }
        catch (GitProblem problem) {
            return false;
        }
    }

    @Override
    public CiGitInspection inspectAgentResult(InspectAgentResultCommand command)
    {
        requireNonNull(command, "command is null");
        try {
            Path worktree = validateWorktree(command.worktree(), command.repository(), command.headRef());
            String head = requiredOutput(worktree, "rev-parse", "HEAD");
            requireCommit(worktree, command.inputSha(), "attempt input");
            requireCommit(worktree, command.baseSha(), "pull request base");
            requireCleanAndResolved(worktree);
            requireLinearRange(worktree, command.baseSha(), head);
            requireDiffCheck(worktree, command.baseSha(), head);
            if (head.equals(command.inputSha())) {
                return CiGitInspection.noChange();
            }
            List<String> changedPaths = changedPaths(worktree, command.baseSha(), head);
            List<String> commitSubjects = commits(worktree, command.baseSha(), head).stream()
                    .map(Commit::subject)
                    .toList();
            return CiGitInspection.ready(
                    head,
                    fingerprint(worktree, command.headRef(), head),
                    changedPaths,
                    commitSubjects);
        }
        catch (GitProblem problem) {
            return CiGitInspection.invalid(problem.getMessage());
        }
    }

    @Override
    public CiPushResult push(PushCiHeadCommand command)
    {
        requireNonNull(command, "command is null");
        Authorization authorization = command.authorization();
        PushJournal journal = command.journal();
        if (!authorization.allowExactLeasePush()) {
            return CiPushResult.failed(REJECTED, "Exact-lease push is not authorized");
        }

        try {
            Path worktree = validateWorktree(command.worktree(), command.repository(), command.headRef());
            Optional<String> remote = remoteHead(worktree, command.headRef());
            if (remote.isEmpty()) {
                return CiPushResult.failed(UNCERTAIN, "Remote branch head could not be read");
            }
            if (remote.orElseThrow().equals(journal.localHeadSha())) {
                return CiPushResult.pushed(journal.localHeadSha());
            }
            if (!remote.orElseThrow().equals(journal.expectedRemoteSha())) {
                return CiPushResult.failed(STALE, "Remote branch changed outside this CI Autofix run");
            }
            if (!Instant.now(clock).isBefore(authorization.expiresAt())) {
                return CiPushResult.failed(REJECTED, "CI Autofix authorization expired");
            }

            requireCleanAndResolved(worktree);
            String head = requiredOutput(worktree, "rev-parse", "HEAD");
            if (!head.equals(journal.localHeadSha())) {
                return CiPushResult.failed(REJECTED, "Local HEAD changed after Git inspection");
            }
            String fingerprint = fingerprint(worktree, command.headRef(), head);
            if (!fingerprint.equals(journal.localFingerprint())) {
                return CiPushResult.failed(REJECTED, "Local Git fingerprint changed after inspection");
            }

            boolean descendant = isAncestor(worktree, journal.expectedRemoteSha(), head);
            if (!descendant && !authorization.allowHistoryRewrites()) {
                return CiPushResult.failed(REJECTED, "Local history rewrite is not authorized");
            }
            if (!authorization.allowExactLeasePush() || !Instant.now(clock).isBefore(authorization.expiresAt())) {
                return CiPushResult.failed(REJECTED, "CI Autofix push authorization is no longer valid");
            }
            worktree = validateWorktree(command.worktree(), command.repository(), command.headRef());
            requireCleanAndResolved(worktree);
            head = requiredOutput(worktree, "rev-parse", "HEAD");
            if (!head.equals(journal.localHeadSha())
                    || !fingerprint(worktree, command.headRef(), head).equals(journal.localFingerprint())) {
                return CiPushResult.failed(REJECTED, "Local Git history changed immediately before push");
            }
            Result push = runner.run(worktree, pushArguments(
                    command.headRef(),
                    journal.expectedRemoteSha(),
                    descendant));
            return reconcilePush(worktree, command.headRef(), journal, push);
        }
        catch (GitProblem problem) {
            return CiPushResult.failed(REJECTED, problem.getMessage());
        }
    }

    @Override
    public FixupPositionCheck checkFixupPositions(CheckFixupPositionsCommand command)
    {
        requireNonNull(command, "command is null");
        try {
            Path worktree = validateWorktree(command.worktree(), command.repository(), command.headRef());
            requireCleanAndResolved(worktree);
            String head = requiredOutput(worktree, "rev-parse", "HEAD");
            if (!head.equals(command.headSha())) {
                throw problem("Local HEAD differs from the head selected for the final check");
            }
            requireCommit(worktree, command.baseSha(), "pull request base");
            requireLinearRange(worktree, command.baseSha(), head);
            List<Commit> commits = commits(worktree, command.baseSha(), head);
            Set<String> fixedTargets = new HashSet<>();
            for (int index = 0; index < commits.size(); index++) {
                Commit commit = commits.get(index);
                if (!commit.subject().startsWith(FIXUP_PREFIX)) {
                    continue;
                }
                String targetSubject = commit.subject().substring(FIXUP_PREFIX.length());
                if (targetSubject.isBlank() || index == 0) {
                    throw problem("Fixup commit " + commit.sha() + " has no preceding target commit");
                }
                Commit target = commits.get(index - 1);
                if (target.subject().startsWith(FIXUP_PREFIX) || !target.subject().equals(targetSubject)) {
                    throw problem("Fixup commit " + commit.sha() + " is not immediately after its exact target");
                }
                if (!fixedTargets.add(target.sha())) {
                    throw problem("Commit " + target.sha() + " has more than one fixup");
                }
            }
            return FixupPositionCheck.valid(head);
        }
        catch (GitProblem problem) {
            return FixupPositionCheck.invalid(command.headSha(), problem.getMessage());
        }
    }

    private CiPushResult reconcilePush(Path worktree, String headRef, PushJournal journal, Result push)
    {
        Optional<String> remote = remoteHead(worktree, headRef);
        if (remote.isEmpty()) {
            return CiPushResult.failed(UNCERTAIN, "Push outcome could not be reconciled");
        }
        if (remote.orElseThrow().equals(journal.localHeadSha())) {
            return CiPushResult.pushed(journal.localHeadSha());
        }
        if (!remote.orElseThrow().equals(journal.expectedRemoteSha())) {
            return CiPushResult.failed(STALE, "Remote branch changed while CI Autofix was pushing");
        }
        if (push.successful()) {
            return CiPushResult.failed(UNCERTAIN, "Git reported success but the remote head is unchanged");
        }
        if (journal.retries() == 0) {
            return CiPushResult.failed(RETRYABLE, "Push failed before changing the remote branch");
        }
        return CiPushResult.failed(REJECTED, "Push retry failed before changing the remote branch");
    }

    private Path validateWorktree(Path requested, RepositoryIdentity repository, String headRef)
    {
        Path worktree;
        try {
            worktree = requested.toRealPath();
        }
        catch (IOException e) {
            throw problem("Cannot resolve CI Autofix worktree");
        }
        if (!Files.isDirectory(worktree)) {
            throw problem("CI Autofix worktree is not a directory");
        }
        Path topLevel = Path.of(requiredOutput(worktree, "rev-parse", "--show-toplevel"));
        try {
            if (!topLevel.toRealPath().equals(worktree)) {
                throw problem("CI Autofix worktree is not the repository root");
            }
        }
        catch (IOException e) {
            throw problem("Cannot resolve Git repository root");
        }
        requireValidBranch(worktree, headRef);
        Result branch = runner.run(worktree, "symbolic-ref", "--quiet", "--short", "HEAD");
        if (!branch.successful() || !branch.output().strip().equals(headRef)) {
            throw problem("Worktree is detached or on another branch");
        }
        RepositoryIdentity actual = parseRepository(requiredOutput(worktree, "config", "--get", "remote.origin.url"));
        if (!actual.toString().equalsIgnoreCase(repository.toString())) {
            throw problem("Worktree repository does not match the authorized pull request");
        }
        return worktree;
    }

    private void requireCleanAndResolved(Path worktree)
    {
        String unmerged = requiredOutput(worktree, "ls-files", "-u");
        if (!unmerged.isEmpty()) {
            throw problem("Git has unresolved index entries:\n" + unmerged);
        }
        for (String state : ImmutableList.of(
                "MERGE_HEAD",
                "CHERRY_PICK_HEAD",
                "rebase-merge",
                "rebase-apply",
                "sequencer")) {
            Path path = Path.of(requiredOutput(worktree, "rev-parse", "--git-path", state));
            if (!path.isAbsolute()) {
                path = worktree.resolve(path).normalize();
            }
            if (Files.exists(path)) {
                throw problem("Git operation is still open: " + state);
            }
        }
        String status = requiredOutput(worktree, "status", "--porcelain=v1", "--untracked-files=all");
        if (!status.isEmpty()) {
            throw problem("Worktree or index is not clean:\n" + status);
        }
    }

    private void requireLinearRange(Path worktree, String baseSha, String headSha)
    {
        Result ancestor = runner.run(worktree, "merge-base", "--is-ancestor", baseSha, headSha);
        if (ancestor.exitCode() == 1) {
            throw problem("Local HEAD contains commits outside the authorized PR range");
        }
        if (!ancestor.successful()) {
            throw problem("Git could not validate the pull request range: " + ancestor.output());
        }
        String merges = requiredOutput(worktree, "rev-list", "--min-parents=2", baseSha + ".." + headSha);
        if (!merges.isEmpty()) {
            throw problem("Pull request history is not linear; merge commit: " + merges.lines().findFirst().orElseThrow());
        }
    }

    private void requireDiffCheck(Path worktree, String baseSha, String headSha)
    {
        Result result = runner.run(worktree, "diff", "--check", baseSha + ".." + headSha);
        if (!result.successful()) {
            throw problem("Git rejected the committed diff:\n" + result.output());
        }
    }

    private void requireCommit(Path worktree, String sha, String name)
    {
        Result result = runner.run(worktree, "cat-file", "-e", sha + "^{commit}");
        if (!result.successful()) {
            throw problem("Git cannot resolve the " + name + " commit");
        }
    }

    private boolean hasCommit(Path worktree, String sha)
    {
        return runner.run(worktree, "cat-file", "-e", sha + "^{commit}").successful();
    }

    private boolean isAncestor(Path worktree, String ancestor, String descendant)
    {
        Result result = runner.run(worktree, "merge-base", "--is-ancestor", ancestor, descendant);
        if (result.exitCode() == 1) {
            return false;
        }
        if (!result.successful()) {
            throw problem("Git could not validate the publish relationship");
        }
        return true;
    }

    private List<String> changedPaths(Path worktree, String baseSha, String headSha)
    {
        String output = requiredRawOutput(
                worktree,
                "diff",
                "--name-only",
                "-z",
                baseSha + ".." + headSha);
        if (output.isEmpty()) {
            return ImmutableList.of();
        }
        ImmutableList.Builder<String> paths = ImmutableList.builder();
        int count = 0;
        for (String path : output.split("\\u0000", -1)) {
            if (path.isEmpty()) {
                continue;
            }
            if (count == MAXIMUM_CHANGED_PATHS) {
                throw problem("Changed paths exceed " + MAXIMUM_CHANGED_PATHS + " values");
            }
            paths.add(path);
            count++;
        }
        return paths.build();
    }

    private String fingerprint(Path worktree, String headRef, String head)
    {
        String tree = requiredOutput(worktree, "rev-parse", "HEAD^{tree}");
        String status = requiredOutput(worktree, "status", "--porcelain=v1", "--untracked-files=all");
        return sha256(headRef + "\u0000" + head + "\u0000" + tree + "\u0000" + status);
    }

    private Optional<String> remoteHead(Path worktree, String headRef)
    {
        Result result = runner.run(worktree, "ls-remote", "--exit-code", REMOTE, "refs/heads/" + headRef);
        if (!result.successful()) {
            return Optional.empty();
        }
        String[] fields = result.output().strip().split("\\s+", 2);
        if (fields.length != 2 || !fields[1].equals("refs/heads/" + headRef)
                || !fields[0].matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")) {
            return Optional.empty();
        }
        return Optional.of(fields[0]);
    }

    private List<Commit> commits(Path worktree, String baseSha, String headSha)
    {
        String output = requiredOutput(
                worktree,
                "log",
                "--reverse",
                "--format=%H%x00%s%x00",
                baseSha + ".." + headSha);
        if (output.isEmpty()) {
            return ImmutableList.of();
        }
        String[] fields = output.split("\\u0000", -1);
        List<Commit> commits = new ArrayList<>();
        for (int index = 0; index + 1 < fields.length; index += 2) {
            String sha = fields[index].strip();
            String subject = fields[index + 1];
            if (!sha.isEmpty()) {
                commits.add(new Commit(sha, subject));
            }
        }
        return ImmutableList.copyOf(commits);
    }

    private String requiredOutput(Path worktree, String... arguments)
    {
        return requiredRawOutput(worktree, arguments).strip();
    }

    private String requiredRawOutput(Path worktree, String... arguments)
    {
        Result result = runner.run(worktree, arguments);
        if (!result.successful()) {
            throw problem("Git command failed: " + result.output());
        }
        if (result.truncated()) {
            throw problem("Git output exceeded " + GitCommandRunner.MAXIMUM_OUTPUT_BYTES + " bytes");
        }
        return result.output();
    }

    static List<String> pushArguments(String headRef, String expectedRemoteSha, boolean descendant)
    {
        ImmutableList.Builder<String> arguments = ImmutableList.builder();
        arguments.add("push", "--porcelain");
        if (!descendant) {
            arguments.add("--force-with-lease=refs/heads/" + headRef + ":" + expectedRemoteSha);
        }
        return arguments
                .add(REMOTE, "HEAD:refs/heads/" + headRef)
                .build();
    }

    private void requireValidBranch(Path worktree, String headRef)
    {
        if (headRef.startsWith("-") || headRef.contains("@{")) {
            throw problem("Authorized pull request branch is invalid");
        }
        Result result = runner.run(worktree, "check-ref-format", "refs/heads/" + headRef);
        if (!result.successful()) {
            throw problem("Authorized pull request branch is invalid");
        }
    }

    private static RepositoryIdentity parseRepository(String remote)
    {
        String path;
        if (remote.startsWith("git@github.com:")) {
            path = remote.substring("git@github.com:".length());
        }
        else {
            try {
                URI uri = new URI(remote);
                if (uri.getHost() == null || !uri.getHost().equalsIgnoreCase("github.com")) {
                    throw problem("Origin is not a GitHub repository");
                }
                String scheme = Optional.ofNullable(uri.getScheme()).orElse("").toLowerCase(Locale.ROOT);
                String user = uri.getUserInfo();
                if (!(scheme.equals("https") && user == null)
                        && !(scheme.equals("ssh") && "git".equals(user))) {
                    throw problem("Origin URL contains unsupported credentials or transport");
                }
                path = uri.getPath();
            }
            catch (URISyntaxException e) {
                throw problem("Origin URL is invalid");
            }
        }
        path = path.replaceFirst("^/+", "").replaceFirst("\\.git$", "");
        try {
            return RepositoryIdentity.valueOf(path);
        }
        catch (IllegalArgumentException e) {
            throw problem("Origin URL does not identify one GitHub repository");
        }
    }

    private static String sha256(String value)
    {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        }
        catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 is unavailable", e);
        }
    }

    private static GitProblem problem(String message)
    {
        String bounded = message == null || message.isBlank() ? "Git validation failed" : message.strip();
        if (bounded.length() > MAXIMUM_DIAGNOSTIC_CHARACTERS) {
            bounded = bounded.substring(0, MAXIMUM_DIAGNOSTIC_CHARACTERS);
        }
        return new GitProblem(bounded);
    }

    private record Commit(String sha, String subject) {}

    private static final class GitProblem
            extends RuntimeException
    {
        private GitProblem(String message)
        {
            super(message);
        }
    }
}

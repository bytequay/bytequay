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
package com.bytequay.app.service.harness;

import com.bytequay.app.service.harness.HarnessModels.GitSafetyProof;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.local.ShellRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * One narrow local-history transaction: fetch/compare, backup, path-scoped
 * fixup, tight autosquash, tree/diff/range proof, restore on every failure.
 * There is deliberately no push method in this class.
 */
@Component
public class HarnessGitSafety
{
    private static final Map<String, String> GIT_ENV = Map.of(
            "GIT_TERMINAL_PROMPT", "0",
            "GIT_EDITOR", "true",
            "GIT_SEQUENCE_EDITOR", "true",
            "LC_ALL", "C");
    private static final int OUTPUT_CAP = 256 * 1024;
    private static final int MAX_FIX_PATHS = 500;
    private static final Pattern RANGE_DIFF_LINE = Pattern.compile(
            "^\\s*(?:\\d+|-):\\s+([0-9a-f]+|-+)\\s+([=!<>])\\s+"
                    + "(?:\\d+|-):\\s+([0-9a-f]+|-+)\\s+(.*)$");

    private final GitRunner git;
    private final ShellRunner commands;

    public HarnessGitSafety(GitRunner git, ShellRunner commands)
    {
        this.git = requireNonNull(git, "git is null");
        this.commands = requireNonNull(commands, "commands is null");
    }

    /** Explicit, reviewed squash of one fixup into the commit it repairs. */
    public SafetyResult commitFixupAndAutosquash(
            Path root,
            List<String> files,
            String exactTargetSubject,
            String baseSha,
            String remote,
            String branch,
            BooleanSupplier active,
            BackupRecorder backupRecorder)
    {
        FixupBatch batch = beginFixupBatch(
                root, baseSha, remote, branch, active, backupRecorder);
        batch.commitFixup(files, exactTargetSubject);
        return batch.finish();
    }

    public FixupBatch beginFixupBatch(
            Path root,
            String baseSha,
            String remote,
            String branch,
            BooleanSupplier active,
            BackupRecorder backupRecorder)
    {
        requireNonNull(active, "active is null");
        requireNonNull(backupRecorder, "backupRecorder is null");
        validateRef(remote, "remote");
        validateRef(branch, "branch");
        try {
            String initialHead = git.headSha(root);
            if (baseSha == null || baseSha.isBlank() || !git.refExists(root, baseSha)) {
                throw new SafetyException("PR base SHA is unavailable; refusing history rewrite");
            }
            run(root, List.of("git", "fetch", "--no-tags", remote, branch), 300);
            String remoteHead = run(root, List.of(
                    "git", "rev-parse", "--verify", "FETCH_HEAD"), 30).strip();
            if (!initialHead.equals(remoteHead)) {
                throw new SafetyException("remote branch diverged after fetch; refusing local history rewrite");
            }
            assertActive(active);

            String backupRef = "bytequay-backup/ci-harness/"
                    + Instant.now().toEpochMilli() + "-" + initialHead.substring(0, 8);
            run(root, List.of("git", "branch", "-f", backupRef, initialHead), 30);
            backupRecorder.record(backupRef, initialHead);
            assertActive(active);
            return new FixupBatch(
                    root, baseSha, remote, branch, active, backupRef, initialHead);
        }
        catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new SafetyException("git safety operation failed: " + e.getMessage(), e);
        }
    }

    /** One backed-up history transaction containing any number of independently
     * verified path-scoped fixups and exactly one final autosquash proof. */
    public final class FixupBatch
    {
        private final Path root;
        private final String baseSha;
        private final String remote;
        private final String branch;
        private final BooleanSupplier active;
        private final String backupRef;
        private final String originalHead;
        private final List<FixupTarget> fixups = new ArrayList<>();

        private boolean mutationStarted;
        private boolean restored;
        private boolean finished;

        private FixupBatch(
                Path root, String baseSha, String remote, String branch, BooleanSupplier active,
                String backupRef, String originalHead)
        {
            this.root = root;
            this.baseSha = baseSha;
            this.remote = remote;
            this.branch = branch;
            this.active = active;
            this.backupRef = backupRef;
            this.originalHead = originalHead;
        }

        public void commitFixup(List<String> files, String exactTargetSubject)
        {
            if (finished || restored) {
                throw new IllegalStateException("fixup batch is already closed");
            }
            if (files == null || files.isEmpty() || files.size() > MAX_FIX_PATHS) {
                throw new IllegalArgumentException(
                        "git safety requires 1-" + MAX_FIX_PATHS + " changed paths");
            }
            List<String> safeFiles = files.stream()
                    .map(HarnessGitSafety::validatePath)
                    .distinct()
                    .toList();
            try {
                assertOnlyIntendedPaths(root, safeFiles);
                String targetSha = exactTarget(root, baseSha, exactTargetSubject);
                assertActive(active);
                List<String> add = new ArrayList<>(List.of("git", "add", "--"));
                add.addAll(safeFiles);
                mutationStarted = true;
                run(root, add, 300);
                List<String> commit = new ArrayList<>(List.of(
                        "git", "commit", "--fixup=" + targetSha, "--"));
                commit.addAll(safeFiles);
                run(root, commit, 300);
                fixups.add(new FixupTarget(
                        targetSha, exactTargetSubject, git.headSha(root)));
                assertActive(active);
            }
            catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                SafetyException failure = new SafetyException(
                        "git fixup operation failed: " + e.getMessage(), e);
                rollback(failure);
                throw failure;
            }
            catch (RuntimeException failure) {
                rollback(failure);
                throw failure;
            }
        }

        /**
         * Closes the batch without touching history: the verified fixups stay
         * as their own commits, and the branch is exactly what the fixup
         * commits made it.
         *
         * <p>This is the unattended path. Folding a fixup into the commit it
         * repairs rewrites commits a human has not read yet, so that waits for
         * a review and an explicit ask — {@link #finish()} is what performs it
         * then, with the full net-neutral proof.
         */
        public SafetyResult finishWithoutSquash()
        {
            if (finished || restored) {
                throw new IllegalStateException("fixup batch is already closed");
            }
            if (fixups.isEmpty()) {
                throw new IllegalStateException("fixup batch has no verified fixes");
            }
            try {
                run(root, List.of("git", "fetch", "--no-tags", remote, branch), 300);
                String remoteHead = run(root, List.of(
                        "git", "rev-parse", "--verify", "FETCH_HEAD"), 30).strip();
                if (!originalHead.equals(remoteHead)) {
                    throw new SafetyException(
                            "remote branch changed during the fixup batch; local branch restored");
                }
                assertActive(active);
                String head = git.headSha(root);
                finished = true;
                // Nothing was rewritten, so the proof records the one fact that
                // matters: head moved forward by the fixups and by nothing else.
                GitSafetyProof proof = new GitSafetyProof(
                        head, head, tree(root, head), tree(root, head),
                        true, true, true, "not squashed; fixups kept for review");
                return new SafetyResult(backupRef, originalHead, proof);
            }
            catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                SafetyException failure = new SafetyException(
                        "closing the fixup batch failed: " + e.getMessage(), e);
                rollback(failure);
                throw failure;
            }
            catch (RuntimeException failure) {
                rollback(failure);
                throw failure;
            }
        }

        /**
         * Folds every verified fixup into the commit it repairs and proves the
         * rewrite changed nothing but attribution. Only ever on an explicit
         * human ask, after review.
         */
        public SafetyResult finish()
        {
            if (finished || restored) {
                throw new IllegalStateException("fixup batch is already closed");
            }
            if (fixups.isEmpty()) {
                throw new IllegalStateException("fixup batch has no verified fixes");
            }
            try {
                run(root, List.of("git", "fetch", "--no-tags", remote, branch), 300);
                String remoteHead = run(root, List.of(
                        "git", "rev-parse", "--verify", "FETCH_HEAD"), 30).strip();
                if (!originalHead.equals(remoteHead)) {
                    throw new SafetyException(
                            "remote branch changed during the fixup batch; local branch restored");
                }
                String earliestTarget = earliestTarget();
                String preRebaseHead = git.headSha(root);
                String preRebaseTree = tree(root, preRebaseHead);
                assertActive(active);
                run(root, List.of(
                        "git", "-c", "sequence.editor=true", "-c", "core.editor=true",
                        "rebase", "-i", "--autosquash", earliestTarget + "^"), 900);
                String afterHead = git.headSha(root);
                String afterTree = tree(root, afterHead);
                assertActive(active);
                ShellRunner.Result diff = execute(root,
                        List.of("git", "diff", "--exit-code", preRebaseHead, afterHead), 120);
                ShellRunner.Result range = execute(root,
                        List.of("git", "range-diff", "--no-color", "--no-patch", "--no-abbrev",
                                earliestTarget + "^.." + preRebaseHead,
                                earliestTarget + "^.." + afterHead), 120);
                boolean treeEqual = preRebaseTree.equals(afterTree);
                boolean diffEmpty = diff.ran() && diff.exitCode() == 0 && diff.output().isBlank();
                boolean rangeEquivalent = range.ran() && range.exitCode() == 0
                        && expectedRangeDiff(
                                range.output(),
                                fixups);
                if (!treeEqual || !diffEmpty || !rangeEquivalent) {
                    throw new SafetyException(
                            "net-neutral history proof failed; local branch restored"
                                    + " (treeEqual=" + treeEqual
                                    + ", diffEmpty=" + diffEmpty
                                    + ", expectedRange=" + rangeEquivalent + ")\n"
                                    + bound(range.output(), 12_000));
                }
                finished = true;
                GitSafetyProof proof = new GitSafetyProof(
                        preRebaseHead, afterHead, preRebaseTree, afterTree,
                        true, true, true, bound(range.output(), 12_000));
                return new SafetyResult(backupRef, originalHead, proof);
            }
            catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                SafetyException failure = new SafetyException(
                        "git normalization operation failed: " + e.getMessage(), e);
                rollback(failure);
                throw failure;
            }
            catch (RuntimeException failure) {
                rollback(failure);
                throw failure;
            }
        }

        public void abort()
        {
            SafetyException failure = new SafetyException("fixup batch aborted");
            rollback(failure);
            if (failure.getSuppressed().length > 0) {
                throw failure;
            }
        }

        private String earliestTarget()
        {
            String earliest = null;
            long longestRange = -1;
            for (String target : fixups.stream()
                    .map(FixupTarget::sha)
                    .distinct()
                    .toList()) {
                long range = Long.parseLong(run(root,
                        List.of("git", "rev-list", "--count", target + "..HEAD"), 30).strip());
                if (range > longestRange) {
                    earliest = target;
                    longestRange = range;
                }
            }
            return requireNonNull(earliest, "earliest target is null");
        }

        private void rollback(RuntimeException failure)
        {
            if (mutationStarted && !restored) {
                restore(root, originalHead, failure);
                restored = true;
            }
        }
    }

    /** Roll back a failed proposal in a worktree that the orchestrator proved
     * clean immediately before applying it. Only the captured proposal paths,
     * including newly generated paths, may be removed. */
    public void discardTrackedProposal(Path root, List<String> files)
    {
        if (files == null || files.isEmpty() || files.size() > MAX_FIX_PATHS) {
            throw new IllegalArgumentException(
                    "proposal cleanup requires 1-" + MAX_FIX_PATHS + " changed paths");
        }
        Set<String> intended = Set.copyOf(
                files.stream().map(HarnessGitSafety::validatePath).toList());
        try {
            List<GitRunner.WorkingTreeFile> changes = git.workingTreeFiles(root);
            Set<String> dirty = changes.stream()
                    .map(GitRunner.WorkingTreeFile::path)
                    .collect(Collectors.toSet());
            if (!intended.containsAll(dirty)) {
                throw new SafetyException(
                        "worktree contains changes outside the proposal; refusing cleanup: " + dirty);
            }
            List<String> untracked = changes.stream()
                    .filter(file -> "A".equals(file.status()))
                    .map(GitRunner.WorkingTreeFile::path)
                    .toList();
            if (!untracked.isEmpty()) {
                List<String> clean = new ArrayList<>(List.of("git", "clean", "-fd", "--"));
                clean.addAll(untracked);
                run(root, clean, 120);
            }
        }
        catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new SafetyException("unable to inspect proposal before cleanup", e);
        }
        run(root, List.of("git", "reset", "--hard", "HEAD"), 120);
    }

    public void restoreOriginalHead(Path root, String originalHead)
    {
        if (originalHead == null || !originalHead.matches("[0-9a-fA-F]{40,64}")) {
            throw new IllegalArgumentException("invalid original head");
        }
        run(root, List.of("git", "reset", "--hard", originalHead), 120);
    }

    /** Recover an interrupted local rewrite from the durable pre-mutation
     * checkpoint. The backup branch is intentionally retained for inspection. */
    public void recoverInterrupted(Path root, String backupRef, String originalHead)
    {
        if (backupRef == null
                || !backupRef.matches("bytequay-backup/ci-harness/[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("invalid harness backup ref");
        }
        if (originalHead == null || !originalHead.matches("[0-9a-fA-F]{40,64}")) {
            throw new IllegalArgumentException("invalid original head");
        }
        try {
            if (!git.refExists(root, backupRef)) {
                throw new SafetyException("durable harness backup ref is unavailable");
            }
        }
        catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new SafetyException("unable to inspect durable harness backup", e);
        }
        String backupHead = run(root,
                List.of("git", "rev-parse", "--verify", backupRef), 30).strip();
        if (!originalHead.equals(backupHead)) {
            throw new SafetyException("durable harness backup does not match original head");
        }
        execute(root, List.of("git", "rebase", "--abort"), 120);
        run(root, List.of("git", "reset", "--hard", backupRef), 120);
        try {
            if (!originalHead.equals(git.headSha(root)) || git.hasUncommittedChanges(root)) {
                throw new SafetyException("interrupted harness rewrite did not restore cleanly");
            }
        }
        catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new SafetyException("unable to prove interrupted rewrite recovery", e);
        }
    }

    private void assertOnlyIntendedPaths(Path root, List<String> files)
            throws IOException, InterruptedException
    {
        Set<String> intended = Set.copyOf(files);
        Set<String> dirty = new HashSet<>();
        for (GitRunner.WorkingTreeFile file : git.workingTreeFiles(root)) {
            dirty.add(file.path());
        }
        if (!dirty.equals(intended)) {
            throw new SafetyException(
                    "worktree changes do not exactly match the proposed fix; intended="
                            + intended + ", actual=" + dirty);
        }
    }

    private String exactTarget(Path root, String baseSha, String subject)
            throws IOException, InterruptedException
    {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("target subject is required");
        }
        List<GitRunner.CommitEntry> matches = git.listCommits(root, baseSha + "..HEAD", 1_000).stream()
                .filter(commit -> subject.equals(commit.subject()))
                .toList();
        if (matches.size() != 1) {
            throw new SafetyException(
                    "target subject must resolve exactly once; found " + matches.size());
        }
        return matches.getFirst().sha();
    }

    private String tree(Path root, String ref)
    {
        return run(root, List.of("git", "rev-parse", ref + "^{tree}"), 30).strip();
    }

    private void restore(Path root, String initialHead, RuntimeException original)
    {
        try {
            execute(root, List.of("git", "rebase", "--abort"), 120);
            run(root, List.of("git", "reset", "--hard", initialHead), 120);
        }
        catch (RuntimeException restoreFailure) {
            original.addSuppressed(restoreFailure);
        }
    }

    private String run(Path root, List<String> argv, long timeoutSeconds)
    {
        ShellRunner.Result result = execute(root, argv, timeoutSeconds);
        if (!result.ran() || result.exitCode() != 0) {
            throw new SafetyException("git command failed: " + String.join(" ", argv)
                    + "\n" + (result.error() == null ? result.output() : result.error()));
        }
        return result.output();
    }

    private ShellRunner.Result execute(Path root, List<String> argv, long timeoutSeconds)
    {
        try {
            return commands.runArgv(root, argv, GIT_ENV, timeoutSeconds, OUTPUT_CAP);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SafetyException("git command interrupted", e);
        }
    }

    private static String validatePath(String value)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("changed path is blank");
        }
        Path path = Path.of(value).normalize();
        if (path.isAbsolute() || path.startsWith("..")) {
            throw new IllegalArgumentException("changed path escapes repository: " + value);
        }
        return path.toString().replace('\\', '/');
    }

    private static void validateRef(String value, String name)
    {
        if (value == null || !value.matches("[A-Za-z0-9._/-]+")
                || value.startsWith("-") || value.contains("..") || value.endsWith("/")) {
            throw new IllegalArgumentException("invalid git " + name);
        }
    }

    private static String bound(String value, int max)
    {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "\n…[truncated]";
    }

    private static void assertActive(BooleanSupplier active)
    {
        if (!active.getAsBoolean()) {
            throw new SafetyException("harness cycle was cancelled");
        }
    }

    static boolean expectedRangeDiff(String output, List<FixupTarget> fixups)
    {
        Map<String, String> expectedChanged = fixups.stream()
                .collect(Collectors.toMap(
                        FixupTarget::sha, FixupTarget::subject, (left, right) -> left));
        Map<String, String> expectedRemoved = fixups.stream()
                .collect(Collectors.toMap(
                        FixupTarget::fixupSha,
                        fixup -> "fixup! " + fixup.subject()));
        Map<String, String> targetBySubject = expectedChanged.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
        Set<String> changedTargets = new HashSet<>();
        Set<String> removedTargets = new HashSet<>();
        Set<String> addedTargets = new HashSet<>();
        Set<String> removedFixups = new HashSet<>();
        for (String line : output.lines().filter(value -> !value.isBlank()).toList()) {
            Matcher match = RANGE_DIFF_LINE.matcher(line);
            if (!match.matches()) {
                return false;
            }
            String beforeSha = match.group(1);
            String marker = match.group(2);
            String subject = match.group(4);
            if ("=".equals(marker)) {
                if (matchingSha(expectedChanged, beforeSha) != null
                        || matchingSha(expectedRemoved, beforeSha) != null) {
                    return false;
                }
                continue;
            }
            if ("!".equals(marker)) {
                String target = matchingSha(expectedChanged, beforeSha);
                if (target == null || !subject.equals(expectedChanged.get(target))
                        || !changedTargets.add(target)) {
                    return false;
                }
                continue;
            }
            if ("<".equals(marker)) {
                String fixup = matchingSha(expectedRemoved, beforeSha);
                if (fixup != null) {
                    if (!subject.equals(expectedRemoved.get(fixup))
                            || !removedFixups.add(fixup)) {
                        return false;
                    }
                    continue;
                }
                String target = matchingSha(expectedChanged, beforeSha);
                if (target == null || !subject.equals(expectedChanged.get(target))
                        || !removedTargets.add(target)) {
                    return false;
                }
                continue;
            }
            if (">".equals(marker)) {
                String target = targetBySubject.get(subject);
                if (target == null || !addedTargets.add(target)) {
                    return false;
                }
                continue;
            }
            return false;
        }
        if (!removedFixups.equals(expectedRemoved.keySet())) {
            return false;
        }
        for (String target : expectedChanged.keySet()) {
            boolean paired = changedTargets.contains(target)
                    && !removedTargets.contains(target)
                    && !addedTargets.contains(target);
            boolean recreated = !changedTargets.contains(target)
                    && removedTargets.contains(target)
                    && addedTargets.contains(target);
            if (!paired && !recreated) {
                return false;
            }
        }
        return true;
    }

    private static String matchingSha(Map<String, String> expected, String abbreviation)
    {
        List<String> matches = expected.keySet().stream()
                .filter(sha -> sha.startsWith(abbreviation))
                .toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    public record SafetyResult(
            String backupRef,
            String originalHead,
            GitSafetyProof proof) {}

    record FixupTarget(String sha, String subject, String fixupSha) {}

    @FunctionalInterface
    public interface BackupRecorder
    {
        void record(String backupRef, String originalHead);
    }

    public static class SafetyException
            extends RuntimeException
    {
        public SafetyException(String message)
        {
            super(message);
        }

        public SafetyException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }
}

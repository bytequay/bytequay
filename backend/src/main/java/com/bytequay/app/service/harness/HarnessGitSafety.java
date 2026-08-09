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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

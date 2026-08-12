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
package com.bytequay.app.flow.upstream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

/**
 * Deterministic Git for one cherry-pick range, in the Task's own worktree.
 *
 * <p>Everything here is program work with a mechanical verdict. Nothing on
 * this class asks a model anything, and nothing on it reaches the network.
 */
public final class UpstreamPicker
{
    private static final Path GIT = Path.of("/usr/bin/git");
    private static final Duration TIMEOUT = Duration.ofMinutes(2);
    private static final int OUTPUT_LIMIT = 4 * 1024 * 1024;
    private static final int MAX_MARKER_SCAN_BYTES = 8 * 1024 * 1024;

    /** Which of the three per-commit outcomes Git actually produced. */
    public enum Outcome
    {
        CLEAN,
        CONFLICTED,
        /**
         * The fork already carries the change. Skipped rather than parked:
         * Git refuses to record an empty commit and holds the sequencer open,
         * and no human resolution can finish that.
         */
        EMPTY
    }

    public record PickResult(
            Outcome outcome,
            String head,
            String commitSha,
            List<String> conflictedPaths,
            boolean provenanceVerified)
    {
        public PickResult
        {
            requireNonNull(outcome, "outcome is null");
            conflictedPaths = List.copyOf(requireNonNull(
                    conflictedPaths, "conflictedPaths is null"));
        }
    }

    /** A mechanical refusal to advance; the caller parks rather than guesses. */
    public static final class UnresolvedRepairException
            extends RuntimeException
    {
        public UnresolvedRepairException(String message)
        {
            super(message);
        }
    }

    private final Path worktree;

    public UpstreamPicker(Path programOwnedWorktree)
    {
        requireNonNull(programOwnedWorktree, "programOwnedWorktree is null");
        Path real;
        try {
            real = programOwnedWorktree.toRealPath();
        }
        catch (IOException failure) {
            throw new UncheckedIOException(
                    "upstream worktree is unavailable", failure);
        }
        if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(
                    "upstream worktree is not a real directory");
        }
        this.worktree = real;
    }

    /**
     * Applies one upstream commit and closes the sequencer before returning.
     *
     * <p>A conflicted pick commits Git's own three-way resolution here, so a
     * crash after this call finds an ordinary repository rather than an open
     * sequencer nobody owns. The marker content that resolution may carry is
     * transient: {@link #verifyRepair} refuses to let it reach a pull request.
     */
    public PickResult pick(String upstreamSha)
    {
        requireText(upstreamSha, "upstreamSha");
        String preHead = head();
        Result attempt = git("cherry-pick", "-x", upstreamSha);
        if (attempt.exitCode() == 0) {
            String head = head();
            dropMergeScratch();
            return new PickResult(
                    Outcome.CLEAN, head, head, List.of(),
                    provenanceVerified(head, upstreamSha));
        }
        List<String> conflicted = unmergedPaths();
        if (conflicted.isEmpty()) {
            if (!sequencerActive()) {
                abortSequencer();
                throw new UnresolvedRepairException(
                        "cherry-pick failed without a sequencer to continue");
            }
            if (git("diff", "--cached", "--quiet", "HEAD").exitCode() != 0) {
                abortSequencer();
                throw new UnresolvedRepairException(
                        "cherry-pick stopped with staged content and no "
                                + "conflict");
            }
            require(git("cherry-pick", "--skip"), "cherry-pick --skip");
            if (!head().equals(preHead)) {
                throw new UnresolvedRepairException(
                        "skipping an empty pick moved the head");
            }
            dropMergeScratch();
            return new PickResult(
                    Outcome.EMPTY, preHead, null, List.of(), false);
        }
        require(git("add", "-A"), "git add");
        if (!unmergedPaths().isEmpty()) {
            throw new UnresolvedRepairException(
                    "unresolved index entries survived staging");
        }
        require(git("-c", "core.editor=true", "cherry-pick", "--continue"),
                "cherry-pick --continue");
        String head = head();
        if (head.equals(preHead)) {
            throw new UnresolvedRepairException(
                    "continuing a conflicted pick recorded no commit");
        }
        dropMergeScratch();
        return new PickResult(
                Outcome.CONFLICTED, head, head, conflicted,
                provenanceVerified(head, upstreamSha));
    }

    /**
     * Drops the scratch ref the ort strategy leaves behind.
     *
     * <p>Git writes {@code AUTO_MERGE} during a three-way merge and does not
     * remove it when a cherry-pick finishes, so it outlives the operation it
     * belonged to. Every reader of this worktree — the runtime's own
     * inspector included — is right to read a leftover control marker as an
     * operation in progress, so the pick is not finished until it is gone.
     */
    private void dropMergeScratch()
    {
        if (sequencerActive()) {
            throw new UnresolvedRepairException(
                    "the sequencer is still open after a finished pick");
        }
        git("update-ref", "-d", "AUTO_MERGE");
    }

    /**
     * Commits the agent's repair as the one fixup attributed to a pick.
     *
     * <p>ponytail: {@code --amend} is correct only because a repair runs
     * immediately after its own pick, so the fixup is still {@code HEAD}. A
     * repair aimed at an earlier pick needs the generated rebase todo that
     * repositions and squashes; until that exists this refuses rather than
     * attaching the change to whatever happens to be on top.
     */
    public String commitFixup(String targetSubject, boolean amendExisting)
    {
        requireText(targetSubject, "targetSubject");
        require(git("add", "-A"), "git add");
        if (git("diff", "--cached", "--quiet", "HEAD").exitCode() == 0
                && !amendExisting) {
            throw new UnresolvedRepairException(
                    "the repair changed nothing to attribute");
        }
        if (amendExisting) {
            if (!subject(head()).equals(fixupSubject(targetSubject))) {
                throw new UnresolvedRepairException(
                        "the existing fixup is no longer the current head");
            }
            require(git("commit", "--amend", "--no-edit", "--allow-empty"),
                    "git commit --amend");
        }
        else {
            require(git("commit", "-m", fixupSubject(targetSubject)),
                    "git commit");
        }
        return head();
    }

    /**
     * Proves a repair before the run advances.
     *
     * <p>Both halves matter. A dirty worktree means the repair is not in
     * history at all; a surviving marker means the agent reported a file
     * resolved that it never opened, and Git's own resolution is already
     * committed by the time it runs.
     */
    public void verifyRepair(List<String> conflictedPaths)
    {
        requireNonNull(conflictedPaths, "conflictedPaths is null");
        if (!clean()) {
            throw new UnresolvedRepairException(
                    "the worktree is not clean after the repair");
        }
        for (String path : conflictedPaths) {
            if (carriesConflictMarker(path)) {
                throw new UnresolvedRepairException(
                        "a conflicted path still carries a conflict marker");
            }
        }
    }

    public boolean clean()
    {
        Result status = git("status", "--porcelain", "--untracked-files=all");
        return status.exitCode() == 0 && status.stdout().isBlank()
                && !sequencerActive() && !controlMarker("AUTO_MERGE");
    }

    public String head()
    {
        Result result = git("rev-parse", "--verify", "HEAD");
        String head = result.stdout().strip();
        if (result.exitCode() != 0 || !head.matches("[0-9a-f]{40,64}")) {
            throw new UnresolvedRepairException("cannot read the current head");
        }
        return head;
    }

    public String subject(String commit)
    {
        requireText(commit, "commit");
        Result result = git("log", "-1", "--format=%s", commit);
        require(result, "git log");
        return result.stdout().strip();
    }

    public String message(String commit)
    {
        requireText(commit, "commit");
        Result result = git("log", "-1", "--format=%B", commit);
        require(result, "git log");
        return result.stdout();
    }

    /**
     * The auditable link back to upstream that {@code -x} records. A continued
     * conflicted pick keeps it, which is why provenance is verified rather
     * than assumed from the exit code.
     */
    public boolean provenanceVerified(String commit, String upstreamSha)
    {
        requireText(commit, "commit");
        requireText(upstreamSha, "upstreamSha");
        return message(commit).contains(
                "(cherry picked from commit " + upstreamSha + ")");
    }

    public List<String> changedPaths(String fromCommit, String toCommit)
    {
        requireText(fromCommit, "fromCommit");
        requireText(toCommit, "toCommit");
        Result result = git(
                "diff", "--name-only", "-z", fromCommit, toCommit);
        require(result, "git diff --name-only");
        return nulFields(result.stdout());
    }

    public List<String> unmergedPaths()
    {
        Result result = git(
                "diff", "--name-only", "-z", "--diff-filter=U");
        if (result.exitCode() != 0) {
            return List.of();
        }
        return nulFields(result.stdout());
    }

    public boolean sequencerActive()
    {
        return controlMarker("CHERRY_PICK_HEAD") || controlMarker("sequencer");
    }

    private boolean controlMarker(String name)
    {
        Result result = git("rev-parse", "--git-path", name);
        if (result.exitCode() != 0) {
            return false;
        }
        return Files.exists(worktree.resolve(result.stdout().strip()));
    }

    /** Best-effort return to a usable repository before the run parks. */
    public void abortSequencer()
    {
        if (sequencerActive()) {
            git("cherry-pick", "--abort");
        }
    }

    public static String fixupSubject(String targetSubject)
    {
        requireText(targetSubject, "targetSubject");
        return "fixup! " + targetSubject;
    }

    private boolean carriesConflictMarker(String path)
    {
        Path file = worktree.resolve(path).normalize();
        if (!file.startsWith(worktree) || !Files.isRegularFile(
                file, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        byte[] content;
        try {
            if (Files.size(file) > MAX_MARKER_SCAN_BYTES) {
                // Fail closed: an unreadably large conflicted file is not
                // evidence that the repair landed.
                return true;
            }
            content = Files.readAllBytes(file);
        }
        catch (IOException unreadable) {
            return true;
        }
        for (String line : new String(content, StandardCharsets.UTF_8)
                .split("\\R", -1)) {
            if (line.startsWith("<<<<<<< ") || line.startsWith(">>>>>>> ")
                    || line.startsWith("||||||| ")) {
                return true;
            }
        }
        return false;
    }

    private static List<String> nulFields(String value)
    {
        List<String> fields = new ArrayList<>();
        for (String field : value.split("\0", -1)) {
            if (!field.isEmpty()) {
                fields.add(field);
            }
        }
        return fields;
    }

    private static void require(Result result, String what)
    {
        if (result.exitCode() != 0) {
            throw new UnresolvedRepairException(what + " failed");
        }
    }

    private record Result(int exitCode, String stdout) {}

    private Result git(String... arguments)
    {
        List<String> command = new ArrayList<>(List.of(
                GIT.toString(),
                "-c", "core.hooksPath=/dev/null",
                "-c", "commit.gpgSign=false",
                "-c", "user.name=ByteQuay",
                "-c", "user.email=bytequay@localhost",
                "-c", "core.fsmonitor=false",
                "-c", "gc.auto=0",
                "-c", "maintenance.auto=false",
                "-c", "protocol.allow=never"));
        command.addAll(Arrays.asList(arguments));
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(worktree.toFile());
        builder.environment().clear();
        builder.environment().put("PATH", "/usr/bin:/bin");
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_NO_REPLACE_OBJECTS", "1");
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().put("GIT_ASKPASS", "/usr/bin/false");
        Process process;
        try {
            process = builder.start();
        }
        catch (IOException failure) {
            throw new UncheckedIOException("cannot start Git", failure);
        }
        AtomicReference<byte[]> stdout = new AtomicReference<>(new byte[0]);
        Thread out = Thread.ofVirtual().start(
                () -> stdout.set(drain(process.getInputStream())));
        Thread err = Thread.ofVirtual().start(
                () -> drain(process.getErrorStream()));
        boolean exited;
        try {
            exited = process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!exited) {
                process.destroyForcibly();
            }
            out.join(TIMEOUT.toMillis());
            err.join(TIMEOUT.toMillis());
        }
        catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new UnresolvedRepairException("Git was interrupted");
        }
        if (!exited) {
            throw new UnresolvedRepairException("Git did not finish in time");
        }
        return new Result(
                process.exitValue(),
                new String(stdout.get(), StandardCharsets.UTF_8));
    }

    private static byte[] drain(InputStream input)
    {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        try (input) {
            int read;
            while ((read = input.read(chunk)) >= 0
                    && buffer.size() <= OUTPUT_LIMIT) {
                buffer.write(chunk, 0, read);
            }
        }
        catch (IOException ignored) {
            // A closed stream is the process ending; the exit code decides.
        }
        return buffer.toByteArray();
    }

    private static void requireText(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}

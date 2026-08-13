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
package com.bytequay.app.flow.ci;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;

/**
 * The program's own mechanical rebases over one attributed commit series.
 *
 * <p>The CI Fixer chooses which commit a repair belongs to, because that is
 * judgment, and says so in the commit subject as {@code fixup! <target
 * subject>}. This class does the repositioning, because that subject is fenced
 * Git state rather than agent prose, and the resulting rebase is mechanical. It
 * parses no model output, guesses no attribution, and reads no CI log.
 *
 * <p>It is stateless and fail-closed. Every entry point verifies the worktree is
 * clean and at the exact head it was told to expect, and restores that head if
 * anything goes wrong, because the alternative to a restored head is a branch
 * left mid-rebase under a program that thinks it published.
 */
public final class AttributedFixupRebase
{
    private static final Path GIT = Path.of("/usr/bin/git");
    private static final Path COPY = Path.of("/bin/cp");
    private static final String FIXUP_PREFIX = "fixup! ";
    private static final int OUTPUT_LIMIT = 1024 * 1024;
    private static final int MAX_SERIES_COMMITS = 10_000;

    public enum FailureCode
    {
        GIT_UNAVAILABLE,
        TIMEOUT,
        INTERRUPTED,
        DIRTY_WORKTREE,
        HEAD_MOVED,
        INVALID_SERIES,
        REBASE_FAILED,
        PROOF_INCOMPLETE
    }

    /** One commit of the series, identified only by Git-owned facts. */
    public record SeriesCommit(String sha, String subject)
    {
        public SeriesCommit
        {
            requireNonNull(sha, "sha is null");
            requireNonNull(subject, "subject is null");
        }

        public boolean fixup()
        {
            return subject.startsWith(FIXUP_PREFIX)
                    && subject.length() > FIXUP_PREFIX.length();
        }

        /** The exact target subject this commit names, or null. */
        public String targetSubject()
        {
            return fixup() ? subject.substring(FIXUP_PREFIX.length()) : null;
        }
    }

    /**
     * The generated rebase todo plus what it could not attribute.
     *
     * <p>{@code identity} means the series is already shaped the way attributed
     * placement wants it, so no rebase runs and no commit identity churns.
     */
    public record RewritePlan(
            List<String> todo,
            List<String> unattributedFixupShas,
            boolean identity)
    {
        public RewritePlan
        {
            todo = List.copyOf(todo);
            unattributedFixupShas = List.copyOf(unattributedFixupShas);
        }
    }

    public record Rewrite(
            String inputHead,
            String outputHead,
            List<SeriesCommit> series,
            List<String> unattributedFixupShas)
    {
        public Rewrite
        {
            requireNonNull(inputHead, "inputHead is null");
            requireNonNull(outputHead, "outputHead is null");
            series = List.copyOf(series);
            unattributedFixupShas = List.copyOf(unattributedFixupShas);
        }
    }

    public static final class RebaseFailure
            extends RuntimeException
    {
        private final FailureCode code;

        RebaseFailure(FailureCode code, String message)
        {
            super(message);
            this.code = requireNonNull(code, "code is null");
        }

        public FailureCode code()
        {
            return code;
        }
    }

    /**
     * Moves every attributable {@code fixup!} commit behind its target.
     *
     * <p>A target that already carries a fixup keeps exactly one: the newer
     * repair is squashed into the existing fixup rather than appended beside it,
     * and the target commit itself is never touched, so it stays comparable to
     * the upstream commit it came from. A fixup naming no target, or naming a
     * subject that more than one commit carries, is left where it is as a plain
     * commit rather than attached to a guess.
     */
    public Rewrite reposition(
            Path worktree, String baseSha, String expectedHead,
            Duration timeout)
    {
        Deadline deadline = new Deadline(timeout);
        requireCleanHead(worktree, baseSha, expectedHead, deadline);
        List<SeriesCommit> series = readSeries(
                worktree, baseSha, expectedHead, deadline);
        RewritePlan plan = plan(series);
        if (plan.identity()) {
            return new Rewrite(
                    expectedHead, expectedHead, series,
                    plan.unattributedFixupShas());
        }
        runTodo(worktree, baseSha, expectedHead, plan.todo(), deadline);
        String outputHead = head(worktree, deadline);
        return new Rewrite(
                expectedHead,
                outputHead,
                readSeries(worktree, baseSha, outputHead, deadline),
                plan.unattributedFixupShas());
    }

    /**
     * Builds the todo that repositions the series. Pure, so the placement rule
     * is testable without a repository.
     */
    public static RewritePlan plan(List<SeriesCommit> series)
    {
        Map<String, Long> targetSubjects = new LinkedHashMap<>();
        for (SeriesCommit commit : series) {
            if (!commit.fixup()) {
                targetSubjects.merge(commit.subject(), 1L, Long::sum);
            }
        }
        Map<String, List<String>> attributed = new LinkedHashMap<>();
        List<String> unattributed = new ArrayList<>();
        for (SeriesCommit commit : series) {
            if (!commit.fixup()) {
                continue;
            }
            if (targetSubjects.getOrDefault(commit.targetSubject(), 0L) == 1L) {
                attributed.computeIfAbsent(
                                commit.targetSubject(),
                                ignored -> new ArrayList<>())
                        .add(commit.sha());
            }
            else {
                unattributed.add(commit.sha());
            }
        }

        List<String> todo = new ArrayList<>();
        List<String> order = new ArrayList<>();
        for (SeriesCommit commit : series) {
            if (commit.fixup()) {
                continue;
            }
            todo.add("pick " + commit.sha());
            order.add(commit.sha());
            List<String> fixups = attributed.getOrDefault(
                    commit.subject(), List.of());
            for (int index = 0; index < fixups.size(); index++) {
                // The first fixup for a target stays a commit of its own so the
                // target is not rewritten; every later one is squashed into it,
                // so a target never accumulates a second.
                todo.add((index == 0 ? "pick " : "fixup ")
                        + fixups.get(index));
                if (index == 0) {
                    order.add(fixups.get(index));
                }
            }
        }
        for (String sha : unattributed) {
            todo.add("pick " + sha);
            order.add(sha);
        }
        boolean identity = order.equals(
                series.stream().map(SeriesCommit::sha).toList());
        return new RewritePlan(todo, unattributed, identity);
    }

    List<SeriesCommit> readSeries(
            Path worktree, String baseSha, String expectedHead,
            Deadline deadline)
    {
        String output = require(git(
                worktree, deadline,
                List.of("log", "--reverse", "--no-color",
                        "--format=%H %P%x00%s",
                        baseSha + ".." + expectedHead)),
                FailureCode.INVALID_SERIES,
                "series could not be read");
        List<SeriesCommit> series = new ArrayList<>();
        for (String line : output.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            int separator = line.indexOf((char) 0);
            if (separator < 0) {
                throw new RebaseFailure(
                        FailureCode.INVALID_SERIES, "unreadable commit line");
            }
            String[] shas = line.substring(0, separator).trim().split(" ");
            if (shas.length != 2) {
                // A merge in the series would be flattened by any todo this
                // class can generate, so it is refused rather than rewritten.
                throw new RebaseFailure(
                        FailureCode.INVALID_SERIES,
                        "series contains a commit that is not single-parent");
            }
            series.add(new SeriesCommit(
                    shas[0], line.substring(separator + 1)));
        }
        if (series.isEmpty() || series.size() > MAX_SERIES_COMMITS) {
            throw new RebaseFailure(
                    FailureCode.INVALID_SERIES,
                    "series is empty or larger than this program will rewrite");
        }
        return List.copyOf(series);
    }

    private void requireCleanHead(
            Path worktree, String baseSha, String expectedHead,
            Deadline deadline)
    {
        requireNonNull(worktree, "worktree is null");
        requireSha(baseSha, "baseSha");
        requireSha(expectedHead, "expectedHead");
        if (!head(worktree, deadline).equals(expectedHead)) {
            throw new RebaseFailure(
                    FailureCode.HEAD_MOVED,
                    "worktree is not at the head it was bound to");
        }
        String status = require(
                git(worktree, deadline,
                        List.of("status", "--porcelain=v1",
                                "--untracked-files=no")),
                FailureCode.DIRTY_WORKTREE,
                "worktree state could not be read");
        if (!status.isBlank()) {
            throw new RebaseFailure(
                    FailureCode.DIRTY_WORKTREE,
                    "worktree has uncommitted changes");
        }
        if (git(worktree, deadline,
                List.of("merge-base", "--is-ancestor", baseSha, expectedHead))
                .exitCode() != 0) {
            throw new RebaseFailure(
                    FailureCode.INVALID_SERIES,
                    "base is not an ancestor of the head");
        }
    }

    private void runTodo(
            Path worktree, String baseSha, String expectedHead,
            List<String> todo, Deadline deadline)
    {
        Path directory = temporaryDirectory("bytequay-todo-");
        Path file = directory.resolve("todo");
        try {
            Files.writeString(
                    file, String.join("\n", todo) + "\n",
                    StandardCharsets.UTF_8);
            CommandResult result = git(
                    worktree,
                    deadline,
                    List.of("rebase", "--interactive", "--no-autosquash",
                            baseSha),
                    Map.of("GIT_SEQUENCE_EDITOR",
                            COPY + " " + shellQuote(file.toString())));
            if (result.exitCode() != 0) {
                throw new RebaseFailure(
                        FailureCode.REBASE_FAILED,
                        "generated rebase did not complete");
            }
        }
        catch (IOException e) {
            throw new RebaseFailure(
                    FailureCode.REBASE_FAILED, "todo could not be written");
        }
        catch (RuntimeException e) {
            restore(worktree, expectedHead);
            throw e;
        }
        finally {
            deleteRecursively(directory);
        }
    }

    /** Restores on its own deadline: the failure may well have been a timeout. */
    private void restore(Path worktree, String expectedHead)
    {
        Deadline restoreDeadline = new Deadline(Duration.ofSeconds(60));
        git(worktree, restoreDeadline, List.of("rebase", "--abort"));
        if (!head(worktree, restoreDeadline).equals(expectedHead)) {
            git(worktree, restoreDeadline,
                    List.of("reset", "--hard", expectedHead));
        }
    }

    private String head(Path worktree, Deadline deadline)
    {
        String head = require(
                git(worktree, deadline, List.of("rev-parse", "HEAD")),
                FailureCode.HEAD_MOVED,
                "head could not be read").trim();
        requireSha(head, "head");
        return head;
    }

    private static String require(
            CommandResult result, FailureCode code, String message)
    {
        if (result.exitCode() != 0) {
            throw new RebaseFailure(code, message);
        }
        return result.stdout();
    }

    private static void requireSha(String value, String name)
    {
        if (value == null || !value.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")) {
            throw new IllegalArgumentException(name + " is not a full SHA");
        }
    }

    private static String shellQuote(String value)
    {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static Path temporaryDirectory(String prefix)
    {
        try {
            return Files.createTempDirectory(prefix);
        }
        catch (IOException e) {
            throw new RebaseFailure(
                    FailureCode.REBASE_FAILED,
                    "temporary directory could not be created");
        }
    }

    private static void deleteRecursively(Path directory)
    {
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                }
                catch (IOException ignored) {
                    // Best effort: a leaked temporary file is not a failure of
                    // the proof it carried.
                }
            });
        }
        catch (IOException ignored) {
            // As above.
        }
    }

    private CommandResult git(
            Path worktree, Deadline deadline, List<String> arguments)
    {
        return git(worktree, deadline, arguments, Map.of());
    }

    private CommandResult git(
            Path worktree,
            Deadline deadline,
            List<String> arguments,
            Map<String, String> extraEnvironment)
    {
        List<String> command = new ArrayList<>();
        command.add(GIT.toString());
        command.add("-c");
        command.add("core.fsmonitor=false");
        command.add("-c");
        command.add("core.hooksPath=/dev/null");
        command.add("-c");
        command.add("commit.gpgsign=false");
        command.add("-c");
        command.add("rebase.autosquash=false");
        command.add("-c");
        command.add("rebase.abbreviateCommands=false");
        command.addAll(arguments);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(worktree.toFile());
        // The environment is inherited rather than cleared: a rebase creates
        // commits, and the committer identity a user configured globally is not
        // this program's to hide.
        Map<String, String> environment = builder.environment();
        environment.put("LC_ALL", "C");
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("GIT_PAGER", "cat");
        environment.put("PAGER", "cat");
        environment.put("GIT_EDITOR", "true");
        environment.remove("GIT_SEQUENCE_EDITOR");
        environment.putAll(extraEnvironment);

        Process process;
        try {
            process = builder.start();
        }
        catch (IOException e) {
            throw new RebaseFailure(
                    FailureCode.GIT_UNAVAILABLE, "git could not be started");
        }
        close(process.getOutputStream());
        Drain stdout = new Drain(process.getInputStream());
        Drain stderr = new Drain(process.getErrorStream());
        Thread stdoutThread = Thread.ofVirtual().start(stdout);
        Thread stderrThread = Thread.ofVirtual().start(stderr);
        try {
            long remaining = deadline.remainingNanos();
            if (remaining <= 0
                    || !process.waitFor(remaining, TimeUnit.NANOSECONDS)) {
                process.destroyForcibly();
                throw new RebaseFailure(
                        FailureCode.TIMEOUT, "git did not finish in time");
            }
            stdoutThread.join();
            stderrThread.join();
        }
        catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new RebaseFailure(
                    FailureCode.INTERRUPTED, "git was interrupted");
        }
        return new CommandResult(process.exitValue(), stdout.text());
    }

    private static void close(OutputStream stream)
    {
        try {
            stream.close();
        }
        catch (IOException ignored) {
            // The child gets EOF either way.
        }
    }

    record CommandResult(int exitCode, String stdout) {}

    static final class Deadline
    {
        private final long expiresAt;

        Deadline(Duration timeout)
        {
            requireNonNull(timeout, "timeout is null");
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout is not positive");
            }
            this.expiresAt = System.nanoTime() + timeout.toNanos();
        }

        long remainingNanos()
        {
            return expiresAt - System.nanoTime();
        }
    }

    private static final class Drain
            implements Runnable
    {
        private final InputStream stream;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        private Drain(InputStream stream)
        {
            this.stream = stream;
        }

        @Override
        public void run()
        {
            byte[] chunk = new byte[8192];
            try (stream) {
                int read;
                while ((read = stream.read(chunk)) >= 0) {
                    if (buffer.size() < OUTPUT_LIMIT) {
                        buffer.write(chunk, 0, Math.min(
                                read, OUTPUT_LIMIT - buffer.size()));
                    }
                }
            }
            catch (IOException ignored) {
                // A truncated read still leaves the exit code authoritative.
            }
        }

        private String text()
        {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }
}

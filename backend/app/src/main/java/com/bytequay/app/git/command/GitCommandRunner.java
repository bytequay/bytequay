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
package com.bytequay.app.git.command;

import com.bytequay.app.git.command.SequenceState.ConflictedPath;
import com.bytequay.app.git.command.SequenceState.ConflictedPath.IndexStage;
import com.google.common.base.Splitter;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toCollection;

@Component
public class GitCommandRunner
{
    public static final int MAXIMUM_OUTPUT_BYTES = 5 * 1024 * 1024;
    /** Git directory entries that exist while a merge, cherry-pick, revert, or rebase is open. */
    public static final List<String> OPERATION_MARKERS = ImmutableList.of("CHERRY_PICK_HEAD", "MERGE_HEAD", "REVERT_HEAD", "rebase-merge", "rebase-apply");
    private static final int MAXIMUM_ERROR_BYTES = 64 * 1024;
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(2);
    // Local object reads never fetch from a promisor remote or follow replacement objects.
    private static final Map<String, String> LOCAL_ONLY = ImmutableMap.of("GIT_NO_LAZY_FETCH", "1", "GIT_NO_REPLACE_OBJECTS", "1");
    // macOS's /usr/bin/git looks up the developer tools on every call, which costs more than most Git commands themselves.
    private static final Supplier<String> DEFAULT_EXECUTABLE = Suppliers.memoize(() -> defaultExecutable(System.getenv().getOrDefault("PATH", "")));
    // Bash job control gives the command its own group, including helpers reparented after Git exits.
    private static final String COMMAND_LAUNCHER = """
            bq_pid=
            stop_group() {
                trap '' HUP INT TERM
                bq_pid=${bq_pid:-$!}
                [ -n "$bq_pid" ] || return
                if kill -0 -- "-$bq_pid" 2>/dev/null; then
                    kill -TERM -- "-$bq_pid" 2>/dev/null || :
                    bq_deadline=$((SECONDS + 2))
                    while kill -0 -- "-$bq_pid" 2>/dev/null && [ "$SECONDS" -lt "$bq_deadline" ]; do
                        /bin/sleep 0.05
                    done
                    kill -KILL -- "-$bq_pid" 2>/dev/null || :
                fi
                wait "$bq_pid" 2>/dev/null || :
                while kill -0 -- "-$bq_pid" 2>/dev/null; do
                    kill -KILL -- "-$bq_pid" 2>/dev/null || :
                    /bin/sleep 0.05
                done
            }
            trap stop_group EXIT
            trap 'exit 143' HUP INT TERM
            set -m
            # Silence the shell first: a command that ends before the next line would otherwise
            # add bash's "[1]+ Done" notice to an output merged with stderr.
            exec 3>&2 2>/dev/null
            exec -- "$@" <&0 2>&3 3>&- &
            bq_pid=$!
            exec 3>&-
            wait "$bq_pid"
            exit "$?"
            """;

    private final String executable;

    public GitCommandRunner()
    {
        this(DEFAULT_EXECUTABLE.get());
    }

    public GitCommandRunner(String executable)
    {
        this.executable = requireNonNull(executable, "executable is null");
    }

    /** The Git that {@code path} finds first, or the developer tools' own Git when that is the macOS launcher. */
    static String defaultExecutable(String path)
    {
        Optional<Path> first = Splitter.on(File.pathSeparatorChar).omitEmptyStrings().splitToStream(path)
                .map(directory -> Path.of(directory, "git"))
                .filter(Files::isExecutable)
                .findFirst();
        if (!first.equals(Optional.of(Path.of("/usr/bin/git")))) {
            return "git";
        }
        try {
            Result developerDirectory = execute(new ProcessBuilder("/usr/bin/xcode-select", "--print-path"), Duration.ofSeconds(10), 4096, "xcode-select", true, 0);
            if (developerDirectory.successful()) {
                Path git = Path.of(developerDirectory.output().strip(), "usr", "bin", "git");
                if (Files.isExecutable(git)) {
                    return git.toString();
                }
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "git";
    }

    public Result run(Path worktree, String... arguments)
    {
        return run(worktree, ImmutableList.copyOf(arguments));
    }

    public Result run(Path worktree, List<String> arguments)
    {
        return run(worktree, COMMAND_TIMEOUT, arguments);
    }

    /**
     * Runs one Git command with an explicit timeout.
     *
     * <p>The default suits local commands. A fetch talks to a remote and needs
     * longer on a large repository.
     *
     * @param worktree directory to run in
     * @param timeout positive timeout for this command
     * @param arguments argv after {@code git -C <worktree>}
     * @return exit code and bounded combined output
     */
    public Result run(Path worktree, Duration timeout, List<String> arguments)
    {
        return run(worktree, timeout, ImmutableMap.of(), arguments);
    }

    /**
     * Runs one Git command with extra environment variables, for commands whose
     * behavior only the environment controls (such as authorship of
     * {@code commit-tree}).
     */
    public Result run(Path worktree, Duration timeout, Map<String, String> environment, List<String> arguments)
    {
        return run(worktree, timeout, MAXIMUM_OUTPUT_BYTES, environment, arguments, false, 0);
    }

    /**
     * Runs one Git command whose standard output is parsed, such as a NUL-separated listing, so a warning
     * Git prints on standard error with a successful exit cannot corrupt it.
     *
     * @return on success the bounded standard output; on failure the bounded standard error
     */
    public Result runStdout(Path worktree, String... arguments)
    {
        return runStdout(worktree, 0, arguments);
    }

    /** Preserves stdout for an additional data-producing exit code, such as merge-tree reporting conflicts with exit 1. */
    public Result runStdout(Path worktree, int stdoutExitCode, String... arguments)
    {
        return run(worktree, COMMAND_TIMEOUT, MAXIMUM_OUTPUT_BYTES, ImmutableMap.of(), ImmutableList.copyOf(arguments), true, stdoutExitCode);
    }

    private Result run(Path worktree, Duration timeout, int maximumOutputBytes, Map<String, String> environment, List<String> arguments, boolean separateErrors, int stdoutExitCode)
    {
        requireNonNull(worktree, "worktree is null");
        requireNonNull(timeout, "timeout is null");
        checkArgument(!timeout.isZero() && !timeout.isNegative(), "timeout is not positive");
        checkArgument(maximumOutputBytes > 0, "maximumOutputBytes is not positive");
        requireNonNull(environment, "environment is null");
        requireNonNull(arguments, "arguments is null");
        List<String> command = ImmutableList.<String>builderWithExpectedSize(arguments.size() + 3)
                .add(executable, "-C", worktree.toString())
                .addAll(arguments)
                .build();
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().put("LC_ALL", "C");
        builder.environment().putAll(environment);
        try {
            return execute(builder, timeout, maximumOutputBytes, "Git", separateErrors, stdoutExitCode);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.failed("Git was interrupted");
        }
    }

    /** Runs a bounded local object read without replacement objects or on-demand fetching from a promisor remote. */
    public Result runLocalBounded(Path worktree, Duration timeout, int maximumOutputBytes, List<String> arguments)
    {
        return run(worktree, timeout, maximumOutputBytes, LOCAL_ONLY, arguments, false, 0);
    }

    /** Local object reads with separate errors, so successful Git warnings cannot corrupt structured stdout. */
    public Result runLocalBoundedStdout(Path worktree, Duration timeout, int maximumOutputBytes, List<String> arguments)
    {
        return run(worktree, timeout, maximumOutputBytes, LOCAL_ONLY, arguments, true, 0);
    }

    /**
     * Resolves several {@code --git-path} locations with one {@code rev-parse}.
     *
     * <p>Callers test a handful of operation markers together. Asking for them
     * one at a time costs a process each, and process startup dominates a
     * command this cheap.
     *
     * @param worktree directory to run in
     * @param markers non-empty {@code --git-path} arguments
     * @return one normalized path per marker, in order, or an empty list when
     *         Git did not report exactly one path per marker
     */
    public List<Path> gitPaths(Path worktree, List<String> markers)
    {
        requireNonNull(worktree, "worktree is null");
        requireNonNull(markers, "markers is null");
        checkArgument(!markers.isEmpty(), "markers is empty");
        ImmutableList.Builder<String> arguments = ImmutableList.builderWithExpectedSize(1 + (2 * markers.size()));
        arguments.add("rev-parse");
        for (String marker : markers) {
            arguments.add("--git-path", marker);
        }
        Result result = run(worktree, arguments.build());
        if (!result.successful()) {
            return ImmutableList.of();
        }
        List<String> reported = result.output().strip().lines().toList();
        // A path holding a line break would misalign the answers, so only a
        // one-to-one reply can be trusted.
        if (reported.size() != markers.size()) {
            return ImmutableList.of();
        }
        return reported.stream()
                .map(line -> worktree.resolve(line).normalize())
                .collect(toImmutableList());
    }

    /** Returns the requested {@code --git-path} markers that currently exist, in request order. */
    public Set<String> openMarkers(Path worktree, List<String> markers)
    {
        List<Path> paths = gitPaths(worktree, markers);
        Set<String> open = new LinkedHashSet<>();
        for (int marker = 0; marker < paths.size(); marker++) {
            if (Files.exists(paths.get(marker))) {
                open.add(markers.get(marker));
            }
        }
        return open;
    }

    /**
     * Reports whether {@code fromSha..toSha} adds no conflict marker.
     *
     * <p>Only leftover conflict markers may fail this check. Whitespace the source author wrote is
     * copied faithfully, and a repository's own {@code core.whitespace} must not change what ByteQuay accepts.
     */
    public boolean withoutConflictMarkers(Path worktree, String fromSha, String toSha)
    {
        return run(worktree, "-c", "core.whitespace=-trailing-space,-space-before-tab,-indent-with-non-tab,-tab-in-indent", "diff", "--check", fromSha + ".." + toSha)
                .successful();
    }

    public Optional<String> markerCommit(Path worktree, String marker)
    {
        Result result = run(worktree, "rev-parse", "--verify", "--quiet", marker);
        return result.successful() && !result.output().isBlank() ? Optional.of(result.output().strip()) : Optional.empty();
    }

    public List<ConflictedPath> conflicts(Path worktree)
    {
        Result unmerged = runStdout(worktree, "ls-files", "-u", "-z");
        if (!unmerged.successful() || unmerged.output().isBlank()) {
            return ImmutableList.of();
        }
        Map<String, List<IndexStage>> stages = new LinkedHashMap<>();
        for (String line : unmerged.output().split("\0")) {
            int tab = line.indexOf('\t');
            if (tab < 0) {
                continue;
            }
            String[] fields = line.substring(0, tab).strip().split("\\s+");
            if (fields.length < 3) {
                continue;
            }
            stages.computeIfAbsent(line.substring(tab + 1), ignored -> new ArrayList<>())
                    .add(new IndexStage(Integer.parseInt(fields[2]), fields[0], fields[1]));
        }
        ImmutableList.Builder<ConflictedPath> result = ImmutableList.builder();
        stages.forEach((path, present) -> {
            present.sort(Comparator.comparingInt(IndexStage::stage));
            Set<Integer> numbers = present.stream()
                    .map(IndexStage::stage)
                    .collect(toCollection(LinkedHashSet::new));
            result.add(new ConflictedPath(path, classify(numbers, present.getFirst().mode()), present));
        });
        return result.build();
    }

    private static ConflictedPath.Type classify(Set<Integer> stages, String mode)
    {
        if ("160000".equals(mode)) {
            return ConflictedPath.Type.SUBMODULE;
        }
        if (!stages.contains(1)) {
            return ConflictedPath.Type.ADD_ADD;
        }
        return stages.contains(2) && stages.contains(3) ? ConflictedPath.Type.CONTENT : ConflictedPath.Type.MODIFY_DELETE;
    }

    /** Git normalizes patch positions, but whitespace stays significant for literals and indentation-sensitive code. */
    public Result patchId(Path repository, String patch)
    {
        try {
            Path input = Files.createTempFile("bytequay-patch-id-", ".diff");
            try {
                Files.writeString(input, patch, StandardCharsets.UTF_8);
                return execute(new ProcessBuilder(executable, "-C", repository.toString(), "patch-id", "--verbatim")
                        .redirectInput(input.toFile()), COMMAND_TIMEOUT, 1024, "Git patch-id", true, 0);
            }
            finally {
                Files.deleteIfExists(input);
            }
        }
        catch (IOException failure) {
            return Result.failed("Git patch-id input could not be read: " + message(failure));
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Result.failed("Git patch-id was interrupted");
        }
    }

    /** Runs an arbitrary argv-based command in one working directory. */
    public Result runCommand(Path workingDirectory, List<String> command, Duration timeout, int maximumOutputBytes)
            throws InterruptedException
    {
        return runCommand(workingDirectory, ImmutableMap.of(), command, timeout, maximumOutputBytes);
    }

    /** Runs an arbitrary argv-based command with extra environment variables on top of the inherited ones. */
    public Result runCommand(Path workingDirectory, Map<String, String> environment, List<String> command, Duration timeout, int maximumOutputBytes)
            throws InterruptedException
    {
        requireNonNull(workingDirectory, "workingDirectory is null");
        requireNonNull(environment, "environment is null");
        requireNonNull(command, "command is null");
        checkArgument(!command.isEmpty(), "command is empty");
        requireNonNull(timeout, "timeout is null");
        checkArgument(!timeout.isZero() && !timeout.isNegative(), "timeout is not positive");
        checkArgument(maximumOutputBytes > 0, "maximumOutputBytes is not positive");
        ProcessBuilder builder = new ProcessBuilder(command).directory(workingDirectory.toFile());
        builder.environment().putAll(environment);
        return execute(builder, timeout, maximumOutputBytes, "Command", false, 0);
    }

    private static Result execute(ProcessBuilder builder, Duration timeout, int maximumOutputBytes, String name, boolean separateErrors, int stdoutExitCode)
            throws InterruptedException
    {
        Process process;
        try {
            // Privileged mode skips BASH_ENV and imported shell functions; argv and stdin stay literal.
            builder.command(ImmutableList.<String>builder()
                    .add("/bin/bash", "--noprofile", "--norc", "-p", "-c", COMMAND_LAUNCHER, "bytequay-command")
                    .addAll(builder.command()).build());
            builder.redirectErrorStream(!separateErrors);
            process = builder.start();
        }
        catch (IOException | RuntimeException failure) {
            return Result.failed(name + " could not start: " + message(failure));
        }
        try {
            process.getOutputStream().close();
        }
        catch (IOException failure) {
            terminate(process);
            return Result.failed(name + " input could not be closed: " + message(failure));
        }

        CompletableFuture<CapturedOutput> output = drain(process.getInputStream(), maximumOutputBytes);
        // A merged error stream reads as empty at once.
        CompletableFuture<CapturedOutput> errors = drain(process.getErrorStream(), MAXIMUM_ERROR_BYTES);
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                terminate(process);
                return Result.failed(name + " timed out");
            }
            CapturedOutput captured = output.get(DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            CapturedOutput error = errors.get(DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (separateErrors && process.exitValue() != 0 && process.exitValue() != stdoutExitCode) {
                return new Result(process.exitValue(), error.text(), error.truncated());
            }
            return new Result(process.exitValue(), captured.text(), captured.truncated());
        }
        catch (InterruptedException e) {
            terminate(process);
            throw e;
        }
        catch (ExecutionException | TimeoutException e) {
            terminate(process);
            return Result.failed(name + " output could not be read: " + message(e));
        }
    }

    private static CompletableFuture<CapturedOutput> drain(InputStream input, int maximumBytes)
    {
        CompletableFuture<CapturedOutput> captured = new CompletableFuture<>();
        Thread.ofVirtual().name("bytequay-command-output").start(() -> {
            try {
                captured.complete(capture(input, maximumBytes));
            }
            catch (RuntimeException failure) {
                captured.completeExceptionally(failure);
            }
        });
        return captured;
    }

    private static void terminate(Process process)
    {
        List<ProcessHandle> descendants = process.descendants().collect(toImmutableList());
        CompletableFuture<?>[] children = descendants.stream().map(ProcessHandle::onExit).toArray(CompletableFuture<?>[]::new);
        process.destroy();
        // The launcher exits only after its group is gone; interruption must not release the worktree lock early.
        process.onExit().join();
        // A hook may have moved a child into a different process group.
        descendants.forEach(ProcessHandle::destroyForcibly);
        CompletableFuture.allOf(children).join();
    }

    private static CapturedOutput capture(InputStream input, int maximumOutputBytes)
    {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            boolean truncated = false;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                int remaining = maximumOutputBytes - output.size();
                if (remaining > 0) {
                    output.write(buffer, 0, Math.min(read, remaining));
                }
                truncated |= read > remaining;
            }
            return new CapturedOutput(output.toString(StandardCharsets.UTF_8), truncated);
        }
        catch (IOException e) {
            throw new IllegalStateException("Cannot read command output", e);
        }
    }

    private static String message(Throwable failure)
    {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    public record Result(int exitCode, String output, boolean truncated)
    {
        public Result
        {
            requireNonNull(output, "output is null");
        }

        public static Result failed(String reason)
        {
            return new Result(-1, reason, false);
        }

        public String requireOutput(Function<String, ? extends RuntimeException> failure)
        {
            if (!successful()) {
                throw failure.apply(output.isBlank() ? "Git exited with status " + exitCode : output.strip());
            }
            if (truncated) {
                throw failure.apply("Git output was truncated");
            }
            return output;
        }

        public boolean successful()
        {
            return exitCode == 0;
        }
    }

    private record CapturedOutput(String text, boolean truncated) {}
}

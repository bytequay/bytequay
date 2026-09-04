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
package com.bytequay.app.git;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Component
public class GitCommandRunner
{
    public static final int MAXIMUM_OUTPUT_BYTES = 5 * 1024 * 1024;
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(2);

    private final String executable;

    public GitCommandRunner()
    {
        this("git");
    }

    public GitCommandRunner(String executable)
    {
        this.executable = requireNonNull(executable, "executable is null");
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
        requireNonNull(worktree, "worktree is null");
        requireNonNull(timeout, "timeout is null");
        checkArgument(!timeout.isZero() && !timeout.isNegative(), "timeout is not positive");
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
            return execute(builder, timeout, MAXIMUM_OUTPUT_BYTES, "Git");
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.failed("Git was interrupted");
        }
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
        ImmutableList.Builder<String> arguments =
                ImmutableList.builderWithExpectedSize(1 + (2 * markers.size()));
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
        return execute(builder, timeout, maximumOutputBytes, "Command");
    }

    private static Result execute(ProcessBuilder builder, Duration timeout, int maximumOutputBytes, String name)
            throws InterruptedException
    {
        Process process;
        try {
            builder.redirectErrorStream(true);
            process = builder.start();
            process.getOutputStream().close();
        }
        catch (IOException | RuntimeException failure) {
            return Result.failed(name + " could not start: " + message(failure));
        }

        CompletableFuture<CapturedOutput> output = new CompletableFuture<>();
        Thread.ofVirtual().name("bytequay-command-output").start(() -> {
            try {
                output.complete(capture(process.getInputStream(), maximumOutputBytes));
            }
            catch (RuntimeException failure) {
                output.completeExceptionally(failure);
            }
        });
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                terminate(process);
                return Result.failed(name + " timed out");
            }
            CapturedOutput captured = output.get(DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return new Result(process.exitValue(), captured.text(), captured.truncated());
        }
        catch (InterruptedException e) {
            process.destroyForcibly();
            throw e;
        }
        catch (ExecutionException | TimeoutException e) {
            process.destroyForcibly();
            return Result.failed(name + " output could not be read: " + message(e));
        }
    }

    private static void terminate(Process process)
            throws InterruptedException
    {
        process.destroy();
        if (!process.waitFor(DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
        }
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

        public boolean successful()
        {
            return exitCode == 0;
        }
    }

    private record CapturedOutput(String text, boolean truncated) {}
}

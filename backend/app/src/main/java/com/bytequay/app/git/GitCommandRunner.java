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
import static java.util.Objects.requireNonNull;

@Component
public final class GitCommandRunner
{
    public static final int MAXIMUM_OUTPUT_BYTES = 1024 * 1024;
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
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectErrorStream(true);
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            builder.environment().put("LC_ALL", "C");
            builder.environment().putAll(environment);
            process = builder.start();
            process.getOutputStream().close();
        }
        catch (IOException | RuntimeException failure) {
            return Result.failed("Git could not start: " + message(failure));
        }

        CompletableFuture<CapturedOutput> output = new CompletableFuture<>();
        Thread.ofVirtual().name("bytequay-git-output").start(() -> {
            try {
                output.complete(capture(process.getInputStream()));
            }
            catch (RuntimeException failure) {
                output.completeExceptionally(failure);
            }
        });
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return Result.failed("Git timed out");
            }
            CapturedOutput captured = output.get(DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return new Result(process.exitValue(), captured.text(), captured.truncated());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return Result.failed("Git was interrupted");
        }
        catch (ExecutionException | TimeoutException e) {
            process.destroyForcibly();
            return Result.failed("Git output could not be read: " + message(e));
        }
    }

    private static CapturedOutput capture(InputStream input)
    {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            boolean truncated = false;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                int remaining = MAXIMUM_OUTPUT_BYTES - output.size();
                if (remaining > 0) {
                    output.write(buffer, 0, Math.min(read, remaining));
                }
                truncated |= read > remaining;
            }
            return new CapturedOutput(output.toString(StandardCharsets.UTF_8), truncated);
        }
        catch (IOException e) {
            throw new IllegalStateException("Cannot read Git output", e);
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

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
package com.bytequay.agent.cli.process;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.util.Objects.requireNonNull;

/** Helpers shared by the Claude and Codex CLI drivers. */
public final class ProviderCli
{
    private ProviderCli() {}

    /**
     * Runs a short command such as a version or status probe. A run that times out, fails, or leaves its process tree
     * unverified exits -1 with the reason in stderr.
     *
     * @param label names the CLI in those reasons, such as {@code Codex CLI}
     */
    public static Result run(ProcessSupervisor supervisor, ProcessSpec spec, String label)
            throws InterruptedException
    {
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        ManagedProcess process = supervisor.prepare(spec).start((stream, bytes) -> append(stream == ProcessStream.STDOUT ? stdout : stderr, bytes));
        try {
            ProcessExit result = process.exit().toCompletableFuture().get(spec.maximumRuntime().toMillis() + 2000, TimeUnit.MILLISECONDS);
            boolean truncated = result.stdoutTruncated() || result.stderrTruncated();
            if (result.treeTermination() != ProcessExit.TreeTermination.VERIFIED_STOPPED) {
                return new Result(-1, text(stdout), label + " process tree could not be verified stopped", false, truncated);
            }
            supervisor.acknowledge(process.launchId());
            if (result.reason() == ProcessExit.Reason.TIMED_OUT) {
                return new Result(-1, text(stdout), label + " command timed out", true, truncated);
            }
            return new Result(result.exitCode(), text(stdout), text(stderr), false, truncated);
        }
        catch (TimeoutException e) {
            process.cancel();
            return new Result(-1, text(stdout), label + " command timed out", true, false);
        }
        catch (ExecutionException e) {
            process.cancel();
            return new Result(-1, text(stdout), label + " command failed: " + e.getMessage(), false, false);
        }
    }

    /** The named variables and Git's own that are set in {@code processEnvironment}. */
    public static Map<String, String> inheritedEnvironment(Map<String, String> processEnvironment, Set<String> names)
    {
        ImmutableMap.Builder<String, String> environment = ImmutableMap.builder();
        for (String name : ImmutableSet.<String>builder().addAll(names).addAll(GitCliEnvironment.variables()).build()) {
            String value = processEnvironment.get(name);
            if (value != null) {
                environment.put(name, value);
            }
        }
        return environment.buildOrThrow();
    }

    /** The innermost cause's message, or its type when it has none. */
    public static String failureMessage(Throwable failure)
    {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    private static void append(StringBuilder output, ByteBuffer bytes)
    {
        synchronized (output) {
            output.append(StandardCharsets.UTF_8.decode(bytes));
        }
    }

    private static String text(StringBuilder output)
    {
        synchronized (output) {
            return output.toString().trim();
        }
    }

    public record Result(int exitCode, String stdout, String stderr, boolean timedOut, boolean truncated)
    {
        public Result
        {
            requireNonNull(stdout, "stdout is null");
            requireNonNull(stderr, "stderr is null");
        }

        /** Both streams, skipping a blank one. */
        public String combinedOutput()
        {
            if (stdout.isBlank()) {
                return stderr;
            }
            if (stderr.isBlank()) {
                return stdout;
            }
            return stdout + "\n" + stderr;
        }
    }
}

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
package com.bytequay.app.service.codegraph;

import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;

/** Thin wrapper around the external {@code codegraph} CLI. */
@Component
public class CodeGraphRunner
{
    private static final long PROBE_TIMEOUT_SECONDS = 5;
    private static final long INDEX_TIMEOUT_SECONDS = 600;
    private static final long QUERY_TIMEOUT_SECONDS = 120;

    public boolean isAvailable()
    {
        try {
            return run(List.of("codegraph", "version"), null, PROBE_TIMEOUT_SECONDS).exitCode() == 0;
        }
        catch (IOException e) {
            return false;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void init(Path project)
            throws IOException, InterruptedException
    {
        run(List.of("codegraph", "init", project.toString()), project, INDEX_TIMEOUT_SECONDS)
                .requireSuccess();
    }

    public void sync(Path project)
            throws IOException, InterruptedException
    {
        run(List.of("codegraph", "sync", project.toString()), project, INDEX_TIMEOUT_SECONDS)
                .requireSuccess();
    }

    public void rebuild(Path project)
            throws IOException, InterruptedException
    {
        run(List.of("codegraph", "index", project.toString(), "--force", "--quiet"),
                project, INDEX_TIMEOUT_SECONDS)
                .requireSuccess();
    }

    public String explore(Path project, String query)
            throws IOException, InterruptedException
    {
        CodeGraphProcessResult result = run(
                List.of("codegraph", "explore", query),
                project,
                QUERY_TIMEOUT_SECONDS);
        result.requireSuccess();
        return result.stdout().isBlank() ? result.stderr().strip() : result.stdout().strip();
    }

    private CodeGraphProcessResult run(List<String> args, Path workingDir, long timeoutSeconds)
            throws IOException, InterruptedException
    {
        requireNonNull(args, "args is null");
        ProcessBuilder pb = new ProcessBuilder(args);
        if (workingDir != null) {
            pb.directory(workingDir.toFile());
        }
        pb.environment().put("LC_ALL", "C");
        Process process = pb.start();
        // Captured by the drain virtual threads; the join() below establishes the
        // happens-before that makes these writes visible here (no shared map to leak).
        String[] captured = new String[2];
        Thread stdoutDrain = Thread.ofVirtual().start(
                () -> captured[0] = drain(process.getInputStream()));
        Thread stderrDrain = Thread.ofVirtual().start(
                () -> captured[1] = drain(process.getErrorStream()));
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            stdoutDrain.interrupt();
            stderrDrain.interrupt();
            throw new IOException("codegraph " + args + " timed out after " + timeoutSeconds + "s");
        }
        stdoutDrain.join(5_000);
        stderrDrain.join(5_000);
        return new CodeGraphProcessResult(
                process.exitValue(),
                captured[0] == null ? "" : captured[0],
                captured[1] == null ? "" : captured[1],
                ImmutableList.copyOf(args));
    }

    private static String drain(InputStream in)
    {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException ignored) {
            return "";
        }
    }

    record CodeGraphProcessResult(
            int exitCode,
            String stdout,
            String stderr,
            List<String> args)
    {
        void requireSuccess()
        {
            if (exitCode != 0) {
                String detail = stderr == null || stderr.isBlank() ? stdout : stderr;
                throw new CodeGraphCommandException(
                        "codegraph command failed (" + String.join(" ", args) + "): " + detail.strip());
            }
        }
    }

    public static class CodeGraphCommandException
            extends RuntimeException
    {
        public CodeGraphCommandException(String message)
        {
            super(message);
        }
    }
}

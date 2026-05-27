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
package com.bytequay.app.service.local;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Runs one bounded shell command for the {@code run_shell} agent
 * tool. The escape-hatch contract is intentionally narrow:
 *
 * <ul>
 *   <li>The command is parsed as space-separated argv — no shell
 *       interpretation. Pipes, redirects, command substitution, and
 *       background forks are refused so the human approving the call
 *       has a clear, parseable cmdline in the prompt.</li>
 *   <li>{@code working dir} is the active task's worktreePath. The
 *       runner won't accept an absolute working dir override; the
 *       agent's reach is fenced to its own task.</li>
 *   <li>{@code timeout} is 60 seconds. Long-running commands are out
 *       of scope for the escape hatch; the agent has request_review
 *       for "ship the work, the build will take a while."</li>
 *   <li>{@code output cap} is 256 KB combined stdout + stderr.
 *       Truncates with a marker rather than returning all of /var/log
 *       to the model context.</li>
 * </ul>
 *
 * Two well-known operators are blocked regardless of position because
 * supporting them would defeat the "human reads exactly this cmdline"
 * contract: {@code | & ; > < ` $( $(())}.
 */
@Component
public class ShellRunner
{
    private static final Logger log = LoggerFactory.getLogger(ShellRunner.class);

    /** Output bytes the runner is willing to ship back. Pegged at
     *  256 KB so a runaway `cat very-large-file` doesn't blow past
     *  the model's context budget. */
    public static final int MAX_OUTPUT_BYTES = 256 * 1024;

    /** Wall-clock cap on one command. 60 s is enough for a unit-test
     *  spot-check or a quick build probe; long jobs belong with the
     *  test-runner abstraction (a separate axis). */
    public static final long TIMEOUT_SECONDS = 60L;

    /** Characters that imply shell features the runner refuses. Each
     *  is checked once across the whole command text — a literal in
     *  the middle of an argument still blocks the call. The agent
     *  can request_review / next_task if it genuinely needs a shell
     *  pipeline. */
    private static final Pattern FORBIDDEN_PATTERN = Pattern.compile("[|&;><`]|\\$\\(");

    public Result run(Path workingDir, String command)
            throws InterruptedException
    {
        if (command == null || command.isBlank()) {
            return Result.refused("command is blank — nothing to run");
        }
        if (FORBIDDEN_PATTERN.matcher(command).find()) {
            return Result.refused(
                    "command contains a forbidden shell operator (| & ; > < ` $(). "
                            + "run_shell only runs plain argv — no pipes / redirects / "
                            + "command substitution / background forks. If you need a "
                            + "pipeline, run each stage separately or use a test runner.");
        }
        List<String> argv = List.of(command.trim().split("\\s+"));
        return runArgv(workingDir, argv, TIMEOUT_SECONDS, MAX_OUTPUT_BYTES);
    }

    /**
     * Run a pre-validated argv directly with caller-supplied
     * timeout and output cap. Skips the forbidden-operator check —
     * callers building argv themselves (e.g. the test runner) own
     * the trust boundary.
     */
    public Result runArgv(Path workingDir, List<String> argv, long timeoutSeconds, int maxOutputBytes)
            throws InterruptedException
    {
        if (argv == null || argv.isEmpty()) {
            return Result.refused("argv is empty — nothing to run");
        }
        ProcessBuilder pb = new ProcessBuilder(argv)
                .directory(workingDir.toFile())
                .redirectErrorStream(true);
        Process process;
        try {
            process = pb.start();
        }
        catch (IOException e) {
            return Result.refused("spawn failed: " + e.getMessage());
        }
        StringBuilder out = new StringBuilder();
        boolean truncated = false;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int read;
            while ((read = reader.read(buf)) != -1) {
                if (out.length() + read >= maxOutputBytes) {
                    int room = Math.max(0, maxOutputBytes - out.length());
                    out.append(buf, 0, room);
                    out.append("\n…[truncated at ").append(maxOutputBytes).append(" bytes]\n");
                    truncated = true;
                    break;
                }
                out.append(buf, 0, read);
            }
        }
        catch (IOException e) {
            log.warn("ShellRunner read failed on {}: {}", argv, e.getMessage());
        }
        boolean exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!exited) {
            process.destroy();
            process.waitFor(2, TimeUnit.SECONDS);
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            return new Result(false, -1, out.toString(), true, "timed out after "
                    + timeoutSeconds + "s");
        }
        return new Result(true, process.exitValue(), out.toString(), truncated, null);
    }

    /**
     * Outcome of one run. {@code ran} reflects whether the process
     * actually started; failed validation / spawn / timeout returns
     * a non-null {@code error}.
     */
    public record Result(boolean ran, int exitCode, String output, boolean truncated, String error)
    {
        public static Result refused(String reason)
        {
            return new Result(false, -1, "", false, reason);
        }
    }
}

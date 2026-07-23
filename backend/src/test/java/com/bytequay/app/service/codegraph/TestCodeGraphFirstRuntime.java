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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TestCodeGraphFirstRuntime
{
    @Test
    void structuredRedirectsFailOpenAndResetOnTheNextTurn()
    {
        String threadId = unique("thread");
        String agentKey = unique("agent");
        ProcessBuilder process = new ProcessBuilder("/usr/bin/true");

        CodeGraphFirstRuntime.prepare(process, threadId, agentKey);

        assertThat(CodeGraphFirstRuntime.shouldRedirect(threadId, agentKey)).isTrue();
        assertThat(CodeGraphFirstRuntime.shouldRedirect(threadId, agentKey)).isTrue();
        assertThat(CodeGraphFirstRuntime.shouldRedirect(threadId, agentKey)).isFalse();
        assertThat(CodeGraphFirstRuntime.finishTurn(threadId, agentKey))
                .isEqualTo(new CodeGraphFirstRuntime.Metrics(2, 0, 0, 0, 0, 1));

        CodeGraphFirstRuntime.prepare(process, threadId, agentKey);
        assertThat(CodeGraphFirstRuntime.shouldRedirect(threadId, agentKey)).isTrue();
        assertThat(CodeGraphFirstRuntime.finishTurn(threadId, agentKey).redirected()).isEqualTo(1);
    }

    @Test
    void aCodeGraphAttemptUnlocksNativeSearch()
    {
        String threadId = unique("thread");
        String agentKey = unique("agent");
        ProcessBuilder process = new ProcessBuilder("/usr/bin/true");
        CodeGraphFirstRuntime.prepare(process, threadId, agentKey);

        CodeGraphFirstRuntime.markAttempted(threadId, agentKey);
        CodeGraphFirstRuntime.markSucceeded(threadId, agentKey);

        assertThat(CodeGraphFirstRuntime.shouldRedirect(threadId, agentKey)).isFalse();
        assertThat(CodeGraphFirstRuntime.finishTurn(threadId, agentKey))
                .isEqualTo(new CodeGraphFirstRuntime.Metrics(0, 1, 1, 0, 1, 0));
    }

    @Test
    void installsManagedCommandShimsAheadOfTheExistingPath()
    {
        String threadId = unique("thread");
        String agentKey = unique("agent");
        ProcessBuilder process = new ProcessBuilder("/usr/bin/true");
        String originalPath = process.environment().getOrDefault("PATH", "");

        CodeGraphFirstRuntime.prepare(process, threadId, agentKey);

        String updatedPath = process.environment().get("PATH");
        assertThat(updatedPath).isNotNull().endsWith(File.pathSeparator + originalPath);
        Path shimDirectory = Path.of(updatedPath.substring(0, updatedPath.indexOf(File.pathSeparator)));
        assertThat(shimDirectory.resolve("rg")).isExecutable();
        assertThat(shimDirectory.resolve("grep")).isExecutable();
        assertThat(process.environment()).containsKey("BYTEQUAY_CODEGRAPH_STATE_DIR");
        assertThat(process.environment()).containsEntry(
                "BYTEQUAY_CODEGRAPH_SHIM_DIR", shimDirectory.toString());
    }

    @Test
    void shellShimBlocksBroadRgButAllowsExactAndPostGraphChecks(@TempDir Path checkout)
            throws Exception
    {
        // The shim's PATH ordering is exercised through a zsh login shell to
        // mirror the provider command shape. Skip where zsh isn't installed
        // (e.g. a stock Linux CI runner) rather than fail on a missing binary.
        assumeTrue(new File("/bin/zsh").canExecute(), "/bin/zsh not available");
        // The shim resolves the real ripgrep before classifying; without it the
        // shim exits 127, not the blocked-broad-search 2 this test asserts. Skip
        // where ripgrep is absent (e.g. some CI runners) instead of failing.
        assumeTrue(hasExecutable("rg"), "ripgrep (rg) not installed");
        Files.writeString(checkout.resolve("Known.java"), "class Known {}\n", StandardCharsets.UTF_8);
        String threadId = unique("thread");
        String agentKey = unique("agent");
        ProcessBuilder broad = shell(checkout, "rg MissingSymbol .");
        CodeGraphFirstRuntime.prepare(broad, threadId, agentKey);

        CommandResult first = run(broad);
        CommandResult second = run(broad);
        CommandResult fallback = run(broad);

        assertThat(first.exitCode()).isEqualTo(2);
        assertThat(first.stderr()).contains(
                "CodeGraph-first", "mcp__bytequay__codegraph_explore", "rg MissingSymbol");
        assertThat(second.exitCode()).isEqualTo(2);
        assertThat(fallback.exitCode()).isNotEqualTo(2);
        assertThat(CodeGraphFirstRuntime.finishTurn(threadId, agentKey))
                .isEqualTo(new CodeGraphFirstRuntime.Metrics(2, 0, 0, 0, 0, 1));

        CodeGraphFirstRuntime.prepare(broad, threadId, agentKey);
        CodeGraphFirstRuntime.markAttempted(threadId, agentKey);
        assertThat(run(broad).exitCode()).isNotEqualTo(2);

        ProcessBuilder exact = shell(checkout, "rg -F MissingSymbol .");
        CodeGraphFirstRuntime.prepare(exact, unique("thread"), unique("agent"));
        assertThat(run(exact).exitCode()).isNotEqualTo(2);
    }

    private static ProcessBuilder shell(Path directory, String command)
    {
        // Match the provider command shape shown by Codex. In particular,
        // verify that zsh's login startup keeps the managed shim first in PATH.
        ProcessBuilder process = new ProcessBuilder("/bin/zsh", "-lc", command);
        process.directory(directory.toFile());
        return process;
    }

    private static CommandResult run(ProcessBuilder process)
            throws Exception
    {
        Process started = process.start();
        started.getOutputStream().close();
        boolean finished = started.waitFor(5, TimeUnit.SECONDS);
        if (!finished) {
            started.destroyForcibly();
        }
        String stdout = new String(started.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(started.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(finished).as("guarded shell command finished").isTrue();
        return new CommandResult(started.exitValue(), stdout, stderr);
    }

    private static String unique(String prefix)
    {
        return prefix + "-" + UUID.randomUUID();
    }

    /** True when a real {@code name} binary is on PATH (a shell function or
     *  alias does not count — the shim runs under /bin/sh). */
    private static boolean hasExecutable(String name)
    {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String directory : path.split(File.pathSeparator)) {
            if (!directory.isEmpty() && new File(directory, name).canExecute()) {
                return true;
            }
        }
        return false;
    }

    private record CommandResult(int exitCode, String stdout, String stderr) {}
}

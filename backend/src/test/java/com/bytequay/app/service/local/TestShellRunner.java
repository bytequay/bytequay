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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Process-spawning unit tests for {@link ShellRunner}. Each case
 * runs a tiny POSIX-portable command (echo, false, sleep, sh -c
 * is intentionally avoided to keep the runner's "plain argv" stance
 * honest in the test surface too).
 */
class TestShellRunner
{
    private final ShellRunner runner = new ShellRunner();

    @Test
    void blankCommandIsRefused(@TempDir Path worktree)
            throws InterruptedException
    {
        ShellRunner.Result result = runner.run(worktree, "");

        assertThat(result.ran()).isFalse();
        assertThat(result.error()).contains("blank");
    }

    @Test
    void pipeIsRefused(@TempDir Path worktree)
            throws InterruptedException
    {
        ShellRunner.Result result = runner.run(worktree, "echo hi | cat");

        assertThat(result.ran()).isFalse();
        assertThat(result.error()).contains("forbidden shell operator");
    }

    @Test
    void redirectIsRefused(@TempDir Path worktree)
            throws InterruptedException
    {
        ShellRunner.Result result = runner.run(worktree, "echo hi > out");

        assertThat(result.ran()).isFalse();
        assertThat(result.error()).contains("forbidden shell operator");
    }

    @Test
    void backgroundForkIsRefused(@TempDir Path worktree)
            throws InterruptedException
    {
        ShellRunner.Result result = runner.run(worktree, "echo hi &");

        assertThat(result.ran()).isFalse();
        assertThat(result.error()).contains("forbidden shell operator");
    }

    @Test
    void commandSubstitutionIsRefused(@TempDir Path worktree)
            throws InterruptedException
    {
        ShellRunner.Result result = runner.run(worktree, "echo $(whoami)");

        assertThat(result.ran()).isFalse();
        assertThat(result.error()).contains("forbidden shell operator");
    }

    @Test
    void simpleEchoRunsAndCapturesOutput(@TempDir Path worktree)
            throws InterruptedException
    {
        ShellRunner.Result result = runner.run(worktree, "echo bytequay-shell-test");

        assertThat(result.ran()).isTrue();
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("bytequay-shell-test");
        assertThat(result.truncated()).isFalse();
        assertThat(result.error()).isNull();
    }

    @Test
    void argvOverloadSkipsTheForbiddenCheck(@TempDir Path worktree)
            throws InterruptedException
    {
        // run() would refuse a command containing "|" — runArgv with
        // a pre-built argv accepts whatever is passed because the
        // caller owns the trust boundary. Use printf with a literal
        // pipe in the argument to prove the bytes flow through.
        ShellRunner.Result result = runner.runArgv(
                worktree, List.of("printf", "a|b"), 5L, 4096);

        assertThat(result.ran()).isTrue();
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).isEqualTo("a|b");
    }

    @Test
    void nonZeroExitIsCaptured(@TempDir Path worktree)
            throws InterruptedException
    {
        ShellRunner.Result result = runner.runArgv(
                worktree, List.of("false"), 5L, 4096);

        assertThat(result.ran()).isTrue();
        assertThat(result.exitCode()).isNotZero();
    }

    @Test
    void emptyArgvIsRefused(@TempDir Path worktree)
            throws InterruptedException
    {
        ShellRunner.Result result = runner.runArgv(worktree, List.of(), 5L, 4096);

        assertThat(result.ran()).isFalse();
        assertThat(result.error()).contains("argv is empty");
    }

    @Test
    void argvOverloadAppliesEnvironmentOverrides(@TempDir Path worktree)
            throws InterruptedException
    {
        ShellRunner.Result result = runner.runArgv(
                worktree, List.of("env"), Map.of("BYTEQUAY_CI_VERIFY", "enabled"), 5L, 4096);

        assertThat(result.ran()).isTrue();
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("BYTEQUAY_CI_VERIFY=enabled");
    }

    @Test
    void timeoutDestroysTheProcessAndReturnsTimedOut(@TempDir Path worktree)
            throws InterruptedException
    {
        // Sleep for longer than the configured timeout; the runner
        // should destroy the child and surface a timed-out error
        // string. 1-second timeout against a 5-second sleep is
        // robust on a busy CI box.
        ShellRunner.Result result = runner.runArgv(
                worktree, List.of("sleep", "5"), 1L, 4096);

        assertThat(result.ran()).isFalse();
        assertThat(result.error()).contains("timed out");
    }
}

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
package com.bytequay.app.service.checks;

import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRCheck;
import com.bytequay.app.service.local.ShellRunner;
import com.bytequay.app.service.local.TestRunnerDetector;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.localpr.PRSyncService;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * The {@code ValidationCheck} that actually runs the repo's tests (design
 * doc slice 4 — "local CI"): auto-detects the ecosystem the same way the
 * {@code run_checks} agent tool does and runs its canonical verify command,
 * then records the outcome on the task's local PR (materialising it first if
 * it doesn't exist yet — {@link PRSyncService#syncFromTask} is
 * idempotent) so the PR page's Tests card shows real runs instead of only
 * whatever the agent recorded manually via {@code record_pr_check}.
 *
 * <p>A failure here feeds into the durable validation flow — this class only
 * supplies one round's verdict, same contract as any other
 * {@link ValidationCheck}.
 *
 * <p>No repo-config file (e.g. a {@code .bytequay/tests.yml}) is read yet —
 * {@link TestRunnerDetector}'s ecosystem-marker detection is the only source
 * of the command, same as the agent-facing tool. A per-workspace override is
 * explicitly deferred (see the detector's own docs) to the work-model
 * integration, not this slice.
 */
@Component
public class RepoTestValidationCheck
        implements ValidationCheck
{
    /** Matches the {@code run_checks} agent tool's bounds — a full test
     *  suite can run long, and 256 KB is plenty for a failure's tail. */
    private static final long TIMEOUT_SECONDS = 300L;
    private static final int MAX_OUTPUT_BYTES = ShellRunner.MAX_OUTPUT_BYTES;

    /** Detail is capped to this many trailing characters of the run's
     *  output — enough to see the failing assertion without flooding the
     *  fix-turn prompt. */
    private static final int DETAIL_TAIL_CHARS = 1000;

    private final TestRunnerDetector detector;
    private final ShellRunner shellRunner;
    private final PRService prService;
    private final PRSyncService prSync;

    public RepoTestValidationCheck(
            TestRunnerDetector detector,
            ShellRunner shellRunner,
            PRService prService,
            PRSyncService prSync)
    {
        this.detector = requireNonNull(detector, "detector is null");
        this.shellRunner = requireNonNull(shellRunner, "shellRunner is null");
        this.prService = requireNonNull(prService, "prService is null");
        this.prSync = requireNonNull(prSync, "prSync is null");
    }

    @Override
    public List<ValidationFailure> run(String taskId, Path worktree)
    {
        Optional<TestRun> run = runWithoutRecording(worktree);
        if (run.isEmpty()) {
            return List.of();
        }
        TestRun result = run.orElseThrow();
        if (Thread.currentThread().isInterrupted()) {
            return result.failures();
        }
        recordCheck(
                taskId, result.ecosystem(), result.passed(), result.durationMs());
        return result.failures();
    }

    /** Runs the detected test command without projecting a PR check. */
    public Optional<TestRun> runWithoutRecording(Path worktree)
    {
        Optional<TestRunnerDetector.Detected> detected = detector.detect(worktree);
        if (detected.isEmpty()) {
            return Optional.empty();
        }
        TestRunnerDetector.Detected runner = detected.get();
        Instant start = Instant.now();
        ShellRunner.Result result;
        try {
            result = shellRunner.runArgv(worktree, runner.argv(), TIMEOUT_SECONDS, MAX_OUTPUT_BYTES);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Instant completed = Instant.now();
            return Optional.of(new TestRun(
                    runner.ecosystem(), false, elapsed(start, completed),
                    List.of(new ValidationFailure(
                            "test", "interrupted running "
                                    + runner.ecosystem() + " tests")),
                    start.toEpochMilli(), completed.toEpochMilli()));
        }
        Instant completed = Instant.now();
        long durationMs = elapsed(start, completed);
        boolean passed = result.ran() && result.exitCode() == 0;
        List<ValidationFailure> failures;
        if (passed) {
            failures = List.of();
        }
        else {
            String detail = result.ran()
                    ? runner.ecosystem() + " tests failed (exit "
                            + result.exitCode() + "): " + tail(result.output())
                    : runner.ecosystem() + " tests failed to run: " + result.error();
            failures = List.of(new ValidationFailure("test", detail));
        }
        return Optional.of(new TestRun(
                runner.ecosystem(), passed, durationMs, failures,
                start.toEpochMilli(), completed.toEpochMilli()));
    }

    private void recordCheck(String taskId, String ecosystem, boolean passed, long durationMs)
    {
        Optional<PR> pr = prSync.syncFromTask(taskId);
        pr.ifPresent(p -> prService.recordCheck(
                p.id(), PRCheck.KIND_LOCAL, ecosystem + " test",
                passed ? PRCheck.STATUS_PASSED : PRCheck.STATUS_FAILED, durationMs));
    }

    private static String tail(String output)
    {
        String trimmed = output == null ? "" : output.strip();
        return trimmed.length() <= DETAIL_TAIL_CHARS
                ? trimmed
                : "…" + trimmed.substring(trimmed.length() - DETAIL_TAIL_CHARS);
    }

    private static long elapsed(Instant start, Instant completed)
    {
        return Math.max(0, Duration.between(start, completed).toMillis());
    }

    public record TestRun(
            String ecosystem,
            boolean passed,
            long durationMs,
            List<ValidationFailure> failures,
            long startedAtMs,
            long completedAtMs)
    {
        public TestRun
        {
            requireNonNull(ecosystem, "ecosystem is null");
            failures = List.copyOf(requireNonNull(failures, "failures is null"));
            if (ecosystem.isBlank() || durationMs < 0 || startedAtMs < 0
                    || completedAtMs < startedAtMs
                    || passed != failures.isEmpty()) {
                throw new IllegalArgumentException("invalid test run");
            }
        }
    }
}

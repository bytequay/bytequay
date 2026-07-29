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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestRepoTestValidationCheck
{
    private final TestRunnerDetector detector = mock(TestRunnerDetector.class);
    private final ShellRunner shellRunner = mock(ShellRunner.class);
    private final PRService prService = mock(PRService.class);
    private final PRSyncService prSync = mock(PRSyncService.class);
    private final RepoTestValidationCheck check =
            new RepoTestValidationCheck(detector, shellRunner, prService, prSync);

    private final Path worktree = Path.of("/tmp/wt");

    @AfterEach
    void clearInterruptFlag()
    {
        Thread.interrupted(); // in case the interrupted-path test left it set.
    }

    @Test
    void noRecognisedEcosystemPassesVacuously()
            throws InterruptedException
    {
        when(detector.detect(worktree)).thenReturn(Optional.empty());

        List<ValidationFailure> failures = check.run("t1", worktree);

        assertThat(failures).isEmpty();
        verify(shellRunner, never()).runArgv(any(), any(), anyLong(), any(Integer.class));
        verify(prSync, never()).syncFromTask(any());
    }

    @Test
    void passingRunRecordsAPassedCheckAndReturnsNoFailures()
            throws InterruptedException
    {
        when(detector.detect(worktree))
                .thenReturn(Optional.of(new TestRunnerDetector.Detected("maven", List.of("mvn", "-q", "verify"))));
        when(shellRunner.runArgv(eq(worktree), any(), anyLong(), any(Integer.class)))
                .thenReturn(new ShellRunner.Result(true, 0, "BUILD SUCCESS", false, null));
        PR pr = pr("pr1");
        when(prSync.syncFromTask("t1")).thenReturn(Optional.of(pr));

        List<ValidationFailure> failures = check.run("t1", worktree);

        assertThat(failures).isEmpty();
        verify(prService).recordCheck(
                eq("pr1"), eq(PRCheck.KIND_LOCAL), eq("maven test"), eq(PRCheck.STATUS_PASSED), anyLong());
    }

    @Test
    void failingRunRecordsAFailedCheckAndReturnsAFailure()
            throws InterruptedException
    {
        when(detector.detect(worktree))
                .thenReturn(Optional.of(new TestRunnerDetector.Detected("npm", List.of("npm", "test"))));
        when(shellRunner.runArgv(eq(worktree), any(), anyLong(), any(Integer.class)))
                .thenReturn(new ShellRunner.Result(true, 1, "1 failing\nAssertionError: expected true", false, null));
        PR pr = pr("pr1");
        when(prSync.syncFromTask("t1")).thenReturn(Optional.of(pr));

        List<ValidationFailure> failures = check.run("t1", worktree);

        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).source()).isEqualTo("test");
        assertThat(failures.get(0).detail()).contains("npm").contains("AssertionError");
        verify(prService).recordCheck(
                eq("pr1"), eq(PRCheck.KIND_LOCAL), eq("npm test"), eq(PRCheck.STATUS_FAILED), anyLong());
    }

    @Test
    void skipsRecordingWhenNoPrExistsYet()
            throws InterruptedException
    {
        when(detector.detect(worktree))
                .thenReturn(Optional.of(new TestRunnerDetector.Detected("maven", List.of("mvn", "-q", "verify"))));
        when(shellRunner.runArgv(eq(worktree), any(), anyLong(), any(Integer.class)))
                .thenReturn(new ShellRunner.Result(true, 0, "BUILD SUCCESS", false, null));
        when(prSync.syncFromTask("t1")).thenReturn(Optional.empty());

        List<ValidationFailure> failures = check.run("t1", worktree);

        assertThat(failures).isEmpty();
        verify(prService, never()).recordCheck(any(), any(), any(), any(), any());
    }

    @Test
    void detachedRunReturnsTypedEvidenceWithoutProjectingAPrCheck()
            throws InterruptedException
    {
        when(detector.detect(worktree)).thenReturn(Optional.of(
                new TestRunnerDetector.Detected(
                        "maven", List.of("mvn", "-q", "verify"))));
        when(shellRunner.runArgv(eq(worktree), any(), anyLong(), any(Integer.class)))
                .thenReturn(new ShellRunner.Result(
                        true, 0, "BUILD SUCCESS", false, null));

        RepoTestValidationCheck.TestRun run =
                check.runWithoutRecording(worktree).orElseThrow();

        assertThat(run.ecosystem()).isEqualTo("maven");
        assertThat(run.passed()).isTrue();
        assertThat(run.failures()).isEmpty();
        verify(prSync, never()).syncFromTask(any());
        verify(prService, never()).recordCheck(any(), any(), any(), any(), any());
    }

    @Test
    void interruptedRunReturnsAFailureAndRestoresTheInterruptFlag()
            throws InterruptedException
    {
        when(detector.detect(worktree))
                .thenReturn(Optional.of(new TestRunnerDetector.Detected("maven", List.of("mvn", "-q", "verify"))));
        when(shellRunner.runArgv(eq(worktree), any(), anyLong(), any(Integer.class)))
                .thenThrow(new InterruptedException());

        List<ValidationFailure> failures = check.run("t1", worktree);

        assertThat(failures).hasSize(1);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    private static PR pr(String id)
    {
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        return PR.create(id, "t1", "dev/x", "main", "T", "", now);
    }
}

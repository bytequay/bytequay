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

import com.bytequay.app.domain.LocalPR;
import com.bytequay.app.domain.LocalPRCheck;
import com.bytequay.app.service.local.ShellRunner;
import com.bytequay.app.service.local.TestRunnerDetector;
import com.bytequay.app.service.localpr.LocalPRService;
import com.bytequay.app.service.localpr.LocalPRSyncService;
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
    private final LocalPRService localPr = mock(LocalPRService.class);
    private final LocalPRSyncService localPrSync = mock(LocalPRSyncService.class);
    private final RepoTestValidationCheck check =
            new RepoTestValidationCheck(detector, shellRunner, localPr, localPrSync);

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
        verify(localPrSync, never()).syncFromTask(any());
    }

    @Test
    void passingRunRecordsAPassedCheckAndReturnsNoFailures()
            throws InterruptedException
    {
        when(detector.detect(worktree))
                .thenReturn(Optional.of(new TestRunnerDetector.Detected("maven", List.of("mvn", "-q", "verify"))));
        when(shellRunner.runArgv(eq(worktree), any(), anyLong(), any(Integer.class)))
                .thenReturn(new ShellRunner.Result(true, 0, "BUILD SUCCESS", false, null));
        LocalPR pr = localPr("pr1");
        when(localPrSync.syncFromTask("t1")).thenReturn(Optional.of(pr));

        List<ValidationFailure> failures = check.run("t1", worktree);

        assertThat(failures).isEmpty();
        verify(localPr).recordCheck(
                eq("pr1"), eq(LocalPRCheck.KIND_LOCAL), eq("maven test"), eq(LocalPRCheck.STATUS_PASSED), anyLong());
    }

    @Test
    void failingRunRecordsAFailedCheckAndReturnsAFailure()
            throws InterruptedException
    {
        when(detector.detect(worktree))
                .thenReturn(Optional.of(new TestRunnerDetector.Detected("npm", List.of("npm", "test"))));
        when(shellRunner.runArgv(eq(worktree), any(), anyLong(), any(Integer.class)))
                .thenReturn(new ShellRunner.Result(true, 1, "1 failing\nAssertionError: expected true", false, null));
        LocalPR pr = localPr("pr1");
        when(localPrSync.syncFromTask("t1")).thenReturn(Optional.of(pr));

        List<ValidationFailure> failures = check.run("t1", worktree);

        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).source()).isEqualTo("test");
        assertThat(failures.get(0).detail()).contains("npm").contains("AssertionError");
        verify(localPr).recordCheck(
                eq("pr1"), eq(LocalPRCheck.KIND_LOCAL), eq("npm test"), eq(LocalPRCheck.STATUS_FAILED), anyLong());
    }

    @Test
    void skipsRecordingWhenNoLocalPrExistsYet()
            throws InterruptedException
    {
        when(detector.detect(worktree))
                .thenReturn(Optional.of(new TestRunnerDetector.Detected("maven", List.of("mvn", "-q", "verify"))));
        when(shellRunner.runArgv(eq(worktree), any(), anyLong(), any(Integer.class)))
                .thenReturn(new ShellRunner.Result(true, 0, "BUILD SUCCESS", false, null));
        when(localPrSync.syncFromTask("t1")).thenReturn(Optional.empty());

        List<ValidationFailure> failures = check.run("t1", worktree);

        assertThat(failures).isEmpty();
        verify(localPr, never()).recordCheck(any(), any(), any(), any(), any());
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

    private static LocalPR localPr(String id)
    {
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        return LocalPR.create(id, "t1", "dev/x", "main", "T", "", now);
    }
}

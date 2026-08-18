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
package com.bytequay.app.ci;

import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixRun.Authorization;
import com.bytequay.core.ci.CiAutofixRun.Scope;
import com.bytequay.core.ci.CiAutofixRunId;
import com.bytequay.core.ci.CiAutofixStore;
import com.bytequay.core.domain.PullRequestRef;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestCiAutofixMonitorJob
{
    @Test
    void advancesABoundedBatchAndContinuesAfterOneRunFails()
    {
        CiAutofixStore store = mock(CiAutofixStore.class);
        CiAutofixService service = mock(CiAutofixService.class);
        CiAutofixRun first = run();
        CiAutofixRun second = run();
        when(store.listNonFinished(2)).thenReturn(ImmutableList.of(first, second));
        doThrow(new IllegalStateException("provider unavailable")).when(service).advance(first.id());

        new CiAutofixMonitorJob(store, service, 2).monitor();

        verify(service).advance(first.id());
        verify(service).advance(second.id());
    }

    @Test
    void skipsAnOverlappingMonitorPass()
            throws Exception
    {
        CiAutofixStore store = mock(CiAutofixStore.class);
        CiAutofixService service = mock(CiAutofixService.class);
        CiAutofixRun run = run();
        when(store.listNonFinished(1)).thenReturn(ImmutableList.of(run));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return run;
        }).when(service).advance(run.id());
        CiAutofixMonitorJob job = new CiAutofixMonitorJob(store, service, 1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var firstPass = executor.submit(job::monitor);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            job.monitor();
            release.countDown();
            firstPass.get();
        }

        verify(store, times(1)).listNonFinished(1);
        verify(service, times(1)).advance(run.id());
    }

    private static CiAutofixRun run()
    {
        CiAutofixRunId id = CiAutofixRunId.random();
        Instant now = Instant.parse("2026-08-18T00:00:00Z");
        return CiAutofixRun.create(
                id,
                id.toString(),
                new Scope(
                        PullRequestRef.of("bytequay", "sample", 1),
                        "author",
                        "feature",
                        "main",
                        Path.of("/tmp/bytequay-ci-monitor"),
                        "codex"),
                "a".repeat(40),
                "b".repeat(40),
                new Authorization(true, true, true, 3, now, now.plus(Duration.ofHours(8))),
                now);
    }
}

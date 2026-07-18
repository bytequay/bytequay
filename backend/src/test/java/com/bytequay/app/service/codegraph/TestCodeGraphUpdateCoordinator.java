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

import com.bytequay.app.service.codegraph.CodeGraphService.Fingerprint;
import com.bytequay.app.service.local.GitRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TestCodeGraphUpdateCoordinator
{
    @Test
    void testSkipsSameFingerprintAfterSuccessfulSync(@TempDir Path tempDir)
            throws Exception
    {
        Path checkout = tempDir.toAbsolutePath().normalize();
        Fingerprint fingerprint = new Fingerprint("one");
        FakeCodeGraphService service = new FakeCodeGraphService(fingerprint);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CodeGraphUpdateCoordinator coordinator = new CodeGraphUpdateCoordinator(service, executor);
        try {
            assertThat(coordinator.ensureFreshSync(checkout, "first").ok()).isTrue();
            CodeGraphResult second = coordinator.ensureFreshSync(checkout, "second");

            assertThat(second.skipped()).isTrue();
            assertThat(service.indexed).containsExactly(fingerprint);
        }
        finally {
            executor.shutdownNow();
        }
    }

    @Test
    void testRerunsOnceWhenCheckoutChangesDuringIndex(@TempDir Path tempDir)
            throws Exception
    {
        Path checkout = tempDir.toAbsolutePath().normalize();
        Fingerprint first = new Fingerprint("first");
        Fingerprint second = new Fingerprint("second");
        FakeCodeGraphService service = new FakeCodeGraphService(first, second, second);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CodeGraphUpdateCoordinator coordinator = new CodeGraphUpdateCoordinator(service, executor);
        try {
            CodeGraphResult result = coordinator.ensureFreshSync(checkout, "before-query");

            assertThat(result.ok()).isTrue();
            assertThat(service.indexed).containsExactly(first, second);
        }
        finally {
            executor.shutdownNow();
        }
    }

    @Test
    void testDuplicateRefreshesJoinRunningLane(@TempDir Path tempDir)
            throws Exception
    {
        Path checkout = tempDir.toAbsolutePath().normalize();
        Fingerprint fingerprint = new Fingerprint("one");
        FakeCodeGraphService service = new FakeCodeGraphService(fingerprint);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        service.blockOnIndex(started, release);
        ExecutorService indexExecutor = Executors.newSingleThreadExecutor();
        ExecutorService callerExecutor = Executors.newSingleThreadExecutor();
        CodeGraphUpdateCoordinator coordinator = new CodeGraphUpdateCoordinator(service, indexExecutor);
        try {
            CompletableFuture<CodeGraphResult> running = CompletableFuture.supplyAsync(
                    () -> coordinator.ensureFreshSync(checkout, "first"),
                    callerExecutor);
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            coordinator.requestRefreshAsync(checkout, "duplicate-one");
            coordinator.requestRefreshAsync(checkout, "duplicate-two");
            release.countDown();

            assertThat(running.get(5, TimeUnit.SECONDS).ok()).isTrue();
            assertThat(service.indexed).containsExactly(fingerprint);
        }
        finally {
            callerExecutor.shutdownNow();
            indexExecutor.shutdownNow();
        }
    }

    @Test
    void testStopsRepassingWhenCheckoutNeverSettles(@TempDir Path tempDir)
            throws Exception
    {
        Path checkout = tempDir.toAbsolutePath().normalize();
        ChurningCodeGraphService service = new ChurningCodeGraphService();
        ExecutorService indexExecutor = Executors.newSingleThreadExecutor();
        ExecutorService callerExecutor = Executors.newSingleThreadExecutor();
        CodeGraphUpdateCoordinator coordinator = new CodeGraphUpdateCoordinator(service, indexExecutor);
        try {
            CompletableFuture<CodeGraphResult> result = CompletableFuture.supplyAsync(
                    () -> coordinator.ensureFreshSync(checkout, "churn"),
                    callerExecutor);

            // Must terminate despite an ever-changing fingerprint: initial pass + at
            // most the churn cap, and the waiter completes rather than hanging forever.
            assertThat(result.get(5, TimeUnit.SECONDS).ok()).isTrue();
            assertThat(service.indexCount).isLessThanOrEqualTo(4);
        }
        finally {
            callerExecutor.shutdownNow();
            indexExecutor.shutdownNow();
        }
    }

    @Test
    void symbolQueryRefreshesTheIndexBeforeSearching(@TempDir Path tempDir)
            throws Exception
    {
        Path checkout = tempDir.toAbsolutePath().normalize();
        Fingerprint fingerprint = new Fingerprint("one");
        FakeCodeGraphService service = new FakeCodeGraphService(fingerprint);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CodeGraphUpdateCoordinator coordinator = new CodeGraphUpdateCoordinator(service, executor);
        try {
            assertThat(coordinator.query(checkout, "AuthToken")).isEqualTo("query:AuthToken");
            assertThat(service.indexed).containsExactly(fingerprint);
            assertThat(service.queries).containsExactly("AuthToken");
        }
        finally {
            executor.shutdownNow();
        }
    }

    private static final class ChurningCodeGraphService
            extends CodeGraphService
    {
        private int fingerprintCalls;
        private volatile int indexCount;

        private ChurningCodeGraphService()
        {
            super(new CodeGraphRunner(new CodeGraphInstaller(false)), new GitRunner());
        }

        @Override
        public Fingerprint fingerprint(Path checkout)
        {
            return new Fingerprint("fp-" + fingerprintCalls++);
        }

        @Override
        public CodeGraphResult ensureIndexed(Path checkout, Fingerprint target, boolean force)
        {
            indexCount++;
            return CodeGraphResult.ok("synced");
        }
    }

    private static final class FakeCodeGraphService
            extends CodeGraphService
    {
        private final Queue<Fingerprint> fingerprints;
        private final List<Fingerprint> indexed = new ArrayList<>();
        private final List<String> queries = new ArrayList<>();
        private CountDownLatch started;
        private CountDownLatch release;

        private FakeCodeGraphService(Fingerprint... fingerprints)
        {
            super(new CodeGraphRunner(new CodeGraphInstaller(false)), new GitRunner());
            this.fingerprints = new ArrayDeque<>(Arrays.asList(fingerprints));
        }

        private void blockOnIndex(CountDownLatch started, CountDownLatch release)
        {
            this.started = started;
            this.release = release;
        }

        @Override
        public Fingerprint fingerprint(Path checkout)
                throws IOException, InterruptedException
        {
            if (fingerprints.size() > 1) {
                return fingerprints.remove();
            }
            return fingerprints.element();
        }

        @Override
        public CodeGraphResult ensureIndexed(Path checkout, Fingerprint target, boolean force)
        {
            indexed.add(target);
            if (started != null) {
                started.countDown();
                try {
                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return CodeGraphResult.error("interrupted");
                }
            }
            return CodeGraphResult.ok("synced");
        }

        @Override
        public String query(Path checkout, String search)
        {
            queries.add(search);
            return "query:" + search;
        }
    }
}

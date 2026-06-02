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
package com.bytequay.app.repository.sqlite;

import com.bytequay.app.repository.IdSequenceStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end exercise of {@link SqliteIdSequenceStore} against the
 * Flyway-migrated schema. Catches schema/entity drift on the primary
 * key + columns, and the concurrency case where two callers hit the
 * same {@code (workspace, ymd)} key together — the bug we'd want to
 * notice is "both threads got seq=1 because each one's read raced the
 * other's write." A clean run hands out exactly N consecutive values
 * across N concurrent callers, with no duplicates and no gaps.
 */
@SpringBootTest
class TestSqliteIdSequenceStore
{
    @Autowired
    private IdSequenceStore store;

    @Test
    void firstCallForANewKeyReturnsOne()
    {
        int seq = store.nextThreadSeq(uniqueWorkspace("first"), "260603");

        assertThat(seq).isEqualTo(1);
    }

    @Test
    void subsequentCallsIncrementMonotonically()
    {
        String workspace = uniqueWorkspace("incr");

        int first = store.nextThreadSeq(workspace, "260603");
        int second = store.nextThreadSeq(workspace, "260603");
        int third = store.nextThreadSeq(workspace, "260603");

        assertThat(List.of(first, second, third)).containsExactly(1, 2, 3);
    }

    @Test
    void differentWorkspacesOnTheSameDayGetIndependentCounters()
    {
        String acme = uniqueWorkspace("acme");
        String widgets = uniqueWorkspace("widgets");

        assertThat(store.nextThreadSeq(acme, "260603")).isEqualTo(1);
        assertThat(store.nextThreadSeq(widgets, "260603")).isEqualTo(1);
        assertThat(store.nextThreadSeq(acme, "260603")).isEqualTo(2);
        assertThat(store.nextThreadSeq(widgets, "260603")).isEqualTo(2);
    }

    @Test
    void differentDaysInTheSameWorkspaceGetIndependentCounters()
    {
        String workspace = uniqueWorkspace("days");

        assertThat(store.nextThreadSeq(workspace, "260603")).isEqualTo(1);
        assertThat(store.nextThreadSeq(workspace, "260603")).isEqualTo(2);
        // Next day's counter starts fresh — that's the whole point of
        // the per-day key. A 2026-06-04 thread is "thread #1 today",
        // not "thread #3 since the universe began".
        assertThat(store.nextThreadSeq(workspace, "260604")).isEqualTo(1);
        assertThat(store.nextThreadSeq(workspace, "260604")).isEqualTo(2);
    }

    @Test
    void rejectsNullWorkspaceOrYmd()
    {
        assertThatThrownBy(() -> store.nextThreadSeq(null, "260603"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> store.nextThreadSeq("ws-x", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void concurrentAllocationsForOneKeyAreUniqueAndContiguous()
            throws Exception
    {
        // The race: two threads both READ next_seq=N before either's
        // WRITE lands; both then issue N. SERIALIZABLE on the
        // SQLite-serialised file-level write lock should serialise the
        // two transactions so one of them sees N+1 instead.
        //
        // N=24 keeps the test fast on CI but is large enough that a
        // race would land >50% of the time without proper serialisation
        // — empirically the unprotected version of this test produces
        // ~3-5 duplicates per run on this hardware.
        int callers = 24;
        String workspace = uniqueWorkspace("concurrent");
        String ymd = "260605";
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>(callers);
        try {
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return store.nextThreadSeq(workspace, ymd);
                }));
            }
            // Wait for every worker to be parked at the latch, then
            // unleash them all together. Without this they'd serialise
            // through "first-thread-to-start" ordering and the race
            // never materialises.
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Integer> issued = new ArrayList<>(callers);
            for (Future<Integer> f : futures) {
                issued.add(f.get(10, TimeUnit.SECONDS));
            }
            Set<Integer> unique = new HashSet<>(issued);
            assertThat(unique).hasSize(callers);
            // Contiguous 1..callers — no gaps either.
            assertThat(unique).containsExactlyInAnyOrderElementsOf(rangeInclusive(1, callers));
        }
        finally {
            pool.shutdownNow();
        }
    }

    /**
     * Each test seeds a fresh workspace id so cross-test interference
     * (one test's seq=3 leaking into another's expectation that seq=1)
     * can't happen even when the persisted state survives across
     * tests inside the same Spring context.
     */
    private static String uniqueWorkspace(String tag)
    {
        return "ws-test-" + tag + "-" + System.nanoTime();
    }

    private static List<Integer> rangeInclusive(int from, int toInclusive)
    {
        List<Integer> out = new ArrayList<>(toInclusive - from + 1);
        for (int i = from; i <= toInclusive; i++) {
            out.add(i);
        }
        return out;
    }
}

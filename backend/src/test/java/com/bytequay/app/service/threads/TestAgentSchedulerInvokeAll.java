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
package com.bytequay.app.service.threads;

import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnEventStore;
import com.bytequay.app.repository.ThreadTurnStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@link AgentScheduler#invokeAll}: out-of-band API work shares the
 * API lane's capacity, results come back in submission order, and a
 * failed item surfaces after the batch.
 */
class TestAgentSchedulerInvokeAll
{
    private AgentScheduler scheduler(int maxApi)
    {
        return new AgentScheduler(
                mock(ThreadStore.class),
                mock(ThreadTurnStore.class),
                mock(ThreadTurnEventStore.class),
                mock(ThreadRegistry.class),
                mock(StageStore.class),
                /* maxCliRunning */ 1,
                maxApi);
    }

    @Test
    void resultsComeBackInSubmissionOrder()
    {
        AgentScheduler scheduler = scheduler(4);
        List<Callable<Integer>> work = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            int value = i;
            work.add(() -> {
                // Reverse-staggered sleeps so completion order is the
                // opposite of submission order.
                Thread.sleep(10L - value);
                return value;
            });
        }
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), scheduler.invokeAll(work));
    }

    @Test
    void concurrencyNeverExceedsTheApiLaneCap()
    {
        int cap = 3;
        AgentScheduler scheduler = scheduler(cap);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        List<Callable<Integer>> work = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            work.add(() -> {
                int now = running.incrementAndGet();
                peak.accumulateAndGet(now, Math::max);
                Thread.sleep(20);
                running.decrementAndGet();
                return now;
            });
        }
        scheduler.invokeAll(work);
        assertTrue(peak.get() <= cap,
                "peak concurrency " + peak.get() + " exceeded the API lane cap " + cap);
    }

    @Test
    void aFailedItemSurfacesAfterTheBatchFinishes()
    {
        AgentScheduler scheduler = scheduler(2);
        AtomicInteger completed = new AtomicInteger();
        List<Callable<Integer>> work = List.of(
                () -> {
                    completed.incrementAndGet();
                    return 1;
                },
                () -> {
                    throw new IllegalStateException("boom");
                },
                () -> {
                    completed.incrementAndGet();
                    return 3;
                });
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> scheduler.invokeAll(work));
        assertEquals("boom", failure.getMessage());
        // The healthy items still ran to completion first.
        assertEquals(2, completed.get());
    }
}

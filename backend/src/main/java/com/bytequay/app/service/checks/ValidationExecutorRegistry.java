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

import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;

/**
 * In-JVM single-flight admission for validation claims: at most one
 * executor per claim key runs in this process, on a dedicated pool so a
 * minute-long verify never occupies a scheduler or event thread. The
 * durable owner/lease CAS on the claim row is the cross-restart
 * guarantee; this registry is the cheap same-process one.
 */
@Component
public class ValidationExecutorRegistry
{
    private final ConcurrentHashMap<String, CompletableFuture<Void>> inFlight = new ConcurrentHashMap<>();
    private final ExecutorService pool =
            Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "validation-executor");
                thread.setDaemon(true);
                return thread;
            });
    private final ScheduledExecutorService leaseRenewer =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "validation-lease-renewer");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * Run {@code work} for {@code claimKey} unless this JVM already has
     * it in flight. Returns true when this call admitted the work.
     */
    public boolean submitIfAbsent(String claimKey, Runnable work)
    {
        requireNonNull(claimKey, "claimKey is null");
        requireNonNull(work, "work is null");
        CompletableFuture<Void> mine = new CompletableFuture<>();
        CompletableFuture<Void> existing = inFlight.putIfAbsent(claimKey, mine);
        if (existing != null) {
            return false;
        }
        pool.execute(() -> {
            try {
                work.run();
                mine.complete(null);
            }
            catch (Throwable t) {
                mine.completeExceptionally(t);
            }
            finally {
                inFlight.remove(claimKey, mine);
            }
        });
        return true;
    }

    public boolean isInFlight(String claimKey)
    {
        return inFlight.containsKey(requireNonNull(claimKey, "claimKey is null"));
    }

    /** Schedule a repeating lease-renewal tick; cancel it when done. */
    public ScheduledFuture<?> scheduleLeaseRenewal(Runnable renew, long periodMillis)
    {
        requireNonNull(renew, "renew is null");
        return leaseRenewer.scheduleAtFixedRate(renew, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
    }
}

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
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Per-checkout serialization and coalescing for CodeGraph updates. */
@Component
public class CodeGraphUpdateCoordinator
{
    /** Cap on self-inflicted re-index passes when a checkout keeps changing under us. */
    private static final int MAX_CHURN_REPASSES = 3;

    private final CodeGraphService codeGraph;
    private final ExecutorService executor;
    private final ConcurrentHashMap<Path, Lane> lanes = new ConcurrentHashMap<>();

    @Autowired
    public CodeGraphUpdateCoordinator(CodeGraphService codeGraph)
    {
        this(codeGraph, Executors.newFixedThreadPool(2, daemonFactory()));
    }

    CodeGraphUpdateCoordinator(CodeGraphService codeGraph, ExecutorService executor)
    {
        this.codeGraph = codeGraph;
        this.executor = executor;
    }

    public static CodeGraphUpdateCoordinator disabled()
    {
        return new CodeGraphUpdateCoordinator(null, null);
    }

    public CodeGraphResult ensureFreshSync(Path checkout, String reason)
    {
        return request(checkout, reason, false, true, 0);
    }

    /**
     * Freshness with a wall-clock cap. If the index has not finished within
     * {@code waitMillis}, the sync keeps running in the background and the
     * caller proceeds rather than blocking (used on the agent turn's hot path).
     */
    public CodeGraphResult ensureFreshWithin(Path checkout, String reason, long waitMillis)
    {
        return request(checkout, reason, false, true, waitMillis);
    }

    public CodeGraphResult rebuildSync(Path checkout, String reason)
    {
        return request(checkout, reason, true, true, 0);
    }

    public void requestRefreshAsync(Path checkout, String reason)
    {
        request(checkout, reason, false, false, 0);
    }

    public void forget(Path checkout)
    {
        lanes.remove(normalize(checkout));
    }

    public String explore(Path checkout, String query)
            throws IOException, InterruptedException
    {
        if (codeGraph == null) {
            throw new IllegalStateException("CodeGraph integration disabled.");
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query is required");
        }
        CodeGraphResult ready = ensureFreshSync(checkout, "before-codegraph-explore");
        if (!ready.ok()) {
            throw new IllegalStateException(ready.message());
        }
        return codeGraph.explore(checkout, query.strip());
    }

    @PreDestroy
    public void stop()
    {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private CodeGraphResult request(Path checkout, String reason, boolean force, boolean wait, long waitMillis)
    {
        if (codeGraph == null || executor == null) {
            return CodeGraphResult.skipped("CodeGraph integration disabled.");
        }
        Path key = normalize(checkout);
        Fingerprint target;
        try {
            target = codeGraph.fingerprint(key);
        }
        catch (IOException | RuntimeException e) {
            return CodeGraphResult.error("could not fingerprint " + key + ": " + e.getMessage());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CodeGraphResult.error("interrupted fingerprinting " + key);
        }

        Lane lane = lanes.computeIfAbsent(key, path -> new Lane());
        CompletableFuture<CodeGraphResult> future = submit(key, lane, target, force, wait, reason);
        if (!wait) {
            return CodeGraphResult.ok("CodeGraph refresh queued for " + key);
        }
        try {
            return waitMillis > 0 ? future.get(waitMillis, TimeUnit.MILLISECONDS) : future.get();
        }
        catch (TimeoutException e) {
            return CodeGraphResult.ok("CodeGraph refresh still running for " + key);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CodeGraphResult.error("interrupted waiting for CodeGraph refresh of " + key);
        }
        catch (ExecutionException e) {
            return CodeGraphResult.error(e.getCause().getMessage());
        }
    }

    private CompletableFuture<CodeGraphResult> submit(
            Path key, Lane lane, Fingerprint target, boolean force, boolean wait, String reason)
    {
        synchronized (lane) {
            if (!force && !lane.running && target.equals(lane.lastIndexed)) {
                return CompletableFuture.completedFuture(
                        CodeGraphResult.skipped("CodeGraph already fresh for " + key));
            }
            CompletableFuture<CodeGraphResult> waiter = wait ? new CompletableFuture<>() : null;
            if (waiter != null) {
                lane.waiters.add(waiter);
            }
            if (lane.running && !force && target.equals(lane.inFlight)) {
                return waiter == null
                        ? CompletableFuture.completedFuture(CodeGraphResult.ok("joined running refresh"))
                        : waiter;
            }
            if (force || lane.pending == null || !target.equals(lane.pending)) {
                lane.pending = target;
                lane.pendingForce |= force;
                lane.pendingReason = reason;
            }
            if (!lane.running) {
                lane.running = true;
                executor.execute(() -> drain(key, lane));
            }
            return waiter == null
                    ? CompletableFuture.completedFuture(CodeGraphResult.ok("queued"))
                    : waiter;
        }
    }

    private void drain(Path key, Lane lane)
    {
        int churnRepasses = 0;
        while (true) {
            Fingerprint target;
            boolean force;
            String reason;
            synchronized (lane) {
                target = lane.pending;
                force = lane.pendingForce;
                reason = lane.pendingReason;
                lane.pending = null;
                lane.pendingForce = false;
                lane.pendingReason = null;
                if (target == null) {
                    lane.running = false;
                    lane.inFlight = null;
                    return;
                }
                lane.inFlight = target;
            }

            CodeGraphResult result;
            try {
                result = codeGraph.ensureIndexed(key, target, force);
            }
            catch (RuntimeException e) {
                result = CodeGraphResult.error(e.getMessage());
            }
            synchronized (lane) {
                if (!result.ok()) {
                    lane.inFlight = null;
                    completeWaiters(lane, result);
                    if (lane.pending != null) {
                        // Newer work queued during this failed pass — attempt it
                        // instead of orphaning it until the next request arrives.
                        continue;
                    }
                    lane.running = false;
                    return;
                }
                Fingerprint current = fingerprintOrNull(key);
                if (current != null && !current.equals(target) && churnRepasses < MAX_CHURN_REPASSES) {
                    churnRepasses++;
                    lane.pending = current;
                    lane.pendingReason = reason == null ? "checkout-changed-during-index" : reason;
                    continue;
                }
                // ponytail: past MAX_CHURN_REPASSES a checkout that never settles is
                // accepted as best-effort so the indexer thread (and any waiter) can't
                // spin forever; the next explicit request re-syncs it.
                if (lane.pending != null) {
                    continue;
                }
                lane.lastIndexed = current == null ? target : current;
                lane.inFlight = null;
                completeWaiters(lane, result);
                lane.running = false;
                return;
            }
        }
    }

    private Fingerprint fingerprintOrNull(Path key)
    {
        try {
            return codeGraph.fingerprint(key);
        }
        catch (IOException | RuntimeException e) {
            return null;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static void completeWaiters(Lane lane, CodeGraphResult result)
    {
        List<CompletableFuture<CodeGraphResult>> waiters = new ArrayList<>(lane.waiters);
        lane.waiters.clear();
        waiters.forEach(waiter -> waiter.complete(result));
    }

    private static ThreadFactory daemonFactory()
    {
        return task -> {
            Thread thread = new Thread(task, "codegraph-indexer");
            thread.setDaemon(true);
            return thread;
        };
    }

    private static Path normalize(Path path)
    {
        return path.toAbsolutePath().normalize();
    }

    private static final class Lane
    {
        private boolean running;
        private Fingerprint inFlight;
        private Fingerprint pending;
        private boolean pendingForce;
        private String pendingReason;
        private Fingerprint lastIndexed;
        private final List<CompletableFuture<CodeGraphResult>> waiters = new ArrayList<>();
    }
}

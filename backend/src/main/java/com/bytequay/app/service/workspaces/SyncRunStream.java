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
package com.bytequay.app.service.workspaces;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Live agent output for a sync run, while the turn is still going.
 *
 * <p>Everything else about a run is durable: the log is in the database and the
 * transcript is stored when the turn ends. This is the one thing that cannot
 * wait for the end — an agent that compiles for four minutes leaves the run
 * looking stalled, and the whole point of watching is seeing that it is not.
 *
 * <p>Deliberately in-memory and best-effort. A dropped line costs a moment of
 * live view; the stored transcript is still the record.
 */
@Component
public class SyncRunStream
{
    private static final Logger log = LoggerFactory.getLogger(SyncRunStream.class);
    /** A wedged subscriber must not grow without bound behind a long turn. */
    private static final int MAX_LISTENERS_PER_RUN = 8;

    /** Handed back when the run already has all the watchers it will take. */
    private static final Runnable NOTHING_TO_UNSUBSCRIBE = SyncRunStream::noop;

    private final Map<String, List<Consumer<String>>> listeners = new ConcurrentHashMap<>();

    /**
     * Adds a watcher.
     *
     * @return the unsubscribe, which controllers wire to the SSE lifecycle.
     */
    public Runnable subscribe(String jobId, Consumer<String> listener)
    {
        List<Consumer<String>> forRun =
                listeners.computeIfAbsent(jobId, key -> new CopyOnWriteArrayList<>());
        if (forRun.size() >= MAX_LISTENERS_PER_RUN) {
            return NOTHING_TO_UNSUBSCRIBE;
        }
        forRun.add(listener);
        return () -> {
            forRun.remove(listener);
            if (forRun.isEmpty()) {
                listeners.remove(jobId, forRun);
            }
        };
    }

    /** Never throws: a broken subscriber must not take the agent turn down. */
    public void publish(String jobId, String line)
    {
        List<Consumer<String>> forRun = listeners.get(jobId);
        if (forRun == null || line == null || line.isBlank()) {
            return;
        }
        for (Consumer<String> listener : forRun) {
            try {
                listener.accept(line);
            }
            catch (RuntimeException gone) {
                forRun.remove(listener);
                log.debug("dropped a sync-run listener for {}: {}", jobId, gone.getMessage());
            }
        }
    }

    private static void noop()
    {
    }

    /** Whether anyone is watching — lets the caller skip the work when nobody is. */
    public boolean hasListeners(String jobId)
    {
        List<Consumer<String>> forRun = listeners.get(jobId);
        return forRun != null && !forRun.isEmpty();
    }
}

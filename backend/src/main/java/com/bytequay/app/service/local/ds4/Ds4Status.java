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
package com.bytequay.app.service.local.ds4;

import java.time.Instant;

/**
 * Immutable snapshot of the ds4 subprocess's current condition.
 * Returned by every read from {@link Ds4LifecycleService#status()}
 * and serialised verbatim onto {@code GET /api/ds4/status}.
 *
 * <p>Carrying the snapshot through one shape (instead of a handful
 * of getters) means the supervisor thread can mutate a single
 * volatile reference and readers always see a self-consistent view —
 * no half-updated PID + startedAt tearing.
 *
 * @param state           lifecycle state from the enum.
 * @param endpoint        the resolved base URL the server answers on
 *                        (e.g. {@code http://127.0.0.1:8000}). Set
 *                        from configuration even in STOPPED so the
 *                        UI can render the Settings field while the
 *                        server isn't running.
 * @param pid             OS process id, or {@code -1} when no
 *                        process is alive.
 * @param startedAt       wall-clock time the supervisor moved into
 *                        RUNNING. {@code null} in any non-running
 *                        state.
 * @param spawnedByUs     {@code true} when this JVM forked the
 *                        subprocess; {@code false} when we attached
 *                        to a server an external client started.
 *                        The Stop endpoint uses this to insist on a
 *                        confirm before stopping someone else's
 *                        server.
 * @param restartAttempts crash-loop counter. Resets to 0 on every
 *                        successful RUNNING transition; the give-up
 *                        threshold trips when it reaches the cap.
 * @param lastError       human-readable failure note from the most
 *                        recent transition that hit an error. Null
 *                        on a clean state.
 */
public record Ds4Status(
        Ds4State state,
        String endpoint,
        long pid,
        Instant startedAt,
        boolean spawnedByUs,
        int restartAttempts,
        String lastError)
{
    /** Convenience for the initial snapshot before any spawn has
     *  been attempted — used at boot before attach-detect runs. */
    public static Ds4Status notConfigured(String endpoint)
    {
        return new Ds4Status(
                Ds4State.NOT_CONFIGURED,
                endpoint,
                /* pid */ -1L,
                /* startedAt */ null,
                /* spawnedByUs */ false,
                /* restartAttempts */ 0,
                /* lastError */ null);
    }

    /** Same shape for the stopped resting state once a binary path
     *  is configured. */
    public static Ds4Status stopped(String endpoint)
    {
        return new Ds4Status(
                Ds4State.STOPPED,
                endpoint,
                -1L, null, false, 0, null);
    }
}

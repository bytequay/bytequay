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
package com.bytequay.app.service.workmodel;

import com.google.common.collect.ImmutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Probes the local machine for each CLI agent ByteQuay knows about.
 * Returns a per-agent readiness snapshot: binary present on PATH +
 * responded to {@code --version}, and a best-effort guess on whether
 * the agent has an active auth session.
 *
 * <p>Strictly local — never calls the agent's HTTPS endpoint. The CLI
 * agent is responsible for its own authentication (the user runs
 * {@code claude login} or similar outside ByteQuay); we just inspect
 * what's installed.
 *
 * <p>Results are memoised for {@link #CACHE_TTL} so the picker doesn't
 * re-spawn a subprocess per render. Call {@link #invalidate()} when
 * the user manually refreshes or after a known install-step event.
 */
@Service
public class CliAgentDetector
{
    private static final Logger log = LoggerFactory.getLogger(CliAgentDetector.class);

    /** Cap on how long a single probe is allowed to block. Tightened
     *  because {@code claude doctor} and {@code codex whoami} can drop
     *  into an interactive TUI that we then have to forcibly kill —
     *  600 ms is enough for a healthy non-interactive exit but won't
     *  let an interactive prompt wedge the picker. */
    private static final long PROBE_TIMEOUT_MS = 600;

    /** Memo TTL — keeps the picker responsive without sticking on a
     *  stale "not installed" result after the user runs an installer. */
    private static final Duration CACHE_TTL = Duration.ofSeconds(30);

    /** Auth-state lookup table. The probe-arg for each agent. Empty
     *  means "no auth probe wired yet — best-effort guess is unknown
     *  (treated as not-authed for the picker's readiness label)."
     *
     *  <p>Both wired probes can drop into an interactive TUI on a
     *  fresh install (no shell, no tty), so we time them out fast and
     *  treat a non-zero / timed-out exit as "not authed" rather than
     *  blocking the picker for 1-2s per agent on cold cache. */
    private static final Map<String, String[]> AUTH_PROBES = ImmutableMap.of(
            "claude-code", new String[] {"claude", "doctor"},
            "codex", new String[] {"codex", "whoami"});

    /** Binary names we exec for the {@code --version} check, keyed by
     *  the catalog agent id. */
    private static final Map<String, String> BINARIES = ImmutableMap.of(
            "claude-code", "claude",
            "codex", "codex");

    public record Readiness(boolean installed, boolean authed) {}

    /** Default value returned for an agent whose readiness hasn't been
     *  probed yet — "unknown but lean negative" so the picker doesn't
     *  pretend a missing CLI is installed during the first-ever fetch. */
    private static final Readiness UNKNOWN = new Readiness(false, false);

    private final Map<String, CachedReadiness> cache = new HashMap<>();
    /** Flips true while a background probe sweep is in flight so we
     *  don't pile up redundant sweeps when multiple {@code /api/work-models}
     *  requests land before the first one finishes. */
    private final AtomicBoolean detectionInFlight = new AtomicBoolean(false);

    /** Warm the cache once the app is up so the first picker open
     *  hits a populated cache and never blocks on a fresh probe. We
     *  intentionally do this on a virtual thread (not the spring
     *  ready-event thread) so a slow {@code claude doctor} can't hold
     *  up the rest of the boot. */
    @EventListener(ApplicationReadyEvent.class)
    void warmOnReady()
    {
        triggerBackgroundDetection();
    }

    /** Returns a snapshot of the local readiness for each CLI agent in
     *  the catalog. Never blocks on a fresh probe — entries missing
     *  from the cache (because warm-on-ready is still running, or the
     *  TTL just expired) come back as {@link #UNKNOWN}. A background
     *  sweep refreshes the cache so the next call after ~the probe
     *  window returns real values. The picker's manual Refresh button
     *  is the path that does block on a sync re-probe. */
    public synchronized Map<String, Readiness> detectAll()
    {
        boolean anyStale = false;
        Instant now = Instant.now();
        ImmutableMap.Builder<String, Readiness> out = ImmutableMap.builder();
        for (WorkModelCatalog.CatalogAgent agent : WorkModelCatalog.CLI_AGENTS) {
            CachedReadiness cached = cache.get(agent.id());
            if (cached != null && cached.expiresAt().isAfter(now)) {
                out.put(agent.id(), cached.readiness());
            }
            else {
                out.put(agent.id(), UNKNOWN);
                anyStale = true;
            }
        }
        if (anyStale) {
            triggerBackgroundDetection();
        }
        return out.build();
    }

    /** Synchronous re-probe path. Called when the picker's "Refresh"
     *  affordance fires — the user explicitly asked for fresh
     *  readiness and is willing to wait the ~probe window. */
    public synchronized Map<String, Readiness> detectAllBlocking()
    {
        ImmutableMap.Builder<String, Readiness> out = ImmutableMap.builder();
        for (WorkModelCatalog.CatalogAgent agent : WorkModelCatalog.CLI_AGENTS) {
            out.put(agent.id(), detectOne(agent.id()));
        }
        return out.build();
    }

    /** Forces the next probe to bypass the cache — call from the
     *  picker's refresh button or after a known install / login event. */
    public synchronized void invalidate()
    {
        cache.clear();
    }

    private void triggerBackgroundDetection()
    {
        if (!detectionInFlight.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                synchronized (this) {
                    for (WorkModelCatalog.CatalogAgent agent : WorkModelCatalog.CLI_AGENTS) {
                        detectOne(agent.id());
                    }
                }
            }
            catch (RuntimeException e) {
                log.warn("Background CLI detection sweep failed: {}", e.getMessage());
            }
            finally {
                detectionInFlight.set(false);
            }
        }, "cli-detect-background");
        t.setDaemon(true);
        t.start();
    }

    private Readiness detectOne(String agentId)
    {
        CachedReadiness cached = cache.get(agentId);
        Instant now = Instant.now();
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached.readiness();
        }
        String binary = BINARIES.get(agentId);
        Readiness readiness;
        if (binary == null) {
            readiness = new Readiness(false, false);
        }
        else {
            boolean installed = probe(binary, "--version");
            boolean authed = false;
            if (installed) {
                String[] authCmd = AUTH_PROBES.get(agentId);
                if (authCmd != null) {
                    authed = probe(authCmd);
                }
            }
            readiness = new Readiness(installed, authed);
        }
        cache.put(agentId, new CachedReadiness(readiness, now.plus(CACHE_TTL)));
        return readiness;
    }

    /** Spawns a probe and returns true iff it exited 0 within the
     *  timeout. The probe's stdout/stderr is drained but discarded —
     *  we only care about the exit code.
     *
     *  <p>Hardening for interactive CLIs:
     *  <ul>
     *    <li>{@code redirectInput(/dev/null)} so a tool that calls
     *        {@code read()} gets EOF immediately instead of blocking
     *        the picker. {@code claude doctor} prompts for input on a
     *        fresh install; without this it hangs until the timeout
     *        and then we kill it.</li>
     *    <li>The drain thread is now a daemon — it can't keep the
     *        JVM alive past shutdown if a wedged probe leaves it
     *        reading.</li>
     *    <li>We {@code waitFor} the drain too so a still-running
     *        thread can't pile up between cache misses.</li>
     *  </ul> */
    private static boolean probe(String... command)
    {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
            p = pb.start();
            Process pf = p;
            Thread drain = new Thread(() -> {
                try {
                    pf.getInputStream().readAllBytes();
                }
                catch (IOException ignored) {
                    // Probe is best-effort; drain failure is non-fatal.
                }
            }, "cli-probe-drain");
            drain.setDaemon(true);
            drain.start();
            boolean done = p.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!done) {
                p.destroyForcibly();
                drain.join(200);
                return false;
            }
            drain.join(200);
            return p.exitValue() == 0;
        }
        catch (IOException e) {
            // The most common cause: binary not on PATH.
            return false;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (p != null) {
                p.destroyForcibly();
            }
            return false;
        }
        catch (RuntimeException e) {
            log.warn("CLI probe {} threw unexpectedly: {}", command[0], e.getMessage());
            return false;
        }
    }

    private record CachedReadiness(Readiness readiness, Instant expiresAt) {}
}

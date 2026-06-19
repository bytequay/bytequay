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

import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.repository.AppSettingsStore.Key;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.app.config.AsyncConfig.DS4_SUPERVISOR_EXECUTOR;
import static com.bytequay.app.config.AsyncConfig.DS4_WORK_EXECUTOR;
import static java.util.Objects.requireNonNull;

/**
 * Singleton supervisor that owns the ds4-server subprocess. One
 * supervisor thread mutates the volatile status snapshot so REST
 * readers always see a self-consistent view without a lock.
 *
 * <p>State machine:
 *
 * <pre>
 *   NOT_CONFIGURED  ──configure──▶  STOPPED
 *   STOPPED         ──start──▶      STARTING
 *   STARTING        ──probe ok──▶   RUNNING
 *   STARTING        ──probe fail──▶ CRASHED
 *   RUNNING         ──stop──▶       STOPPING
 *   STOPPING        ──exit──▶       STOPPED
 *   RUNNING         ──exit──▶       CRASHED  (auto-restart if enabled)
 *   CRASHED         ──backoff──▶    STARTING (until give-up cap)
 * </pre>
 *
 * <p>The lifecycle service mirrors the {@code spawnBackend} pattern
 * Electron uses for the Spring Boot sidecar: ProcessBuilder spawn,
 * redirected stdout/stderr into a bounded ring buffer, SIGTERM via
 * {@code Process.destroy()}, grace-deadline + forceful fallback only
 * if the server refuses to exit. Auto-restart back-off matches the
 * design doc's 1s · 2s · 5s · 15s ladder; give-up trips at 5
 * consecutive failures.
 *
 * <p>Attach-detect runs once at boot: if a healthy server already
 * answers on the configured port, the supervisor marks
 * {@code spawnedByUs = false} and skips the spawn entirely.
 */
@Service
public class Ds4LifecycleService
{
    private static final Logger log = LoggerFactory.getLogger(Ds4LifecycleService.class);

    /** Back-off ladder for auto-restart after a crash. Reset on
     *  every successful RUNNING transition. */
    static final long[] BACKOFF_MS = {1_000L, 2_000L, 5_000L, 15_000L};
    /** Hard cap on consecutive crashes before the supervisor parks
     *  the state at CRASHED and stops trying. */
    static final int MAX_RESTART_ATTEMPTS = 5;
    /** Window the spawn path waits for the health probe to succeed.
     *  ds4-server can take a while to load weights on a cold boot
     *  (~5–15s on Mac Studio), so this is generous. */
    static final Duration STARTING_PROBE_DEADLINE = Duration.ofSeconds(45);
    /** Per-probe HTTP timeout. The probe runs every probe-interval
     *  inside STARTING; shorter than the spawn deadline so we don't
     *  hang on a single dead-air response. */
    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);
    static final Duration PROBE_INTERVAL = Duration.ofMillis(500);
    /** Wait window after SIGTERM for the server to flush its KV
     *  "shutdown" checkpoint before we resort to destroyForcibly().
     *  Used for user-driven Stop (Settings → Stop, /api/ds4/stop). */
    static final Duration STOP_GRACE = Duration.ofSeconds(20);

    /** Shorter grace used by {@link #shutdown()} on JVM exit. Has to
     *  fit inside dev.sh's 5s SIGKILL fallback, otherwise the dev
     *  script force-kills the JVM mid-flush and we leak the ds4
     *  subprocess. User-driven Stop still uses the full 20s. */
    static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(3);

    /** App-owned marker file with the spawned ds4 PID so external
     *  cleanup (dev.sh, or a future packaged-app uninstall) can reap
     *  the subprocess when the JVM didn't get a chance to. */
    private static final Path PID_FILE = defaultPidFile();

    /** Bounded stdout/stderr capture for the request log + crash
     *  diagnosis surface. Older lines drop off the head; the head is
     *  the oldest so the UI can show the tail without reading the
     *  whole buffer. */
    private static final int LOG_RING_CAPACITY = 5_000;

    private final AppSettingsStore settings;
    private final ApplicationEventPublisher events;
    private final HttpClient probeClient;

    /** Single-thread supervisor — every transition runs here so the
     *  status snapshot has exactly one writer. */
    private final ExecutorService supervisor;
    private final ExecutorService worker;

    private final AtomicReference<Ds4Status> status = new AtomicReference<>();
    /** Live process handle while we own a subprocess. Null after
     *  Stop / Crash exits or when attached to someone else's server. */
    private volatile Process process;
    /** Snapshot of the config every spawn was launched with so a
     *  follow-up Restart uses the same surface (and the UI can show
     *  what's actually running, not just what's saved). */
    private volatile Ds4Config runningConfig;
    /** Bounded ring of combined stdout+stderr lines for the request
     *  log and crash diagnosis. */
    private final Deque<String> logRing = new ArrayDeque<>(LOG_RING_CAPACITY);

    public Ds4LifecycleService(
            AppSettingsStore settings,
            ApplicationEventPublisher events,
            @Qualifier(DS4_SUPERVISOR_EXECUTOR) ExecutorService supervisor,
            @Qualifier(DS4_WORK_EXECUTOR) ExecutorService worker)
    {
        this.settings = requireNonNull(settings, "settings is null");
        this.events = requireNonNull(events, "events is null");
        this.supervisor = requireNonNull(supervisor, "supervisor is null");
        this.worker = requireNonNull(worker, "worker is null");
        this.probeClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        Ds4Config cfg = loadConfig();
        this.status.set(initialStatusFor(cfg));
    }

    /** Read the persisted config (every key falls back to the
     *  baked default when missing). Called by every transition so
     *  the supervisor always uses what's on disk *now*. */
    public Ds4Config getConfig()
    {
        return loadConfig();
    }

    /** Persist {@code config} but do <strong>not</strong> relaunch.
     *  The server has no hot-reload, so apply-on-restart is the only
     *  honest contract; the UI surfaces a "restart required" banner
     *  whenever this is called against a RUNNING server. */
    public void setConfig(Ds4Config config)
    {
        requireNonNull(config, "config is null");
        persistConfig(config);
        Ds4Status snap = status.get();
        if (!config.enabled()) {
            // Master switch turned off. Stop a live server so its
            // Metal/GPU resources are released now, then park DISABLED.
            if (snap.state() == Ds4State.RUNNING || snap.state() == Ds4State.STARTING) {
                supervisor.submit(() -> {
                    stopBlocking();
                    transition(Ds4Status.disabled(endpointFor(config)));
                });
            }
            else {
                transition(Ds4Status.disabled(endpointFor(config)));
            }
            return;
        }
        // Switched back on: leave DISABLED for the normal resting
        // state so Start becomes available, but never auto-spawn here —
        // apply-on-restart stays the contract.
        if (snap.state() == Ds4State.DISABLED) {
            transition(config.canStart()
                    ? Ds4Status.stopped(endpointFor(config))
                    : Ds4Status.notConfigured(endpointFor(config)));
            return;
        }
        // A config write that newly satisfies canStart() should
        // immediately drop us out of NOT_CONFIGURED so the UI can
        // enable the Start button without a refresh.
        if (snap.state() == Ds4State.NOT_CONFIGURED && config.canStart()) {
            transition(Ds4Status.stopped(endpointFor(config)));
        }
    }

    /** Saves {@code config} and triggers a graceful Stop → Start.
     *  Returns the snapshot the supervisor is heading toward; reads
     *  via {@link #status()} will see the live progression. */
    public Ds4Status restartWithConfig(Ds4Config config)
    {
        persistConfig(config);
        supervisor.submit(() -> {
            if (status.get().state() == Ds4State.RUNNING
                    || status.get().state() == Ds4State.STARTING) {
                stopBlocking();
            }
            startBlocking();
        });
        return status.get();
    }

    public Ds4Status status()
    {
        return status.get();
    }

    /** Recent stdout/stderr tail. Used by the management page's
     *  crash-diagnosis affordance and (later) the proxy follow-up. */
    public synchronized List<String> recentLogs(int limit)
    {
        int take = Math.max(0, Math.min(limit, logRing.size()));
        List<String> out = new ArrayList<>(take);
        int skip = logRing.size() - take;
        int i = 0;
        for (String line : logRing) {
            if (i++ < skip) {
                continue;
            }
            out.add(line);
        }
        return out;
    }

    /** Async Start. Returns the live status snapshot; the supervisor
     *  drives transitions on its own thread. */
    public Ds4Status start()
    {
        supervisor.submit(this::startBlocking);
        return status.get();
    }

    /** Async Stop. Returns the live status snapshot; the supervisor
     *  drives the graceful SIGTERM + grace-deadline path. */
    public Ds4Status stop()
    {
        supervisor.submit(() -> stopBlocking());
        return status.get();
    }

    /** Combined Stop + Start using the persisted config. */
    public Ds4Status restart()
    {
        supervisor.submit(() -> {
            stopBlocking();
            startBlocking();
        });
        return status.get();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady()
    {
        Ds4Config cfg = loadConfig();
        if (!cfg.enabled()) {
            // Local AI is off (the experimental default). The constructor
            // already parked us at DISABLED, so do nothing at all here:
            // never spawn or attach, hold no Metal/GPU resources, and
            // print no boot info — the feature is invisible until opted in.
            return;
        }
        if (!cfg.canStart()) {
            // No binary path — keep NOT_CONFIGURED and let the UI
            // surface the configure / download affordance.
            return;
        }
        supervisor.submit(() -> {
            if (cfg.attachIfRunning() && probeOnce(endpointFor(cfg))) {
                transition(new Ds4Status(
                        Ds4State.RUNNING, endpointFor(cfg), /* pid */ -1L,
                        Instant.now(), /* spawnedByUs */ false,
                        /* restartAttempts */ 0, /* lastError */ null));
                log.info("Attached to an already-running ds4 server at {}", endpointFor(cfg));
                return;
            }
            if (cfg.autoStartOnBoot()) {
                startBlocking();
            }
        });
    }

    @PreDestroy
    void shutdown()
    {
        if (status.get().state() == Ds4State.DISABLED) {
            // Local AI off — nothing was ever spawned, so skip the
            // teardown path entirely: no SIGTERM, no PID-file churn, no
            // shutdown logging. Just drop the idle supervisor thread.
            supervisor.shutdownNow();
            return;
        }
        try {
            stopBlocking(SHUTDOWN_GRACE);
        }
        catch (RuntimeException e) {
            log.warn("Stop during JVM shutdown threw: {}", e.getMessage());
        }
        supervisor.shutdownNow();
    }

    // ── Supervisor-thread bodies ──────────────────────────────────

    /** Blocking Start, called on the supervisor thread. Walks
     *  STOPPED → STARTING → RUNNING (or → CRASHED on probe failure
     *  / early exit). */
    void startBlocking()
    {
        Ds4Config cfg = loadConfig();
        if (!cfg.enabled()) {
            // Master switch off — a manual Start (or a queued restart)
            // must not bring the subprocess up behind the user's back.
            transition(Ds4Status.disabled(endpointFor(cfg)));
            return;
        }
        if (!cfg.canStart()) {
            transition(new Ds4Status(
                    Ds4State.NOT_CONFIGURED, endpointFor(cfg),
                    -1L, null, false, 0,
                    "Binary path is not configured. Set ds4.binary_path or run the in-app installer."));
            return;
        }
        Ds4State cur = status.get().state();
        if (cur == Ds4State.RUNNING || cur == Ds4State.STARTING) {
            log.debug("Ignoring Start in state {}", cur);
            return;
        }
        Process spawned = trySpawn(cfg);
        if (spawned == null) {
            return;
        }
        this.process = spawned;
        this.runningConfig = cfg;
        transition(new Ds4Status(
                Ds4State.STARTING, endpointFor(cfg), spawned.pid(),
                null, /* spawnedByUs */ true,
                status.get().restartAttempts(), null));
        captureLogs(spawned);
        boolean ready = waitForHealthy(endpointFor(cfg));
        if (!ready) {
            log.warn("ds4 health probe never came up; treating as crashed");
            killAndMarkCrashed("Health probe never came up.");
            maybeScheduleRestart(cfg);
            return;
        }
        transition(new Ds4Status(
                Ds4State.RUNNING, endpointFor(cfg), spawned.pid(),
                Instant.now(), true, /* restartAttempts reset */ 0, null));
        writePidFile(spawned.pid());
        // Spin a daemon watcher so we notice an exit even when no
        // turn is in flight. The watcher only fires the CRASHED
        // transition; the supervisor decides whether to restart.
        worker.submit(() -> watchProcess(spawned, cfg));
    }

    void stopBlocking()
    {
        stopBlocking(STOP_GRACE);
    }

    void stopBlocking(Duration grace)
    {
        Process p = this.process;
        Ds4Status snap = status.get();
        if (snap.state() == Ds4State.STOPPED
                || snap.state() == Ds4State.NOT_CONFIGURED
                || snap.state() == Ds4State.DISABLED) {
            // Nothing was ever spawned — teardown is a no-op, so the
            // @PreDestroy path costs nothing and holds no GPU state.
            removePidFile();
            return;
        }
        if (p == null) {
            // Attached state — we don't own the process, so we can
            // only mark our local view as Stopped. The actual remote
            // process keeps running for whoever started it. The
            // controller layer guards this with a confirm prompt.
            transition(Ds4Status.stopped(snap.endpoint()));
            return;
        }
        transition(new Ds4Status(
                Ds4State.STOPPING, snap.endpoint(), p.pid(),
                snap.startedAt(), snap.spawnedByUs(),
                snap.restartAttempts(), null));
        p.destroy();
        try {
            boolean exited = p.waitFor(grace.toMillis(), TimeUnit.MILLISECONDS);
            if (!exited) {
                log.warn("ds4 didn't honour SIGTERM within {}; forcing", grace);
                p.destroyForcibly();
                p.waitFor(2, TimeUnit.SECONDS);
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        this.process = null;
        removePidFile();
        transition(Ds4Status.stopped(snap.endpoint()));
    }

    private void watchProcess(Process p, Ds4Config cfg)
    {
        try {
            p.waitFor();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        Ds4Status snap = status.get();
        if (snap.state() == Ds4State.STOPPING || snap.state() == Ds4State.STOPPED) {
            // Expected exit; nothing to do.
            return;
        }
        // Unexpected exit on the watcher — flip to CRASHED on the
        // supervisor thread so the status mutation stays single-
        // writer, and let it decide whether to schedule a restart.
        supervisor.submit(() -> {
            killAndMarkCrashed("ds4 exited unexpectedly (code " + p.exitValue() + ")");
            maybeScheduleRestart(cfg);
        });
    }

    private void maybeScheduleRestart(Ds4Config cfg)
    {
        Ds4Status snap = status.get();
        if (!cfg.enabled() || !cfg.autoRestartOnCrash()) {
            return;
        }
        if (snap.restartAttempts() >= MAX_RESTART_ATTEMPTS) {
            log.warn("ds4 reached the {}-attempt give-up threshold; staying CRASHED",
                    MAX_RESTART_ATTEMPTS);
            return;
        }
        long delay = BACKOFF_MS[Math.min(snap.restartAttempts(), BACKOFF_MS.length - 1)];
        log.info("ds4 auto-restart attempt {} after {}ms", snap.restartAttempts() + 1, delay);
        supervisor.submit(() -> {
            try {
                Thread.sleep(delay);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            // Re-stamp the counter on the snapshot before retrying so
            // the give-up threshold is honest.
            Ds4Status now = status.get();
            transition(new Ds4Status(
                    now.state(), now.endpoint(), now.pid(),
                    now.startedAt(), now.spawnedByUs(),
                    now.restartAttempts() + 1, now.lastError()));
            startBlocking();
        });
    }

    private Process trySpawn(Ds4Config cfg)
    {
        try {
            Path binary = Path.of(cfg.binaryPath());
            if (!Files.exists(binary)) {
                transition(new Ds4Status(
                        Ds4State.NOT_CONFIGURED, endpointFor(cfg),
                        -1L, null, false, 0,
                        "Binary not found at " + cfg.binaryPath()));
                return null;
            }
            if (!Files.isExecutable(binary)) {
                transition(new Ds4Status(
                        Ds4State.NOT_CONFIGURED, endpointFor(cfg),
                        -1L, null, false, 0,
                        "Binary at " + cfg.binaryPath() + " is not executable"));
                return null;
            }
            ensureKvCacheDir(cfg);
            ProcessBuilder pb = new ProcessBuilder(buildArgs(cfg));
            pb.redirectErrorStream(true);
            // ds4-server resolves its Metal shaders ({@code metal/*.metal}) and the
            // default {@code ./ds4flash.gguf} relative to the working directory;
            // launching from elsewhere makes it fail to find them. Setting cwd to
            // the binary's parent dir matches the README's {@code --chdir} guidance
            // for the common case where the binary sits at the repo root.
            File binaryParent = binary.getParent() == null ? null : binary.getParent().toFile();
            if (binaryParent != null && binaryParent.isDirectory()) {
                pb.directory(binaryParent);
            }
            return pb.start();
        }
        catch (IOException e) {
            transition(new Ds4Status(
                    Ds4State.CRASHED, endpointFor(cfg),
                    -1L, null, false,
                    status.get().restartAttempts() + 1,
                    "Spawn failed: " + e.getMessage()));
            return null;
        }
    }

    private static void ensureKvCacheDir(Ds4Config cfg)
    {
        try {
            Files.createDirectories(Path.of(cfg.kvCacheDir()));
        }
        catch (IOException e) {
            // Non-fatal: the server will report its own error if it
            // can't actually open the directory. Logging it on this
            // path means the UI surfaces a useful starting point.
            log.warn("Could not pre-create KV cache dir {}: {}", cfg.kvCacheDir(), e.getMessage());
        }
    }

    /**
     * Build the argv for {@code ds4-server}. Flag names match the
     * upstream README's §Server example:
     *
     * <pre>
     *   ./ds4-server --ctx 100000 --kv-disk-dir /tmp/ds4-kv --kv-disk-space-mb 8192
     * </pre>
     *
     * <p>Notes on what's omitted on purpose:
     * <ul>
     *   <li>{@code --model} / {@code -m} — ds4-server defaults to
     *       {@code ./ds4flash.gguf} which is the symlink the upstream
     *       {@code download_model.sh} script keeps pointing at the
     *       chosen GGUF, so the launcher just needs cwd set to the
     *       repo root (we do that in {@link #trySpawn}).</li>
     *   <li>{@code --quant} — not a real flag; quantisation is baked
     *       into whichever GGUF the user downloaded.</li>
     *   <li>{@code --thinking} — not a server flag; thinking mode is
     *       a per-request toggle on the chat endpoint.</li>
     * </ul>
     *
     * <p>{@code --port} is included so the lifecycle endpoint and the
     * ds4-server's bind address agree; if a future upstream release
     * removes the flag, ds4-server will reject the launch loudly and
     * the next pass can drop it.
     */
    static List<String> buildArgs(Ds4Config cfg)
    {
        List<String> args = new ArrayList<>();
        args.add(cfg.binaryPath());
        args.add("--port");
        args.add(Integer.toString(cfg.port()));
        args.add("--ctx");
        args.add(Integer.toString(cfg.contextTokens()));
        args.add("--kv-disk-dir");
        args.add(cfg.kvCacheDir());
        args.add("--kv-disk-space-mb");
        args.add(Integer.toString(cfg.kvDiskBudgetMb()));
        if (cfg.trace()) {
            // ds4-server's {@code --trace} takes a destination path
            // (used by the eval tools too); keep the trace log
            // alongside the KV cache so it's easy to find.
            args.add("--trace");
            args.add(cfg.kvCacheDir() + "/trace.log");
        }
        return args;
    }

    private void captureLogs(Process p)
    {
        worker.submit(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    appendLog(line);
                }
            }
            catch (IOException ignored) {
                // Stream closed on process exit; nothing to do.
            }
        });
    }

    private synchronized void appendLog(String line)
    {
        if (logRing.size() >= LOG_RING_CAPACITY) {
            logRing.pollFirst();
        }
        logRing.addLast(line);
    }

    private boolean waitForHealthy(String endpoint)
    {
        Instant deadline = Instant.now().plus(STARTING_PROBE_DEADLINE);
        while (Instant.now().isBefore(deadline)) {
            if (probeOnce(endpoint)) {
                return true;
            }
            try {
                Thread.sleep(PROBE_INTERVAL.toMillis());
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    boolean probeOnce(String endpoint)
    {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + "/v1/models"))
                    .timeout(PROBE_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<Void> resp = probeClient.send(req, HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() / 100 == 2;
        }
        catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private void killAndMarkCrashed(String reason)
    {
        Process p = this.process;
        if (p != null && p.isAlive()) {
            p.destroyForcibly();
        }
        this.process = null;
        removePidFile();
        Ds4Status snap = status.get();
        transition(new Ds4Status(
                Ds4State.CRASHED, snap.endpoint(),
                -1L, null, snap.spawnedByUs(),
                snap.restartAttempts(), reason));
    }

    private void transition(Ds4Status next)
    {
        Ds4Status prev = status.getAndSet(next);
        if (prev != null && prev.state() != next.state()) {
            log.info("ds4 {} → {} ({})", prev.state(), next.state(),
                    next.lastError() == null ? "ok" : next.lastError());
        }
        events.publishEvent(new Ds4StatusEvent(prev == null ? null : prev.state(), next));
    }

    private Ds4Status initialStatusFor(Ds4Config cfg)
    {
        if (!cfg.enabled()) {
            return Ds4Status.disabled(endpointFor(cfg));
        }
        if (!cfg.canStart()) {
            return Ds4Status.notConfigured(endpointFor(cfg));
        }
        return Ds4Status.stopped(endpointFor(cfg));
    }

    static String endpointFor(Ds4Config cfg)
    {
        return "http://127.0.0.1:" + cfg.port();
    }

    // ── PID file ──────────────────────────────────────────────────

    /** Stamp the spawned ds4 PID into an app-owned marker file so
     *  external tooling (dev.sh's cleanup, a future packaged-app
     *  uninstall path) can reap the subprocess when the JVM didn't
     *  get a chance to run {@link #shutdown()}. Best-effort: any I/O
     *  failure logs and continues — the supervisor still tracks the
     *  Process handle in-memory. */
    private static void writePidFile(long pid)
    {
        try {
            Files.createDirectories(PID_FILE.getParent());
            Files.writeString(PID_FILE, Long.toString(pid));
        }
        catch (IOException e) {
            log.warn("Could not write ds4 pid file at {}: {}", PID_FILE, e.getMessage());
        }
    }

    private static void removePidFile()
    {
        try {
            Files.deleteIfExists(PID_FILE);
        }
        catch (IOException e) {
            log.warn("Could not remove ds4 pid file at {}: {}", PID_FILE, e.getMessage());
        }
    }

    /** App-owned PID file under
     *  {@code ~/Library/Application Support/ds4/ds4-server.pid} on
     *  macOS, {@code ~/.ds4/ds4-server.pid} elsewhere. Mirrors the
     *  KV cache layout so dev.sh + uninstall scripts can find it
     *  with one well-known glob. */
    private static Path defaultPidFile()
    {
        String home = System.getProperty("user.home");
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return Path.of(home, "Library", "Application Support", "ds4", "ds4-server.pid");
        }
        return Path.of(home, ".ds4", "ds4-server.pid");
    }

    // ── Persistence ───────────────────────────────────────────────

    private Ds4Config loadConfig()
    {
        Ds4Config d = Ds4Config.defaults();
        return new Ds4Config(
                settings.get(Key.DS4_BINARY_PATH).filter(s -> !s.isBlank()).orElse(d.binaryPath()),
                settings.get(Key.DS4_PORT).map(Ds4LifecycleService::parseInt).orElse(d.port()),
                settings.get(Key.DS4_MODEL).filter(s -> !s.isBlank()).orElse(d.model()),
                settings.get(Key.DS4_QUANT).filter(s -> !s.isBlank()).orElse(d.quant()),
                settings.get(Key.DS4_CONTEXT_TOKENS).map(Ds4LifecycleService::parseInt).orElse(d.contextTokens()),
                settings.get(Key.DS4_KV_CACHE_DIR).filter(s -> !s.isBlank()).orElse(d.kvCacheDir()),
                settings.get(Key.DS4_KV_DISK_BUDGET_MB).map(Ds4LifecycleService::parseInt).orElse(d.kvDiskBudgetMb()),
                settings.get(Key.DS4_THINKING_DEFAULT).map(Ds4LifecycleService::parseBool).orElse(d.thinkingDefault()),
                settings.get(Key.DS4_TRACE).map(Ds4LifecycleService::parseBool).orElse(d.trace()),
                settings.get(Key.DS4_REPO_DIR).filter(s -> !s.isBlank()).orElse(d.repoDir()),
                settings.get(Key.DS4_MODEL_VARIANT).filter(s -> !s.isBlank()).orElse(d.modelVariant()),
                settings.get(Key.DS4_INSTALL_URL).filter(s -> !s.isBlank()).orElse(d.installUrl()),
                settings.get(Key.DS4_AUTO_RESTART_ON_CRASH).map(Ds4LifecycleService::parseBool).orElse(d.autoRestartOnCrash()),
                settings.get(Key.DS4_AUTO_START_ON_BOOT).map(Ds4LifecycleService::parseBool).orElse(d.autoStartOnBoot()),
                settings.get(Key.DS4_ATTACH_IF_RUNNING).map(Ds4LifecycleService::parseBool).orElse(d.attachIfRunning()),
                settings.get(Key.DS4_ENABLED).map(Ds4LifecycleService::parseBool).orElse(d.enabled()));
    }

    private void persistConfig(Ds4Config c)
    {
        settings.set(Key.DS4_BINARY_PATH, c.binaryPath() == null ? "" : c.binaryPath());
        settings.set(Key.DS4_PORT, Integer.toString(c.port()));
        settings.set(Key.DS4_MODEL, c.model() == null ? "" : c.model());
        settings.set(Key.DS4_QUANT, c.quant() == null ? "" : c.quant());
        settings.set(Key.DS4_CONTEXT_TOKENS, Integer.toString(c.contextTokens()));
        settings.set(Key.DS4_KV_CACHE_DIR, c.kvCacheDir() == null ? "" : c.kvCacheDir());
        settings.set(Key.DS4_KV_DISK_BUDGET_MB, Integer.toString(c.kvDiskBudgetMb()));
        settings.set(Key.DS4_THINKING_DEFAULT, Boolean.toString(c.thinkingDefault()));
        settings.set(Key.DS4_TRACE, Boolean.toString(c.trace()));
        settings.set(Key.DS4_REPO_DIR, c.repoDir() == null ? "" : c.repoDir());
        settings.set(Key.DS4_MODEL_VARIANT, c.modelVariant() == null ? "" : c.modelVariant());
        settings.set(Key.DS4_INSTALL_URL, c.installUrl() == null ? "" : c.installUrl());
        settings.set(Key.DS4_AUTO_RESTART_ON_CRASH, Boolean.toString(c.autoRestartOnCrash()));
        settings.set(Key.DS4_AUTO_START_ON_BOOT, Boolean.toString(c.autoStartOnBoot()));
        settings.set(Key.DS4_ATTACH_IF_RUNNING, Boolean.toString(c.attachIfRunning()));
        settings.set(Key.DS4_ENABLED, Boolean.toString(c.enabled()));
    }

    private static int parseInt(String s)
    {
        try {
            return Integer.parseInt(s);
        }
        catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean parseBool(String s)
    {
        return "true".equalsIgnoreCase(s.trim());
    }

    // ── Hooks used by tests to bypass auto-start machinery ────────

    String currentEndpoint()
    {
        return endpointFor(loadConfig());
    }

    Ds4Config snapshotRunningConfig()
    {
        return runningConfig;
    }

    /** Test-only escape hatch: drain queued supervisor work so a
     *  test can assert post-conditions without race-y polling. */
    void awaitQuiet(Duration timeout)
            throws InterruptedException
    {
        long endNanos = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < endNanos) {
            // Submit a no-op and wait for it; that means everything
            // queued before it has run.
            try {
                supervisor.submit(Ds4LifecycleService::noop).get(50, TimeUnit.MILLISECONDS);
                return;
            }
            catch (ExecutionException | TimeoutException e) {
                Thread.sleep(20);
            }
        }
    }

    private static void noop()
    {
        // intentionally empty — submit target for awaitQuiet
    }
}

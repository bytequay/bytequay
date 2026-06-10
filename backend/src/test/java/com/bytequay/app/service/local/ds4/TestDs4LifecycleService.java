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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TestDs4LifecycleService
{
    @Test
    void freshInstallStaysInNotConfiguredUntilBinaryPathIsSet()
    {
        InMemorySettings settings = new InMemorySettings();
        Ds4LifecycleService service = new Ds4LifecycleService(settings, mock(ApplicationEventPublisher.class));

        Ds4Status snap = service.status();
        assertThat(snap.state()).isEqualTo(Ds4State.NOT_CONFIGURED);
        assertThat(snap.endpoint()).isEqualTo("http://127.0.0.1:8000");
        assertThat(snap.pid()).isEqualTo(-1L);
        assertThat(snap.spawnedByUs()).isFalse();
    }

    @Test
    void settingBinaryPathThroughSetConfigDropsFromNotConfiguredToStopped()
    {
        InMemorySettings settings = new InMemorySettings();
        Ds4LifecycleService service = new Ds4LifecycleService(settings, mock(ApplicationEventPublisher.class));

        Ds4Config cfg = Ds4Config.defaults();
        Ds4Config withPath = new Ds4Config(
                "/usr/local/bin/ds4-server", cfg.port(), cfg.model(), cfg.quant(),
                cfg.contextTokens(), cfg.kvCacheDir(), cfg.kvDiskBudgetMb(),
                cfg.thinkingDefault(), cfg.trace(),
                cfg.repoDir(), cfg.modelVariant(), cfg.installUrl(),
                cfg.autoRestartOnCrash(), cfg.autoStartOnBoot(), cfg.attachIfRunning());
        service.setConfig(withPath);

        Ds4Status snap = service.status();
        assertThat(snap.state()).isEqualTo(Ds4State.STOPPED);
        // The persisted config is what loadConfig now sees on the next call.
        assertThat(service.getConfig().binaryPath()).isEqualTo("/usr/local/bin/ds4-server");
    }

    @Test
    void startBlockingWithoutBinaryFlipsToNotConfiguredWithAClearLastError()
            throws InterruptedException
    {
        InMemorySettings settings = new InMemorySettings();
        settings.set(Key.DS4_BINARY_PATH, ""); // explicit blank
        Ds4LifecycleService service = new Ds4LifecycleService(settings, mock(ApplicationEventPublisher.class));

        service.startBlocking();
        service.awaitQuiet(Duration.ofSeconds(2));

        Ds4Status snap = service.status();
        assertThat(snap.state()).isEqualTo(Ds4State.NOT_CONFIGURED);
        assertThat(snap.lastError()).contains("binary");
    }

    @Test
    void startBlockingWithAMissingBinaryStaysAtNotConfigured(@TempDir Path workingDir)
            throws InterruptedException
    {
        InMemorySettings settings = new InMemorySettings();
        settings.set(Key.DS4_BINARY_PATH, workingDir.resolve("does-not-exist").toString());
        Ds4LifecycleService service = new Ds4LifecycleService(settings, mock(ApplicationEventPublisher.class));

        service.startBlocking();
        service.awaitQuiet(Duration.ofSeconds(2));

        Ds4Status snap = service.status();
        assertThat(snap.state()).isEqualTo(Ds4State.NOT_CONFIGURED);
        assertThat(snap.lastError()).contains("Binary not found");
    }

    @Test
    void buildArgsUsesTheUpstreamDs4ServerFlagNames()
    {
        // Matches the README §Server example:
        //   ./ds4-server --ctx 100000 --kv-disk-dir /tmp/ds4-kv --kv-disk-space-mb 8192
        Ds4Config cfg = new Ds4Config(
                "/opt/ds4-server", 8123, "deepseek-v4-flash", "q4_K_M",
                65_536, "/var/kv", 50_000,
                /* thinkingDefault */ true, /* trace */ true,
                /* repoDir */ "/opt/ds4", /* modelVariant */ "q2-imatrix",
                /* installUrl */ "", true, true, true);

        List<String> args = Ds4LifecycleService.buildArgs(cfg);

        // The binary lands as argv[0]; the rest is verbatim flags.
        assertThat(args.get(0)).isEqualTo("/opt/ds4-server");
        assertThat(args).contains("--port", "8123");
        assertThat(args).contains("--ctx", "65536");
        assertThat(args).contains("--kv-disk-dir", "/var/kv");
        assertThat(args).contains("--kv-disk-space-mb", "50000");
        // Trace takes a path destination (matching the eval tools).
        assertThat(args).containsSequence("--trace", "/var/kv/trace.log");
        // No --model / --quant / --thinking — those are either
        // baked into the chosen GGUF or per-request only.
        assertThat(args).doesNotContain("--model", "--quant", "--thinking");
    }

    @Test
    void buildArgsOmitsTheTraceFlagWhenOff()
    {
        Ds4Config cfg = new Ds4Config(
                "/opt/ds4-server", 8000, "deepseek-v4-flash", "q4_K_M",
                32_768, "/var/kv", 40_000,
                /* thinkingDefault */ false, /* trace */ false,
                /* repoDir */ "/opt/ds4", /* modelVariant */ "q2-imatrix",
                /* installUrl */ "", true, true, true);

        List<String> args = Ds4LifecycleService.buildArgs(cfg);

        assertThat(args).doesNotContain("--trace");
    }

    @Test
    void backoffLadderHonoursTheDesignDocDelays()
    {
        // 1s · 2s · 5s · 15s — the doc-specified back-off ladder.
        assertThat(Ds4LifecycleService.BACKOFF_MS).containsExactly(1_000L, 2_000L, 5_000L, 15_000L);
        assertThat(Ds4LifecycleService.MAX_RESTART_ATTEMPTS).isEqualTo(5);
    }

    @Test
    void startBlockingSpawnsARealBinaryAndProbesItToHealthy(@TempDir Path workingDir)
            throws IOException, InterruptedException
    {
        // Drive the lifecycle service against a fake `ds4-server`
        // built from a tiny shell script that boots an HTTP server
        // answering 200 on GET /v1/models. That's enough to walk
        // STARTING → RUNNING and gives us a real PID + exit handle
        // for stopBlocking to terminate.
        int port = freePort();
        Path fakeBinary = writeFakeServer(workingDir, port);

        InMemorySettings settings = new InMemorySettings();
        settings.set(Key.DS4_BINARY_PATH, fakeBinary.toString());
        settings.set(Key.DS4_PORT, Integer.toString(port));
        // The fake server doesn't understand the real CLI flags
        // ds4-server takes, so trim the args we send to the shape
        // it accepts. The lifecycle service still spawns it via
        // ProcessBuilder; we just drop the flag set that would
        // confuse a plain `python3 -m http.server` style stub.
        settings.set(Key.DS4_QUANT, "");
        settings.set(Key.DS4_THINKING_DEFAULT, "false");
        settings.set(Key.DS4_TRACE, "false");
        settings.set(Key.DS4_KV_CACHE_DIR, workingDir.resolve("kv").toString());

        RecordingPublisher events = new RecordingPublisher();
        Ds4LifecycleService service = new Ds4LifecycleService(settings, events);

        service.startBlocking();
        service.awaitQuiet(Duration.ofSeconds(60));

        Ds4Status snap = service.status();
        assertThat(snap.state()).isEqualTo(Ds4State.RUNNING);
        assertThat(snap.pid()).isGreaterThan(0L);
        assertThat(snap.spawnedByUs()).isTrue();
        assertThat(snap.startedAt()).isNotNull();
        // Every transition published an event; we ought to have at
        // least STARTING and RUNNING in the trail.
        assertThat(events.events).extracting(Ds4StatusEvent::to)
                .extracting(Ds4Status::state)
                .contains(Ds4State.STARTING, Ds4State.RUNNING);

        service.stopBlocking();
        service.awaitQuiet(Duration.ofSeconds(30));

        assertThat(service.status().state()).isEqualTo(Ds4State.STOPPED);
    }

    /** Picks a random unused TCP port by binding 0 and immediately
     *  releasing. There's a thin race window but nothing else in this
     *  test grabs it before the fake server starts. */
    private static int freePort()
            throws IOException
    {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    /** Tiny shell script that uses python3's built-in http.server to
     *  return 200 on every request. Good enough to satisfy
     *  {@code GET /v1/models} for the lifecycle probe. */
    private static Path writeFakeServer(Path dir, int port)
            throws IOException
    {
        Path script = dir.resolve("fake-ds4-server.sh");
        String body = ""
                + "#!/bin/sh\n"
                + "exec python3 -c '\n"
                + "import http.server, socketserver, sys\n"
                + "PORT = " + port + "\n"
                + "class H(http.server.BaseHTTPRequestHandler):\n"
                + "    def do_GET(self):\n"
                + "        self.send_response(200)\n"
                + "        self.send_header(\"Content-Type\", \"application/json\")\n"
                + "        self.end_headers()\n"
                + "        self.wfile.write(b\"{}\")\n"
                + "    def log_message(self, format, *args):\n"
                + "        pass\n"
                + "with socketserver.TCPServer((\"127.0.0.1\", PORT), H) as s:\n"
                + "    s.serve_forever()\n"
                + "'\n";
        Files.writeString(script, body, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return script;
    }

    private static final class RecordingPublisher
            implements ApplicationEventPublisher
    {
        final List<Ds4StatusEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void publishEvent(Object event)
        {
            if (event instanceof Ds4StatusEvent e) {
                events.add(e);
            }
        }
    }

    private static final class InMemorySettings
            implements AppSettingsStore
    {
        private final Map<String, String> map = new HashMap<>();

        @Override
        public Optional<String> get(String key)
        {
            return Optional.ofNullable(map.get(key));
        }

        @Override
        public void set(String key, String value)
        {
            map.put(key, value);
        }
    }
}

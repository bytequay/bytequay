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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns a private, ByteQuay-managed copy of the {@code codegraph} CLI so the
 * user never installs it themselves. The binary lives under the app support
 * dir (never on the user's PATH), and {@link CodeGraphRunner} invokes it by
 * absolute path. Acquisition reuses the upstream installer
 * ({@code install.sh}), which downloads the prebuilt per-platform binary and
 * self-updates via {@code codegraph upgrade}; we only point its two directory
 * overrides at our managed location.
 */
@Component
public class CodeGraphInstaller
{
    private static final Logger log = LoggerFactory.getLogger(CodeGraphInstaller.class);

    private static final String INSTALL_URL =
            "https://raw.githubusercontent.com/colbymchenry/codegraph/main/install.sh";
    /** Pinned release. Bump to upgrade — a reviewed one-line change shipped in
     *  a ByteQuay release, not a background chase of latest. The installer
     *  reinstalls whenever this version's directory isn't the one on disk. */
    private static final String CODEGRAPH_VERSION = "v1.4.1";
    private static final long INSTALL_TIMEOUT_SECONDS = 300;

    private final boolean autoInstall;
    private final Path bundleDir;
    private final Path binDir;
    private final Path binary;
    private final AtomicBoolean installing = new AtomicBoolean(false);

    public CodeGraphInstaller(@Value("${bytequay.codegraph.auto-install:true}") boolean autoInstall)
    {
        this.autoInstall = autoInstall;
        Path tools = Path.of(System.getProperty("user.home"),
                "Library", "Application Support", "ByteQuay", "tools");
        this.bundleDir = tools.resolve("codegraph");
        this.binDir = tools.resolve("bin");
        this.binary = binDir.resolve("codegraph");
    }

    /**
     * Absolute path to the managed binary, present only once the pinned
     * version is installed. The version directory is the state: if a bump
     * left a different version (or nothing) on disk, this reads as absent so
     * {@link #ensureInstalledAsync()} reinstalls the pin. When management is
     * disabled (tests, opt-out) this is always empty so callers fall back to
     * a {@code codegraph} on PATH — the pre-managed behaviour.
     */
    public Optional<Path> installedBinary()
    {
        if (!autoInstall) {
            return Optional.empty();
        }
        Path versioned = bundleDir.resolve("versions").resolve(CODEGRAPH_VERSION);
        return Files.isExecutable(binary) && Files.isDirectory(versioned)
                ? Optional.of(binary) : Optional.empty();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void installOnStartup()
    {
        ensureInstalledAsync();
    }

    /**
     * Installs the managed binary in the background if it is not already
     * present. Idempotent: a second call while an install is in flight, or
     * after success, is a no-op. A no-op entirely when management is disabled,
     * so tests never reach out to the network.
     */
    public void ensureInstalledAsync()
    {
        if (!autoInstall || installedBinary().isPresent() || !installing.compareAndSet(false, true)) {
            return;
        }
        Thread.ofVirtual().name("codegraph-install").start(() -> {
            try {
                install();
            }
            finally {
                installing.set(false);
            }
        });
    }

    private void install()
    {
        try {
            Files.createDirectories(bundleDir);
            Files.createDirectories(binDir);
            ProcessBuilder pb = new ProcessBuilder(
                    "sh", "-c", "curl -fsSL " + INSTALL_URL + " | sh");
            pb.environment().put("CODEGRAPH_VERSION", CODEGRAPH_VERSION);
            pb.environment().put("CODEGRAPH_INSTALL_DIR", bundleDir.toString());
            pb.environment().put("CODEGRAPH_BIN_DIR", binDir.toString());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            // Drain on a virtual thread so the pipe can't fill and wedge the
            // installer before the timed wait below can reap it.
            String[] captured = new String[1];
            Thread drain = Thread.ofVirtual().start(() -> captured[0] = drain(process.getInputStream()));
            boolean finished = process.waitFor(INSTALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                drain.interrupt();
                log.warn("codegraph install timed out after {}s", INSTALL_TIMEOUT_SECONDS);
                return;
            }
            drain.join(5_000);
            String output = captured[0] == null ? "" : captured[0].strip();
            if (process.exitValue() == 0 && installedBinary().isPresent()) {
                log.info("codegraph ready at {}", binary);
            }
            else {
                log.warn("codegraph install failed (exit {}): {}", process.exitValue(), output);
            }
        }
        catch (IOException e) {
            log.warn("codegraph install error: {}", e.getMessage());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String drain(InputStream in)
    {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException ignored) {
            return "";
        }
    }
}

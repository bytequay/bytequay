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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

/**
 * In-app fetcher for the ds4-server binary. The user can either
 * point the lifecycle service at a binary they installed elsewhere
 * (preferred path on a developer machine) or have ByteQuay download
 * the binary into the app-owned location below — the lifecycle
 * service then records {@code binary_path} pointing at the downloaded
 * file and a follow-up Start works without further configuration.
 *
 * <p>Install URL is configurable via {@code ds4.install_url} so the
 * user can update the source without redeploying the app; the
 * service refuses to fetch from a blank URL with a clear "configure
 * the download URL first" error rather than 404'ing a guess.
 *
 * <p>Status reads via {@link #current()} surface progress: idle,
 * downloading (with bytes-so-far / total), failed (with reason),
 * ready (with the resolved path). The Settings page polls this while
 * an install is in flight.
 */
@Service
public class Ds4InstallerService
{
    private static final Logger log = LoggerFactory.getLogger(Ds4InstallerService.class);

    private final Ds4LifecycleService lifecycle;
    private final HttpClient http;
    private final AtomicReference<InstallStatus> status = new AtomicReference<>(InstallStatus.idle());

    public Ds4InstallerService(Ds4LifecycleService lifecycle)
    {
        this.lifecycle = requireNonNull(lifecycle, "lifecycle is null");
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public InstallStatus current()
    {
        return status.get();
    }

    /** Spawn the download on a virtual thread so the controller
     *  returns immediately; the Settings page polls {@link #current()}
     *  for progress. */
    public InstallStatus startInstall()
    {
        InstallStatus snap = status.get();
        if (snap.phase() == InstallPhase.DOWNLOADING) {
            return snap;
        }
        Ds4Config cfg = lifecycle.getConfig();
        String url = cfg.installUrl();
        if (url == null || url.isBlank()) {
            InstallStatus failed = InstallStatus.failed(
                    "ds4.install_url is empty. Set the download URL in Settings → Local AI (ds4) first.");
            status.set(failed);
            return failed;
        }
        Path destination = defaultInstallPath();
        status.set(InstallStatus.downloading(url, destination, 0L, -1L));
        Thread.ofVirtual().name("ds4-installer").start(() -> downloadInto(url, destination, cfg));
        return status.get();
    }

    private void downloadInto(String url, Path destination, Ds4Config cfg)
    {
        try {
            Files.createDirectories(destination.getParent());
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMinutes(20))
                    .GET()
                    .build();
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                String msg = "Download returned HTTP " + resp.statusCode();
                log.warn("ds4 install failed: {}", msg);
                status.set(InstallStatus.failed(msg));
                return;
            }
            long contentLength = resp.headers().firstValueAsLong("Content-Length").orElse(-1L);
            Path tmp = destination.getParent().resolve(destination.getFileName() + ".part");
            try (InputStream in = resp.body()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            try {
                Files.setPosixFilePermissions(destination,
                        PosixFilePermissions.fromString("rwxr-xr-x"));
            }
            catch (UnsupportedOperationException | IOException ignored) {
                // Non-POSIX filesystem; the +x bit is the user's
                // problem on those targets. Logging keeps the trail.
                log.info("Could not set executable bit on {}; user may need to chmod manually", destination);
            }
            // Record the resolved path on the lifecycle config so a
            // follow-up Start works without manual configuration.
            Ds4Config next = new Ds4Config(
                    destination.toString(), cfg.port(), cfg.model(), cfg.quant(),
                    cfg.contextTokens(), cfg.kvCacheDir(), cfg.kvDiskBudgetMb(),
                    cfg.thinkingDefault(), cfg.trace(), cfg.installUrl(),
                    cfg.autoRestartOnCrash(), cfg.autoStartOnBoot(), cfg.attachIfRunning());
            lifecycle.setConfig(next);
            status.set(InstallStatus.ready(destination, contentLength < 0 ? Files.size(destination) : contentLength));
            log.info("ds4 binary installed at {}", destination);
        }
        catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("ds4 install failed: {}", e.getMessage());
            status.set(InstallStatus.failed(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    /** App-owned install path under
     *  {@code ~/Library/Application Support/ds4/bin/ds4-server} on
     *  macOS, {@code ~/.ds4/bin/ds4-server} elsewhere. */
    private static Path defaultInstallPath()
    {
        String home = System.getProperty("user.home");
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return Path.of(home, "Library", "Application Support", "ds4", "bin", "ds4-server");
        }
        return Path.of(home, ".ds4", "bin", "ds4-server");
    }

    public enum InstallPhase
    {
        IDLE, DOWNLOADING, READY, FAILED
    }

    public record InstallStatus(
            InstallPhase phase,
            String sourceUrl,
            String destination,
            long bytesSoFar,
            long bytesTotal,
            String error)
    {
        public static InstallStatus idle()
        {
            return new InstallStatus(InstallPhase.IDLE, null, null, 0L, -1L, null);
        }

        public static InstallStatus downloading(String sourceUrl, Path destination, long bytesSoFar, long bytesTotal)
        {
            return new InstallStatus(InstallPhase.DOWNLOADING, sourceUrl, destination.toString(),
                    bytesSoFar, bytesTotal, null);
        }

        public static InstallStatus ready(Path destination, long bytesTotal)
        {
            return new InstallStatus(InstallPhase.READY, null, destination.toString(),
                    bytesTotal, bytesTotal, null);
        }

        public static InstallStatus failed(String error)
        {
            return new InstallStatus(InstallPhase.FAILED, null, null, 0L, -1L, error);
        }
    }
}

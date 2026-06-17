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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

/**
 * Multi-step installer for the ds4 inference engine. antirez/ds4
 * ships as source — there is no pre-built release tarball — so the
 * v1 surface is:
 *
 * <ol>
 *   <li><strong>Phase 1 — source</strong>. Either {@code git clone}
 *       the upstream repo into {@code repoDir} and run {@code make},
 *       <em>or</em> point at an existing checkout the user already
 *       built ({@code reuseExisting=true}).</li>
 *   <li><strong>Phase 2 — model weights</strong>. If
 *       {@code <repoDir>/ds4flash.gguf} is missing (or broken), run
 *       {@code ./download_model.sh <modelVariant>} from {@code repoDir}.
 *       This is the slow piece — 80+ GB on a fresh install.</li>
 *   <li><strong>Phase 3 — finalise</strong>. Stamp the lifecycle
 *       service's {@code binary_path} with {@code <repoDir>/ds4-server}
 *       so the next Start works without further configuration.</li>
 * </ol>
 *
 * <p>The Settings page polls {@link #current()} while an install is
 * in flight; transitions are intentionally coarse so the UI can show
 * what the user is waiting on without scraping logs.
 */
@Service
public class Ds4InstallerService
{
    private static final Logger log = LoggerFactory.getLogger(Ds4InstallerService.class);

    private final Ds4LifecycleService lifecycle;
    private final AtomicReference<InstallStatus> status = new AtomicReference<>(InstallStatus.idle());

    public Ds4InstallerService(Ds4LifecycleService lifecycle)
    {
        this.lifecycle = requireNonNull(lifecycle, "lifecycle is null");
    }

    public InstallStatus current()
    {
        return status.get();
    }

    /**
     * Start a background install. Returns immediately with the
     * snapshot the supervisor began with; the Settings page polls
     * {@link #current()} for progress.
     */
    public InstallStatus startInstall(InstallRequest req)
    {
        requireNonNull(req, "req is null");
        InstallStatus snap = status.get();
        if (snap.phase() != InstallPhase.IDLE
                && snap.phase() != InstallPhase.READY
                && snap.phase() != InstallPhase.FAILED) {
            return snap;
        }
        Ds4Config cfg = lifecycle.getConfig();
        String repoDir = req.repoDir() == null || req.repoDir().isBlank()
                ? defaultRepoDir()
                : req.repoDir().trim();
        String modelVariant = req.modelVariant() == null || req.modelVariant().isBlank()
                ? (cfg.modelVariant() == null || cfg.modelVariant().isBlank()
                        ? "q2-imatrix"
                        : cfg.modelVariant())
                : req.modelVariant().trim();
        String installUrl = cfg.installUrl() == null || cfg.installUrl().isBlank()
                ? "https://github.com/antirez/ds4.git"
                : cfg.installUrl();

        Path repo = Path.of(repoDir);
        status.set(InstallStatus.starting(repoDir, modelVariant));
        Thread.ofVirtual().name("ds4-installer").start(
                () -> runInstall(repo, modelVariant, installUrl, req.reuseExisting()));
        return status.get();
    }

    private void runInstall(Path repoDir, String modelVariant, String installUrl, boolean reuseExisting)
    {
        try {
            // Phase 1: source.
            if (reuseExisting) {
                if (!Files.exists(repoDir.resolve("ds4-server"))) {
                    fail("No `ds4-server` binary at " + repoDir + ". Build it (`make`) "
                            + "or pick a different directory.");
                    return;
                }
            }
            else {
                if (Files.exists(repoDir)) {
                    boolean populated;
                    try (Stream<Path> entries = Files.list(repoDir)) {
                        populated = entries.findAny().isPresent();
                    }
                    if (populated) {
                        fail("Install directory " + repoDir + " is not empty. Pick an empty "
                                + "destination or check 'I already have ds4 built'.");
                        return;
                    }
                }
                Files.createDirectories(repoDir.getParent() == null ? repoDir : repoDir.getParent());
                progress(InstallPhase.CLONING, repoDir, modelVariant,
                        "git clone " + installUrl + " " + repoDir);
                int rc = runCommand(
                        repoDir.getParent() == null ? repoDir : repoDir.getParent(),
                        List.of("git", "clone", installUrl, repoDir.toString()));
                if (rc != 0) {
                    fail("git clone failed with exit code " + rc);
                    return;
                }
                progress(InstallPhase.BUILDING, repoDir, modelVariant, "make (macOS Metal)");
                rc = runCommand(repoDir, List.of("make"));
                if (rc != 0) {
                    fail("make failed with exit code " + rc + ". Check Xcode CLT is "
                            + "installed (xcode-select --install) and re-run.");
                    return;
                }
                if (!Files.exists(repoDir.resolve("ds4-server"))) {
                    fail("Build finished but `ds4-server` was not produced under " + repoDir + ".");
                    return;
                }
            }

            // Phase 2: model.
            Path modelSymlink = repoDir.resolve("ds4flash.gguf");
            if (!Files.exists(modelSymlink)) {
                progress(InstallPhase.DOWNLOADING_MODEL, repoDir, modelVariant,
                        "./download_model.sh " + modelVariant + " (this can take a while)");
                int rc = runCommand(repoDir,
                        List.of("./download_model.sh", modelVariant));
                if (rc != 0) {
                    fail("download_model.sh failed with exit code " + rc);
                    return;
                }
                if (!Files.exists(modelSymlink)) {
                    fail("Model download reported success but ds4flash.gguf is missing. "
                            + "Check the script output under the install dir.");
                    return;
                }
            }

            // Phase 3: stamp the lifecycle config.
            Ds4Config cfg = lifecycle.getConfig();
            Path binary = repoDir.resolve("ds4-server");
            Ds4Config next = new Ds4Config(
                    binary.toString(), cfg.port(), cfg.model(), cfg.quant(),
                    cfg.contextTokens(), cfg.kvCacheDir(), cfg.kvDiskBudgetMb(),
                    cfg.thinkingDefault(), cfg.trace(),
                    repoDir.toString(), modelVariant, cfg.installUrl(),
                    cfg.autoRestartOnCrash(), cfg.autoStartOnBoot(), cfg.attachIfRunning(),
                    cfg.enabled());
            lifecycle.setConfig(next);
            status.set(InstallStatus.ready(repoDir, modelVariant));
            log.info("ds4 ready: {} (model variant {})", repoDir, modelVariant);
        }
        catch (IOException e) {
            log.warn("ds4 install failed: {}", e.getMessage());
            fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Interrupted.");
        }
    }

    /** Run a shell command in {@code workingDir}, blocking until it
     *  exits. stdout/stderr are streamed at INFO so the user can
     *  trace what happened from the backend logs while the Settings
     *  page only shows the coarse phase. */
    private int runCommand(Path workingDir, List<String> argv)
            throws IOException, InterruptedException
    {
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.directory(workingDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        Thread drain = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    log.info("[ds4-install] {}", line);
                }
            }
            catch (IOException ignored) {
                // stream closed at exit
            }
        }, "ds4-install-stdout");
        drain.setDaemon(true);
        drain.start();
        boolean exited = p.waitFor(120, TimeUnit.MINUTES);
        if (!exited) {
            p.destroyForcibly();
            throw new IOException("command timed out: " + String.join(" ", argv));
        }
        return p.exitValue();
    }

    private void progress(InstallPhase phase, Path repoDir, String modelVariant, String step)
    {
        status.set(new InstallStatus(
                phase, repoDir.toString(), modelVariant, step, null));
    }

    private void fail(String message)
    {
        status.set(new InstallStatus(
                InstallPhase.FAILED, null, null, null, message));
    }

    /** App-owned default install dir under
     *  {@code ~/Library/Application Support/ds4/repo} on macOS, or
     *  {@code ~/.ds4/repo} elsewhere. */
    private static String defaultRepoDir()
    {
        String home = System.getProperty("user.home");
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return home + "/Library/Application Support/ds4/repo";
        }
        return home + "/.ds4/repo";
    }

    public enum InstallPhase
    {
        IDLE, CLONING, BUILDING, DOWNLOADING_MODEL, READY, FAILED
    }

    /** Body of {@code POST /api/ds4/install}. */
    public record InstallRequest(
            String repoDir,
            boolean reuseExisting,
            String modelVariant)
    {
    }

    public record InstallStatus(
            InstallPhase phase,
            String repoDir,
            String modelVariant,
            String currentStep,
            String error)
    {
        public static InstallStatus idle()
        {
            return new InstallStatus(InstallPhase.IDLE, null, null, null, null);
        }

        public static InstallStatus starting(String repoDir, String modelVariant)
        {
            return new InstallStatus(InstallPhase.CLONING, repoDir, modelVariant, "Preparing…", null);
        }

        public static InstallStatus ready(Path repoDir, String modelVariant)
        {
            return new InstallStatus(InstallPhase.READY, repoDir.toString(), modelVariant, null, null);
        }
    }
}

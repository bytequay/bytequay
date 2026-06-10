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
package com.bytequay.app.web;

import com.bytequay.app.beans.ds4.Ds4MetricsDto;
import com.bytequay.app.beans.ds4.Ds4StatusDto;
import com.bytequay.app.service.local.ds4.Ds4Config;
import com.bytequay.app.service.local.ds4.Ds4InstallerService;
import com.bytequay.app.service.local.ds4.Ds4Instrumentation;
import com.bytequay.app.service.local.ds4.Ds4LifecycleService;
import com.bytequay.app.service.local.ds4.Ds4State;
import com.bytequay.app.service.local.ds4.Ds4Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * REST surface for the ds4 local-inference subprocess. None of these
 * endpoints are agent-callable — they're explicitly listed in the
 * "never exposed (safety wall)" section of the agent-tool catalog so
 * an LLM never gets to Stop / Restart / reconfigure the server.
 *
 * <p>Endpoints follow the same local-host-only contract the rest of
 * ByteQuay's REST API uses (no auth, sanity checks only).
 */
@RestController
@RequestMapping("/api/ds4")
public class Ds4Controller
{
    private final Ds4LifecycleService lifecycle;
    private final Ds4InstallerService installer;
    private final Ds4Instrumentation instrumentation;

    public Ds4Controller(
            Ds4LifecycleService lifecycle,
            Ds4InstallerService installer,
            Ds4Instrumentation instrumentation)
    {
        this.lifecycle = requireNonNull(lifecycle, "lifecycle is null");
        this.installer = requireNonNull(installer, "installer is null");
        this.instrumentation = requireNonNull(instrumentation, "instrumentation is null");
    }

    /** GET /api/ds4/status — read-mostly snapshot the floating
     *  widget polls every few seconds. */
    @GetMapping("/status")
    public Ds4StatusDto status()
    {
        return Ds4StatusDto.from(lifecycle.status());
    }

    /** POST /api/ds4/start — 202 once the supervisor has queued the
     *  Start. 409 when the server is already running or attached, so
     *  the UI surfaces a clear error rather than a phantom 200. */
    @PostMapping("/start")
    public ResponseEntity<Ds4StatusDto> start()
    {
        Ds4State current = lifecycle.status().state();
        if (current == Ds4State.RUNNING || current == Ds4State.STARTING) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Ds4StatusDto.from(lifecycle.status()));
        }
        Ds4Status next = lifecycle.start();
        return ResponseEntity.accepted().body(Ds4StatusDto.from(next));
    }

    /** POST /api/ds4/stop — 200 with {@code requiresConfirm=true}
     *  when the server is attached (we didn't spawn it) so the UI
     *  prompts for confirmation before re-posting with
     *  {@code ?confirm=true}. 202 once the SIGTERM has been queued.
     *  409 when the server is already stopped. */
    @PostMapping("/stop")
    public ResponseEntity<StopResponse> stop(
            @RequestParam(value = "confirm", defaultValue = "false") boolean confirm)
    {
        Ds4Status snap = lifecycle.status();
        if (snap.state() == Ds4State.STOPPED || snap.state() == Ds4State.NOT_CONFIGURED) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new StopResponse(false, Ds4StatusDto.from(snap),
                            "Server is not running."));
        }
        if (!snap.spawnedByUs() && !confirm) {
            return ResponseEntity.ok(new StopResponse(true, Ds4StatusDto.from(snap),
                    "ds4 is shared with external clients — stopping it affects them too."));
        }
        Ds4Status next = lifecycle.stop();
        return ResponseEntity.accepted().body(new StopResponse(false, Ds4StatusDto.from(next), null));
    }

    /** POST /api/ds4/restart — graceful Stop + Start with the
     *  currently-persisted config. */
    @PostMapping("/restart")
    public ResponseEntity<Ds4StatusDto> restart()
    {
        Ds4Status next = lifecycle.restart();
        return ResponseEntity.accepted().body(Ds4StatusDto.from(next));
    }

    /** GET /api/ds4/config — read the apply-on-restart config. */
    @GetMapping("/config")
    public Ds4Config getConfig()
    {
        return lifecycle.getConfig();
    }

    /** PUT /api/ds4/config — write the config without relaunching.
     *  The response includes {@code restartRequired=true} when the
     *  server is currently running so the UI shows the "applies on
     *  restart" banner. A separate {@code POST /restart} (or
     *  {@code ?restart=true} below) triggers the relaunch. */
    @PutMapping("/config")
    public ConfigResponse setConfig(
            @RequestBody Ds4Config config,
            @RequestParam(value = "restart", defaultValue = "false") boolean restartNow)
    {
        if (restartNow) {
            lifecycle.restartWithConfig(config);
        }
        else {
            lifecycle.setConfig(config);
        }
        Ds4Status snap = lifecycle.status();
        boolean restartRequired = !restartNow
                && (snap.state() == Ds4State.RUNNING || snap.state() == Ds4State.STARTING);
        return new ConfigResponse(config, restartRequired, Ds4StatusDto.from(snap));
    }

    /** GET /api/ds4/metrics — memory + throughput + recent calls
     *  snapshot for the Metrics tab. v1 only carries ByteQuay's own
     *  calls; external clients' traffic shows up once the front-door
     *  proxy follow-up lands. */
    @GetMapping("/metrics")
    public Ds4MetricsDto metrics()
    {
        // Memory probe is a no-op stub in v1 — the production probe
        // shells out to `ps` against the ds4 PID and is wired in
        // alongside the lifecycle service when the OS-specific
        // sampler lands. The instrumentation ring still returns
        // useful throughput / latency / recent-requests data.
        return instrumentation.snapshot(/* memoryProbe */ null);
    }

    /** GET /api/ds4/install/status — progress of the in-app
     *  installer (idle / downloading / ready / failed). */
    @GetMapping("/install/status")
    public Ds4InstallerService.InstallStatus installStatus()
    {
        return installer.current();
    }

    /** POST /api/ds4/install — kick off (or resume) the multi-step
     *  installer. Body fields:
     *  <ul>
     *    <li>{@code repoDir} — install destination (or existing
     *        checkout when reusing). Optional; defaults to
     *        {@code ~/Library/Application Support/ds4/repo}.</li>
     *    <li>{@code reuseExisting} — skip clone+build and validate
     *        the binary at {@code repoDir/ds4-server} instead.</li>
     *    <li>{@code modelVariant} — argument passed to
     *        {@code ./download_model.sh}; defaults to
     *        {@code q2-imatrix}.</li>
     *  </ul>
     *  Auto-configures {@code binary_path} to
     *  {@code <repoDir>/ds4-server} on success. */
    @PostMapping("/install")
    public ResponseEntity<Ds4InstallerService.InstallStatus> install(
            @RequestBody(required = false) Ds4InstallerService.InstallRequest body)
    {
        Ds4InstallerService.InstallRequest req = body == null
                ? new Ds4InstallerService.InstallRequest(null, false, null)
                : body;
        Ds4InstallerService.InstallStatus status = installer.startInstall(req);
        if (status.phase() == Ds4InstallerService.InstallPhase.FAILED) {
            return ResponseEntity.badRequest().body(status);
        }
        return ResponseEntity.accepted().body(status);
    }

    /** GET /api/ds4/logs — tail of combined stdout+stderr the
     *  lifecycle service has captured. Used by the Management tab
     *  for crash diagnosis. */
    @GetMapping("/logs")
    public List<String> logs(
            @RequestParam(value = "limit", defaultValue = "200") int limit)
    {
        return lifecycle.recentLogs(limit);
    }

    public record StopResponse(boolean requiresConfirm, Ds4StatusDto status, String message)
    {
    }

    public record ConfigResponse(Ds4Config config, boolean restartRequired, Ds4StatusDto status)
    {
    }
}

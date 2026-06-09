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

import java.util.Locale;

/**
 * Apply-on-restart configuration for the ds4 subprocess. Every field
 * folds into either a CLI flag or an environment variable on the
 * next spawn — the server has no hot-reload, so saving is "Stop +
 * relaunch with these values" and never an inline patch.
 *
 * <p>Carries the full surface the Management tab exposes; legacy
 * keys missing from {@code app_settings} fall back to the defaults
 * baked here ({@link #defaults()}).
 *
 * @param binaryPath        absolute path to the {@code ds4-server}
 *                          executable. Null / blank means the
 *                          lifecycle service stays in
 *                          {@link Ds4State#NOT_CONFIGURED}.
 * @param port              TCP port the server listens on.
 * @param model             model id (e.g. {@code deepseek-v4-flash}).
 * @param quant             quantisation flag (e.g. {@code q4_K_M}).
 * @param contextTokens     maximum context window in tokens.
 * @param kvCacheDir        absolute path the server should persist
 *                          its KV cache under (never /tmp — wipes on
 *                          reboot defeat the whole point of the disk
 *                          KV cache).
 * @param kvDiskBudgetMb    soft cap on KV disk use, in megabytes.
 * @param thinkingDefault   whether thinking blocks default on.
 * @param trace             {@code --trace} flag for cache-hit logs.
 * @param installUrl        URL the in-app "Download ds4" affordance
 *                          fetches the binary from. Decoupled from
 *                          the binary path so the user can update
 *                          the source without re-installing the app.
 * @param autoRestartOnCrash whether the supervisor should reschedule
 *                          a Start after a crash with back-off, or
 *                          park the state at CRASHED.
 * @param autoStartOnBoot   whether the lifecycle service should fire
 *                          a Start at {@code ApplicationReadyEvent}.
 * @param attachIfRunning   whether the boot probe should attach to a
 *                          healthy server an external client started
 *                          rather than abort.
 */
public record Ds4Config(
        String binaryPath,
        int port,
        String model,
        String quant,
        int contextTokens,
        String kvCacheDir,
        int kvDiskBudgetMb,
        boolean thinkingDefault,
        boolean trace,
        String installUrl,
        boolean autoRestartOnCrash,
        boolean autoStartOnBoot,
        boolean attachIfRunning)
{
    /** Defaults baked into the app for a fresh install. Binary path
     *  is intentionally null — the design calls for an explicit
     *  user configure (or in-app download), never a silent first
     *  spawn. */
    public static Ds4Config defaults()
    {
        return new Ds4Config(
                /* binaryPath */ null,
                /* port */ 8000,
                /* model */ "deepseek-v4-flash",
                /* quant */ "q4_K_M",
                /* contextTokens */ 32_768,
                /* kvCacheDir */ defaultKvCacheDir(),
                /* kvDiskBudgetMb */ 40_960,
                /* thinkingDefault */ true,
                /* trace */ false,
                /* installUrl */ defaultInstallUrl(),
                /* autoRestartOnCrash */ true,
                /* autoStartOnBoot */ true,
                /* attachIfRunning */ true);
    }

    /** Persistent path under {@code ~/Library/Application Support/ds4/kv}
     *  on macOS so the KV cache survives a reboot. Falls back to
     *  {@code ~/.ds4/kv} on non-macOS targets so dev iteration on
     *  Linux still has a stable location. */
    private static String defaultKvCacheDir()
    {
        String home = System.getProperty("user.home");
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return home + "/Library/Application Support/ds4/kv";
        }
        return home + "/.ds4/kv";
    }

    /** Default fetch URL for the in-app installer. Tracked here
     *  rather than burned into the installer so the URL can be
     *  updated through the Settings page without a redeploy. */
    private static String defaultInstallUrl()
    {
        // Place a known stable releases URL here when antirez/ds4
        // cuts release artifacts. v1 ships with the URL blank so the
        // installer surfaces a "configure download URL first"
        // message rather than 404'ing a guess.
        return "";
    }

    /** Whether this config has enough to spawn a process. The
     *  lifecycle service flips into NOT_CONFIGURED whenever this is
     *  false; the UI renders an "Install" or "Configure binary
     *  path" affordance in that state. */
    public boolean canStart()
    {
        return binaryPath != null && !binaryPath.isBlank();
    }
}

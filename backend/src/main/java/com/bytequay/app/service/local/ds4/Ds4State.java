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

/**
 * Coarse lifecycle state for the local ds4-server subprocess.
 *
 * <p>Seven values rather than five: {@link #NOT_CONFIGURED} sits ahead
 * of the design doc's classic Stopped → Starting → Running → Stopping
 * + Crashed because a fresh install has no binary path on file and we
 * want the UI to tell the user "configure or download ds4" rather
 * than show a Start button that silently fails. The lifecycle service
 * refuses to leave NOT_CONFIGURED until {@code ds4.binary_path}
 * resolves to an executable file. {@link #DISABLED} sits even further
 * ahead: when local AI is switched off in settings the service never
 * spawns, attaches, or restarts, so no Metal/GPU resources are held
 * and shutdown is a no-op.
 */
public enum Ds4State
{
    /** Local AI is switched off in settings. The supervisor refuses
     *  to spawn, attach, or auto-restart, and {@code @PreDestroy}
     *  teardown is a no-op. The UI greys the widget out with a
     *  re-enable affordance. Master switch — wins over every other
     *  state at boot. */
    DISABLED,

    /** No binary path is configured (or it doesn't resolve to an
     *  executable file). The Management tab surfaces a configure /
     *  download affordance; Start is disabled. */
    NOT_CONFIGURED,

    /** Configured but not running. The happy resting state after a
     *  clean Stop, and the boot state once a healthy binary path is
     *  set but the server hasn't been launched yet. */
    STOPPED,

    /** Subprocess spawned, waiting for the health probe to succeed.
     *  Transitions to RUNNING on first 200 from {@code GET /v1/models}
     *  or back to CRASHED on early exit / probe timeout. */
    STARTING,

    /** Health probe is succeeding. Steady state — either we forked
     *  the subprocess and the supervisor watches it, or we attached
     *  to a server an external client already started. */
    RUNNING,

    /** SIGTERM issued; waiting for the server to flush its KV
     *  "shutdown" checkpoint before reporting STOPPED. The forceful
     *  Process.destroyForcibly() fallback only fires when the grace
     *  deadline expires. */
    STOPPING,

    /** Subprocess exited unexpectedly (non-zero, or zero without a
     *  stop request). The supervisor schedules a restart with
     *  back-off until the give-up threshold trips and the state
     *  stays here for the user to inspect. */
    CRASHED,
}

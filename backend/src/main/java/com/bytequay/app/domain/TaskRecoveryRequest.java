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
package com.bytequay.app.domain;

import java.time.Instant;

/**
 * A durable, server-validated request to leave NEEDS_ATTENTION. The task
 * stays parked while it is live; only the stop reconciler's barrier
 * command consumes it. {@code payloadJson} carries the kind's immutable
 * validated input (null for a plain restore).
 */
public record TaskRecoveryRequest(
        String id,
        String kind,
        String payloadJson,
        Instant requestedAt)
{
    /** Restore the checkpointed phase once the old runtime is proven
     *  gone — the only kind until replan/saga/legacy recovery land with
     *  their owners. */
    public static final String KIND_NORMAL = "NORMAL";

    /** Explicitly retry a CI lifecycle after its autonomous attempts were exhausted. */
    public static final String KIND_CI_RETRY = "CI_RETRY";

    /** Reopen planning only after the old task runtime is proven gone. */
    public static final String KIND_REPLAN = "REPLAN";

    /** Resume one exact durable external-effect cursor after the user fixes
     * its operational blocker. The payload binds token, cursor, fingerprint,
     * reason, and any added retry allowance. */
    public static final String KIND_EXTERNAL_SAGA = "EXTERNAL_SAGA";
}

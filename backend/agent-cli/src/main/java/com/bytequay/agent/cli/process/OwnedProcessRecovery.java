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
package com.bytequay.agent.cli.process;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * Result of reconciling one launch marker written by an earlier backend instance.
 *
 * @param launchId agent-cli-local opaque launch identifier
 * @param owner owner recorded by the marker, absent when the marker cannot be decoded
 * @param phase durable launch phase recorded by the marker, absent when it cannot be decoded
 * @param outcome fail-closed classification of the recorded launch
 * @param detail short operator-facing explanation without prompt text or credentials
 */
public record OwnedProcessRecovery(
        String launchId,
        Optional<ProcessOwner> owner,
        Optional<LaunchPhase> phase,
        Outcome outcome,
        String detail)
{
    public OwnedProcessRecovery
    {
        requireNonNull(launchId, "launchId is null");
        checkArgument(!launchId.isBlank(), "launchId is blank");
        requireNonNull(owner, "owner is null");
        requireNonNull(phase, "phase is null");
        requireNonNull(outcome, "outcome is null");
        requireNonNull(detail, "detail is null");
    }

    /** Whether the recovered launch is proven safe for its owner to release. */
    public enum Outcome
    {
        /** The gate never opened, so the payload provably never executed. */
        KNOWN_UNLAUNCHED,
        /** Every recorded process-group member and the root are observed gone. */
        VERIFIED_STOPPED,
        /** Identity, membership, or termination could not be proven; the owner stays blocked. */
        UNVERIFIABLE
    }
}

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
package com.bytequay.agent.core;

import java.time.Instant;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * Durable ownership record for one supervised provider process.
 *
 * <p>The row is committed before the payload can execute, so a crash always leaves a record naming
 * the owner of anything that might still be running. It stores no prompt text, credential, or raw
 * argument vector.
 *
 * @param launchId opaque launch identifier shared with the agent-cli launch marker
 * @param ownerKind closed owner role
 * @param ownerId opaque owner identifier
 * @param state durable launch intent
 * @param executable resolved executable path
 * @param argvDigest digest of the argument vector
 * @param verifiedStopped whether the owned process tree was proven stopped, once terminal
 * @param updatedAt time of the last recorded transition
 */
public record AgentProcessLaunch(
        String launchId,
        LaunchOwnerKind ownerKind,
        String ownerId,
        LaunchIntentState state,
        String executable,
        String argvDigest,
        Optional<Boolean> verifiedStopped,
        Instant updatedAt)
{
    public AgentProcessLaunch
    {
        requireNonNull(launchId, "launchId is null");
        checkArgument(!launchId.isBlank(), "launchId is blank");
        requireNonNull(ownerKind, "ownerKind is null");
        requireNonNull(ownerId, "ownerId is null");
        checkArgument(!ownerId.isBlank(), "ownerId is blank");
        requireNonNull(state, "state is null");
        requireNonNull(executable, "executable is null");
        requireNonNull(argvDigest, "argvDigest is null");
        requireNonNull(verifiedStopped, "verifiedStopped is null");
        requireNonNull(updatedAt, "updatedAt is null");
        checkArgument(state == LaunchIntentState.TERMINAL || verifiedStopped.isEmpty(),
                "only a terminal launch carries a tree result");
    }
}

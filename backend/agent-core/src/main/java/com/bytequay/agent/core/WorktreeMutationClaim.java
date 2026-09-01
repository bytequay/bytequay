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

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static java.util.Objects.requireNonNull;

/** Identifies the one operation currently allowed to mutate a task worktree. */
public record WorktreeMutationClaim(Kind kind, String ownerId, Instant acquiredAt)
{
    public WorktreeMutationClaim
    {
        requireNonNull(kind, "kind is null");
        checkNotBlank(ownerId, "ownerId");
        requireNonNull(acquiredAt, "acquiredAt is null");
    }

    public static WorktreeMutationClaim turn(TurnId turnId, Instant acquiredAt)
    {
        requireNonNull(turnId, "turnId is null");
        return new WorktreeMutationClaim(Kind.TURN, turnId.toString(), acquiredAt);
    }

    public enum Kind
    {
        TURN,
        COMPLETION
    }
}

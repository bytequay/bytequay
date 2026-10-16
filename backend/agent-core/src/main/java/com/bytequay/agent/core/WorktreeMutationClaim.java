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

import static com.bytequay.core.domain.Checks.checkText;
import static java.util.Objects.requireNonNull;

/** Identifies the one operation currently allowed to mutate a task worktree. */
public record WorktreeMutationClaim(Kind kind, String ownerId, Instant acquiredAt)
{
    public WorktreeMutationClaim
    {
        requireNonNull(kind, "kind is null");
        checkText(ownerId, "ownerId");
        requireNonNull(acquiredAt, "acquiredAt is null");
    }

    public static WorktreeMutationClaim turn(TurnId turnId, Instant acquiredAt)
    {
        requireNonNull(turnId, "turnId is null");
        return new WorktreeMutationClaim(Kind.TURN, turnId.toString(), acquiredAt);
    }

    /**
     * Holds a task's worktree because a process it launched could not be shown stopped.
     *
     * <p>Keyed by the launch, so the block is released by the launch that caused it rather than by
     * whatever happens to run next.
     *
     * @param launchId the unresolved launch
     * @param acquiredAt when the block was taken
     * @return the claim
     */
    public static WorktreeMutationClaim recovery(String launchId, Instant acquiredAt)
    {
        checkText(launchId, "launchId");
        return new WorktreeMutationClaim(Kind.RECOVERY, launchId, acquiredAt);
    }

    /** Holds a task's worktree while a user-requested command runs in it; the owner is the run. */
    public static WorktreeMutationClaim localRun(String runId, Instant acquiredAt)
    {
        return new WorktreeMutationClaim(Kind.LOCAL_RUN, runId, acquiredAt);
    }

    /** What a refused writer reports while this claim holds the worktree. */
    public String conflictMessage()
    {
        return kind == Kind.LOCAL_RUN ? "A run is using this working copy" : "Task worktree is claimed by " + kind;
    }

    public enum Kind
    {
        TURN,
        COMPLETION,
        RECOVERY,
        LOCAL_RUN
    }
}

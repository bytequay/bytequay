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
package com.bytequay.app.ci;

public interface CiGitService
{
    /**
     * Inspects the local result of one completed agent fix turn.
     *
     * <p>Returns no-change when HEAD is unchanged and the worktree is clean.
     * Returns ready only when all changes are committed, no Git operation or
     * unresolved conflict is open, the PR range is linear, and the local head
     * can be published from the expected remote head. Agent output is not read.
     *
     * @param command authorized worktree and exact PR range
     * @return no change, ready local head, or bounded rejection
     */
    CiGitInspection inspectAgentResult(InspectAgentResultCommand command);

    /**
     * Pushes one previously inspected local head with an exact lease.
     *
     * <p>The caller must persist the supplied journal before calling this
     * method. The implementation rechecks authorization, repository, branch,
     * local fingerprint, and remote SHA immediately before the push.
     *
     * @param command exact push journal and authorization
     * @return reconciled pushed, retryable, stale, uncertain, or rejected result
     */
    CiPushResult push(PushCiHeadCommand command);

    /**
     * Checks fixup positions once before successful task completion.
     *
     * <p>This method is read-only. Every fixup must immediately follow one
     * non-fixup commit with the exact target subject. The PR range must remain
     * linear and free of unresolved Git state.
     *
     * @param command worktree, current head, and exact PR range
     * @return valid or bounded position diagnostics
     */
    FixupPositionCheck checkFixupPositions(CheckFixupPositionsCommand command);
}

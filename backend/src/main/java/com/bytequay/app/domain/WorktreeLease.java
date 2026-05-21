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
 * One row in {@code worktree_leases} — at most one live agent may
 * hold a worktree at a time. The lease, not the thread, is the lock
 * (per the "Automation and system-initiated tasks" section of the
 * model doc).
 *
 * <p>A held worktree means a headless auto-fix must defer instead of
 * barging in; a free worktree lets the auto-fixer acquire, run, and
 * park at {@code AWAITING_REVIEW}. The interactive jump-in flow
 * transfers the lease from a headless holder to the human's session.
 *
 * @param holderPid   OS pid for {@link ThreadKind#CLI_AGENT} holders;
 *                    null for {@code LOGIC_LOOP} (no separate process).
 * @param expiresAt   soft expiry the reaper sweeps when a holder has
 *                    crashed; null means "no auto-expiry, release
 *                    explicitly".
 */
public record WorktreeLease(
        String worktreePath,
        String taskId,
        ThreadKind agentKind,
        Integer holderPid,
        Instant acquiredAt,
        Instant expiresAt)
{
}

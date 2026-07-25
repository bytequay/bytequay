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
 * A fingerprinted validation ownership row: which context (normal dev
 * round, brain-fix verification, local-review roots-closed pass) owns
 * one validation of exactly one code state. The claim key is the
 * identity (task + context + scope + fingerprint); owner/lease admit at
 * most one live executor while the checks run outside any database
 * transaction.
 *
 * @param throughSequence local-review claims only — the task-wide
 *        submission watermark this pass covers
 * @param rootSetDigest local-review claims only — digest of the root
 *        comment set covered by this pass
 */
public record ValidationClaim(
        long id,
        String claimKey,
        String taskId,
        String context,
        String roundId,
        String codeFingerprint,
        Long throughSequence,
        String rootSetDigest,
        Instant startedAt,
        Instant endedAt,
        Boolean passed,
        String failuresJson,
        Instant cancelRequestedAt,
        Instant supersededAt,
        String ownerId,
        Instant leaseUntil)
{
    /** Live = started but not yet terminal, cancelled, or superseded. */
    public boolean isLive()
    {
        return endedAt == null && cancelRequestedAt == null && supersededAt == null;
    }

    public boolean isTerminalGreen()
    {
        return endedAt != null && Boolean.TRUE.equals(passed);
    }
}

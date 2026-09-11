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
package com.bytequay.app.agent;

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static com.google.common.base.Preconditions.checkArgument;

/** Delivery cleanliness excludes ignored build outputs; worktree cleanup keeps its stronger rule. */
public record DevelopmentLocalFacts(
        String branch,
        String headSha,
        String treeSha,
        String fingerprint,
        int stagedFiles,
        int unstagedFiles,
        int untrackedFiles,
        int uncommittedFileCount,
        long commitCount,
        boolean diffFromBase,
        boolean gitOperationInProgress)
{
    public DevelopmentLocalFacts
    {
        checkNotBlank(branch, "branch");
        checkNotBlank(headSha, "headSha");
        checkNotBlank(treeSha, "treeSha");
        checkNotBlank(fingerprint, "fingerprint");
        checkArgument(stagedFiles >= 0 && unstagedFiles >= 0 && untrackedFiles >= 0 && uncommittedFileCount >= 0 && commitCount >= 0, "Git counts are negative");
    }
}

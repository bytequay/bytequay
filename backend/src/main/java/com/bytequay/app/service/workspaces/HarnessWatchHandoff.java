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
package com.bytequay.app.service.workspaces;

/** Narrow seam from cherry-pick setup into the independently-owned harness. */
public interface HarnessWatchHandoff
{
    /**
     * @param agentSessionId the session the picks ran in. Phase 2 resumes it rather
     *         than opening its own, so a compile failure in the pull request is read
     *         by the session that made the conflict resolution behind it.
     */
    String create(
            String workspaceId,
            String repoFullName,
            int prNumber,
            String localPrId,
            String branchName,
            String worktreePath,
            long budgetMilliUsd,
            String agentSessionId);

    /**
     * Stops a watch this handoff created. Closing the sync run that owns the
     * watch has to end the agent side of it too, or the harness keeps looping
     * on a pull request nobody is watching any more.
     */
    void stopWatch(String workspaceId, String watchId);
}

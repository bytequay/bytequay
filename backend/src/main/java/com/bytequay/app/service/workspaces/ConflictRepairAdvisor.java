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

import java.nio.file.Path;
import java.util.List;

/**
 * Narrow seam from cherry-pick conflict repair into an advisory agent, mirroring
 * {@link HarnessWatchHandoff}. The advisor <em>proposes</em>; this package applies
 * and commits, so the agent never touches the worktree itself.
 *
 * <p>When no implementation is registered the job simply parks for the human,
 * which is the behaviour that predates agent repair.
 */
public interface ConflictRepairAdvisor
{
    /**
     * @param worktree      the app-owned cherry-pick worktree, at the conflicted commit
     * @param targetSubject subject of the commit whose conflict is being repaired
     * @param conflictPaths files git reported as conflicted
     * @param compileOutput tail of the failing compile, the evidence for the repair
     * @param resumeSessionId the run's live agent session, or null to start one.
     *        One session spans the whole run so a later conflict still knows what
     *        the fork decided about an earlier one.
     * @return a proposal, or empty when the agent declines or is not confident
     */
    Repair propose(
            Path worktree,
            String workspaceId,
            String targetSubject,
            List<String> conflictPaths,
            String compileOutput,
            long budgetMilliUsd,
            String resumeSessionId);

    /**
     * @param edits unique-anchor find/replace edits; empty means "no proposal"
     * @param costMilliUsd what the turn spent, charged whether or not it helped
     * @param sessionId the session to resume next time, null when the engine
     *        announced none (the next turn then starts fresh)
     */
    record Repair(List<Edit> edits, String rationale, long costMilliUsd, String sessionId)
    {
        public boolean isEmpty()
        {
            return edits == null || edits.isEmpty();
        }
    }

    record Edit(String path, String find, String replace) {}
}

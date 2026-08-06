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
 * Narrow seam from cherry-pick conflict repair into the agent that performs it,
 * mirroring {@link HarnessWatchHandoff}.
 *
 * <p>The agent <em>owns</em> the repair: it edits the conflicted files, commits
 * the fixup, and decides how to validate. This package runs the picks and reads
 * the outcome; it no longer applies edits, counts attempts or runs the build on
 * the agent's behalf. See "The upstream sync run" in
 * {@code docs/intermediate/ci-autofix-design.md}.
 *
 * <p>When no implementation is registered the job simply parks for the human,
 * which is the behaviour that predates agent repair.
 */
public interface ConflictRepairAdvisor
{
    /**
     * @param worktree        the app-owned cherry-pick worktree, at the conflicted commit
     * @param targetSubject   subject of the pick whose conflict is being repaired; the
     *                        agent's fixup must name it
     * @param conflictPaths   files git reported as conflicted
     * @param validateHint    a validation command read from the job's configuration, or
     *                        null to let the agent find one in the repo itself
     * @param resumeSessionId the run's live agent session, or null to start one. One
     *                        session spans the whole run, so a later conflict still knows
     *                        what the fork decided about an earlier one.
     */
    Outcome repair(
            Path worktree,
            String workspaceId,
            String targetSubject,
            List<String> conflictPaths,
            String validateHint,
            long budgetMilliUsd,
            String resumeSessionId);

    /**
     * @param resolved  the agent reports the conflict resolved; false means it asked to
     *                  park, and the run stops with nothing pushed
     * @param validated the agent actually ran a check. False is not a failure — a repo
     *                  whose build cannot run here still gets its verdict from CI once
     *                  the range is pushed — but it changes what "resolved" is worth,
     *                  so the run log says which picks were taken on trust.
     * @param detail    the agent's own sentence: what it did, or why it stopped
     * @param sessionId the session to resume next time, null when the engine announced
     *                  none (the next turn then starts fresh)
     */
    record Outcome(
            boolean resolved,
            boolean validated,
            String detail,
            /** The turn's own JSONL, kept so a run nobody was watching can be read back. */
            String transcript,
            long costMilliUsd,
            String sessionId)
    {
        public Outcome(
                boolean resolved, boolean validated, String detail,
                long costMilliUsd, String sessionId)
        {
            this(resolved, validated, detail, null, costMilliUsd, sessionId);
        }
    }
}

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

/**
 * Narrow seam from a merged sync run into the agent that writes its retrospective,
 * mirroring {@link HarnessWatchHandoff}. Kept as an interface so the picker does
 * not depend on the harness package.
 */
public interface SyncRetrospectiveWriter
{
    /**
     * @param worktree        still present — the retrospective reads the merged history
     * @param resumeSessionId the run's session, which is what makes this worth writing:
     *         it remembers what was tried and rejected across the whole range
     */
    void write(
            Path worktree,
            String workspaceId,
            Integer prNumber,
            long budgetMilliUsd,
            String resumeSessionId);
}

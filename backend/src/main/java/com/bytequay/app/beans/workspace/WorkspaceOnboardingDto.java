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
package com.bytequay.app.beans.workspace;

public record WorkspaceOnboardingDto(
        String workspaceId,
        boolean cloneComplete,
        String syncState,
        int syncCurrent,
        int syncTotal,
        boolean memorySeedComplete,
        boolean firstTrunkComplete,
        boolean memoryImported,
        // Project-learning card state, derived from the durable learning run.
        // Null until a run exists. The workspace remains usable while learning
        // continues, but onboarding stays visible until the initial knowledge
        // bar is useful or the available history is caught up.
        String learningState,
        String learningLastError,
        int learningCataloged,
        int learningAnalyzed,
        int learningLessons,
        int learningPendingLessons,
        Long dismissedAt,
        long updatedAt)
{
    public boolean complete()
    {
        return cloneComplete
                && "ready".equals(syncState)
                && firstTrunkComplete
                && (learningState == null
                        || "useful".equals(learningState)
                        || "caught-up".equals(learningState));
    }
}

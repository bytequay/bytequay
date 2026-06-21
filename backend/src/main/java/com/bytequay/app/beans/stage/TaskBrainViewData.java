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
package com.bytequay.app.beans.stage;

import java.util.List;

/**
 * The full brain-view payload for {@code GET /api/tasks/{taskId}/brain}.
 * The shape is locked against the frontend mock; many fields are
 * placeholders in this milestone (0 / empty / null) and fill in as the
 * loop, conversation, and cost machinery land.
 */
public record TaskBrainViewData(
        BrainTask task,
        Aggregate aggregate,
        List<StageDto> stages,
        List<StageDto> subStages,
        List<BrainFeedRow> brainFeed,
        RightRail rightRail,
        Scrubbers scrubbers)
{
    /**
     * The Task header shown above the brain feed.
     *
     * @param prNumber null when the Task has no PR yet
     * @param agentRuntime CLI | API
     */
    public record BrainTask(
            String id,
            String title,
            long taskNumber,
            String branch,
            String repoFullName,
            Integer prNumber,
            boolean prDraft,
            String currentPhase,
            String statusLabel,
            String agentRuntime,
            String agentModel,
            /** True when the task is parked at PAUSED — drives the rail's
             *  Pause/Resume button so it never offers Pause on a paused task. */
            boolean paused)
    {
    }

    /**
     * The 8-field metrics strip summed across a Task's stages.
     *
     * @param autoPushBudget null until the budget machinery lands
     */
    public record Aggregate(
            int pushes,
            long activeTimeSec,
            long waitingUserTimeSec,
            int toolCalls,
            int turns,
            int messages,
            int panels,
            int costCents,
            AutoPushBudget autoPushBudget)
    {
    }

    public record AutoPushBudget(int used, int limit)
    {
    }

    /**
     * The brain view's right rail.
     *
     * @param approval null until the approval-gate machinery lands
     * @param linkedPr null when the Task has no PR
     * @param panelSpawnable true when the task is in an internal-review
     *                       context and a panel review can be launched
     * @param parentStageId the open stage a panel review would be called
     *                      from, or null when none is spawnable
     */
    public record RightRail(
            ApprovalDto approval,
            LinkedPrDto linkedPr,
            ContextWindowDto context,
            List<CommitDto> recentCommits,
            boolean panelSpawnable,
            String parentStageId,
            CostBreakdown costBreakdown)
    {
    }

    /**
     * Per-Task spend, attributed from {@code thread_messages} costs.
     *
     * @param costPerPush total / autonomous pushes, or null when pushes == 0
     */
    public record CostBreakdown(
            long totalCents,
            List<StageCost> perStage,
            List<AgentCost> perAgent,
            Long costPerPush)
    {
    }

    public record StageCost(String stageId, String stageType, long costCents) {}

    public record AgentCost(String agentKind, long costCents) {}

    public record Scrubbers(
            List<ScrubberDash> stageEvents,
            List<ScrubberDash> userMessages)
    {
    }
}

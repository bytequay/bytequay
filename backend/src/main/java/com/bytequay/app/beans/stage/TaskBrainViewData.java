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

import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.BranchGuard;
import com.bytequay.app.domain.ReviewRound;

import java.util.List;

/**
 * The full brain-view payload for {@code GET /api/tasks/{taskId}/brain}.
 * The shape is locked against the frontend mock; many fields are
 * placeholders in this milestone (0 / empty / null) and fill in as the
 * loop, conversation, and cost machinery land.
 *
 * @param liveRuns the task's live-or-gated {@link AgentRun}s — what the
 *                 plan rail renders CI/comment rows from (folded into this
 *                 existing payload rather than a separate endpoint, since
 *                 nothing else consumes the rail standalone)
 * @param guard the task's branch guard state, always present (a disabled
 *              row is created lazily)
 * @param liveRound the task's currently-open {@link ReviewRound} (not yet
 *                  posted/closed), or null — drives the Remote Development
 *                  comments row, same folded-in-payload rationale as
 *                  {@code liveRuns}/{@code guard}
 * @param devPhases Development's in-stage phase ladder (Implementing /
 *                  Validation / Brain review, plan-rail-runs.md R29) — empty
 *                  until a Development stage exists
 */
public record TaskBrainViewData(
        BrainTask task,
        Aggregate aggregate,
        List<StageDto> stages,
        List<StageDto> subStages,
        String brainThreadId,
        List<BrainFeedRow> brainFeed,
        RightRail rightRail,
        Scrubbers scrubbers,
        List<AgentRun> liveRuns,
        BranchGuard guard,
        ReviewRound liveRound,
        List<DevPhase> devPhases,
        RecoveryAction recovery)
{
    /** One exact user action that can replace a failed V2 owner operation. */
    public record RecoveryAction(
            String kind, String stageId, String blockerId,
            String failedTurnId) {}

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
            /** True when the task is stopped but resumable (paused, needs
             *  attention, errored, or archived) — drives the rail's Resume
             *  action so it never offers Pause on dormant work. */
            boolean paused,
            /** True when the task has reached an irreversible terminal status
             *  (closed, canceled, or completed) — the rail then
             *  shows a closed state instead of Pause/Close controls. */
            boolean terminal)
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
            CostBreakdown costBreakdown,
            PlanCard plan)
    {
    }

    /**
     * The structured plan card. {@code state} is {@code draft} while the brain
     * is still recording/reviewing it, {@code revision_required} when Brain's
     * completed self-review requested changes, {@code awaiting} once a finalized
     * plan is pending the user, {@code locked} after approval (the PlanStage is
     * closed). Null on the rail when the task has no PlanStage data.
     */
    public record PlanCard(
            String planStageId,
            String state,
            String status,
            String source,
            /** One concise sentence naming the objective — the card's
             *  headline. Falls back to the understanding summary for plans
             *  recorded before the brain emitted a dedicated goal. */
            String goal,
            String understandingSummary,
            String intentSummary,
            List<PlanStep> steps,
            String validationStrategy,
            String pushStrategy,
            PlanSignals signals,
            int revisionCount,
            List<PlanFollowup> followups,
            /** Things the task deliberately does NOT do — rendered as the
             *  out-of-scope mini-card. Empty for plans recorded before it. */
            List<String> outOfScope,
            /** Set when the latest planning turn failed before recording a
             *  plan — surfaced on the card instead of a silent empty draft. */
            String error)
    {
    }

    /**
     * One plan step. {@code action} is the short imperative; {@code detail} is
     * the longer rationale shown on expand; {@code files} render as file
     * chips; {@code risk} is the per-step risk pill ({@code low} / {@code med}
     * / {@code high} / {@code opt}), null when the plan predates per-step risk.
     */
    public record PlanStep(int ordinal, String action, List<String> files, String detail, String risk) {}

    public record PlanSignals(
            String riskLevel, String estimatedComplexity, int componentsCount, String expectedGain,
            /** Overall confidence the plan will succeed as written: {@code
             *  high} / {@code medium} / {@code low}. Derived from risk for
             *  plans recorded before the brain emitted it explicitly. */
            String confidence) {}

    public record PlanFollowup(
            String eventId, String note, String sourceAgent, String createdAt, String status) {}

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

    /**
     * One row of Development's phase ladder. {@code status} is already in the
     * rail's own vocabulary ({@code done} / {@code running} / {@code future})
     * so the frontend renders it with no reinterpretation layer.
     *
     * @param key {@code implementing} / {@code validation} / {@code brainReview}
     * @param meta short hint text (e.g. iteration count, {@code brain approved},
     *             or {@code brain unresolved · N}), or null
     * @param badgeRunId the live {@link AgentRun} id to badge this phase with
     *                    (the local {@code ci_fix} run for Validation), or
     *                    null when nothing's live
     */
    public record DevPhase(String key, String status, String meta, String badgeRunId) {}
}

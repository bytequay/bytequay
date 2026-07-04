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
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * The drill-in stage-detail payload for {@code GET /api/stages/{id}/detail}.
 * Pure read/compose over data M3/M3.5/M4 already wrote — no operation cards
 * (the codebase emits no OPERATION events), no per-iteration CI-fix detail
 * (the trigger payload doesn't carry it), and metrics are the derivable
 * subset only; uncomputed fields are omitted (never zero) via
 * {@link JsonInclude}.
 *
 * @param liveRuns the task's live-or-gated {@link AgentRun}s, same field
 *                 and rationale as {@code TaskBrainViewData.liveRuns} —
 *                 this page renders the plan rail too
 * @param guard the task's branch guard state
 * @param liveRound the task's currently-open {@link ReviewRound}, same field
 *                  and rationale as {@code TaskBrainViewData.liveRound}
 */
public record StageDetailData(
        DetailTask task,
        StageInfo stage,
        List<StageDto> allStages,
        List<StageDto> subStages,
        List<IterationDetail> iterations,
        /** The stage's conversation transcript — the base timeline the detail
         *  view renders. For a PlanStage it's the brain thread (seed → planning
         *  → plan); for other stages it's the dev agent's turns + tool calls
         *  within the stage window. Iteration boundaries (CI-fixing /
         *  addressing-comments loops) are interleaved as {@code iteration_marker}
         *  rows so the same flat timeline works whether a stage looped or not. */
        List<ConversationRow> conversation,
        RealtimeCi realtimeCi,
        List<CiFixHistoryEntry> ciFixHistory,
        PrTab pr,
        ContextWindowDto context,
        Scrubber scrubber,
        List<AgentRun> liveRuns,
        BranchGuard guard,
        ReviewRound liveRound)
{
    /**
     * The pull-request block shown on the stage page's PR tab. Surfaced from
     * the same {@link com.bytequay.app.domain.PullRequestDetail} the
     * realtime-CI snapshot already fetches, so it adds no extra GitHub call.
     * Null when the task has no linked PR.
     *
     * @param status open | draft | queued | merged
     * @param queueState the raw merge-queue entry state (e.g. AWAITING_CHECKS)
     *                   when {@code status} is {@code queued}; null otherwise
     */
    public record PrTab(
            int number,
            String status,
            String queueState,
            String headRef,
            String baseRef,
            List<String> reviewers,
            List<String> labels,
            PrChecks checks,
            List<PrThread> threads)
    {
    }

    /** Per-conclusion tallies of the PR's CI check runs. */
    public record PrChecks(int passed, int failed, int pending, int total)
    {
    }

    /**
     * One per-line review thread on the PR: the reviewer's root message
     * first, then any replies (including the agent's).
     */
    public record PrThread(
            String id,
            String file,
            Integer line,
            boolean resolved,
            List<PrThreadMsg> messages)
    {
    }

    /** One message in a {@link PrThread}. */
    public record PrThreadMsg(String author, String body)
    {
    }

    /**
     * One row of the stage transcript. {@code kind} selects which fields are
     * meaningful: {@code agent}/{@code user} use {@code text}; {@code tool_call}
     * uses {@code toolTag}/{@code toolLabel}/{@code toolDetail}; {@code
     * iteration_marker} uses {@code iterationNumber}/{@code text} (the loop
     * trigger); {@code permission} (a still-pending prompt) uses {@code
     * toolLabel} (tool name) + {@code text} (summary) + {@code callId} (to
     * answer it). All carry {@code ts} for ordering.
     */
    public record ConversationRow(
            String id,
            String kind,
            String text,
            String toolTag,
            String toolLabel,
            String toolDetail,
            String toolResult,
            Boolean toolError,
            /** For an edit/write tool call, a +/- diff built from the call
             *  input (old_string → new_string, or written content). */
            String toolDiff,
            Integer iterationNumber,
            String ts,
            /** For a pending {@code permission} row, the callId to answer the
             *  prompt with; null for every other row kind. */
            String callId) {}

    /**
     * @param prNumber null when the task has no PR
     * @param agentRuntime CLI | API
     */
    public record DetailTask(
            String id,
            long taskNumber,
            String title,
            String branch,
            String repoFullName,
            Integer prNumber,
            boolean prDraft,
            String currentPhase,
            String agentRuntime,
            String agentModel)
    {
    }

    /**
     * @param currentIterationNumber null when no iteration is open
     */
    public record StageInfo(
            String id,
            String type,
            String state,
            String openedAt,
            String closedAt,
            String callerStageId,
            int iterationCount,
            Integer currentIterationNumber,
            StageConfig config,
            StageMetricsSubset metrics)
    {
    }

    /**
     * Stage config knobs.
     *
     * @param autoPushBudget null for stages without a budget
     */
    public record StageConfig(
            TaskBrainViewData.AutoPushBudget autoPushBudget,
            boolean internalReviewEnabled)
    {
    }

    /**
     * The metrics we can actually derive today. Uncomputed catalog fields
     * (activeTime, waitingUserTime, operationsCount, interventionsCount,
     * backflows) are deliberately absent rather than 0 — a 0 would
     * misrepresent "not yet measured" as "measured zero".
     * {@code panelInvocationsCount} is a genuine 0 (no ReviewStage instances
     * exist until the panel milestone lands).
     *
     * @param terminalState succeeded | failed | paused | aborted, or null while open
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StageMetricsSubset(
            Long wallTimeSec,
            Integer loopIterations,
            Integer toolCallsCount,
            Integer turnsCount,
            Integer messagesCount,
            Long tokensCount,
            Long costCents,
            int panelInvocationsCount,
            /** Sum of inferred-operation run durations in the window. */
            Long activeTimeSec,
            /** Time the stage spent in user-gated phases in its window. */
            Long waitingUserTimeSec,
            /** Inferred-operation run counts by kind: code/validate/push/publish. */
            Map<String, Integer> operationsCount,
            /** Steering messages on the dev thread within the stage window. */
            Integer interventionsCount,
            /** Backward (rework) phase transitions within the stage window. */
            Integer backflowsCount,
            String terminalState)
    {
    }

    /**
     * @param endedAt null while in progress
     * @param recordedBy agent | orchestrator_fallback | synthesized, or null when unsummarised
     */
    public record IterationDetail(
            String id,
            int iterationNumber,
            String trigger,
            String startedAt,
            String endedAt,
            String endedReason,
            String summaryText,
            String recordedBy,
            List<LogRow> log)
    {
    }

    /**
     * One chronological row inside an iteration. Exactly one of the typed
     * payloads is set per {@code kind}; the rest are null (omitted on the
     * wire).
     *
     * @param kind tool_call | stage_event | iteration_summary | user_message
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LogRow(
            String id,
            String ts,
            String kind,
            ToolCallPayload toolCall,
            StageEventPayload stageEvent,
            IterationSummaryPayload iterationSummary,
            UserMessagePayload userMessage,
            OperationPayload operation)
    {
    }

    /**
     * @param tag Read | Write | Run | MCP | Tool (coarse category from the tool name)
     */
    public record ToolCallPayload(String tag, String label, String detail)
    {
    }

    /**
     * A run of consecutive same-kind tool calls, grouped at read time (no
     * OPERATION_* events are written — the CLI agents give no real-time
     * boundary). The nested {@code toolCalls} render inside the card.
     *
     * @param operation code | validate | push | publish
     * @param status    ok (we don't track per-tool failure yet)
     */
    public record OperationPayload(
            String operation,
            String startedAt,
            String completedAt,
            long durationSec,
            int toolCallCount,
            String status,
            List<LogRow> toolCalls)
    {
    }

    public record StageEventPayload(String eventType, String message, String dataJson)
    {
    }

    public record IterationSummaryPayload(String text, String recordedBy, String recordedAt)
    {
    }

    public record UserMessagePayload(String text)
    {
    }

    /**
     * The linked PR's current CI snapshot.
     *
     * @param status green | failing | pending | unknown
     */
    public record RealtimeCi(
            String status,
            String prUrl,
            List<CiCheck> checks,
            String lastPolledAt)
    {
    }

    /**
     * One CI check row.
     *
     * @param status ok | fail | pending
     */
    public record CiCheck(String name, String status, Integer durationSec)
    {
    }

    /**
     * Simplified per-iteration "what we tried" — the genuine record-backed
     * history (the rich per-check breakdown the mockup shows isn't captured
     * in the trigger payload today).
     */
    /**
     * @param failedCheck   the check that triggered the fix (red-CI iters
     *                      written after the payload-enrichment landed); null
     *                      on older iters → the card falls back to the summary
     * @param errorMessage  truncated error summary for the hover tooltip, or null
     * @param actionsRunUrl GitHub Actions run URL for the ↗ link, or null
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CiFixHistoryEntry(int iterationNumber, String endedReason, String summaryText,
            String failedCheck, String errorMessage, String actionsRunUrl)
    {
    }

    public record Scrubber(List<ScrubberDash> userMessages)
    {
    }
}

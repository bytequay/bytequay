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
 * An isolated agent session + log attached to whatever it serves —
 * containment without lifecycle position (plan-rail-runs.md R6). {@code
 * kind} is {@code ci_fix} / {@code review_round} / {@code branch_guard} /
 * {@code panel_review}; {@code source} narrows {@code ci_fix} and {@code
 * branch_guard} to {@code local} / {@code remote} / {@code scheduled}.
 *
 * <p>Task/stage runs get their own backing {@code task_stage} row ({@link
 * #stageId}) so turns land in {@code stage_messages}; artifact-owned runs
 * such as external PR reviews are detached and leave it null. {@link
 * #parentStageId} is the separate, semantic stage the rail attaches this
 * run's lazy sub-row to (e.g. the Development stage for a local {@code
 * ci_fix}); it may differ from {@link #stageId} and may be null (a
 * top-level run, e.g. the branch guard).
 */
public record AgentRun(
        String id,
        String taskId,
        String kind,
        String source,
        String parentStageId,
        String reviewRoundId,
        String stageId,
        String status,
        int iterations,
        Integer budget,
        String headline,
        String metricsJson,
        Instant startedAt,
        Instant finishedAt,
        String workspaceId,
        String threadId,
        String provider,
        String model,
        long costUsdMilli,
        long tokensIn,
        long tokensOut,
        int stepCursor,
        String launchInput,
        String pauseReason,
        String outcome)
{
    public static final String KIND_PLAN = "plan";
    public static final String KIND_DEV = "dev";
    public static final String KIND_REVIEW = "review";
    public static final String KIND_CI_FIX = "ci_fix";
    public static final String KIND_REVIEW_ROUND = "review_round";
    public static final String KIND_BRANCH_GUARD = "branch_guard";
    public static final String KIND_PANEL_REVIEW = "panel_review";

    public static final String SOURCE_LOCAL = "local";
    public static final String SOURCE_REMOTE = "remote";
    public static final String SOURCE_SCHEDULED = "scheduled";

    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_PAUSED = "paused";
    public static final String STATUS_AWAITING_GATE = "awaiting_gate";
    public static final String STATUS_SUCCEEDED = "succeeded";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_CANCELLED = "cancelled";

    /** Compatibility shape for the pre-workspace Session fields. */
    public AgentRun(
            String id,
            String taskId,
            String kind,
            String source,
            String parentStageId,
            String reviewRoundId,
            String stageId,
            String status,
            int iterations,
            Integer budget,
            String headline,
            String metricsJson,
            Instant startedAt,
            Instant finishedAt)
    {
        this(id, taskId, kind, source, parentStageId, reviewRoundId, stageId,
                status, iterations, budget, headline, metricsJson, startedAt,
                finishedAt, null, null, null, null, 0L, 0L, 0L, 0,
                null, null, terminalOutcome(status));
    }

    /** True while the run is still doing (or waiting to post) its work —
     *  the set a "live runs" rail/endpoint query watches for. */
    public boolean isLive()
    {
        return STATUS_QUEUED.equals(status)
                || STATUS_RUNNING.equals(status)
                || STATUS_PAUSED.equals(status)
                || STATUS_AWAITING_GATE.equals(status);
    }

    /** Copy with the iteration count bumped and an optional new headline. */
    public AgentRun withIteration(int newIterationCount, String newHeadlineOrNull)
    {
        return new AgentRun(
                id, taskId, kind, source, parentStageId, reviewRoundId, stageId, status,
                newIterationCount, budget, newHeadlineOrNull == null ? headline : newHeadlineOrNull,
                metricsJson, startedAt, finishedAt, workspaceId, threadId, provider, model,
                costUsdMilli, tokensIn, tokensOut, stepCursor, launchInput, pauseReason, outcome);
    }

    /** Copy with the budget decremented by one spent unit (floor zero). */
    public AgentRun withBudgetSpent()
    {
        Integer next = budget == null ? null : Math.max(0, budget - 1);
        return new AgentRun(
                id, taskId, kind, source, parentStageId, reviewRoundId, stageId, status,
                iterations, next, headline, metricsJson, startedAt, finishedAt,
                workspaceId, threadId, provider, model, costUsdMilli, tokensIn,
                tokensOut, stepCursor, launchInput, pauseReason, outcome);
    }

    public AgentRun withMetrics(String newMetricsJson)
    {
        return new AgentRun(
                id, taskId, kind, source, parentStageId, reviewRoundId, stageId, status,
                iterations, budget, headline, newMetricsJson, startedAt, finishedAt,
                workspaceId, threadId, provider, model, costUsdMilli, tokensIn,
                tokensOut, stepCursor, launchInput, pauseReason, outcome);
    }

    /** Copy transitioned to a terminal (or gated) status; stamps {@code
     *  finishedAt} only for the terminal ones. */
    public AgentRun withStatus(String newStatus, Instant when)
    {
        boolean terminal = STATUS_SUCCEEDED.equals(newStatus) || STATUS_FAILED.equals(newStatus)
                || STATUS_CANCELLED.equals(newStatus);
        return new AgentRun(
                id, taskId, kind, source, parentStageId, reviewRoundId, stageId, newStatus,
                iterations, budget, headline, metricsJson, startedAt, terminal ? when : finishedAt,
                workspaceId, threadId, provider, model, costUsdMilli, tokensIn, tokensOut,
                stepCursor, launchInput,
                STATUS_PAUSED.equals(newStatus) ? pauseReason : null,
                terminal ? terminalOutcome(newStatus) : outcome);
    }

    public AgentRun withOwnership(
            String newWorkspaceId, String newThreadId, String newProvider, String newModel,
            String newLaunchInput)
    {
        return new AgentRun(
                id, taskId, kind, source, parentStageId, reviewRoundId, stageId, status,
                iterations, budget, headline, metricsJson, startedAt, finishedAt,
                newWorkspaceId, newThreadId, newProvider, newModel, costUsdMilli,
                tokensIn, tokensOut, stepCursor, newLaunchInput, pauseReason, outcome);
    }

    public AgentRun withAccounting(
            long newCostUsdMilli, long newTokensIn, long newTokensOut, int newStepCursor)
    {
        return new AgentRun(
                id, taskId, kind, source, parentStageId, reviewRoundId, stageId, status,
                iterations, budget, headline, metricsJson, startedAt, finishedAt,
                workspaceId, threadId, provider, model, Math.max(0L, newCostUsdMilli),
                Math.max(0L, newTokensIn), Math.max(0L, newTokensOut),
                Math.max(0, newStepCursor), launchInput, pauseReason, outcome);
    }

    public AgentRun paused(String reason)
    {
        return new AgentRun(
                id, taskId, kind, source, parentStageId, reviewRoundId, stageId,
                STATUS_PAUSED, iterations, budget, headline, metricsJson, startedAt,
                finishedAt, workspaceId, threadId, provider, model, costUsdMilli,
                tokensIn, tokensOut, stepCursor, launchInput, reason, outcome);
    }

    /** Re-open the same task/stage episode for its next scheduler turn. */
    public AgentRun requeued()
    {
        return new AgentRun(
                id, taskId, kind, source, parentStageId, reviewRoundId, stageId,
                STATUS_QUEUED, iterations, budget, headline, metricsJson, startedAt,
                null, workspaceId, threadId, provider, model, costUsdMilli,
                tokensIn, tokensOut, stepCursor, launchInput, null, null);
    }

    public AgentRun withOutcome(String newOutcome)
    {
        return new AgentRun(
                id, taskId, kind, source, parentStageId, reviewRoundId, stageId, status,
                iterations, budget, headline, metricsJson, startedAt, finishedAt,
                workspaceId, threadId, provider, model, costUsdMilli, tokensIn,
                tokensOut, stepCursor, launchInput, pauseReason, newOutcome);
    }

    private static String terminalOutcome(String status)
    {
        if (STATUS_SUCCEEDED.equals(status)) {
            return "completed";
        }
        if (STATUS_FAILED.equals(status)) {
            return "failed";
        }
        if (STATUS_CANCELLED.equals(status)) {
            return "cancelled";
        }
        return null;
    }
}

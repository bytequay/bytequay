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
package com.bytequay.app.beans.session;

import com.bytequay.app.domain.AgentRun;

import java.time.Duration;
import java.time.Instant;

/** Workspace-facing projection of the internal AgentRun record. */
public record SessionDto(
        String id,
        String workspaceId,
        String trunkId,
        String taskId,
        String stageId,
        String reviewRoundId,
        boolean durableReview,
        Controls controls,
        String kind,
        String status,
        String provider,
        String model,
        long costUsdMilli,
        long tokensIn,
        long tokensOut,
        int stepCursor,
        Integer budget,
        String headline,
        String launchInput,
        String pauseReason,
        String outcome,
        long startedAt,
        Long finishedAt,
        long durationMs)
{
    public static SessionDto from(AgentRun run, Instant now)
    {
        return from(run.id(), run, now);
    }

    /** A durable PR review Session keeps the AgentReview id while its
     * underlying round/remote runs may be replaced. */
    public static SessionDto from(String sessionId, AgentRun run, Instant now)
    {
        return from(sessionId, run, now, false);
    }

    public static SessionDto from(
            String sessionId, AgentRun run, Instant now, boolean durableReview)
    {
        return from(sessionId, run, now, durableReview, !durableReview);
    }

    public static SessionDto from(
            String sessionId, AgentRun run, Instant now,
            boolean durableReview, boolean controllable)
    {
        Instant end = run.finishedAt() == null ? now : run.finishedAt();
        return new SessionDto(
                sessionId,
                run.workspaceId(),
                run.threadId(),
                run.taskId(),
                run.stageId(),
                run.reviewRoundId(),
                durableReview,
                controls(run, controllable),
                publicKind(run.kind()),
                publicStatus(run.status()),
                run.provider(),
                run.model(),
                run.costUsdMilli(),
                run.tokensIn(),
                run.tokensOut(),
                run.stepCursor(),
                run.budget(),
                run.headline(),
                run.launchInput(),
                run.pauseReason(),
                run.outcome(),
                run.startedAt().toEpochMilli(),
                run.finishedAt() == null ? null : run.finishedAt().toEpochMilli(),
                Math.max(0L, Duration.between(run.startedAt(), end).toMillis()));
    }

    private static Controls controls(AgentRun run, boolean controllable)
    {
        if (!controllable) {
            return Controls.NONE;
        }
        return new Controls(
                AgentRun.STATUS_RUNNING.equals(run.status()),
                AgentRun.STATUS_PAUSED.equals(run.status()),
                run.isLive(),
                !run.isLive());
    }

    public static boolean isPublic(AgentRun run)
    {
        if (AgentRun.KIND_BRANCH_GUARD.equals(run.kind())) {
            return false;
        }
        // Detached remote-review jobs remain compatibility work, not
        // workspace Sessions. PR-owned full reviews need a watched workspace,
        // but intentionally have no synthetic thread.
        return !AgentRun.KIND_PANEL_REVIEW.equals(run.kind())
                || run.workspaceId() != null;
    }

    private static String publicKind(String kind)
    {
        return switch (kind) {
            case AgentRun.KIND_CI_FIX -> "ci-fix";
            case AgentRun.KIND_REVIEW, AgentRun.KIND_REVIEW_ROUND,
                    AgentRun.KIND_PANEL_REVIEW -> "review";
            case AgentRun.KIND_DEV -> "dev";
            default -> "plan";
        };
    }

    private static String publicStatus(String status)
    {
        return switch (status) {
            case AgentRun.STATUS_QUEUED -> "queued";
            case AgentRun.STATUS_RUNNING -> "running";
            case AgentRun.STATUS_PAUSED, AgentRun.STATUS_AWAITING_GATE -> "paused";
            case AgentRun.STATUS_FAILED -> "errored";
            default -> "done";
        };
    }

    public record Controls(boolean pause, boolean resume, boolean stop, boolean restart)
    {
        private static final Controls NONE =
                new Controls(false, false, false, false);
    }
}

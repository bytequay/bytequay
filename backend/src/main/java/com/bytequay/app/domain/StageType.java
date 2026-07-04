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

import java.util.Optional;
import java.util.Set;

/**
 * The coarse grouping above the fine-grained {@link TaskPhase} machine — a
 * Task is a sequence of stage instances, each holding a loop of operations
 * that move phases. This enum is the single source of truth for which
 * phases legally live inside which stage (the {@link #allowedPhases()}
 * mapping) and for resolving the stage a phase belongs to
 * ({@link #forPhase}).
 *
 * <p>{@code PLAN_STAGE} is the mandatory planning stage every Task opens
 * with; {@code DEVELOPMENT_STAGE} / {@code REVIEW_MONITOR_STAGE} are
 * looping work stages driven by phase transitions, {@code CLEANUP_STAGE}
 * is the terminal stage, and {@code REVIEW_STAGE} / {@code CI_FIXING_STAGE} /
 * {@code REVIEW_ROUND_STAGE} are pure containers with no phases of their
 * own — opened directly by an {@code AgentRun} (or, for {@code
 * REVIEW_STAGE}, the review panel) purely so its turns land in {@code
 * stage_messages} via the same stage-id FK every other turn uses; all
 * three carry a {@code callerStageId}.
 */
public enum StageType
{
    /** Mandatory first stage of every Task: the brain agent produces a
     *  user-approved plan here. Opens at Task creation and closes on
     *  approval, which is the only thing that lets the DevelopmentStage
     *  open. */
    PLAN_STAGE(Set.of(TaskPhase.PLANNING)),

    /** First-time creation of the change. */
    DEVELOPMENT_STAGE(Set.of(
            TaskPhase.IMPLEMENTING,
            TaskPhase.VALIDATING,
            TaskPhase.INTERNAL_REVIEW,
            TaskPhase.AWAITING_PUSH,
            TaskPhase.ADDRESSING_LOCAL_COMMENTS)),

    /** Pure container for {@code ci_fix} {@link AgentRun}s — never opened via
     *  a phase transition; {@link AgentRun} opens one directly as a run's
     *  backing stage. Empty {@code allowedPhases} mirrors {@code
     *  REVIEW_STAGE}. */
    CI_FIXING_STAGE(Set.of()),

    /** Arms as soon as the PR is out for review ({@code
     *  AWAITING_REMOTE_REVIEW}) and stays active for the rest of the
     *  task's remote-review life — the phase no longer moves while
     *  {@code review_round} runs address comment batches beside it,
     *  mirroring how {@code CI_FIXING_STAGE} relates to {@code ci_fix}. */
    REVIEW_MONITOR_STAGE(Set.of(TaskPhase.AWAITING_REMOTE_REVIEW)),

    /** Terminal stage; runs once the PR closes. */
    CLEANUP_STAGE(Set.of(TaskPhase.COMPLETED)),

    /** Callable multi-agent review sub-stage with its own internal phases —
     *  left empty here; populated when the panel lifecycle lands. */
    REVIEW_STAGE(Set.of()),

    /** Pure container for {@code review_round} {@link AgentRun}s — never
     *  opened via a phase transition; {@link AgentRun} opens one directly
     *  as a round's backing stage. Empty {@code allowedPhases} mirrors
     *  {@code CI_FIXING_STAGE}. */
    REVIEW_ROUND_STAGE(Set.of());

    private final Set<TaskPhase> allowedPhases;

    StageType(Set<TaskPhase> allowedPhases)
    {
        this.allowedPhases = allowedPhases;
    }

    /**
     * Phases that may legally appear inside an instance of this stage. The
     * phase machine uses this to validate transitions and to route a phase
     * to its stage.
     */
    public Set<TaskPhase> allowedPhases()
    {
        return allowedPhases;
    }

    /**
     * The PascalCase display name used in user-facing prose (e.g.
     * "CiFixingStage"). The brain agent references stages by this name and
     * the brain feed scans responses for it to resolve drill-in chips, so
     * it's the single source of truth for both.
     */
    public String displayName()
    {
        return switch (this) {
            case PLAN_STAGE -> "PlanStage";
            case DEVELOPMENT_STAGE -> "DevelopmentStage";
            case CI_FIXING_STAGE -> "CiFixingStage";
            case REVIEW_MONITOR_STAGE -> "ReviewMonitorStage";
            case CLEANUP_STAGE -> "CleanupStage";
            case REVIEW_STAGE -> "ReviewStage";
            case REVIEW_ROUND_STAGE -> "ReviewRoundStage";
        };
    }

    /**
     * The stage a phase resolves to, or empty for a cross-cutting phase that
     * isn't bound to any single stage. {@link TaskPhase#QUEUED} and
     * {@link TaskPhase#NEEDS_ATTENTION} are cross-cutting by design — they
     * attach to whatever stage is already active. {@link TaskPhase#AWAITING_READY}
     * and {@link TaskPhase#PUSHED_AWAITING_CI} are post-push idle waits that
     * stay unmapped; they keep the current stage (a live {@code ci_fix} run
     * works beside it via its own directly-opened backing stage instead).
     *
     * <p>Returning empty (rather than throwing) is deliberate: the lifecycle
     * hook runs inside the phase-transition's transaction, so a throw here
     * would roll back a legitimate phase move. An unmapped phase is treated
     * as "stay in the current stage".
     */
    public static Optional<StageType> forPhase(TaskPhase phase)
    {
        for (StageType type : values()) {
            if (type.allowedPhases.contains(phase)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}

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
 * <p>The first three are looping work stages, {@code CLEANUP_STAGE} is the
 * terminal stage, and {@code REVIEW_STAGE} is a callable sub-stage (the
 * multi-agent panel) that carries a {@code callerStageId}. Only
 * {@code DEVELOPMENT_STAGE} and {@code CLEANUP_STAGE} produce runtime rows
 * in this milestone; the other three are declared so the phase-to-stage
 * mapping and downstream code have them ready.
 */
public enum StageType
{
    /** First-time creation of the change. */
    DEVELOPMENT_STAGE(Set.of(
            TaskPhase.IMPLEMENTING,
            TaskPhase.VALIDATING,
            TaskPhase.INTERNAL_REVIEW,
            TaskPhase.AWAITING_PUSH)),

    /** Polling loop on remote CI. */
    CI_FIXING_STAGE(Set.of(
            TaskPhase.CI_FIXING,
            TaskPhase.AWAITING_UPDATE_PUSH,
            TaskPhase.PUSHED_AWAITING_CI)),

    /** Polling loop on remote review comments. Arms as soon as the PR is
     *  out for review ({@code AWAITING_REMOTE_REVIEW}), then stays active
     *  while comments are addressed. */
    REVIEW_MONITOR_STAGE(Set.of(
            TaskPhase.AWAITING_REMOTE_REVIEW,
            TaskPhase.ADDRESSING_COMMENTS,
            TaskPhase.AGENT_RE_REVIEW,
            TaskPhase.AWAITING_UPDATE_PUSH)),

    /** Terminal stage; runs once the PR closes. */
    CLEANUP_STAGE(Set.of(TaskPhase.COMPLETED)),

    /** Callable multi-agent review sub-stage with its own internal phases —
     *  left empty here; populated when the panel lifecycle lands. */
    REVIEW_STAGE(Set.of());

    private final Set<TaskPhase> allowedPhases;

    StageType(Set<TaskPhase> allowedPhases)
    {
        this.allowedPhases = allowedPhases;
    }

    /**
     * Phases that may legally appear inside an instance of this stage. The
     * phase machine uses this to validate transitions and to route a phase
     * to its stage. Note {@link TaskPhase#AWAITING_UPDATE_PUSH} legally
     * belongs to both {@code CI_FIXING_STAGE} and {@code REVIEW_MONITOR_STAGE};
     * {@link #forPhase} resolves the overlap by declaration order.
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
            case DEVELOPMENT_STAGE -> "DevelopmentStage";
            case CI_FIXING_STAGE -> "CiFixingStage";
            case REVIEW_MONITOR_STAGE -> "ReviewMonitorStage";
            case CLEANUP_STAGE -> "CleanupStage";
            case REVIEW_STAGE -> "ReviewStage";
        };
    }

    /**
     * The stage a phase resolves to, or empty for a cross-cutting phase that
     * isn't bound to any single stage. {@link TaskPhase#QUEUED} and
     * {@link TaskPhase#NEEDS_ATTENTION} are cross-cutting by design — they
     * attach to whatever stage is already active. {@link TaskPhase#AWAITING_READY}
     * is the post-push CI-green idle wait that stays unmapped; it keeps the
     * current stage.
     *
     * <p>Returning empty (rather than throwing) is deliberate: the lifecycle
     * hook runs inside the phase-transition's transaction, so a throw here
     * would roll back a legitimate phase move. An unmapped phase is treated
     * as "stay in the current stage".
     *
     * <p>{@code AWAITING_UPDATE_PUSH} belongs to both {@code CI_FIXING_STAGE}
     * and {@code REVIEW_MONITOR_STAGE}; this method resolves the overlap by
     * declaration order (CI-fixing wins), but the lifecycle prefers to keep
     * whichever monitor stage is already active when the phase is legal there
     * (see {@code StageLifecycle}), so the resolver precedence only decides
     * the cold-start case.
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

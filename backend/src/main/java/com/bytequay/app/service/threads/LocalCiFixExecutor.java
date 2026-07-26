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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.domain.TurnLiveness;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.checks.ValidationFailure;
import com.bytequay.app.service.runs.AgentRunService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * The bounded local-CI fix loop. When a code-touching round's local CI fails,
 * re-engage an agent to fix it on the same worktree before the task advances —
 * up to {@link #MAX_ATTEMPTS} fix turns, then hand it back to the human.
 *
 * <p>A pure collaborator, not an event listener: {@link TaskPhaseMachine}
 * calls {@link #tryFix} on a failed validation pass (parking only when this
 * returns false) and {@link #closeIfGreen} on a passed one. Keeping it
 * one-directional avoids a bean cycle with the phase machine.
 */
@Component
public class LocalCiFixExecutor
{
    private static final Logger log = LoggerFactory.getLogger(LocalCiFixExecutor.class);

    /** Autonomous local-CI fix turns before the task is parked for the human. */
    static final int MAX_ATTEMPTS = 5;

    private final ThreadStore threadStore;
    private final StageStore stageStore;
    private final AgentRunService agentRuns;
    private final ThreadTurnScheduler scheduler;
    private final WorktreeLeaseService leaseService;

    public LocalCiFixExecutor(
            ThreadStore threadStore,
            StageStore stageStore,
            AgentRunService agentRuns,
            ThreadTurnScheduler scheduler,
            WorktreeLeaseService leaseService)
    {
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.agentRuns = requireNonNull(agentRuns, "agentRuns is null");
        this.scheduler = requireNonNull(scheduler, "scheduler is null");
        this.leaseService = requireNonNull(leaseService, "leaseService is null");
    }

    /**
     * Queues an agent turn to fix the failing local checks, staying on the
     * task's worktree and stage.
     *
     * @return true when a fix turn was queued (or one is already running, so
     *         the caller should wait rather than park); false when the caller
     *         should park the task for the human — the attempt budget is spent,
     *         or there's nothing to run a fix on.
     */
    public boolean tryFix(Task task, List<ValidationFailure> failures)
    {
        return tryFix(task, failures, false);
    }

    /** Same-transaction form used by validation acceptance. */
    public boolean tryFixInCommand(Task task, List<ValidationFailure> failures)
    {
        TaskCommandExecutor.requireCurrent(task.id());
        return tryFix(task, failures, true);
    }

    private boolean tryFix(Task task, List<ValidationFailure> failures, boolean inCommand)
    {
        if (task.worktreePath() == null || task.worktreePath().isBlank()) {
            return false;
        }
        var threadOpt = threadStore.findThreadById(task.threadId());
        if (threadOpt.isEmpty()) {
            return false;
        }
        com.bytequay.app.domain.Thread thread = threadOpt.get();
        if (thread.status() != ThreadStatus.IDLE) {
            // The agent is mid-turn (or awaiting a permission card); it will
            // re-validate when it finishes. Don't park, don't double-queue.
            return true;
        }
        if (leaseService.isHeldByAnotherTask(task.worktreePath(), task.id())) {
            // Another task holds the worktree; retry on the next validation.
            return true;
        }
        Optional<StageInstance> stage = stageStore.findActiveStage(task.id());
        if (stage.isEmpty()) {
            return false;
        }
        String stageId = stage.get().id().toString();
        AgentRun run = inCommand
                ? agentRuns.openInStageInCommand(
                        task.id(), AgentRun.KIND_CI_FIX, AgentRun.SOURCE_LOCAL,
                        stageId, MAX_ATTEMPTS)
                : agentRuns.openInStage(
                        task.id(), AgentRun.KIND_CI_FIX, AgentRun.SOURCE_LOCAL,
                        stageId, MAX_ATTEMPTS);
        if (run.iterations() >= MAX_ATTEMPTS) {
            if (inCommand) {
                agentRuns.transitionInCommand(
                        task.id(), run.id(), AgentRun.STATUS_FAILED,
                        "local_ci_attempts_exhausted");
            }
            else {
                agentRuns.transition(
                        run.id(), AgentRun.STATUS_FAILED, "local_ci_attempts_exhausted");
            }
            log.info("local CI fix exhausted after {} attempts for task {}; parking", MAX_ATTEMPTS, task.id());
            return false;
        }
        try {
            String turnId = scheduler.enqueueTaskTurn(
                    thread, buildPrompt(failures), task.id(), stageId,
                    TurnInitiator.unattended("local-ci-fix"), run.id(), TurnLiveness.CODE);
            agentRuns.recordIteration(run.id(), headline(failures));
            log.info("local CI fix queued for task {} → turn {} (attempt {})",
                    task.id(), turnId, run.iterations() + 1);
            return true;
        }
        catch (RuntimeException e) {
            log.warn("local CI fix enqueue failed for task {}: {}", task.id(), e.getMessage());
            return false;
        }
    }

    /** Local checks passed — close any live local CI-fix run for the task. */
    public void closeIfGreen(String taskId)
    {
        closeIfGreen(taskId, false);
    }

    /** Same-transaction form used by validation acceptance. */
    public void closeIfGreenInCommand(String taskId)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        closeIfGreen(taskId, true);
    }

    private void closeIfGreen(String taskId, boolean inCommand)
    {
        agentRuns.findByTask(taskId, AgentRun.KIND_CI_FIX, null).stream()
                .filter(AgentRun::isLive)
                .filter(run -> AgentRun.SOURCE_LOCAL.equals(run.source()))
                .findFirst()
                .ifPresent(run -> {
                    if (inCommand) {
                        agentRuns.transitionInCommand(
                                taskId, run.id(), AgentRun.STATUS_SUCCEEDED,
                                "local_checks_green");
                    }
                    else {
                        agentRuns.transition(
                                run.id(), AgentRun.STATUS_SUCCEEDED, "local_checks_green");
                    }
                });
    }

    private static String buildPrompt(List<ValidationFailure> failures)
    {
        StringBuilder out = new StringBuilder(
                "Your last change left the local checks red. Fix them on the existing branch.\n");
        if (failures != null && !failures.isEmpty()) {
            out.append("\nFailing checks:\n");
            for (ValidationFailure failure : failures) {
                out.append("  - ").append(failure.source()).append(": ").append(failure.detail()).append('\n');
            }
        }
        out.append("\nRe-run focused checks for the fix, then leave the verified changes in the ")
                .append("worktree. Do not commit, push, or open a review — ByteQuay checkpoints the ")
                .append("changes and re-runs the canonical checks outside the provider sandbox after this turn.");
        return out.toString();
    }

    private static String headline(List<ValidationFailure> failures)
    {
        if (failures == null || failures.isEmpty()) {
            return "local checks failed";
        }
        String detail = failures.get(0).detail();
        return detail == null || detail.isBlank() ? "local checks failed" : detail.strip();
    }
}

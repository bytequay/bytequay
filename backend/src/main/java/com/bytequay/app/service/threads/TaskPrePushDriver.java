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

import com.bytequay.app.domain.Actor;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.checks.ValidationPassResult;
import com.bytequay.app.service.checks.ValidationPassService;
import com.bytequay.app.service.local.GitRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;

import static java.util.Objects.requireNonNull;

/**
 * Drives the <em>pre-push</em> spine of the dev-task lifecycle that the
 * PR-observing {@link TaskLifecycleDriver} can't see: an idle task that
 * has committed work locally but hasn't reached the remote moves
 * {@code IMPLEMENTING → VALIDATING → INTERNAL_REVIEW → AWAITING_PUSH}.
 *
 * <p>Trigger: a task that's {@link TaskStatus#IDLE} (its coding turn
 * ended) at {@link TaskPhase#IMPLEMENTING}, not yet on the remote
 * (no push, no PR), with commits on its branch ahead of base. The
 * "commits ahead" signal is what distinguishes "done a chunk of work"
 * from a task merely paused mid-edit.
 *
 * <p>Validation drives {@code VALIDATING → INTERNAL_REVIEW} through its
 * existing finished-event; internal review auto-advances to
 * {@code AWAITING_PUSH} because no internal-review pass is wired to run
 * yet (it passes vacuously). As real checks / a review pass land, those
 * two phases stop being instantaneous — the wiring here doesn't change.
 */
@Component
public class TaskPrePushDriver
{
    private static final Logger log = LoggerFactory.getLogger(TaskPrePushDriver.class);

    /** Cap on idle tasks scanned per sweep — the candidate set (idle dev
     *  tasks still implementing) is small. */
    private static final int SCAN_LIMIT = 200;

    private final TaskStore taskStore;
    private final ValidationPassService validation;
    private final GitRunner git;
    private final TaskPhaseMachine phaseMachine;

    public TaskPrePushDriver(
            TaskStore taskStore,
            ValidationPassService validation,
            GitRunner git,
            TaskPhaseMachine phaseMachine)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.validation = requireNonNull(validation, "validation is null");
        this.git = requireNonNull(git, "git is null");
        this.phaseMachine = requireNonNull(phaseMachine, "phaseMachine is null");
    }

    /** Offset from the other lifecycle sweeps so they don't bunch up. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 120_000)
    public void reconcile()
    {
        for (Task task : taskStore.listByStatus(TaskStatus.IDLE, SCAN_LIMIT)) {
            try {
                if (isReadyForPrePush(task)) {
                    runPrePush(task);
                }
            }
            catch (RuntimeException e) {
                log.warn("pre-push reconcile for task {} failed: {}", task.id(), e.getMessage());
            }
        }
    }

    private boolean isReadyForPrePush(Task task)
    {
        if (task.phase() != TaskPhase.IMPLEMENTING) {
            return false; // already past the implement phase, or parked.
        }
        if (task.pushedAt() != null || task.prNumber() != null) {
            return false; // on the remote — the PR-observing driver owns it.
        }
        if (task.worktreePath() == null || task.worktreePath().isBlank()
                || task.branchName() == null || task.baseBranch() == null) {
            return false;
        }
        try {
            Integer ahead = git.commitCountUniqueTo(
                    Path.of(task.worktreePath()), task.branchName(), task.baseBranch());
            return ahead != null && ahead > 0; // the agent has committed a chunk of work.
        }
        catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    /** Visible for the unit test: run the pre-push phase sequence. */
    void runPrePush(Task task)
    {
        phaseMachine.transition(task.id(), TaskPhase.VALIDATING, "pre_push_checks", Actor.SCHEDULER);
        // Synchronous: publishes ValidationPassFinishedEvent, which the
        // phase machine reacts to (VALIDATING -> INTERNAL_REVIEW on clean,
        // -> NEEDS_ATTENTION on a cap-hit failure).
        ValidationPassResult result = validation.run(task.id());
        if (result.passed()) {
            // No internal-review pass is wired to run yet, so a clean
            // validation advances straight to the push gate.
            phaseMachine.transition(
                    task.id(), TaskPhase.AWAITING_PUSH, "internal_review_clean", Actor.SCHEDULER);
        }
    }
}

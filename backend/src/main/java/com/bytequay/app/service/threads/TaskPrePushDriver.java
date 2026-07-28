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
import com.bytequay.app.service.localpr.PRSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Drives the <em>pre-push</em> spine of the dev-task lifecycle that the
 * PR-observing {@link TaskLifecycleDriver} can't see: after Development
 * explicitly hands off into {@code VALIDATING}, an idle task that has
 * committed work locally but hasn't reached the remote moves to
 * {@code INTERNAL_REVIEW}. The Brain review
 * owns the last transition to {@code AWAITING_PUSH} once the private local PR
 * is ready for the user.
 *
 * <p>Trigger: a task that's {@link TaskStatus#IDLE} (its coding turn
 * ended) at {@link TaskPhase#VALIDATING}, not yet on the remote
 * (no remote push or remote PR), with commits on its branch ahead of base.
 * The explicit phase is the durable completion signal; "commits ahead"
 * remains a sanity check that there is a local diff to review.
 *
 * <p>Validation drives {@code VALIDATING → INTERNAL_REVIEW} through its
 * existing finished-event. A local-PR sync then starts (or resumes) the Brain
 * adversarial review; it must not be skipped by creating a publish gate here.
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
    private final PRSyncService prSync;
    private final TaskPhaseMachine phaseMachine;

    public TaskPrePushDriver(
            TaskStore taskStore,
            ValidationPassService validation,
            GitRunner git,
            PRSyncService prSync,
            TaskPhaseMachine phaseMachine)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.validation = requireNonNull(validation, "validation is null");
        this.git = requireNonNull(git, "git is null");
        this.prSync = requireNonNull(prSync, "prSync is null");
        this.phaseMachine = requireNonNull(phaseMachine, "phaseMachine is null");
    }

    /** Offset from the other lifecycle sweeps so they don't bunch up. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 120_000)
    public void reconcile()
    {
        for (Task task : taskStore.listByStatus(TaskStatus.IDLE, SCAN_LIMIT)) {
            if (taskStore.isV2Task(task.id())) {
                continue;
            }
            try {
                if (task.phase() == TaskPhase.INTERNAL_REVIEW) {
                    prSync.syncFromTask(task.id());
                }
                else if (isReadyForPrePush(task)) {
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
        if (task.phase() != TaskPhase.VALIDATING) {
            return false; // Development has not explicitly handed off.
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
        if (!checkpointLocalFix(task)) {
            return;
        }
        // Synchronous: publishes ValidationPassFinishedEvent, which the
        // phase machine reacts to (VALIDATING -> INTERNAL_REVIEW on clean,
        // -> NEEDS_ATTENTION on a failed check).
        ValidationPassResult result = validation.run(task.id());
        if (result.passed()) {
            // Validation's finished event already moved the task to
            // INTERNAL_REVIEW. Syncing creates/updates the local PR and starts
            // the Brain review; that review alone can hand off to Local Review.
            prSync.syncFromTask(task.id());
        }
    }

    /** A provider-sandboxed fix turn may edit the worktree but cannot write
     *  the shared Git index. Checkpoint through ByteQuay before re-validating
     *  so a green pass can never advance with uncommitted fixes. */
    private boolean checkpointLocalFix(Task task)
    {
        Path worktree = Path.of(task.worktreePath());
        try {
            if (git.hasUncommittedChanges(worktree)) {
                git.stageAll(worktree, List.of(WorktreeService.HOOK_DIR_REL));
                git.commit(worktree, "Fix local validation failures");
            }
            return true;
        }
        catch (IOException e) {
            parkCheckpointFailure(task,
                    "Local validation could not checkpoint Git changes: " + e.getMessage());
            return false;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            parkCheckpointFailure(task,
                    "Local validation was interrupted while checkpointing Git changes");
            return false;
        }
    }

    private void parkCheckpointFailure(Task task, String error)
    {
        taskStore.saveTask(task.withErrorMessage(error));
        phaseMachine.transition(
                task.id(), TaskPhase.NEEDS_ATTENTION,
                "local_validation_checkpoint_failed", Actor.AGENT);
    }
}

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

import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.Task;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.localpr.PRPublishService;
import com.bytequay.app.service.localpr.PRService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Executor;

import static java.util.Objects.requireNonNull;

/**
 * Tears down a completed task's branches — the remote head branch on GitHub
 * and the local worktree + branch — but only when the task's PR actually
 * merged. Rides the {@link TaskCleanupEvent} that {@code StageLifecycle}
 * fires from the (policy-free) CleanupStage branch for every completion, so
 * the merged-only gate and all git/GitHub I/O live here rather than in the
 * phase machine.
 *
 * <p>Runs after the phase-transition transaction commits and dispatches the
 * work onto an injected runner (a virtual thread in production), so nothing
 * blocks the commit thread. The whole body is best-effort: it must never
 * throw back into the phase machine's listeners, and a closed-unmerged /
 * no-PR completion deletes nothing.
 *
 * <p>ponytail: auto-cleanup trusts the local PR row to already read {@code
 * merged} by the time completion fires — the dashboard sync stamps it. If a
 * sync lag leaves the row un-stamped at completion, this skips (best-effort)
 * and the user-gated "Delete branch" button still reaps it. Upgrade path if
 * that race shows up in practice: fire cleanup off a dedicated PR-merged
 * event instead of the completion event.
 */
@Component
public class MergedTaskBranchCleanup
{
    private static final Logger log = LoggerFactory.getLogger(MergedTaskBranchCleanup.class);

    private final TaskStore taskStore;
    private final PRService prService;
    private final PRPublishService prPublishService;
    private final WorktreeService worktreeService;
    private final Executor runner;

    @Autowired
    public MergedTaskBranchCleanup(
            TaskStore taskStore,
            PRService prService,
            PRPublishService prPublishService,
            WorktreeService worktreeService)
    {
        this(taskStore, prService, prPublishService, worktreeService,
                r -> Thread.ofVirtual().start(r));
    }

    /** Test seam — pass a synchronous runner ({@code Runnable::run}) so the
     *  teardown runs inline and asserts deterministically. */
    MergedTaskBranchCleanup(
            TaskStore taskStore,
            PRService prService,
            PRPublishService prPublishService,
            WorktreeService worktreeService,
            Executor runner)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.prService = requireNonNull(prService, "prService is null");
        this.prPublishService = requireNonNull(prPublishService, "prPublishService is null");
        this.worktreeService = requireNonNull(worktreeService, "worktreeService is null");
        this.runner = requireNonNull(runner, "runner is null");
    }

    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true)
    public void onTaskCleanup(TaskCleanupEvent event)
    {
        runner.execute(() -> cleanup(event.taskId()));
    }

    private void cleanup(String taskId)
    {
        try {
            Optional<Task> task = taskStore.findTaskById(taskId);
            if (task.isEmpty()) {
                return;
            }
            Optional<PR> pr = prService.findByTask(taskId);
            if (pr.isEmpty() || !PR.STATUS_MERGED.equals(pr.get().status())) {
                // Completion alone deletes nothing — only a merged PR does.
                return;
            }
            deleteRemoteBranch(pr.get());
            reapLocalWorktree(task.get());
        }
        catch (RuntimeException e) {
            log.warn("post-merge branch cleanup for task {} threw: {}", taskId, e.getMessage());
        }
    }

    private void deleteRemoteBranch(PR pr)
    {
        if (pr.branchDeletedAt() != null) {
            // Already reaped (e.g. GitHub auto-deleted the head on merge and
            // the sync stamped it) — nothing to do on the remote.
            return;
        }
        try {
            prPublishService.deleteBranch(pr.id());
        }
        catch (RuntimeException e) {
            // Swallow — a 404 is expected when GitHub auto-deleted the head on
            // merge; any other failure is logged and never blocks local reap.
            log.warn("remote branch delete for merged PR {} threw: {}", pr.id(), e.getMessage());
        }
    }

    private void reapLocalWorktree(Task task)
    {
        if (task.workingDir() == null || task.workingDir().isBlank()
                || task.worktreePath() == null || task.worktreePath().isBlank()) {
            return;
        }
        worktreeService.remove(Path.of(task.workingDir()), task.worktreePath(), task.branchName());
    }
}

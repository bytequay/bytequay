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

import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.pr.PullRequestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Drives the post-push spine of the dev-task lifecycle from the linked
 * PR's live state. Periodically, for each task waiting on its PR, it
 * fetches that PR <em>directly</em> by {@code owner/repo#n} (the task's
 * {@code linkedPrRef}) and moves the phase to match its CI / draft state.
 *
 * <p>Going straight to the PR — rather than reading the dashboard sync's
 * cached {@code ci_status} — is deliberate: a task's PR may not be in the
 * dashboard search set at all (e.g. a freshly-opened fork PR GitHub
 * search hasn't indexed yet), in which case the cached status is never
 * filled and the task would hang at PUSHED_AWAITING_CI forever. The
 * direct fetch is ETag-cached (an unchanged PR costs a 304), bounded to
 * the handful of tasks on the remote spine, and stops the moment a task
 * leaves it.
 *
 * <p>Merge → COMPLETED stays on the PR-merged event path; this driver
 * only places the task on the CI / ready / review spine.
 */
@Component
public class TaskLifecycleDriver
{
    private static final Logger log = LoggerFactory.getLogger(TaskLifecycleDriver.class);

    /** Cap on tasks scanned per sweep — the linked-PR task set is small. */
    private static final int SCAN_LIMIT = 200;

    /** Phases that are waiting on the PR's remote state, so the linked PR
     *  is worth polling. A task outside these isn't waiting on CI/review,
     *  so we don't fetch its PR. */
    private static final Set<TaskPhase> REMOTE_SPINE = EnumSet.of(
            TaskPhase.PUSHED_AWAITING_CI,
            TaskPhase.AWAITING_READY,
            TaskPhase.CI_FIXING,
            TaskPhase.AWAITING_REMOTE_REVIEW,
            TaskPhase.AWAITING_UPDATE_PUSH);

    private final TaskStore taskStore;
    private final PullRequestService pullRequests;
    private final TaskPhaseMachine phaseMachine;

    public TaskLifecycleDriver(
            TaskStore taskStore, PullRequestService pullRequests, TaskPhaseMachine phaseMachine)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.phaseMachine = requireNonNull(phaseMachine, "phaseMachine is null");
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    public void reconcile()
    {
        for (Task task : taskStore.listWithLinkedPr(SCAN_LIMIT)) {
            if (!REMOTE_SPINE.contains(task.phase()) || task.linkedPrRef() == null) {
                continue;
            }
            try {
                reconcileTask(task);
            }
            catch (RuntimeException e) {
                log.warn("lifecycle reconcile for task {} (PR {}) failed: {}",
                        task.id(), task.linkedPrRef(), e.getMessage());
            }
        }
    }

    /** Visible for the unit test: fetch the task's PR fresh and move its
     *  phase to match. */
    void reconcileTask(Task task)
    {
        String ref = task.linkedPrRef();
        int hash = ref.lastIndexOf('#');
        if (hash <= 0 || hash == ref.length() - 1) {
            return;
        }
        String repo = ref.substring(0, hash);
        int number;
        try {
            number = Integer.parseInt(ref.substring(hash + 1).trim());
        }
        catch (NumberFormatException e) {
            return;
        }
        PullRequestDetail detail = pullRequests.getPullRequestDetail(repo, number);
        Optional<TaskPhase> target = TaskLifecyclePhases.observedPhaseFromDetail(detail);
        target.ifPresent(phase -> phaseMachine.observe(task.id(), phase, "pr_state_observed"));
    }
}

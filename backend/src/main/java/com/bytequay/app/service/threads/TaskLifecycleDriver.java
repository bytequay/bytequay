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

import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.repository.PullRequestStore;
import com.bytequay.app.repository.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Drives the post-push spine of the dev-task lifecycle from observed PR
 * state. Runs shortly after each PR sync: for every synced PR it finds
 * the task on the PR's head branch, recovers the task ↔ PR link the
 * agent may not have recorded (it often pushes / opens the PR via raw
 * git + the GitHub API, bypassing our tools), and fast-forwards the
 * task's phase to match the PR's CI / draft / review / merge state via
 * {@link TaskPhaseMachine#observe}.
 *
 * <p>Server-side observation is deliberate: it's robust to the agent
 * skipping our tools, since the PR's state on GitHub is the same either
 * way. The pre-push phases (validate / internal review) are local and
 * driven separately.
 */
@Component
public class TaskLifecycleDriver
{
    private static final Logger log = LoggerFactory.getLogger(TaskLifecycleDriver.class);

    private final PullRequestStore prStore;
    private final TaskStore taskStore;
    private final TaskPhaseMachine phaseMachine;

    public TaskLifecycleDriver(
            PullRequestStore prStore, TaskStore taskStore, TaskPhaseMachine phaseMachine)
    {
        this.prStore = requireNonNull(prStore, "prStore is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.phaseMachine = requireNonNull(phaseMachine, "phaseMachine is null");
    }

    /** Offset from the 60s PR sync so it reconciles against fresh PR
     *  rows rather than racing the sync. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    public void reconcile()
    {
        for (PullRequest pr : prStore.findAll()) {
            try {
                reconcilePr(pr);
            }
            catch (RuntimeException e) {
                log.warn("lifecycle reconcile for PR {}#{} failed: {}",
                        pr.repo(), pr.number(), e.getMessage());
            }
        }
    }

    /** Visible for the unit test: link + phase-reconcile one PR's task. */
    void reconcilePr(PullRequest pr)
    {
        if (pr.headRef() == null || pr.headRef().isBlank()) {
            return;
        }
        Task task = taskStore.findTaskByBranch(pr.headRef()).orElse(null);
        if (task == null) {
            return;
        }
        // Recover the link the agent didn't record (raw push / API).
        String ref = pr.repo() + "#" + pr.number();
        if (!ref.equals(task.linkedPrRef())) {
            taskStore.linkTaskToPr(task.id(), ref);
        }
        if (task.prNumber() == null || task.prNumber().intValue() != pr.number()) {
            taskStore.linkPullRequest(task.id(), pr.number(), pr.state());
        }
        Optional<TaskPhase> target = TaskLifecyclePhases.observedPhaseFor(pr);
        target.ifPresent(phase -> phaseMachine.observe(task.id(), phase, "pr_state_observed"));
    }
}

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
package com.bytequay.app.flow.runtime;

import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import com.bytequay.app.flow.upstream.UpstreamSync;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.UpstreamSyncRun;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * The one entry command for an upstream cherry-pick range.
 *
 * <p>A run owns a range, so there is deliberately no way to attach one to an
 * existing pull request. The confirmed selection becomes an ordinary flow Task
 * — which is what lets the ordinary {@code INITIAL_PUBLISH} gate and generic
 * CI Autofix own everything after the first push, with their gates and
 * adversarial review intact.
 *
 * <p>No Git runs here. The handler persists the request and the run, and the
 * runtime dispatcher does the rest.
 */
public final class UpstreamSyncCommands
{
    /**
     * ponytail: one budget knob, spent per conflict-repair turn. Phase 1 is
     * bounded by the run's budget and nothing else — no pick ceiling and no
     * round count, because a large range legitimately needs many repairs.
     */
    public static final int DEFAULT_REPAIR_TURN_BUDGET = 50;

    public record StartReceipt(Task task, UpstreamSyncRun run)
    {
        public StartReceipt
        {
            requireNonNull(task, "task is null");
            requireNonNull(run, "run is null");
        }
    }

    private final TaskProvisioning provisioning;
    private final UpstreamSync upstreamSync;
    private final NewFlowDispatcher dispatcher;
    private final InitialTaskDispatcher initialTasks;

    public UpstreamSyncCommands(
            TaskProvisioning provisioning,
            UpstreamSync upstreamSync,
            NewFlowDispatcher dispatcher,
            InitialTaskDispatcher initialTasks)
    {
        this.provisioning = requireNonNull(
                provisioning, "provisioning is null");
        this.upstreamSync = requireNonNull(
                upstreamSync, "upstreamSync is null");
        this.dispatcher = requireNonNull(dispatcher, "dispatcher is null");
        this.initialTasks = requireNonNull(
                initialTasks, "initialTasks is null");
    }

    public StartReceipt startConfirmed(
            String requestKey,
            String repositoryId,
            String goalText,
            String sourceRemote,
            String sourceFromRef,
            String sourceToRef,
            String targetRef,
            List<String> selectedUpstreamShas,
            String requestedByUserId)
    {
        return startConfirmed(
                requestKey, repositoryId, goalText, sourceRemote,
                sourceFromRef, sourceToRef, targetRef, selectedUpstreamShas,
                requestedByUserId, DEFAULT_REPAIR_TURN_BUDGET);
    }

    public StartReceipt startConfirmed(
            String requestKey,
            String repositoryId,
            String goalText,
            String sourceRemote,
            String sourceFromRef,
            String sourceToRef,
            String targetRef,
            List<String> selectedUpstreamShas,
            String requestedByUserId,
            int repairTurnBudget)
    {
        Task task = provisioning.startTask(
                requestKey, repositoryId, goalText);
        UpstreamSyncRun run = upstreamSync.startRun(
                requestKey, repositoryId, goalText, sourceRemote,
                sourceFromRef, sourceToRef, targetRef, selectedUpstreamShas,
                requestedByUserId, task.taskId(), repairTurnBudget);
        dispatcher.wake();
        initialTasks.wake();
        return new StartReceipt(task, run);
    }
}

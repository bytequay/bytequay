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
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TaskStatus;
import com.bytequay.app.flow.upstream.UpstreamSync;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.RunState;

import static java.util.Objects.requireNonNull;

/**
 * What closing an upstream sync run releases, in the one order that is safe.
 *
 * <p>Two callers reach it and they differ only in when: a run with a turn in
 * flight closes from the pick loop, at the boundary where the worktree is not
 * being written; a parked or finished run closes from the command that asked,
 * because nothing holds it. Both release the same things, so both come here.
 */
final class UpstreamSyncTeardown
{
    private UpstreamSyncTeardown() {}

    /**
     * Cancels the Task and releases the worktree, in that order.
     *
     * <p>The Task is cancelled first so that a failure to remove the checkout
     * cannot leave a run that is closed on disk but still live in the model —
     * the state that would let the dispatcher hand it another turn. A worktree
     * left behind is inert and removable by hand; a run the program still
     * believes in is not.
     */
    static void close(
            FlowRuntime runtime,
            TaskProvisioning provisioning,
            UpstreamSync upstreamSync,
            Task task,
            String runId,
            String reasonCode)
    {
        requireNonNull(runtime, "runtime is null");
        requireNonNull(provisioning, "provisioning is null");
        requireNonNull(upstreamSync, "upstreamSync is null");
        requireNonNull(task, "task is null");
        requireNonNull(runId, "runId is null");
        if (task.status() != TaskStatus.COMPLETED
                && task.status() != TaskStatus.CANCELED) {
            runtime.transitionTask(
                    task.taskId(),
                    task.currentLifecycleRevisionId(),
                    TaskStatus.CANCELED,
                    reasonCode,
                    null);
        }
        upstreamSync.advanceState(runId, RunState.CANCELED);
        provisioning.releaseWorktree(task);
    }
}

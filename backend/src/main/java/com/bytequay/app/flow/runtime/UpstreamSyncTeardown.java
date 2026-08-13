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
     * Closes the run, and releases what it holds if nothing else does.
     *
     * <p>The run's own record goes first because it is the only part that is
     * always ours to close. Everything after it belongs to the Task, and a
     * Task's lifecycle may not move while a writer lease is live or an
     * operation is claimed — the runtime refuses it, and rightly: that lease
     * is what says an agent may still be mutating the worktree, so cancelling
     * around it would be cancelling around a live process.
     *
     * <p>So a run whose turn is still in flight closes with its checkout
     * intact. The lease and the claim clear when the turn ends, and closing
     * again from there finishes the release. This is deliberately not a wait:
     * the user asked for the run to stop and the run has stopped.
     *
     * @return whether the run's local hold was torn down — false only when a
     *         live turn still has it. Git's own success at removing the
     *         directory is not part of this: a worktree someone already
     *         deleted by hand is one nothing is holding.
     */
    static boolean close(
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
        upstreamSync.advanceState(runId, RunState.CANCELED);
        if (heldByALiveTurn(runtime, task)) {
            return false;
        }
        if (task.status() != TaskStatus.COMPLETED
                && task.status() != TaskStatus.CANCELED) {
            runtime.transitionTask(
                    task.taskId(),
                    task.currentLifecycleRevisionId(),
                    TaskStatus.CANCELED,
                    reasonCode,
                    null);
        }
        provisioning.releaseWorktree(task);
        return true;
    }

    /**
     * Whether something the runtime knows about may still be writing.
     *
     * <p>The same three conditions {@code TaskRuntime} refuses a lifecycle
     * change on. They are read here rather than discovered by catching that
     * refusal, so a close that cannot release yet is an ordinary outcome
     * instead of an exception the surface has to translate.
     */
    private static boolean heldByALiveTurn(FlowRuntime runtime, Task task)
    {
        return task.selectedWriterOperationId() != null
                || runtime.writerFence(task.taskId()).isPresent()
                || runtime.hasClaimedOperation(task.taskId());
    }
}

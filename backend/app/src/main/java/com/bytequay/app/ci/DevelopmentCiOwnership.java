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
package com.bytequay.app.ci;

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.impl.AgentRuntimeRegistry;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.core.ci.CiAutofixConflictException;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixRunId;
import com.bytequay.core.ci.CiAutofixStore;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Live authority only; a persisted parent link can never recreate it after restart. */
public final class DevelopmentCiOwnership
{
    private final CiAutofixStore store;
    private final AgentTaskStore tasks;
    private final AgentRuntimeRegistry runtimes;
    private final Clock clock;
    private final ConcurrentMap<CiAutofixRunId, Owner> owners = new ConcurrentHashMap<>();

    public DevelopmentCiOwnership(CiAutofixStore store, AgentTaskStore tasks, AgentRuntimeRegistry runtimes, Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.runtimes = requireNonNull(runtimes, "runtimes is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public boolean authorized(CiAutofixRun run)
    {
        if (run.scope().parentTaskId().isEmpty()) {
            return true;
        }
        Owner owner = owners.get(run.id());
        return owner != null && !owner.revoked().get()
                && clock.instant().isBefore(run.authorization().expiresAt())
                && run.scope().parentTaskId().orElseThrow().equals(owner.parentTaskId().toString())
                && runtimes.ownsDevelopmentContinuation(owner.parentTaskId(), owner.sourceTurnId());
    }

    public void requireSourceAdmission(AgentTask task)
    {
        if (!task.interactive() || task.taskWorktree().isEmpty()) {
            return;
        }
        var worktree = task.taskWorktree().orElseThrow();
        store.findByActiveBranch(worktree.physicalRepository().owner() + "/" + worktree.physicalRepository().name(), worktree.generatedBranch())
                .ifPresent(run -> {
                    throw new AgentConflictException("CI Autofix owns this task branch; stop its run before starting source work");
                });
    }

    /** Revocation is immediate; callers separately drain actions before stopping provider processes. */
    public void revoke(TaskId parentTaskId)
    {
        owners.values().stream().filter(owner -> owner.parentTaskId().equals(parentTaskId)).forEach(owner -> owner.revoked().set(true));
    }

    void drain(TaskId parentTaskId)
    {
        for (var entry : owners.entrySet()) {
            Owner owner = entry.getValue();
            if (owner.parentTaskId().equals(parentTaskId)) {
                synchronized (owner) {
                    // An action admitted before revoke may still be returning from a native call.
                    if (owner.revoked().get()) {
                        owners.remove(entry.getKey(), owner);
                    }
                }
            }
        }
    }

    void forgetFinished(CiAutofixRun run)
    {
        if (run.status() == CiAutofixRun.Status.FINISHED) {
            owners.remove(run.id());
        }
    }

    void insert(CiAutofixRun run, Optional<TurnId> sourceTurnId)
    {
        if (run.scope().parentTaskId().isPresent()) {
            TaskId parent = TaskId.valueOf(run.scope().parentTaskId().orElseThrow());
            synchronized (runtimes.taskLock(parent)) {
                TurnId source = sourceTurnId.orElseThrow();
                requireParent(parent, source);
                if (!tasks.find(parent).filter(task -> matchesSource(task, run)).isPresent()) {
                    throw new CiAutofixConflictException("The CI pull request branch does not belong to the development parent");
                }
                store.insert(run);
                owners.put(run.id(), new Owner(parent, source, new AtomicBoolean()));
            }
            return;
        }
        List<AgentTask> sources = tasks.listByWorkspace(run.scope().workspaceId()).stream()
                .filter(task -> matchesSource(task, run))
                .collect(toImmutableList());
        if (sources.size() > 1) {
            throw new CiAutofixConflictException("More than one development task owns the pull request branch");
        }
        if (sources.isEmpty()) {
            store.insert(run);
            return;
        }
        TaskId source = sources.getFirst().id();
        synchronized (runtimes.taskLock(source)) {
            AgentTask current = tasks.find(source).orElseThrow();
            if (current.worktreeMutationClaim().isPresent() || current.developmentContinuationUnfinished() || runtimes.hasDevelopmentOwner(source)) {
                throw new CiAutofixConflictException("The development task is still using this pull request branch");
            }
            store.insert(run);
        }
    }

    void require(CiAutofixRun run)
    {
        if (!authorized(run)) {
            throw new CiAutofixConflictException("The development coordinator no longer authorizes this CI Autofix run");
        }
    }

    public void requireTask(TaskId taskId)
    {
        store.findByAgentTaskId(taskId.toString()).ifPresent(this::require);
    }

    public boolean deliveryOwnedTask(String taskId)
    {
        return store.findByAgentTaskId(taskId).flatMap(run -> run.scope().parentTaskId()).isPresent();
    }

    <T> T execute(CiAutofixRun run, Supplier<T> action)
    {
        if (run.scope().parentTaskId().isEmpty()) {
            return action.get();
        }
        Owner owner = owners.get(run.id());
        if (owner == null) {
            throw new CiAutofixConflictException("The development coordinator is unavailable for this CI Autofix run");
        }
        synchronized (owner) {
            require(run);
            return action.get();
        }
    }

    public <T> T executeTask(TaskId taskId, Supplier<T> action)
    {
        return store.findByAgentTaskId(taskId.toString()).map(run -> execute(run, action)).orElseGet(action);
    }

    void requireParent(TaskId parent, TurnId source)
    {
        AgentTask task = tasks.find(parent).orElseThrow(() -> new CiAutofixConflictException("Development parent task is missing"));
        if (task.state() != TaskState.OPEN || !task.interactive() || !task.developmentContinuationUnfinished() || !runtimes.ownsDevelopmentContinuation(parent, source)) {
            throw new CiAutofixConflictException("Development parent is no longer active");
        }
    }

    private static boolean matchesSource(AgentTask task, CiAutofixRun run)
    {
        return task.state() == TaskState.OPEN && task.interactive()
                && task.taskWorktree().filter(worktree -> worktree.generatedBranch().equals(run.scope().headRef())
                        && (worktree.physicalRepository().owner() + "/" + worktree.physicalRepository().name())
                                .equalsIgnoreCase(run.scope().pullRequest().repoFullName())).isPresent();
    }

    private record Owner(TaskId parentTaskId, TurnId sourceTurnId, AtomicBoolean revoked) {}
}

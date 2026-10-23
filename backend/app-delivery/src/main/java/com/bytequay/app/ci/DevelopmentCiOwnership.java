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
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.impl.AgentRuntimeRegistry;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.app.agent.runtime.ReviewFixTurns;
import com.bytequay.core.ci.CiAutofixConflictException;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixRunId;
import com.bytequay.core.ci.CiAutofixStore;
import com.bytequay.core.domain.PullRequestTask;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.PullRequestTaskStore;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

import static com.bytequay.agent.core.AgentEventSource.root;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * A development task's CI runs watch its pull request beside it. They act while the task is open and
 * nothing stopped them, so the monitor resumes them after a restart.
 */
public final class DevelopmentCiOwnership
{
    private static final int MAXIMUM_SCOPE_CHARACTERS = 128 * 1024;

    private final CiAutofixStore store;
    private final AgentTaskStore tasks;
    private final AgentTurnStore turns;
    private final PullRequestTaskStore assignments;
    private final PullRequestStore pullRequests;
    private final AgentEventService events;
    private final AgentRuntimeRegistry runtimes;
    private final Set<CiAutofixRunId> revoked = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<CiAutofixRunId, Object> monitors = new ConcurrentHashMap<>();

    public DevelopmentCiOwnership(CiAutofixStore store, AgentTaskStore tasks, AgentTurnStore turns, PullRequestTaskStore assignments, PullRequestStore pullRequests,
            AgentEventService events, AgentRuntimeRegistry runtimes)
    {
        this.store = requireNonNull(store, "store is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.assignments = requireNonNull(assignments, "assignments is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.events = requireNonNull(events, "events is null");
        this.runtimes = requireNonNull(runtimes, "runtimes is null");
    }

    public boolean authorized(CiAutofixRun run)
    {
        return run.scope().parentTaskId().isEmpty() || !revoked.contains(run.id()) && parentOpen(parent(run));
    }

    /** Whether a run is repairing in its own checkout, which source work on the same branch must wait for or stop. */
    public static boolean writing(CiAutofixRun run)
    {
        return run.status() != CiAutofixRun.Status.FINISHED && (run.currentTurnId().isPresent() || run.currentRebaseId().isPresent());
    }

    /** Whether the development task is working on its branch, so its CI runs must not start another repair. */
    public boolean parentBusy(CiAutofixRun run)
    {
        return run.scope().parentTaskId().isPresent() && tasks.find(parent(run))
                .filter(task -> task.developmentContinuationUnfinished()
                        || task.worktreeMutationClaim().filter(claim -> claim.kind() != WorktreeMutationClaim.Kind.LOCAL_RUN).isPresent())
                .isPresent();
    }

    public void requireSourceAdmission(AgentTask task)
    {
        if (!task.interactive() || task.taskWorktree().isEmpty()) {
            return;
        }
        var worktree = task.taskWorktree().orElseThrow();
        Optional<String> assignedBranch = assignment(task)
                .flatMap(pr -> pullRequests.findIdByRepoAndNumber(pr.repo(), pr.number()).flatMap(pullRequests::findById))
                .flatMap(pr -> Optional.ofNullable(pr.headRef()));
        // The task's own runs only watch CI; a user's turn stops their repairs before it reaches this check.
        if (store.listByParentTaskId(task.id().toString()).stream().anyMatch(DevelopmentCiOwnership::writing)) {
            throw new AgentConflictException("CI Autofix is still repairing this task branch; wait for it or stop it first");
        }
        store.findByActiveBranch(worktree.physicalRepository().owner() + "/" + worktree.physicalRepository().name(), assignedBranch.orElse(worktree.generatedBranch()))
                .filter(run -> run.scope().parentTaskId().filter(task.id().toString()::equals).isEmpty())
                .ifPresent(run -> {
                    throw new AgentConflictException("CI Autofix owns this task branch; stop its run before starting source work");
                });
    }

    /** Revocation is immediate; callers drain admitted actions before stopping provider processes. */
    void revoke(CiAutofixRunId runId)
    {
        revoked.add(runId);
    }

    void drain(CiAutofixRunId runId)
    {
        synchronized (monitor(runId)) {
            // An action admitted before revoke may still be returning from a native call.
            checkState(revoked.contains(runId), "CI Autofix run was not revoked before draining");
        }
    }

    void forgetFinished(CiAutofixRun run)
    {
        if (run.status() == CiAutofixRun.Status.FINISHED) {
            monitors.remove(run.id());
        }
    }

    void insert(CiAutofixRun run)
    {
        if (run.scope().parentTaskId().isPresent()) {
            TaskId parent = parent(run);
            // Fails before any CI work when the user's scope cannot be carried to the repair in full.
            sourceInstructions(parent);
            synchronized (runtimes.taskLock(parent)) {
                requireParent(parent);
                if (!tasks.find(parent).filter(task -> matchesSource(task, run)).isPresent()) {
                    throw new CiAutofixConflictException("The CI pull request branch does not belong to the development parent");
                }
                store.insert(run);
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
            throw new CiAutofixConflictException("The development task no longer authorizes this CI Autofix run");
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

    /** Applies the development task's accepted user scope to repairs, first-turn rebase conflicts, and CI replies. */
    public String instructions(AgentTask task)
    {
        return store.findByAgentTaskId(task.id().toString())
                .filter(run -> run.scope().parentTaskId().isPresent())
                .map(run -> {
                    require(run);
                    return sourceInstructions(parent(run));
                })
                .orElse("");
    }

    <T> T execute(CiAutofixRun run, Supplier<T> action)
    {
        if (run.scope().parentTaskId().isEmpty()) {
            return action.get();
        }
        synchronized (monitor(run.id())) {
            require(run);
            return action.get();
        }
    }

    public <T> T executeTask(TaskId taskId, Supplier<T> action)
    {
        return store.findByAgentTaskId(taskId.toString()).map(run -> execute(run, action)).orElseGet(action);
    }

    void requireParent(TaskId parent)
    {
        if (!parentOpen(parent)) {
            throw new CiAutofixConflictException("Development parent is no longer active");
        }
    }

    private boolean parentOpen(TaskId parent)
    {
        return tasks.find(parent).filter(task -> task.state() == TaskState.OPEN && task.interactive()).isPresent();
    }

    private Object monitor(CiAutofixRunId runId)
    {
        return monitors.computeIfAbsent(runId, ignored -> new Object());
    }

    private static TaskId parent(CiAutofixRun run)
    {
        return TaskId.valueOf(run.scope().parentTaskId().orElseThrow());
    }

    private String sourceInstructions(TaskId parent)
    {
        Set<TurnId> userTurns = new HashSet<>();
        for (AgentTurn turn : turns.list(parent)) {
            checkState(turn.taskId().equals(parent), "Development turn history belongs to another task");
            if (turn.developmentOrigin().isEmpty() && !turn.knownUnsent() && !ReviewFixTurns.isFixTurn(turn)) {
                userTurns.add(turn.id());
            }
        }
        Set<TurnId> found = new HashSet<>();
        StringBuilder inputs = new StringBuilder();
        long cursor = 0;
        while (true) {
            var page = events.readHistory(parent, OptionalLong.empty(), OptionalLong.of(cursor));
            for (var event : page.events()) {
                checkState(event.taskId().equals(parent), "Development input history belongs to another task");
                if (event.source().equals(root()) && event.turnId().filter(userTurns::contains).isPresent()
                        && event.payload() instanceof AgentEventPayload.UserMessage message) {
                    if (message.deliveryKind() != AgentEventPayload.DeliveryKind.STEER) {
                        found.add(event.turnId().orElseThrow());
                    }
                    inputs.append("\n--- Accepted user input ---\n").append(message.text()).append('\n');
                    message.attachments().forEach(attachment -> inputs.append("Attachment unavailable in this CI task: ").append(attachment.name()).append('\n'));
                    checkState(inputs.length() <= MAXIMUM_SCOPE_CHARACTERS, "Development user scope exceeds the prompt limit; CI cannot continue without the full text");
                }
            }
            if (page.afterCursor().isEmpty()) {
                break;
            }
            long next = page.afterCursor().orElseThrow();
            checkState(next > cursor, "Development input history did not advance");
            cursor = next;
        }
        checkState(found.equals(userTurns), "Development user inputs are missing; CI cannot continue without the full scope");
        return """


                Development request scope for this CI task:
                These accepted user inputs are in conversation order, including earlier questions' answers and steering.
                Preserve their objective and restrictions while resolving the current CI failure or rebase conflict.
                Later explicit user decisions supersede earlier ones. This is scope context, not a request to repeat
                completed development or publication. Do not execute parent instructions to push, create a PR, merge,
                delete remote resources, fetch, or control a rebase. The current CI/rebase instructions still govern
                those actions, and ByteQuay owns remote writes and rebase progression. If a repair requires violating
                the user's file or workflow restrictions, leave it unchanged and explain the blocker to the user.
                Attachment contents are not copied here. If an unavailable attachment is needed to determine the
                permitted repair, do not guess its contents; leave the repair unchanged and ask for that context.
                """ + inputs + "\nEnd of development request scope. Continue only the current CI/rebase assignment.\n";
    }

    private boolean matchesSource(AgentTask task, CiAutofixRun run)
    {
        return task.state() == TaskState.OPEN && task.interactive()
                && task.workspaceId().filter(run.scope().workspaceId()::equals).isPresent()
                && task.taskWorktree().filter(worktree -> (worktree.physicalRepository().owner() + "/" + worktree.physicalRepository().name())
                        .equalsIgnoreCase(run.scope().headRepository().fullName())
                        && assignments.findByTaskId(task.id().toString()).map(assigned -> assigned.taskId().equals(task.id().toString())
                                && assigned.workspaceId().equals(run.scope().workspaceId())
                                && assigned.repo().equalsIgnoreCase(run.scope().pullRequest().repoFullName()) && assigned.number() == run.scope().pullRequest().number())
                                .orElseGet(() -> worktree.generatedBranch().equals(run.scope().headRef()))).isPresent();
    }

    private Optional<PullRequestTask> assignment(AgentTask task)
    {
        return assignments.findByTaskId(task.id().toString())
                .filter(assigned -> assigned.taskId().equals(task.id().toString()) && task.workspaceId().filter(assigned.workspaceId()::equals).isPresent());
    }

}

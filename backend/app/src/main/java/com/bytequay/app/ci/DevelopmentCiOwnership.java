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
import com.bytequay.agent.core.impl.AgentRuntimeRegistry;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.core.ci.CiAutofixConflictException;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixRunId;
import com.bytequay.core.ci.CiAutofixStore;
import com.bytequay.core.domain.PullRequestTask;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.PullRequestTaskStore;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static com.bytequay.agent.core.AgentEventSource.root;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Live authority only; a persisted parent link can never recreate it after restart. */
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
    private final Clock clock;
    private final ConcurrentMap<CiAutofixRunId, Owner> owners = new ConcurrentHashMap<>();

    public DevelopmentCiOwnership(CiAutofixStore store, AgentTaskStore tasks, AgentTurnStore turns, PullRequestTaskStore assignments, PullRequestStore pullRequests,
            AgentEventService events, AgentRuntimeRegistry runtimes, Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.assignments = requireNonNull(assignments, "assignments is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.events = requireNonNull(events, "events is null");
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
        Optional<String> assignedBranch = assignment(task)
                .flatMap(pr -> pullRequests.findIdByRepoAndNumber(pr.repo(), pr.number()).flatMap(pullRequests::findById))
                .flatMap(pr -> Optional.ofNullable(pr.headRef()));
        store.listByParentTaskId(task.id().toString()).stream().filter(run -> run.status() != CiAutofixRun.Status.FINISHED).findFirst()
                .or(() -> store.findByActiveBranch(worktree.physicalRepository().owner() + "/" + worktree.physicalRepository().name(),
                        assignedBranch.orElse(worktree.generatedBranch())))
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
            TurnId source = sourceTurnId.orElseThrow();
            String instructions = sourceInstructions(parent, sourceUserTurn(parent, source).id());
            synchronized (runtimes.taskLock(parent)) {
                requireParent(parent, source);
                if (!tasks.find(parent).filter(task -> matchesSource(task, run)).isPresent()) {
                    throw new CiAutofixConflictException("The CI pull request branch does not belong to the development parent");
                }
                store.insert(run);
                owners.put(run.id(), new Owner(parent, source, instructions, new AtomicBoolean()));
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

    /** Applies the same accepted user scope to repairs, first-turn rebase conflicts, and CI replies. */
    public String instructions(AgentTask task)
    {
        return store.findByAgentTaskId(task.id().toString())
                .filter(run -> run.scope().parentTaskId().isPresent())
                .map(run -> {
                    Owner owner = owners.get(run.id());
                    require(run);
                    checkState(owner != null, "Development CI user scope is unavailable");
                    return owner.instructions();
                })
                .orElse("");
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

    private AgentTurn sourceUserTurn(TaskId parent, TurnId source)
    {
        AgentTurn turn = turns.find(source).filter(value -> value.taskId().equals(parent))
                .orElseThrow(() -> new CiAutofixConflictException("Development source turn is missing"));
        AgentTurn user = turn.developmentOrigin()
                .map(origin -> turns.find(origin.parentUserTurnId()).filter(value -> value.taskId().equals(parent))
                        .orElseThrow(() -> new CiAutofixConflictException("Development source user turn is missing")))
                .orElse(turn);
        checkState(user.developmentOrigin().isEmpty(), "Development source does not resolve to a user turn");
        return user;
    }

    private String sourceInstructions(TaskId parent, TurnId source)
    {
        Set<TurnId> userTurns = new HashSet<>();
        for (AgentTurn turn : turns.list(parent)) {
            checkState(turn.taskId().equals(parent), "Development turn history belongs to another task");
            if (turn.developmentOrigin().isEmpty() && !turn.knownUnsent()) {
                userTurns.add(turn.id());
            }
            if (turn.id().equals(source)) {
                break;
            }
        }
        checkState(userTurns.contains(source), "Development source user turn is missing from history");
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

    private record Owner(TaskId parentTaskId, TurnId sourceTurnId, String instructions, AtomicBoolean revoked) {}
}

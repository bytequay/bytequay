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
package com.bytequay.app.agent;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.TaskBranches;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskNames;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.impl.AgentRuntimeRegistry;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.app.workspace.WorkspaceOperationLocks;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.store.PullRequestTaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

/** Names are suggestions until an idle worktree boundary grants authority to change Git. */
@Component
public class DevelopmentBranchService
{
    private static final Logger log = LoggerFactory.getLogger(DevelopmentBranchService.class);

    private final AgentTaskStore tasks;
    private final AgentTurnStore turns;
    private final AgentRuntimeRegistry runtime;
    private final DevelopmentGitInspector git;
    private final AgentReviewStore reviews;
    private final PullRequestTaskStore assignments;
    private final ObjectProvider<DevelopmentFollowupCoordinator> followups;
    private final DevelopmentBranchRenameJournal journal;
    private final Clock clock;
    private final ConcurrentMap<TaskId, String> proposed = new ConcurrentHashMap<>();
    private final Set<TaskId> agentNamed = ConcurrentHashMap.newKeySet();

    public DevelopmentBranchService(AgentTaskStore tasks, AgentTurnStore turns, AgentRuntimeRegistry runtime, DevelopmentGitInspector git,
            AgentReviewStore reviews, PullRequestTaskStore assignments, ObjectProvider<DevelopmentFollowupCoordinator> followups,
            DevelopmentBranchRenameJournal journal, Clock clock)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.git = requireNonNull(git, "git is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.assignments = requireNonNull(assignments, "assignments is null");
        this.followups = requireNonNull(followups, "followups is null");
        this.journal = requireNonNull(journal, "journal is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /** An optional naming helper never changes Git, and cannot replace the agent's own suggestion. */
    public void propose(TaskId taskId, String suffix)
    {
        new TaskNames("Name branch", suffix);
        synchronized (runtime.taskLock(taskId)) {
            if (!agentNamed.contains(taskId)) {
                tasks.find(taskId).filter(this::eligible).ifPresent(task -> proposed.putIfAbsent(taskId, fullName(task, suffix)));
            }
        }
    }

    public String rename(TaskId taskId, TurnId turnId, String suffix)
    {
        new TaskNames("Name branch", suffix);
        synchronized (runtime.taskLock(taskId)) {
            AgentTask task = tasks.require(taskId);
            checkState(task.interactive() && task.state() == TaskState.OPEN
                    && reviews.findReviewByTask(taskId.toString()).isEmpty(), "Branch naming requires a development task");
            AgentTurn turn = turns.findActive(taskId).orElseThrow(() -> new IllegalStateException("The development turn has ended"));
            checkState(turn.id().equals(turnId) && turn.state() == TurnState.RUNNING && turn.cancellationRequestedAt().isEmpty() && turn.developmentOrigin().isEmpty()
                    && turn.settings().flatMap(AgentTurnSettings::developmentAutomation).filter(DevelopmentAutomation::enabled).isPresent(),
                    "Branch naming requires the active user development turn");
            List<AgentTurn> history = turns.list(taskId);
            checkState(history.size() == 1 && history.getFirst().id().equals(turnId), "Branch naming is only available during the first turn");
            checkState(task.worktreeMutationClaim().filter(claim -> claim.kind() == WorktreeMutationClaim.Kind.TURN && claim.ownerId().equals(turnId.toString())).isPresent(),
                    "The development turn no longer owns the worktree");
            TaskWorktree previous = task.taskWorktree().orElseThrow();
            if (assignments.findByTaskId(taskId.toString()).isPresent()) {
                return "Keep the imported PR branch: " + previous.generatedBranch();
            }
            String branch = fullName(task, suffix);
            agentNamed.add(taskId);
            proposed.put(taskId, branch);
            return "Recorded proposed branch: " + branch + ". ByteQuay will apply it after this turn and its processes stop, before automatic publication. "
                    + "Keep using the current branch for this turn: " + previous.generatedBranch();
        }
    }

    /** Called under core's verified idle authority, before another turn or cleanup reads the task. */
    public AgentTask recover(AgentTask supplied)
    {
        synchronized (runtime.taskLock(supplied.id())) {
            AgentTask task = tasks.require(supplied.id());
            if (task.state().terminal()) {
                proposed.remove(task.id());
                agentNamed.remove(task.id());
            }
            if (!task.ownsWorktree() || task.worktreeRemovedAt().isPresent()) {
                return task;
            }
            Optional<DevelopmentBranchRename> pending = journal.read(task.id());
            if (pending.isEmpty()) {
                return task;
            }
            runtime.requireStoppedSession(task.id());
            checkState(turns.findActive(task.id()).isEmpty()
                    && task.worktreeMutationClaim().filter(claim -> claim.kind() != WorktreeMutationClaim.Kind.RECOVERY).isEmpty(),
                    "The pending branch rename needs a stopped task");
            TaskWorktree persisted = task.taskWorktree().orElseThrow();
            return WorkspaceOperationLocks.withLock(persisted.workspaceId(), () -> {
                TaskWorktree renamed = git.recoverRename(persisted, pending.orElseThrow(), updated -> record(task.id(), updated));
                TaskWorktree original = new TaskWorktree(persisted.taskId(), persisted.workspaceId(), persisted.physicalRepository(), persisted.canonicalWorktree(),
                        pending.orElseThrow().previousBranch(), persisted.startCommit());
                if (!original.equals(renamed)) {
                    followups.getObject().branchRenamed(task.id(), original, renamed);
                }
                journal.clear(task.id());
                return tasks.require(task.id());
            });
        }
    }

    /** The caller retains continuation authority after successful provider shutdown until this returns. */
    public AgentTask prepare(AgentTask supplied)
    {
        synchronized (runtime.taskLock(supplied.id())) {
            AgentTask task = recover(supplied);
            String branch = proposed.get(task.id());
            if (branch == null) {
                return task;
            }
            if (!eligible(task)) {
                proposed.remove(task.id(), branch);
                return task;
            }
            runtime.requireStoppedSession(task.id());
            checkState(turns.findActive(task.id()).isEmpty() && task.worktreeMutationClaim().isEmpty(), "Branch naming requires a stopped task");
            Optional<AgentTurn> latest = turns.list(task.id()).reversed().stream().filter(turn -> turn.developmentOrigin().isEmpty()).findFirst();
            if (latest.filter(turn -> turn.state() == TurnState.COMPLETED && turn.cancellationRequestedAt().isEmpty()).isEmpty()) {
                return task;
            }
            // A retained review has its own immutable worktree snapshot. Keep its shared branch stable.
            if (tasks.listByWorkspace(task.workspaceId().orElseThrow()).stream()
                    .anyMatch(other -> !other.id().equals(task.id()) && other.taskWorktree().map(TaskWorktree::taskId).filter(task.id()::equals).isPresent())) {
                proposed.remove(task.id(), branch);
                return task;
            }
            TaskWorktree previous = task.taskWorktree().orElseThrow();
            return WorkspaceOperationLocks.withLock(previous.workspaceId(), () -> {
                TaskWorktree renamed;
                try {
                    renamed = git.renameBranch(git.capture(task), branch, intent -> journal.write(task.id(), intent), updated -> record(task.id(), updated));
                }
                catch (RuntimeException failure) {
                    // Once intent exists, Git or persistence may have changed and recovery must remain mandatory.
                    if (journal.read(task.id()).isPresent()) {
                        throw failure;
                    }
                    // Before that, a name is only a suggestion: one that cannot be applied, such as a name already taken,
                    // is dropped rather than failing this and every later delivery.
                    proposed.remove(task.id(), branch);
                    log.warn("Keeping branch {} for task {}: the proposed name {} could not be applied", previous.generatedBranch(), task.id(), branch, failure);
                    return tasks.require(task.id());
                }
                if (!renamed.equals(previous)) {
                    followups.getObject().branchRenamed(task.id(), previous, renamed);
                    journal.clear(task.id());
                }
                proposed.remove(task.id(), branch);
                return tasks.require(task.id());
            });
        }
    }

    private void record(TaskId taskId, TaskWorktree renamed)
    {
        tasks.update(tasks.require(taskId).withBranch(renamed.generatedBranch(), Instant.now(clock)));
    }

    private boolean eligible(AgentTask task)
    {
        return task.interactive() && task.state() == TaskState.OPEN && task.ownsWorktree() && task.worktreeRemovedAt().isEmpty()
                && reviews.findReviewByTask(task.id().toString()).isEmpty() && assignments.findByTaskId(task.id().toString()).isEmpty();
    }

    private static String fullName(AgentTask task, String suffix)
    {
        String current = task.taskWorktree().orElseThrow().generatedBranch();
        String prefix = TaskBranches.prefix(current.split("/", 2)[0]);
        checkState(current.startsWith(prefix), "The task branch has no user namespace");
        return prefix + suffix;
    }
}

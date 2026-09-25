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
package com.bytequay.app.context;

import com.bytequay.agent.core.AgentApprovalService;
import com.bytequay.agent.core.AgentCompletionService;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.CompleteAgentTaskCommand;
import com.bytequay.agent.core.CreateAgentTaskCommand;
import com.bytequay.agent.core.StartAgentTurnCommand;
import com.bytequay.agent.core.TaskBranches;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.agent.WorkflowAgentTasks;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.settings.AgentModelSetting;
import com.bytequay.app.service.settings.AgentModelType;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.app.workspace.WorkspaceOperationLocks;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.context.ContextPreparationResult;
import com.bytequay.core.context.RepositoryNotebook;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.Striped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.Lock;

import static com.google.common.base.Preconditions.checkArgument;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/** Runs one explicitly requested repository-learning task using the existing agent lifecycle. */
@Service
public class ContextBuildService
{
    private static final Logger log = LoggerFactory.getLogger(ContextBuildService.class);
    private final ContextBuildStore builds;
    private final RepositoryContextService notebooks;
    private final RepositoryContextPreparation preparation;
    private final WorkspaceRepositoryService workspaces;
    private final ModelSettingsService models;
    private final CredentialService credentials;
    private final AgentDriverRegistry drivers;
    private final AgentTaskService tasks;
    private final AgentTaskStore taskStore;
    private final AgentTurnService turns;
    private final AgentTurnStore turnStore;
    private final AgentCompletionService completions;
    private final AgentApprovalService approvals;
    private final ObjectProvider<ContextBuildCoordinator> coordinator;
    private final GitCommandRunner git;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Striped<Lock> locks = Striped.lock(64);

    public ContextBuildService(
            ContextBuildStore builds, RepositoryContextService notebooks, RepositoryContextPreparation preparation, WorkspaceRepositoryService workspaces,
            ModelSettingsService models, CredentialService credentials, AgentDriverRegistry drivers, AgentTaskService tasks, AgentTaskStore taskStore,
            AgentTurnService turns, AgentTurnStore turnStore, AgentCompletionService completions, AgentApprovalService approvals,
            ObjectProvider<ContextBuildCoordinator> coordinator, GitCommandRunner git, ObjectMapper mapper, Clock clock)
    {
        this.builds = requireNonNull(builds, "builds is null");
        this.notebooks = requireNonNull(notebooks, "notebooks is null");
        this.preparation = requireNonNull(preparation, "preparation is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.models = requireNonNull(models, "models is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.drivers = requireNonNull(drivers, "drivers is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.turnStore = requireNonNull(turnStore, "turnStore is null");
        this.completions = requireNonNull(completions, "completions is null");
        this.approvals = requireNonNull(approvals, "approvals is null");
        this.coordinator = requireNonNull(coordinator, "coordinator is null");
        this.git = requireNonNull(git, "git is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public View get(String workspaceId)
    {
        builds.find(workspaceId).ifPresent(build -> reconcile(workspaceId, build.taskId()));
        return view(workspaceId);
    }

    public View start(String workspaceId, Request request)
    {
        Lock lock = locks.get(workspaceId);
        lock.lock();
        try {
            String digest = digest(request);
            Optional<ContextBuild> previous = builds.find(workspaceId);
            if (previous.filter(build -> build.requestKey().equals(request.requestKey())).isPresent()) {
                checkArgument(previous.orElseThrow().requestDigest().equals(digest), "Request key was reused with different learning inputs");
                return view(workspaceId);
            }
            checkArgument(previous.map(ContextBuild::sequence).orElse(0L) == request.expectedBuildSequence(), "Build request sequence changed; inspect the latest build");
            checkArgument(previous.flatMap(build -> taskStore.find(TaskId.valueOf(build.taskId())))
                            .map(task -> task.state().terminal() && task.worktreeRemovedAt().isPresent()).orElse(true),
                    "The previous learning task must finish and its worktree must be cleaned up before another request");
            RepositoryNotebook notebook = notebooks.getNotebook(workspaceId);
            checkArgument(notebook.revision() == request.expectedRevision(), "Notebook revision changed; inspect the latest configuration");
            request.scopeIds().forEach(id -> checkArgument(notebook.scopes().stream().anyMatch(scope -> scope.id().equals(id) && scope.enabled()),
                    "Unknown or disabled learning scope: %s", id));
            AgentModelSetting model = models.agentModel(AgentModelType.REVIEW, Optional.of(workspaceId));
            AgentLaneId lane = new AgentLaneId(model.configId());
            WorkflowAgentTasks.requireReady(drivers, lane, AgentPermissionPolicy.AUTO);
            models.validateSelection(lane.value(), model.model(), model.effort(), Optional.empty());
            WorkspaceRepositoryContext workspace = workspaces.requireReadableRepository(workspaceId);
            GitCommandRunner.Result resolved = git.run(workspace.repository(), "rev-parse", "--verify",
                    "refs/remotes/" + workspace.sourceRemote() + "/" + workspace.defaultBranch() + "^{commit}");
            checkArgument(resolved.successful() && !resolved.truncated() && resolved.output().strip().matches("[0-9a-f]{40}|[0-9a-f]{64}"),
                    "The current monitored baseline is unavailable");
            TaskId id = TaskId.random();
            String title = "Learn " + workspace.monitoredRepository().fullName();
            String branch = TaskBranches.generate(id, credentials.resolveGitHubCredential().profile().login(), title);
            ContextBuild build = new ContextBuild(Math.addExact(request.expectedBuildSequence(), 1), request.requestKey(), digest, workspaceId, id.toString(),
                    WorkflowAgentTasks.repositoryIdentity(workspace.selectedRepository()), RepositoryContextReader.repositoryId(workspace), workspace.monitoredRepository(),
                    resolved.output().strip(), branch, lane.value(), new AgentTaskDefaults(Optional.of(model.model()), model.effort(), AgentPermissionPolicy.AUTO),
                    request.scopeIds(), request.history(), notebook.sources(), notebook.revision(), Instant.now(clock), ContextBuild.Capture.empty());
            WorkspaceOperationLocks.withLock(workspaceId, () -> {
                checkArgument(notebooks.getNotebook(workspaceId).equals(notebook), "Notebook changed while preparing learning");
                return builds.save(previous, build);
            });
            try {
                tasks.create(new CreateAgentTaskCommand(id, workspaceId, lane, build.physicalRepository(), build.baselineSha(), branch, title, build.defaults()));
                coordinator.getObject().watch(build);
                turns.start(new StartAgentTurnCommand(id, prompt(build), ImmutableList.of(), build.turnKey()));
            }
            catch (RuntimeException failure) {
                recordGap(workspaceId, build.taskId(), "Learning could not start or its response was interrupted: " + failure.getClass().getSimpleName());
                reconcile(workspaceId, build.taskId());
            }
            return view(workspaceId);
        }
        finally {
            lock.unlock();
        }
    }

    public AgentTask task(String workspaceId, String taskId)
    {
        ContextBuild build = builds.find(workspaceId).filter(value -> value.taskId().equals(taskId))
                .orElseThrow(() -> new IllegalArgumentException("This task is not the workspace's current learning request"));
        AgentTask task = tasks.get(TaskId.valueOf(taskId));
        checkArgument(!task.interactive() && task.workspaceId().filter(workspaceId::equals).isPresent()
                && task.permissionPolicy() == AgentPermissionPolicy.AUTO && task.repository().equals(build.physicalRepository())
                && task.taskWorktree().filter(tree -> tree.startCommit().equals(build.baselineSha()) && tree.generatedBranch().equals(build.branch())).isPresent(),
                "Learning task ownership differs from the accepted request");
        return task;
    }

    public View stop(String workspaceId, String taskId)
    {
        AgentTask task = task(workspaceId, taskId);
        activeTurn(task).ifPresent(turn -> turns.cancel(turn.id()));
        return get(workspaceId);
    }

    /** Validates the precise accepted builder turn again after every external source read. */
    public ContextBuild authorize(String taskId, Optional<TurnId> capturedTurn)
    {
        ContextBuild build = builds.findByTask(taskId).orElseThrow(() -> new IllegalArgumentException("Learning request was replaced or removed"));
        AgentTask task = task(build.workspaceId(), taskId);
        TurnId turnId = capturedTurn.orElseThrow(() -> new IllegalArgumentException("Learning tool has no accepted turn"));
        AgentTurn turn = turnStore.find(turnId).orElseThrow(() -> new IllegalArgumentException("Learning turn is unavailable"));
        checkArgument(task.state() == TaskState.OPEN && task.worktreeMutationClaim()
                        .filter(claim -> claim.kind() == WorktreeMutationClaim.Kind.TURN && claim.ownerId().equals(turnId.toString())).isPresent()
                        && turn.taskId().equals(task.id()) && turn.idempotencyKey().equals(build.turnKey())
                        && !turn.state().terminal() && turn.cancellationRequestedAt().isEmpty(),
                "Learning turn was stopped, replaced or completed");
        return build;
    }

    /** Settles only an already requested task; it never starts or resends learning. */
    public void reconcile(String workspaceId, String taskId)
    {
        Lock lock = locks.get(workspaceId);
        lock.lock();
        try {
            Optional<ContextBuild> found = builds.find(workspaceId).filter(build -> build.taskId().equals(taskId));
            Optional<AgentTask> task = taskStore.find(TaskId.valueOf(taskId));
            if (found.isEmpty() || task.isEmpty() || task.orElseThrow().worktreeMutationClaim().isPresent()) {
                return;
            }
            ContextBuild build = found.orElseThrow();
            Optional<AgentTurn> turn = turns.findTurnByIdempotencyKey(task.orElseThrow().id(), build.turnKey());
            if (turn.filter(value -> !value.state().terminal()).isPresent()) {
                return;
            }
            WorkspaceOperationLocks.withLock(workspaceId, () -> {
                notebooks.getNotebook(workspaceId).activePreparation().filter(active -> active.ownerTaskId().equals(taskId))
                        .ifPresent(active -> preparation.fail(workspaceId, active, "The learning task ended before submitting its preparation."));
                return null;
            });
            // Mark-failed can discard an unverified recovery claim. A later read must not turn that choice into automatic worktree removal.
            if (task.orElseThrow().state() == TaskState.FAILED) {
                coordinator.getObject().unwatch(taskId);
                return;
            }
            if (task.orElseThrow().state() == TaskState.OPEN) {
                if (turn.filter(value -> value.state() == TurnState.COMPLETED).isPresent() && build.capture().result().isPresent()) {
                    completions.complete(new CompleteAgentTaskCommand(task.orElseThrow().id(), Optional.of("Repository learning finished; source limitations remain recorded.")));
                }
                else {
                    String reason = "Learning ended without a completed report; retained knowledge was preserved."
                            + turn.map(value -> " Turn outcome: " + value.state()).orElse(" No turn was accepted.");
                    recordGap(workspaceId, taskId, reason);
                    completions.abandon(task.orElseThrow().id(), reason);
                }
            }
            completions.cleanupWorktree(task.orElseThrow().id());
            coordinator.getObject().unwatch(taskId);
        }
        catch (RuntimeException failure) {
            recordGap(workspaceId, taskId, "Learning task or worktree cleanup needs attention: " + failure.getClass().getSimpleName());
            log.debug("Context build {} remains available for recovery: {}", taskId, failure.toString());
        }
        finally {
            lock.unlock();
        }
    }

    void recordGap(String workspaceId, String taskId, String gap)
    {
        WorkspaceOperationLocks.withLock(workspaceId, () -> {
            builds.find(workspaceId).filter(build -> build.taskId().equals(taskId)).ifPresent(build -> {
                List<String> gaps = gaps(build.capture().gaps(), gap);
                ContextBuild.Capture capture = build.capture();
                builds.save(Optional.of(build), build.withCapture(new ContextBuild.Capture(capture.sourceReads(), capture.evidence(), capture.attempts(),
                        capture.operations(), capture.result(), gaps)));
            });
            return null;
        });
    }

    static List<String> gaps(List<String> current, String gap)
    {
        String bounded = ContextText.excerpt(gap, 1000);
        return current.contains(bounded) || current.size() >= 40 ? current : ImmutableList.<String>builder().addAll(current).add(bounded).build();
    }

    private View view(String workspaceId)
    {
        RepositoryNotebook notebook = notebooks.getNotebook(workspaceId);
        Optional<ContextBuild> found = builds.find(workspaceId);
        Optional<BuildView> build = found.map(value -> {
            Optional<AgentTask> task = taskStore.find(TaskId.valueOf(value.taskId()));
            Optional<AgentTurn> active = task.flatMap(this::activeTurn);
            return new BuildView(value.requestKey(), value.taskId(), value.baselineSha(), value.scopeIds(), value.history(), value.requestedAt(),
                    task.map(TaskView::of), active.map(TurnView::of), active.map(turn -> approvals.listPending(turn.id()).size()).orElse(0),
                    value.capture().result().map(ContextBuild.Result::scopeResults).orElse(ImmutableList.of()), value.capture().gaps());
        });
        boolean canStart = found.flatMap(value -> taskStore.find(TaskId.valueOf(value.taskId())))
                .map(task -> task.state().terminal() && task.worktreeRemovedAt().isPresent()).orElse(true);
        return new View(found.map(ContextBuild::sequence).orElse(0L), notebook.revision(), canStart, build);
    }

    private Optional<AgentTurn> activeTurn(AgentTask task)
    {
        return task.worktreeMutationClaim().filter(claim -> claim.kind() == WorktreeMutationClaim.Kind.TURN)
                .flatMap(claim -> turnStore.find(TurnId.valueOf(claim.ownerId()))).filter(turn -> !turn.state().terminal());
    }

    private String digest(Request request)
    {
        try {
            return "sha256:" + Hashing.sha256().hashString(mapper.writeValueAsString(request), UTF_8);
        }
        catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Learning request cannot be encoded", failure);
        }
    }

    private static String prompt(ContextBuild build)
    {
        return "Learn repository " + build.repository().fullName() + " from its frozen monitored baseline " + build.baselineSha()
                + ". Selected knowledge scopes: " + build.scopeIds() + ". Use repository_context_read SELECTION for the exact source assignment. "
                + "Inspect directory structure, repository documentation and files, and attempt every registered documentation source before preparation BEGIN. "
                + "Read only the explicitly selected historical references. Save generated knowledge with repository_context_prepare, then end the turn. "
                + "Record source failures, incomplete coverage and unsupported claims as limitations. Do not author user lessons, commit, push or publish.";
    }

    public record Request(String requestKey, long expectedRevision, long expectedBuildSequence, List<String> scopeIds, List<ContextBuild.History> history)
    {
        public Request
        {
            ContextLimits.text(requestKey, "requestKey", 100);
            checkArgument(expectedRevision >= 0 && expectedBuildSequence >= 0, "Revisions must not be negative");
            scopeIds = ImmutableList.copyOf(scopeIds);
            history = ImmutableList.copyOf(history);
            ContextLimits.unique(scopeIds, "scopeIds", 100);
            checkArgument(!scopeIds.isEmpty(), "Select at least one knowledge scope");
            scopeIds.forEach(id -> ContextLimits.text(id, "scopeId", 1100));
            ContextLimits.unique(history, "history", 10);
        }
    }

    public record View(long buildRequestSequence, long notebookRevision, boolean canStart, Optional<BuildView> build) {}

    public record BuildView(String requestKey, String taskId, String baselineSha, List<String> scopeIds, List<ContextBuild.History> history, Instant requestedAt,
            Optional<TaskView> task, Optional<TurnView> activeTurn, int pendingApprovals, List<ContextPreparationResult> results, List<String> gaps) {}

    public record TaskView(String id, String workspaceId, String state, long version, String laneId, Optional<String> model,
            Optional<String> mutationClaim, Optional<String> terminalReason, Optional<Instant> worktreeRemovedAt, String worktreePath)
    {
        static TaskView of(AgentTask task)
        {
            return new TaskView(task.id().toString(), task.workspaceId().orElseThrow(), task.state().name(), task.version(), task.laneId().value(), task.defaults().model(),
                    task.worktreeMutationClaim().map(claim -> claim.kind().name()), task.terminalReason(), task.worktreeRemovedAt(), task.worktree().toString());
        }
    }

    public record TurnView(String id, String state, Optional<Instant> startedAt, Optional<Instant> cancellationRequestedAt)
    {
        static TurnView of(AgentTurn turn)
        {
            return new TurnView(turn.id().toString(), turn.state().name(), turn.startedAt(), turn.cancellationRequestedAt());
        }
    }
}

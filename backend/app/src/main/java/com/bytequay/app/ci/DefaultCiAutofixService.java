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

import com.bytequay.agent.core.AgentCompletionService;
import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTaskNotFoundException;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.CompleteAgentTaskCommand;
import com.bytequay.agent.core.CreateAgentTaskCommand;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.StartAgentTurnCommand;
import com.bytequay.agent.core.TaskBranches;
import com.bytequay.agent.core.TaskCompletion;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.WorktreeService;
import com.bytequay.agent.core.WorktreeSnapshot;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentTurnFailureKind;
import com.bytequay.agent.spi.CiDescriptionProposal;
import com.bytequay.app.agent.AgentStartupRecovery;
import com.bytequay.app.pr.PullRequestTimelineService;
import com.bytequay.app.rebase.BranchRebaseService;
import com.bytequay.app.rebase.StartBranchRebaseCommand;
import com.bytequay.app.repository.github.GitHubPullRequestIdentity;
import com.bytequay.app.repository.github.GitHubPullRequestWriteClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.workspace.WorkspaceCodeSyncResult;
import com.bytequay.app.workspace.WorkspaceProvisioningService;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.ci.CiAutofixAttempt;
import com.bytequay.core.ci.CiAutofixAttempt.CiResult;
import com.bytequay.core.ci.CiAutofixAttempt.FailureEvidence;
import com.bytequay.core.ci.CiAutofixAttempt.GitResult;
import com.bytequay.core.ci.CiAutofixAttemptId;
import com.bytequay.core.ci.CiAutofixConflictException;
import com.bytequay.core.ci.CiAutofixNotFoundException;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixRun.Authorization;
import com.bytequay.core.ci.CiAutofixRun.CheckResult;
import com.bytequay.core.ci.CiAutofixRun.FinalFixupCheck;
import com.bytequay.core.ci.CiAutofixRun.FinishReason;
import com.bytequay.core.ci.CiAutofixRun.PauseReason;
import com.bytequay.core.ci.CiAutofixRun.PushJournal;
import com.bytequay.core.ci.CiAutofixRun.RerunJournal;
import com.bytequay.core.ci.CiAutofixRun.Scope;
import com.bytequay.core.ci.CiAutofixRun.Status;
import com.bytequay.core.ci.CiAutofixRunId;
import com.bytequay.core.ci.CiAutofixStore;
import com.bytequay.core.ci.CiDescriptionChange;
import com.bytequay.core.ci.CiRerunAssessment;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.rebase.BranchRebase;
import com.bytequay.core.rebase.BranchRebaseId;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Striped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static com.bytequay.app.agent.WorkflowAgentTasks.createOrGet;
import static com.bytequay.app.agent.WorkflowAgentTasks.repositoryIdentity;
import static com.bytequay.app.agent.WorkflowAgentTasks.requireReady;
import static com.bytequay.app.agent.WorkflowAgentTasks.requireSelectedRepository;
import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Ascii.truncate;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

@Service
public class DefaultCiAutofixService
        implements CiAutofixService
{
    private static final Logger log = LoggerFactory.getLogger(DefaultCiAutofixService.class);

    static final Duration AUTHORIZATION_PERIOD = Duration.ofHours(8);
    static final int MAXIMUM_MESSAGE_CHARACTERS = 16 * 1024;

    private static final int MAXIMUM_SUMMARY_CHARACTERS = 8 * 1024;
    private static final Duration RERUN_OBSERVATION_PERIOD = Duration.ofMinutes(2);

    private final CiAutofixStore store;
    private final GitHubActionsClient gitHub;
    private final GitHubPullRequestWriteClient writes;
    private final AgentTaskService taskService;
    private final AgentTurnService turnService;
    private final AgentCompletionService completionService;
    private final AgentDriverRegistry drivers;
    private final WorktreeService worktreeService;
    private final WorkspaceRepositoryService workspaces;
    private final WorkspaceProvisioningService provisioning;
    private final CiAutofixLogReader logReader;
    private final CiMemoryService memoryService;
    private final CiGitService gitService;
    private final BranchRebaseService rebaseService;
    private final PullRequestTimelineService timelineService;
    private final CredentialService credentials;
    private final Clock clock;
    private final DevelopmentCiOwnership ownership;
    private final ObjectProvider<AgentStartupRecovery> recovery;
    private final CiAutofixPromptBuilder promptBuilder = new CiAutofixPromptBuilder();
    private final Striped<Lock> advanceLocks = Striped.lock(64);

    DefaultCiAutofixService(
            CiAutofixStore store,
            GitHubActionsClient gitHub,
            GitHubPullRequestWriteClient writes,
            AgentTaskService taskService,
            AgentTurnService turnService,
            AgentCompletionService completionService,
            AgentDriverRegistry drivers,
            WorktreeService worktreeService,
            WorkspaceRepositoryService workspaces,
            WorkspaceProvisioningService provisioning,
            CiAutofixLogReader logReader,
            CiMemoryService memoryService,
            CiGitService gitService,
            BranchRebaseService rebaseService,
            PullRequestTimelineService timelineService,
            CredentialService credentials,
            Clock clock,
            DevelopmentCiOwnership ownership,
            ObjectProvider<AgentStartupRecovery> recovery)
    {
        this.store = requireNonNull(store, "store is null");
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.writes = requireNonNull(writes, "writes is null");
        this.taskService = requireNonNull(taskService, "taskService is null");
        this.turnService = requireNonNull(turnService, "turnService is null");
        this.completionService = requireNonNull(completionService, "completionService is null");
        this.drivers = requireNonNull(drivers, "drivers is null");
        this.worktreeService = requireNonNull(worktreeService, "worktreeService is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.provisioning = requireNonNull(provisioning, "provisioning is null");
        this.logReader = requireNonNull(logReader, "logReader is null");
        this.memoryService = requireNonNull(memoryService, "memoryService is null");
        this.gitService = requireNonNull(gitService, "gitService is null");
        this.rebaseService = requireNonNull(rebaseService, "rebaseService is null");
        this.timelineService = requireNonNull(timelineService, "timelineService is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.ownership = requireNonNull(ownership, "ownership is null");
        this.recovery = requireNonNull(recovery, "recovery is null");
    }

    @Override
    public void proposeDescription(TaskId taskId, TurnId turnId, CiDescriptionProposal proposal)
    {
        requireNonNull(proposal, "proposal is null");
        updateRepairProposal(taskId, turnId, (run, snapshot) -> {
            CiAutofixAttempt attempt = run.attempts().getLast();
            CiWorkflowRun workflow = proposalWorkflow(attempt, snapshot, proposal.jobId());
            CiDescriptionChange change = new CiDescriptionChange(
                    proposal.expectedBody(), proposal.body(), workflow.workflowId(), workflow.id(), Optional.empty(), Optional.empty());
            checkState(attempt.descriptionChange().isEmpty() || attempt.descriptionChange().orElseThrow().equals(change),
                    "This attempt already has another description proposal");
            checkState(attempt.rerunAssessment().isEmpty(), "This attempt already requested a rerun");
            checkState(gitHub.readDescription(run.scope().pullRequest()).equals(change.expectedBody()), "The PR description changed; read it again before proposing a repair");
            store.update(run.updateAttempt(attempt.withDescriptionChange(change, clock.instant()), clock.instant()));
        });
    }

    @Override
    public void requestRerun(TaskId taskId, TurnId turnId, long jobId, String reason)
    {
        CiRerunAssessment assessment = new CiRerunAssessment(jobId, reason);
        updateRepairProposal(taskId, turnId, (run, snapshot) -> {
            CiAutofixAttempt attempt = run.attempts().getLast();
            proposalWorkflow(attempt, snapshot, jobId);
            checkState(attempt.descriptionChange().isEmpty(), "This attempt already proposed a description repair");
            checkState(attempt.rerunAssessment().isEmpty() || attempt.rerunAssessment().orElseThrow().equals(assessment), "This attempt already requested another rerun");
            store.update(run.updateAttempt(attempt.withRerunAssessment(assessment, clock.instant()), clock.instant()));
        });
    }

    private static CiWorkflowRun proposalWorkflow(CiAutofixAttempt attempt, CiHeadSnapshot snapshot, long jobId)
    {
        checkArgument(attempt.failures().stream().anyMatch(failure -> failure.jobId() == jobId), "jobId is not part of this repair attempt");
        return snapshot.workflowRuns().stream()
                .filter(value -> value.jobs().stream().anyMatch(job -> job.id() == jobId && job.actionableFailure()))
                .findFirst().orElseThrow(() -> new CiAutofixConflictException("The failed job has been replaced"));
    }

    private void updateRepairProposal(TaskId taskId, TurnId turnId, BiConsumer<CiAutofixRun, CiHeadSnapshot> update)
    {
        CiAutofixRun found = store.findByAgentTaskId(taskId.toString()).orElseThrow(() -> new CiAutofixConflictException("No CI run owns this task"));
        withRunLock(found.id(), () -> {
            return ownership.execute(get(found.id()), () -> {
                CiAutofixRun run = get(found.id());
                repairToken(run);
                checkState(run.status() == Status.ACTIVE && run.currentTurnId().filter(turnId.toString()::equals).isPresent(), "CI repair turn is no longer current");
                AgentTurn turn = turnService.get(turnId);
                checkState(turn.taskId().equals(taskId) && turn.state().active(), "CI repair turn is no longer active");
                inspectWorktree(requireOpenTask(run), run);
                CiHeadSnapshot snapshot = gitHub.inspect(run.scope().pullRequest());
                checkState(snapshot.open() && matchesPullRequestScope(run, snapshot) && snapshot.headSha().equals(run.expectedRemoteHead()), "CI pull request changed");
                update.accept(run, snapshot);
                return null;
            });
        });
    }

    @Override
    public CiAutofixRun start(StartCiAutofixCommand command)
    {
        return start(command, Optional.empty());
    }

    @Override
    public CiAutofixRun startDevelopment(StartCiAutofixCommand command, TaskId parentTaskId, TurnId sourceTurnId,
            GitHubPullRequestIdentity expectedPr, Instant activatedAt)
    {
        requireNonNull(parentTaskId, "parentTaskId is null");
        requireNonNull(sourceTurnId, "sourceTurnId is null");
        requireNonNull(expectedPr, "expectedPr is null");
        requireNonNull(activatedAt, "activatedAt is null");
        checkArgument(command.maxAttempts() == CiAutofixRun.MAX_FIX_ATTEMPTS, "Development CI allows exactly three fix attempts");
        checkArgument(!activatedAt.isAfter(clock.instant()) && clock.instant().isBefore(activatedAt.plus(AUTHORIZATION_PERIOD)),
                "Development CI authorization is expired or invalid");
        checkArgument(expectedPr.state() == GitHubPullRequestIdentity.State.OPEN, "Development CI requires an open pull request");
        ownership.requireParent(parentTaskId, sourceTurnId);
        return start(command, Optional.of(new DevelopmentStart(parentTaskId, sourceTurnId, expectedPr, activatedAt)));
    }

    private CiAutofixRun start(StartCiAutofixCommand command, Optional<DevelopmentStart> development)
    {
        validateStartCommand(command);
        Optional<CiAutofixRun> existing = store.findByIdempotencyKey(command.idempotencyKey());
        if (existing.isPresent()) {
            CiAutofixRun run = existing.orElseThrow();
            requireStartWorkspace(run, command.workspaceId());
            requireStartOwnership(run, development);
            return ownership.execute(run, () -> {
                if (run.status() != Status.FINISHED && run.agentTaskId().isPresent()) {
                    ensureAgentTask(run);
                }
                return run;
            });
        }

        requireReady(drivers, command.laneId(), AgentPermissionPolicy.CI_AUTOFIX);
        WorkspaceRepositoryContext workspace = workspaces.requireReadyRepository(command.workspaceId());
        PullRequestRef pullRequest = PullRequestRef.of(
                workspace.monitoredRepository().owner(),
                workspace.monitoredRepository().repo(),
                command.pullRequestNumber());
        CiHeadSnapshot snapshot = gitHub.inspect(pullRequest);
        String currentUser = currentUser();
        validatePullRequest(snapshot, pullRequest, currentUser);
        validateStartWorkspace(workspace, snapshot);
        development.ifPresent(value -> requireExpectedPullRequest(snapshot, value.expectedPr()));

        WorkspaceCodeSyncResult sync = provisioning.synchronizeCode(command.workspaceId());
        checkArgument(sync.successful(), "Workspace repository could not be synchronized");
        workspace = workspaces.requireReadyRepository(command.workspaceId());
        validateStartWorkspace(workspace, snapshot);
        Instant now = Instant.now(clock);
        TaskId taskId = TaskId.random();
        CiAutofixRun run = CiAutofixRun.create(
                CiAutofixRunId.random(),
                command.idempotencyKey(),
                new Scope(
                        pullRequest,
                        snapshot.headRepository(),
                        command.workspaceId(),
                        RepoRef.of(workspace.selectedRepository().owner(), workspace.selectedRepository().repo()),
                        currentUser,
                        snapshot.headRef(),
                        snapshot.baseRef(),
                        command.laneId().toString(),
                        development.map(value -> value.parentTaskId().toString())),
                snapshot.headSha(),
                snapshot.headSha(),
                snapshot.baseSha(),
                new Authorization(
                        true,
                        true,
                        true,
                        command.maxAttempts(),
                        development.map(DevelopmentStart::activatedAt).orElse(now),
                        development.map(DevelopmentStart::activatedAt).orElse(now).plus(AUTHORIZATION_PERIOD)),
                now)
                .assignAgentTask(taskId.toString(), now);
        try {
            ownership.insert(run, development.map(DevelopmentStart::sourceTurnId));
        }
        catch (CiAutofixConflictException conflict) {
            CiAutofixRun raced = store.findByIdempotencyKey(command.idempotencyKey()).orElseThrow(() -> conflict);
            requireStartWorkspace(raced, command.workspaceId());
            requireStartOwnership(raced, development);
            return ownership.execute(raced, () -> {
                if (raced.status() != Status.FINISHED && raced.agentTaskId().isPresent()) {
                    ensureAgentTask(raced);
                }
                return raced;
            });
        }

        return ownership.execute(run, () -> startTask(run, snapshot));
    }

    private CiAutofixRun startTask(CiAutofixRun run, CiHeadSnapshot snapshot)
    {
        ownership.require(run);
        AgentTask task;
        try {
            task = ensureAgentTask(run);
        }
        catch (RuntimeException failure) {
            log.error("Cannot create agent task for CI Autofix run {}", run.id(), failure);
            throw failure;
        }

        if (snapshot.actionableFailures().isEmpty()) {
            return run;
        }
        try {
            RebaseGate gate = ensureBranchRebase(run, task, snapshot, Instant.now(clock));
            return gate.ready() ? startFixTurn(gate.run(), task, snapshot) : gate.run();
        }
        catch (RuntimeException failure) {
            log.error("Cannot prepare initial CI Autofix work for run {} task {}", run.id(), task.id(), failure);
            CiAutofixRun current = store.find(run.id()).orElse(run);
            if (current.status() == Status.ACTIVE) {
                return store.update(current.pause(
                        PauseReason.ERROR,
                        failureSummary("Initial CI Autofix work could not be prepared", failure),
                        Instant.now(clock)));
            }
            throw failure;
        }
    }

    @Override
    public CiAutofixRun get(CiAutofixRunId runId)
    {
        requireNonNull(runId, "runId is null");
        return store.find(runId).orElseThrow(() -> new CiAutofixNotFoundException(runId));
    }

    /** Runs an action under the run's advance lock, which serializes every mutation of that run. */
    private <T> T withRunLock(CiAutofixRunId runId, Supplier<T> action)
    {
        Lock lock = advanceLocks.get(runId);
        lock.lock();
        try {
            return action.get();
        }
        finally {
            lock.unlock();
        }
    }

    @Override
    public List<CiAutofixRun> list(int limit)
    {
        checkArgument(limit > 0 && limit <= 200, "limit must be between 1 and 200");
        return store.listRecent(limit);
    }

    @Override
    public boolean canMessage(CiAutofixRun run)
    {
        if (run.status() != Status.PAUSED) {
            return false;
        }
        return withRunLock(run.id(), () -> canMessageLocked(get(run.id())));
    }

    private boolean canMessageLocked(CiAutofixRun run)
    {
        if (run.status() != Status.PAUSED || !ownership.authorized(run) || !clock.instant().isBefore(run.authorization().expiresAt())
                || run.pushJournal().isPresent() || run.rerunJournal().isPresent() || run.agentTaskId().isEmpty()
                || (run.attempts().isEmpty() && run.currentRebaseId().isEmpty())) {
            return false;
        }
        if (run.scope().parentTaskId().isEmpty()) {
            return true;
        }
        if (findChild(TaskId.valueOf(run.agentTaskId().orElseThrow()))
                .filter(task -> task.state() == TaskState.OPEN && task.worktreeMutationClaim().isEmpty()).isEmpty()) {
            return false;
        }
        return switch (run.pauseReason().orElseThrow()) {
            case AUTHENTICATION_REQUIRED, INVALID_GIT_RESULT, INVALID_FIXUP_POSITION -> true;
            case REBASE_PAUSED -> run.currentRebaseId().map(value -> rebaseService.get(BranchRebaseId.valueOf(value)))
                    .filter(value -> value.status() == BranchRebase.Status.PAUSED
                            && value.pauseReason().filter(reason -> reason == BranchRebase.PauseReason.UNSAFE_GIT_STATE
                                    || reason == BranchRebase.PauseReason.RECONCILIATION_MISMATCH).isPresent()).isPresent();
            case TURN_FAILED, TURN_INTERRUPTED, TURN_CANCELLED, RERUN_UNAVAILABLE, RERUN_EXHAUSTED, ERROR -> false;
        };
    }

    @Override
    public void stopDevelopment(TaskId parentTaskId)
    {
        ownership.revoke(parentTaskId);
        ownership.drain(parentTaskId);
        RuntimeException failure = null;
        for (CiAutofixRun run : store.listByParentTaskId(parentTaskId.toString())) {
            Lock lock = advanceLocks.get(run.id());
            lock.lock();
            try {
                stopDevelopmentRun(get(run.id()));
            }
            catch (RuntimeException stopped) {
                if (failure == null) {
                    failure = stopped;
                }
                else {
                    failure.addSuppressed(stopped);
                }
            }
            finally {
                lock.unlock();
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void stopDevelopmentRun(CiAutofixRun run)
    {
        if (run.agentTaskId().isPresent()) {
            TaskId childId = TaskId.valueOf(run.agentTaskId().orElseThrow());
            Optional<AgentTask> child = findChild(childId);
            if (child.filter(task -> task.state() == TaskState.OPEN).isPresent()) {
                AgentTask task = child.orElseThrow();
                AgentTask stopped;
                if (task.worktreeMutationClaim().filter(claim -> claim.kind() == WorktreeMutationClaim.Kind.RECOVERY).isPresent()) {
                    stopped = recovery.getObject().stopRecoveredRuntime(childId, "task:" + task.version());
                }
                else {
                    stopped = turnService.stopRuntime(childId, turnService.stopImpact(childId).generation());
                }
                if (stopped.worktreeMutationClaim().isPresent() || turnService.stopImpact(childId).activeTurnId().isPresent()) {
                    throw new AgentConflictException("The CI Autofix child writer could not be verified stopped: " + childId);
                }
            }
        }
        run.currentRebaseId().ifPresent(value -> rebaseService.stopForLostOwner(BranchRebaseId.valueOf(value)));
        if (run.status() != Status.FINISHED) {
            // A stopped parent may accept another message; the old CI run must release its branch
            // reservation without deleting its checkout or becoming resumable again.
            store.update(run.finish(FinishReason.CANCELLED, "Development automation stopped; local and remote results were preserved", clock.instant()));
        }
    }

    @Override
    public void deleteDevelopment(TaskId parentTaskId)
    {
        stopDevelopment(parentTaskId);
        for (CiAutofixRun run : store.listByParentTaskId(parentTaskId.toString())) {
            Lock lock = advanceLocks.get(run.id());
            lock.lock();
            try {
                run.agentTaskId().map(TaskId::valueOf).ifPresent(completionService::delete);
                CiAutofixRun current = get(run.id());
                if (current.status() != Status.FINISHED) {
                    store.update(current.finish(FinishReason.CANCELLED, "Development task and local CI work were deleted; remote results remain", clock.instant()));
                }
            }
            finally {
                lock.unlock();
            }
        }
    }

    private Optional<AgentTask> findChild(TaskId taskId)
    {
        try {
            return Optional.of(taskService.get(taskId));
        }
        catch (AgentTaskNotFoundException ignored) {
            return Optional.empty();
        }
    }

    @Override
    public CiAutofixRun sendMessage(SendCiAutofixMessageCommand command)
    {
        validateMessageCommand(command);
        return withRunLock(command.runId(), () -> ownership.execute(get(command.runId()), () -> sendMessageLocked(command)));
    }

    private CiAutofixRun sendMessageLocked(SendCiAutofixMessageCommand command)
    {
        CiAutofixRun run = get(command.runId());
        ownership.require(run);
        if (matchesCurrentMessage(run, command.idempotencyKey())) {
            return run;
        }
        if (run.status() == Status.ACTIVE && run.currentRebaseId().isPresent()) {
            return run;
        }
        if (run.status() != Status.PAUSED) {
            throw new CiAutofixConflictException("Only a paused CI Autofix run accepts a message");
        }
        if (run.scope().parentTaskId().isPresent() && !canMessage(run)) {
            throw new CiAutofixConflictException("This development-owned CI run cannot resume");
        }
        if (run.pushJournal().isPresent() || run.rerunJournal().isPresent()) {
            throw new CiAutofixConflictException("The pending remote outcome must be reconciled or cancelled first");
        }

        Instant now = Instant.now(clock);
        if (!now.isBefore(run.authorization().expiresAt())) {
            return finishRun(run,
                    FinishReason.AUTHORIZATION_EXPIRED,
                    "CI Autofix authorization expired",
                    now);
        }
        if (run.attempts().isEmpty() || run.agentTaskId().isEmpty()) {
            if (run.currentRebaseId().isEmpty()) {
                throw new CiAutofixConflictException("CI Autofix run has no resumable work");
            }
        }

        CiHeadSnapshot snapshot = gitHub.inspect(run.scope().pullRequest());
        validatePullRequest(snapshot, run.scope().pullRequest(), currentUser());
        if (!snapshot.headRepository().fullName().equalsIgnoreCase(run.scope().headRepository().fullName())
                || !snapshot.headRef().equals(run.scope().headRef())
                || !snapshot.baseRef().equals(run.scope().baseRef())
                || !snapshot.headSha().equals(run.expectedRemoteHead())) {
            throw new CiAutofixConflictException("Pull request head or base branch changed outside this CI Autofix run");
        }
        requireWorkspace(run);
        AgentTask task = requireOpenTask(run);

        if (run.currentRebaseId().isPresent()) {
            return resumeBranchRebase(run, command.message());
        }
        inspectWorktree(task, run);

        requireWriteAuthority(run);
        AgentTurn turn = turnService.start(new StartAgentTurnCommand(
                task.id(),
                command.message().strip(),
                ImmutableList.of(),
                command.idempotencyKey()));
        CiAutofixRun resumed = run.resume(Instant.now(clock))
                .linkUserTurn(turn.id().toString(), Instant.now(clock));
        return store.update(resumed);
    }

    @Override
    public CiAutofixRun cancel(CiAutofixRunId runId)
    {
        requireNonNull(runId, "runId is null");
        CiAutofixRun existing = get(runId);
        if (existing.scope().parentTaskId().isPresent()) {
            if (existing.status() == Status.FINISHED) {
                return existing;
            }
            TaskId parent = TaskId.valueOf(existing.scope().parentTaskId().orElseThrow());
            if (findChild(parent).filter(task -> task.state() == TaskState.OPEN && task.developmentContinuationUnfinished()).isPresent()) {
                turnService.stopRuntime(parent, turnService.stopImpact(parent).generation());
            }
            else {
                stopDevelopment(parent);
            }
            return get(runId);
        }
        return withRunLock(runId, () -> cancelLocked(runId));
    }

    private CiAutofixRun cancelLocked(CiAutofixRunId runId)
    {
        CiAutofixRun run = get(runId);
        if (run.status() == Status.FINISHED) {
            completeFinishedTask(run);
            return run;
        }
        return finishRun(run, FinishReason.CANCELLED, "CI Autofix cancelled by user", Instant.now(clock));
    }

    @Override
    public CiAutofixRun advance(CiAutofixRunId runId)
    {
        requireNonNull(runId, "runId is null");
        return withRunLock(runId, () -> {
            CiAutofixRun result = ownership.execute(get(runId), () -> advanceLocked(runId));
            ownership.forgetFinished(result);
            return result;
        });
    }

    private CiAutofixRun advanceLocked(CiAutofixRunId runId)
    {
        CiAutofixRun run = get(runId);
        Instant now = Instant.now(clock);
        if (run.status() == Status.FINISHED) {
            completeFinishedTask(run);
            return run;
        }
        if (run.agentTaskId().isPresent()) {
            ensureAgentTask(run);
        }
        if (run.status() == Status.PAUSED) {
            return now.isBefore(run.authorization().expiresAt())
                    ? run
                    : finishRun(run,
                            FinishReason.AUTHORIZATION_EXPIRED,
                            "CI Autofix authorization expired",
                            now);
        }
        Optional<CiAutofixRun> reconciledGreen = reconcileCompletedGreenTask(run, now);
        if (reconciledGreen.isPresent()) {
            return reconciledGreen.orElseThrow();
        }
        try {
            requireWorkspace(run);
        }
        catch (RuntimeException failure) {
            return pauseAndRefresh(
                    run,
                    PauseReason.ERROR,
                    failureSummary("Workspace routing changed; CI Autofix was paused", failure),
                    now);
        }
        if (run.pushJournal().isPresent()) {
            return reconcilePush(run, now);
        }
        if (run.rerunJournal().isPresent()) {
            return reconcileRerun(run, now);
        }
        Optional<CiAutofixAttempt> unpublishedTimeline = run.attempts().stream()
                .filter(attempt -> attempt.pushedSha().isPresent() && attempt.timelineEventKey().isEmpty())
                .findFirst();
        if (unpublishedTimeline.isPresent()) {
            return recordTimelineEvent(run, unpublishedTimeline.orElseThrow(), now);
        }
        if (!now.isBefore(run.authorization().expiresAt())) {
            return finishRun(run,
                    FinishReason.AUTHORIZATION_EXPIRED,
                    "CI Autofix authorization expired",
                    now);
        }

        CiHeadSnapshot snapshot = gitHub.inspect(run.scope().pullRequest());
        Optional<CiAutofixRun> rejected = rejectChangedPullRequest(run, snapshot, now);
        if (rejected.isPresent()) {
            return rejected.orElseThrow();
        }
        if (!snapshot.headSha().equals(run.expectedRemoteHead())) {
            return finishRun(run,
                    FinishReason.STALE_HEAD,
                    "Pull request head changed outside this CI Autofix run",
                    now);
        }
        if (run.currentTurnId().isPresent()) {
            return observeCurrentTurn(run, now);
        }
        if (!run.attempts().isEmpty() && run.attempts().getLast().descriptionChange().isPresent()) {
            return advanceDescriptionChange(run, snapshot, now);
        }
        boolean refreshPreparedAttempt = preparedAttemptNeedsNewBase(run, snapshot);
        boolean basePrepared = false;
        if (run.currentRebaseId().isPresent()) {
            RebaseGate gate = advanceBranchRebase(run, snapshot, now);
            if (!gate.ready()) {
                return gate.run();
            }
            run = gate.run();
            basePrepared = run.latestObservedBaseSha().equals(snapshot.baseSha());
        }
        if (!basePrepared && needsBranchRebase(run, snapshot)) {
            RebaseGate gate = ensureBranchRebase(run, requireOpenTask(run), snapshot, now);
            if (!gate.ready()) {
                return gate.run();
            }
            run = gate.run();
        }
        if (!snapshot.baseSha().equals(run.latestObservedBaseSha())) {
            run = store.update(run.observeBase(snapshot.headSha(), snapshot.baseSha(), now));
        }
        if (refreshPreparedAttempt) {
            return refreshPreparedAttempt(run, requireOpenTask(run), now);
        }
        if (run.attempts().isEmpty()) {
            if (snapshot.actionableFailures().isEmpty()) {
                return snapshot.green() ? finishGreenOrCheckFixups(run, now) : run;
            }
            return prepareFixAttempt(run, requireOpenTask(run), snapshot);
        }

        CiAutofixAttempt attempt = run.attempts().getLast();
        if (attempt.agentTurnId().isEmpty()) {
            if (snapshot.actionableFailures().isEmpty()) {
                return snapshot.green() ? observeCi(run, attempt, CiResult.PASSED, snapshot, now) : run;
            }
            return startAttemptTurn(run, requireOpenTask(run), snapshot);
        }
        if (attempt.pushedSha().isEmpty()) {
            if (attempt.gitResult().isPresent()) {
                return recordPushJournal(run, attempt, now);
            }
            return inspectCompletedAttempt(run, attempt, snapshot, now);
        }

        CiResult result = ciResult(snapshot);
        if (attempt.observedCiResult() != result) {
            return observeCi(run, attempt, result, snapshot, now);
        }
        return switch (result) {
            case FAILED -> startNextAttemptOrFinish(run, snapshot, now);
            case PASSED -> finishGreenOrCheckFixups(run, now);
            case PENDING, UNKNOWN -> run;
        };
    }

    private CiAutofixRun recordTimelineEvent(CiAutofixRun run, CiAutofixAttempt attempt, Instant now)
    {
        GitResult git = attempt.gitResult().orElseThrow();
        String eventKey = "ci-autofix:" + run.id() + ":" + attempt.id() + ":" + attempt.pushedSha().orElseThrow();
        timelineService.record(
                run.scope().pullRequest(),
                eventKey,
                run.scope().author(),
                attempt.pushedAt().orElseThrow(),
                attempt.expectedRemoteSha().orElseThrow(),
                attempt.pushedSha().orElseThrow(),
                git.commitSubjects(),
                git.changedPaths());
        return store.update(run.updateAttempt(attempt.withTimelineEvent(eventKey, now), now));
    }

    private CiAutofixRun observeCurrentTurn(CiAutofixRun run, Instant now)
    {
        AgentTurn turn = turnService.get(TurnId.valueOf(run.currentTurnId().orElseThrow()));
        if (turn.state().active()) {
            return run;
        }
        CiAutofixRun observed = copyFinalMessage(run, turn, now);
        observed = switch (turn.state()) {
            case COMPLETED -> observed.clearCurrentTurn(turn.id().toString(), now);
            case FAILED -> observed.pause(
                    turn.failureKind().filter(AgentTurnFailureKind.AUTHENTICATION_REQUIRED::equals).isPresent()
                            ? PauseReason.AUTHENTICATION_REQUIRED
                            : PauseReason.TURN_FAILED,
                    turn.failureKind().filter(AgentTurnFailureKind.AUTHENTICATION_REQUIRED::equals).isPresent()
                            ? authenticationRecovery(run)
                            : terminalSummary("Agent turn failed", turn),
                    now);
            case INTERRUPTED -> observed.pause(
                    PauseReason.TURN_INTERRUPTED,
                    terminalSummary("Agent turn was interrupted", turn),
                    now);
            case CANCELLED -> observed.pause(
                    PauseReason.TURN_CANCELLED,
                    terminalSummary("Agent turn was cancelled", turn),
                    now);
            case ACCEPTED, RUNNING -> throw new AssertionError("active turn reached terminal reconciliation");
        };
        return store.update(observed);
    }

    private static String authenticationRecovery(CiAutofixRun run)
    {
        return run.scope().laneId().startsWith("deepseek-key-")
                ? "DeepSeek key needs attention. Check it in Models settings."
                : "Agent provider sign-in required";
    }

    private CiAutofixRun inspectCompletedAttempt(
            CiAutofixRun run,
            CiAutofixAttempt attempt,
            CiHeadSnapshot snapshot,
            Instant now)
    {
        WorktreeSnapshot worktree = inspectWorktree(requireOpenTask(run), run);
        CiGitInspection inspection = gitService.inspectAgentResult(new CiWorktreeRange(
                worktree.worktree().canonicalWorktree(),
                worktree.worktree().physicalRepository(),
                worktree.worktree().generatedBranch(),
                snapshot.baseSha(),
                run.expectedRemoteHead()));
        return switch (inspection.outcome()) {
            case READY -> journalPush(run, attempt, inspection, now);
            case NO_CHANGE -> handleNoChange(run, attempt, snapshot, now);
            case INVALID -> pauseAndRefresh(
                    run,
                    PauseReason.INVALID_GIT_RESULT,
                    summary("Agent Git result is invalid", inspection.diagnostics()),
                    now);
        };
    }

    private CiAutofixRun journalPush(
            CiAutofixRun run,
            CiAutofixAttempt attempt,
            CiGitInspection inspection,
            Instant now)
    {
        GitResult gitResult = new GitResult(
                inspection.localHead().orElseThrow(),
                inspection.fingerprint().orElseThrow(),
                inspection.changedPaths(),
                inspection.commitSubjects());
        CiAutofixAttempt inspected = attempt.withGitResult(gitResult, now);
        return recordPushJournal(run.updateAttempt(inspected, now), inspected, now);
    }

    private CiAutofixRun recordPushJournal(CiAutofixRun run, CiAutofixAttempt attempt, Instant now)
    {
        GitResult result = attempt.gitResult().orElseThrow();
        CiAutofixRun stored = store.update(run.recordPushJournal(
                new PushJournal(
                        attempt.id(),
                        run.expectedRemoteHead(),
                        result.localHead(),
                        result.fingerprint(),
                        now,
                        0),
                now));
        return refreshMemory(stored);
    }

    private CiAutofixRun advanceDescriptionChange(CiAutofixRun run, CiHeadSnapshot snapshot, Instant now)
    {
        CiAutofixAttempt attempt = run.attempts().getLast();
        CiDescriptionChange change = attempt.descriptionChange().orElseThrow();
        if (change.appliedAt().isPresent()) {
            if (attempt.gitResult().isPresent() && attempt.pushedSha().isEmpty()) {
                return recordPushJournal(run, attempt, now);
            }
            boolean oldEventPresent = snapshot.workflowRuns().stream().anyMatch(workflow -> workflow.id() == change.previousRunId());
            Optional<CiWorkflowRun> replacement = snapshot.workflowRuns().stream()
                    .filter(workflow -> !oldEventPresent && workflow.workflowId() == change.workflowId() && workflow.id() > change.previousRunId()).findFirst();
            if (replacement.isEmpty()) {
                return now.isBefore(change.appliedAt().orElseThrow().plus(RERUN_OBSERVATION_PERIOD)) ? run
                        : pauseAndRefresh(run, PauseReason.RERUN_UNAVAILABLE, "Description updated, but GitHub has not started a new check; the old event will not be rerun", now);
            }
            if (!"completed".equals(replacement.orElseThrow().status())) {
                return run;
            }
            CiResult result = ciResult(snapshot);
            if (attempt.observedCiResult() != result) {
                return observeCi(run, attempt, result, snapshot, now);
            }
            return switch (result) {
                case FAILED -> startNextAttemptOrFinish(run, snapshot, now);
                case PASSED -> finishGreenOrCheckFixups(run, now);
                case PENDING, UNKNOWN -> run;
            };
        }
        WorktreeSnapshot worktree = inspectWorktree(requireOpenTask(run), run);
        CiGitInspection inspection = gitService.inspectAgentResult(new CiWorktreeRange(worktree.worktree().canonicalWorktree(),
                worktree.worktree().physicalRepository(), worktree.worktree().generatedBranch(), snapshot.baseSha(), run.expectedRemoteHead()));
        if (inspection.outcome() == CiGitInspection.Outcome.INVALID) {
            return pauseAndRefresh(run, PauseReason.INVALID_GIT_RESULT, summary("Agent Git result is invalid", inspection.diagnostics()), now);
        }
        if (inspection.outcome() == CiGitInspection.Outcome.READY && attempt.gitResult().isEmpty()) {
            GitResult result = new GitResult(inspection.localHead().orElseThrow(), inspection.fingerprint().orElseThrow(), inspection.changedPaths(), inspection.commitSubjects());
            return store.update(run.updateAttempt(attempt.withGitResult(result, now), now));
        }
        String currentBody = gitHub.readDescription(run.scope().pullRequest());
        if (change.writeStartedAt().isPresent()) {
            if (!currentBody.equals(change.body())) {
                return pauseAndRefresh(run, PauseReason.ERROR, "PR description write could not be verified; it will not be repeated or overwrite another edit", now);
            }
            return store.update(run.updateAttempt(attempt.withDescriptionChange(change.applied(now), now).withCiResult(CiResult.PENDING, now), now));
        }
        if (!currentBody.equals(change.expectedBody())) {
            return pauseAndRefresh(run, PauseReason.ERROR, "PR description changed after the repair was proposed; no edit was applied", now);
        }
        CiHeadSnapshot latest = gitHub.inspect(run.scope().pullRequest());
        Optional<CiAutofixRun> rejected = rejectChangedPullRequest(run, latest, now);
        if (rejected.isPresent()) {
            return rejected.orElseThrow();
        }
        if (!latest.headSha().equals(run.expectedRemoteHead())) {
            return finishRun(run, FinishReason.STALE_HEAD, "Pull request head changed before its description repair", now);
        }
        if (!gitHub.readDescription(run.scope().pullRequest()).equals(change.expectedBody())) {
            return pauseAndRefresh(run, PauseReason.ERROR, "PR description changed while validating the repair; no edit was applied", now);
        }
        repairToken(run);
        CiAutofixRun journaled = store.update(run.updateAttempt(attempt.withDescriptionChange(change.startingWrite(now), now), now));
        writes.updatePullRequestDescription(repairToken(journaled), run.scope().pullRequest(), change.body());
        return journaled;
    }

    private String repairToken(CiAutofixRun run)
    {
        CredentialService.GitHubCredential credential = credentials.resolveGitHubCredential();
        requireWriteAuthority(run);
        checkState(run.status() == Status.ACTIVE && clock.instant().isBefore(run.authorization().expiresAt()), "CI repair authorization expired or stopped");
        checkState(run.scope().author().equalsIgnoreCase(credential.profile().login()), "The authenticated GitHub account changed");
        return credential.token();
    }

    private CiAutofixRun handleNoChange(
            CiAutofixRun run,
            CiAutofixAttempt attempt,
            CiHeadSnapshot snapshot,
            Instant now)
    {
        if (snapshot.green()) {
            return attempt.observedCiResult() == CiResult.PASSED
                    ? finishGreenOrCheckFixups(run, now)
                    : observeCi(run, attempt, CiResult.PASSED, snapshot, now);
        }
        if (attempt.observedCiResult() == CiResult.FAILED) {
            return startNextAttemptOrFinish(run, snapshot, now);
        }
        Optional<CiWorkflowRun> failedWorkflow = failedWorkflow(snapshot);
        if (failedWorkflow.isEmpty()) {
            return attempt.observedCiResult() == CiResult.PENDING
                    ? run
                    : observeCi(run, attempt, CiResult.PENDING, snapshot, now);
        }
        if (run.rerunCounter().headSha().filter(snapshot.headSha()::equals).isPresent()
                && run.rerunCounter().used() >= CiAutofixRun.MAX_RERUNS_PER_HEAD) {
            return pauseAndRefresh(
                    run,
                    PauseReason.RERUN_EXHAUSTED,
                    "No committed fix was produced and the failed-job rerun limit is exhausted",
                    now);
        }

        if (attempt.rerunAssessment().isEmpty()) {
            return pauseAndRefresh(run, PauseReason.ERROR, "Agent produced no repair and did not request a rerun with evidence of a flaky or transient failure", now);
        }
        Optional<CiWorkflowRun> requested = snapshot.workflowRuns().stream()
                .filter(workflow -> workflow.jobs().stream().anyMatch(job -> job.id() == attempt.rerunAssessment().orElseThrow().jobId() && job.actionableFailure()))
                .findFirst();
        if (requested.isEmpty()) {
            return startNextAttemptOrFinish(run, snapshot, now);
        }
        CiWorkflowRun workflow = requested.orElseThrow();
        RerunJournal journal = new RerunJournal(
                attempt.id(),
                workflow.id(),
                snapshot.headSha(),
                workflow.attempt(),
                now,
                Optional.empty());
        CiAutofixRun journaled = store.update(run.recordRerunJournal(journal, now));
        CiAutofixRun latest = get(run.id());
        if (latest.version() != journaled.version() || latest.status() != Status.ACTIVE) {
            return latest;
        }
        CiRerunResult result;
        try {
            requireWriteAuthority(run);
            result = gitHub.rerunFailedJobs(new RerunFailedJobsCommand(
                    run.scope().pullRequest(),
                    workflow.id(),
                    snapshot.headSha(),
                    workflow.attempt(),
                    run.authorization().allowFailedJobReruns() && now.isBefore(run.authorization().expiresAt())));
        }
        catch (RuntimeException failure) {
            refreshMemory(journaled);
            throw failure;
        }
        CiAutofixRun handled = switch (result.outcome()) {
            case ACCEPTED -> journaled;
            case OBSOLETE, REJECTED -> store.update(journaled.clearRerunJournal(now).pause(
                    PauseReason.RERUN_UNAVAILABLE,
                    summary("Failed-job rerun is unavailable", result.reason()),
                    now));
        };
        return refreshMemory(handled);
    }

    private CiAutofixRun reconcilePush(CiAutofixRun run, Instant now)
    {
        CiAutofixRun latest = get(run.id());
        if (latest.version() != run.version() || latest.status() != Status.ACTIVE) {
            return latest;
        }
        CiHeadSnapshot snapshot = gitHub.inspect(run.scope().pullRequest());
        Optional<CiAutofixRun> rejected = rejectChangedPullRequest(run, snapshot, now);
        if (rejected.isPresent()) {
            return rejected.orElseThrow();
        }
        WorktreeSnapshot worktree = inspectWorktree(requireOpenTask(run), run);
        WorkspaceRepositoryContext workspace = requireWorkspace(run);
        requireWriteAuthority(run);
        CiPushResult result = gitService.push(new PushCiHeadCommand(
                worktree.worktree().canonicalWorktree(),
                worktree.worktree().physicalRepository(),
                worktree.worktree().generatedBranch(),
                run.scope().headRepository().fullName().equalsIgnoreCase(run.scope().pullRequest().repoFullName()) ? workspace.sourceRemote() : workspace.targetRemote(),
                new RepositoryIdentity(run.scope().headRepository().owner(), run.scope().headRepository().repo()),
                run.scope().headRef(),
                run.authorization(),
                run.pushJournal().orElseThrow()));
        return switch (result.outcome()) {
            case PUSHED -> refreshMemory(store.update(run.recordPublishedAttempt(
                    result.pushedSha().orElseThrow(),
                    now,
                    now)));
            case RETRYABLE -> retryPush(run, now);
            case STALE -> finishRun(
                    run,
                    FinishReason.STALE_HEAD,
                    summary("Pull request head changed before the CI push", result.reason()),
                    now);
            case UNCERTAIN -> run;
            case REJECTED -> now.isBefore(run.authorization().expiresAt())
                    ? store.update(run.clearPushJournal(now).pause(
                            PauseReason.ERROR,
                            summary("CI push was rejected", result.reason()),
                            now))
                    : finishRun(run,
                            FinishReason.AUTHORIZATION_EXPIRED,
                            "CI Autofix authorization expired before the push",
                            now);
        };
    }

    private CiAutofixRun retryPush(CiAutofixRun run, Instant now)
    {
        PushJournal journal = run.pushJournal().orElseThrow();
        if (journal.retries() >= 1) {
            return store.update(run.clearPushJournal(now).pause(
                    PauseReason.ERROR,
                    "CI push retry limit is exhausted",
                    now));
        }
        return store.update(run.recordPushJournal(new PushJournal(
                journal.attemptId(),
                journal.expectedRemoteSha(),
                journal.localHeadSha(),
                journal.localFingerprint(),
                journal.startedAt(),
                journal.retries() + 1), now));
    }

    private CiAutofixRun reconcileRerun(CiAutofixRun run, Instant now)
    {
        RerunJournal journal = run.rerunJournal().orElseThrow();
        CiHeadSnapshot snapshot = gitHub.inspect(run.scope().pullRequest());
        Optional<CiAutofixRun> rejected = rejectChangedPullRequest(run, snapshot, now);
        if (rejected.isPresent()) {
            return rejected.orElseThrow();
        }
        if (!snapshot.headSha().equals(journal.headSha())) {
            return finishRun(run,
                    FinishReason.STALE_HEAD,
                    "Pull request head changed while a failed-job rerun was being observed",
                    now);
        }
        Optional<CiWorkflowRun> later = snapshot.workflowRuns().stream()
                .filter(workflow -> workflow.id() == journal.workflowRunId())
                .filter(workflow -> workflow.attempt() > journal.previousAttempt())
                .max(Comparator.comparingInt(CiWorkflowRun::attempt));
        if (journal.observedAttempt().isEmpty()) {
            if (later.isPresent()) {
                return refreshMemory(store.update(run.observeRerunAttempt(later.orElseThrow().attempt(), now)));
            }
            if (!now.isBefore(run.authorization().expiresAt())) {
                return finishRun(run,
                        FinishReason.AUTHORIZATION_EXPIRED,
                        "CI Autofix authorization expired while the rerun outcome was uncertain",
                        now);
            }
            if (!now.isBefore(journal.startedAt().plus(RERUN_OBSERVATION_PERIOD))) {
                return store.update(run.clearRerunJournal(now).pause(
                        PauseReason.RERUN_UNAVAILABLE,
                        "GitHub did not expose a later workflow attempt; the rerun will not be repeated blindly",
                        now));
            }
            return run;
        }
        Optional<CiWorkflowRun> observed = later
                .filter(workflow -> workflow.attempt() >= journal.observedAttempt().orElseThrow());
        if (observed.isEmpty() || !"completed".equals(observed.orElseThrow().status())) {
            return run;
        }
        CiAutofixRun resolved = store.update(run.resolveRerun(ciResult(snapshot), snapshot.baseSha(), now));
        return refreshMemory(resolved);
    }

    private CiAutofixRun startNextAttemptOrFinish(
            CiAutofixRun run,
            CiHeadSnapshot snapshot,
            Instant now)
    {
        if (run.attemptsUsed() >= run.authorization().maxFixAttempts()) {
            return finishRun(run,
                    FinishReason.MAX_ATTEMPTS,
                    "GitHub Actions still fails after " + run.attemptsUsed() + " fix attempts",
                    now);
        }
        return snapshot.actionableFailures().isEmpty()
                ? run
                : prepareFixAttempt(run, requireOpenTask(run), snapshot);
    }

    private CiAutofixRun finishGreenOrCheckFixups(CiAutofixRun run, Instant now)
    {
        if (run.finalFixupCheck().isEmpty()) {
            WorktreeSnapshot worktree = inspectWorktree(requireOpenTask(run), run);
            FixupPositionCheck check = gitService.checkFixupPositions(new CiWorktreeRange(
                    worktree.worktree().canonicalWorktree(),
                    worktree.worktree().physicalRepository(),
                    worktree.worktree().generatedBranch(),
                    run.latestObservedBaseSha(),
                    run.expectedRemoteHead()));
            boolean valid = check.result() == FixupPositionCheck.Result.VALID
                    && check.headSha().equals(run.expectedRemoteHead());
            CheckResult result = valid
                    ? CheckResult.VALID
                    : CheckResult.INVALID;
            String diagnostics = check.headSha().equals(run.expectedRemoteHead())
                    ? check.diagnostics()
                    : "Final fixup check returned another head";
            return store.update(run.withFinalFixupCheck(
                    new FinalFixupCheck(check.headSha(), result, diagnostics, now),
                    now));
        }
        FinalFixupCheck check = run.finalFixupCheck().orElseThrow();
        if (check.result() == CheckResult.INVALID) {
            return store.update(run.pause(
                    PauseReason.INVALID_FIXUP_POSITION,
                    summary("Fixup commits are not in the required positions", check.diagnostics()),
                    now));
        }
        cleanupSynchronizedBase(run);
        return completeGreenRun(run, now);
    }

    private CiAutofixRun completeGreenRun(CiAutofixRun run, Instant now)
    {
        TaskCompletion completion = completionService.complete(new CompleteAgentTaskCommand(
                TaskId.valueOf(run.agentTaskId().orElseThrow()),
                Optional.of("CI Autofix verified GitHub Actions at " + run.expectedRemoteHead())));
        CiAutofixRun finished = store.update(run.finish(
                FinishReason.GREEN, completionSummary(run, completion), now));
        cleanupFinishedTask(finished);
        return finished;
    }

    private Optional<CiAutofixRun> reconcileCompletedGreenTask(CiAutofixRun run, Instant now)
    {
        if (run.agentTaskId().isEmpty()
                || run.finalFixupCheck().map(FinalFixupCheck::result).filter(CheckResult.VALID::equals).isEmpty()) {
            return Optional.empty();
        }
        TaskId taskId = TaskId.valueOf(run.agentTaskId().orElseThrow());
        if (taskService.get(taskId).state() != TaskState.COMPLETED) {
            return Optional.empty();
        }
        return Optional.of(completeGreenRun(run, now));
    }

    private CiAutofixRun observeCi(
            CiAutofixRun run,
            CiAutofixAttempt attempt,
            CiResult result,
            CiHeadSnapshot snapshot,
            Instant now)
    {
        return refreshMemory(store.update(run.observeCiResult(attempt.id(), result, snapshot.baseSha(), now)));
    }

    private RebaseGate ensureBranchRebase(
            CiAutofixRun run,
            AgentTask task,
            CiHeadSnapshot snapshot,
            Instant now)
    {
        SynchronizeCiBaseCommand syncCommand = synchronizeBaseCommand(run, snapshot.baseSha());
        requireWriteAuthority(run);
        CiBaseSynchronization synchronization = gitService.synchronizeBase(syncCommand);
        if (synchronization.outcome() != CiBaseSynchronization.Outcome.READY) {
            return RebaseGate.waiting(store.update(run.pause(
                    PauseReason.REBASE_PAUSED,
                    summary("The pull request base could not be synchronized", synchronization.diagnostics()),
                    now)));
        }
        WorktreeSnapshot workspace = inspectWorktree(task, run);
        if (workspace.uncommitted() || workspace.gitOperationInProgress()) {
            return RebaseGate.waiting(store.update(run.pause(
                    PauseReason.REBASE_PAUSED,
                    "The worktree is dirty or another Git operation is open before the target rebase",
                    now)));
        }
        try {
            requireWriteAuthority(run);
            BranchRebase rebase = rebaseService.start(new StartBranchRebaseCommand(
                    workspace.worktree(),
                    synchronization.localBaseBranch().orElseThrow(),
                    rebaseKey(run, workspace.head(), snapshot.baseSha())));
            verifyBranchRebaseScope(run, rebase, snapshot.baseSha());
            if (rebase.status() == BranchRebase.Status.FINISHED) {
                return finishedBranchRebase(run, rebase, now);
            }
            return RebaseGate.waiting(store.update(run.linkBranchRebase(rebase.id().toString(), now)));
        }
        catch (RuntimeException failure) {
            return RebaseGate.waiting(store.update(run.pause(
                    PauseReason.REBASE_PAUSED,
                    failureSummary("The target rebase could not start", failure),
                    now)));
        }
    }

    private RebaseGate advanceBranchRebase(CiAutofixRun run, CiHeadSnapshot snapshot, Instant now)
    {
        BranchRebase rebase = rebaseService.get(BranchRebaseId.valueOf(run.currentRebaseId().orElseThrow()));
        verifyBranchRebaseScope(run, rebase, rebase.baseSha());
        if (!rebase.baseSha().equals(snapshot.baseSha())) {
            // Finish the already-started exact rebase. A newer base is handled before the next fix action.
            log.debug("CI Autofix run {} is finishing rebase {} before observing newer base {}",
                    run.id(), rebase.id(), snapshot.baseSha());
        }
        if (rebase.status() == BranchRebase.Status.RUNNING) {
            requireWriteAuthority(run);
            rebase = rebaseService.advance(rebase.id());
        }
        if (rebase.status() == BranchRebase.Status.FINISHED) {
            RebaseGate finished = finishedBranchRebase(run, rebase, now);
            return rebase.baseSha().equals(snapshot.baseSha())
                    ? finished
                    : RebaseGate.waiting(finished.run());
        }
        return switch (rebase.status()) {
            case RUNNING -> RebaseGate.waiting(run);
            case PAUSED -> RebaseGate.waiting(store.update(run.pause(
                    PauseReason.REBASE_PAUSED,
                    summary("The target rebase is paused", rebase.summary().orElse("Inspect the worktree")),
                    now)));
            case FINISHED -> throw new AssertionError("finished rebase was handled above");
        };
    }

    private RebaseGate finishedBranchRebase(CiAutofixRun run, BranchRebase rebase, Instant now)
    {
        if (rebase.finishReason().filter(BranchRebase.FinishReason.COMPLETED::equals).isEmpty()) {
            CiAutofixRun cleared = run.currentRebaseId().isPresent()
                    ? run.clearBranchRebase(rebase.id().toString(), now)
                    : run;
            return RebaseGate.waiting(store.update(cleared.pause(
                    PauseReason.REBASE_PAUSED,
                    "The program-owned target rebase was cancelled",
                    now)));
        }
        CiAutofixRun prepared = run.currentRebaseId().isPresent()
                ? run.completeBranchRebase(rebase.id().toString(), rebase.baseSha(), now)
                : run.observeBase(run.expectedRemoteHead(), rebase.baseSha(), now);
        return RebaseGate.ready(store.update(prepared));
    }

    private CiAutofixRun resumeBranchRebase(CiAutofixRun run, String message)
    {
        BranchRebase rebase = rebaseService.get(BranchRebaseId.valueOf(run.currentRebaseId().orElseThrow()));
        verifyBranchRebaseScope(run, rebase, rebase.baseSha());
        if (rebase.status() == BranchRebase.Status.PAUSED) {
            requireWriteAuthority(run);
            rebase = rebaseService.sendMessage(rebase.id(), message.strip());
        }
        if (rebase.status() == BranchRebase.Status.PAUSED) {
            return run;
        }
        Instant now = Instant.now(clock);
        CiAutofixRun resumed = run.resume(now);
        if (rebase.status() == BranchRebase.Status.FINISHED) {
            return finishedBranchRebase(resumed, rebase, now).run();
        }
        return store.update(resumed);
    }

    private void cancelBranchRebase(CiAutofixRun run)
    {
        ownership.require(run);
        run.currentRebaseId().ifPresent(value ->
                rebaseService.cancel(BranchRebaseId.valueOf(value)));
    }

    private void cleanupSynchronizedBase(CiAutofixRun run)
    {
        if (!gitService.removeSynchronizedBase(
                synchronizeBaseCommand(run, run.latestObservedBaseSha()))) {
            log.debug("Private CI base branch remains for run {}", run.id());
        }
    }

    private static boolean needsBranchRebase(CiAutofixRun run, CiHeadSnapshot snapshot)
    {
        if (run.attempts().isEmpty()) {
            return !snapshot.actionableFailures().isEmpty();
        }
        CiAutofixAttempt attempt = run.attempts().getLast();
        if (attempt.agentTurnId().isEmpty()) {
            return !snapshot.baseSha().equals(run.latestObservedBaseSha());
        }
        if (attempt.pushedSha().isEmpty() && attempt.gitResult().isEmpty()) {
            return true;
        }
        return attempt.observedCiResult() == CiResult.FAILED
                && run.attemptsUsed() < run.authorization().maxFixAttempts()
                && !snapshot.baseSha().equals(run.latestObservedBaseSha());
    }

    private static boolean preparedAttemptNeedsNewBase(CiAutofixRun run, CiHeadSnapshot snapshot)
    {
        return !run.attempts().isEmpty()
                && run.attempts().getLast().agentTurnId().isEmpty()
                && !snapshot.baseSha().equals(run.latestObservedBaseSha());
    }

    private CiAutofixRun refreshPreparedAttempt(CiAutofixRun run, AgentTask task, Instant now)
    {
        WorktreeSnapshot workspace = inspectWorktree(task, run);
        checkArgument(!workspace.uncommitted() && !workspace.gitOperationInProgress(),
                "CI Autofix worktree is dirty or another Git operation is open after the target rebase");
        CiAutofixAttempt attempt = run.attempts().getLast();
        return store.update(run.updateAttempt(attempt.withPreparedInput(workspace.head(), now), now));
    }

    private void verifyBranchRebaseScope(CiAutofixRun run, BranchRebase rebase, String baseSha)
    {
        TaskWorktree worktree = requireTaskWorktree(requireOpenTask(run), run);
        if (!rebase.agentTaskId().equals(run.agentTaskId().orElseThrow())
                || !rebase.repository().equals(RepoRef.of(
                        worktree.physicalRepository().owner(), worktree.physicalRepository().name()))
                || !rebase.worktree().equals(worktree.canonicalWorktree())
                || !rebase.workBranch().equals(worktree.generatedBranch())
                || !rebase.baseBranch().equals(localBaseBranch(run))
                || !rebase.baseSha().equals(baseSha)) {
            throw new CiAutofixConflictException("Branch rebase is outside the CI Autofix scope");
        }
    }

    private static String rebaseKey(CiAutofixRun run, String localHead, String baseSha)
    {
        return "ci-rebase:" + run.id() + ":" + localHead + ":" + baseSha;
    }

    private static String localBaseBranch(CiAutofixRun run)
    {
        return "bytequay/ci-base/" + run.id();
    }

    private SynchronizeCiBaseCommand synchronizeBaseCommand(CiAutofixRun run, String baseSha)
    {
        WorktreeSnapshot worktree = inspectWorktree(requireOpenTask(run), run);
        WorkspaceRepositoryContext workspace = requireWorkspace(run);
        return new SynchronizeCiBaseCommand(
                worktree.worktree().canonicalWorktree(),
                worktree.worktree().physicalRepository(),
                worktree.worktree().generatedBranch(),
                workspace.sourceRemote(),
                repository(run.scope().pullRequest()),
                run.scope().baseRef(),
                baseSha,
                localBaseBranch(run));
    }

    private CiAutofixRun finishRun(
            CiAutofixRun run,
            FinishReason reason,
            String message,
            Instant now)
    {
        Optional<AgentTask> task = Optional.empty();
        if (run.agentTaskId().isPresent()) {
            try {
                task = Optional.of(ensureAgentTask(run));
            }
            catch (RuntimeException failure) {
                return preserveUnfinishedRun(
                        run,
                        failureSummary("The agent task could not be verified and was preserved", failure),
                        now);
            }
        }
        cancelBranchRebase(run);
        run.currentTurnId().ifPresent(value -> {
            AgentTurn turn = turnService.get(TurnId.valueOf(value));
            if (turn.state().active()) {
                turnService.cancel(turn.id());
            }
        });
        if (task.isPresent()) {
            WorktreeSnapshot worktree;
            try {
                worktree = inspectWorktree(task.orElseThrow(), run);
            }
            catch (RuntimeException failure) {
                return preserveUnfinishedRun(
                        run,
                        failureSummary("The task worktree could not be verified and was preserved", failure),
                        now);
            }
            if (worktree.uncommitted() || worktree.gitOperationInProgress()) {
                return preserveUnfinishedRun(
                        run,
                        "The task worktree has local changes or an open Git operation and was preserved",
                        now);
            }
        }
        cleanupSynchronizedBase(run);
        CiAutofixRun finished = store.update(run.finish(reason, message, now));
        completeFinishedTask(finished);
        return finished;
    }

    private CiAutofixRun preserveUnfinishedRun(CiAutofixRun run, String message, Instant now)
    {
        return run.status() == Status.PAUSED
                ? run
                : store.update(run.pause(PauseReason.ERROR, message, now));
    }

    private void completeFinishedTask(CiAutofixRun run)
    {
        if (run.agentTaskId().isEmpty()) {
            return;
        }
        TaskId taskId = TaskId.valueOf(run.agentTaskId().orElseThrow());
        try {
            AgentTask task = taskService.get(taskId);
            if (task.state() == TaskState.OPEN || task.state() == TaskState.COMPLETED) {
                completionService.complete(new CompleteAgentTaskCommand(
                        taskId,
                        Optional.of("CI Autofix finished with " + run.finishReason().orElseThrow()
                                + ": " + run.summary().orElse("No summary"))));
            }
            cleanupFinishedTask(run);
        }
        catch (AgentConflictException deferred) {
            log.debug("CI Autofix task {} is not ready to close: {}", taskId, deferred.getMessage());
        }
        catch (RuntimeException failure) {
            log.warn("Cannot close finished CI Autofix task {}: {}", taskId, failureSummary(failure));
        }
    }

    private void cleanupFinishedTask(CiAutofixRun run)
    {
        TaskId taskId = TaskId.valueOf(run.agentTaskId().orElseThrow());
        try {
            completionService.cleanupWorktree(taskId);
        }
        catch (RuntimeException failure) {
            log.warn("Cannot clean up finished CI Autofix task {}: {}", taskId, failureSummary(failure));
        }
    }

    private CiAutofixRun pauseAndRefresh(
            CiAutofixRun run,
            PauseReason reason,
            String message,
            Instant now)
    {
        return refreshMemory(store.update(run.pause(reason, message, now)));
    }

    private CiAutofixRun refreshMemory(CiAutofixRun run)
    {
        memoryService.update(run.id());
        return get(run.id());
    }

    private static CiResult ciResult(CiHeadSnapshot snapshot)
    {
        if (!snapshot.actionableFailures().isEmpty()) {
            return CiResult.FAILED;
        }
        if (snapshot.green()) {
            return CiResult.PASSED;
        }
        return snapshot.pending() ? CiResult.PENDING : CiResult.UNKNOWN;
    }

    private static Optional<CiWorkflowRun> failedWorkflow(CiHeadSnapshot snapshot)
    {
        return snapshot.workflowRuns().stream()
                .filter(workflow -> workflow.jobs().stream().anyMatch(CiJob::actionableFailure))
                .findFirst();
    }

    private CiAutofixRun prepareFixAttempt(
            CiAutofixRun run,
            AgentTask task,
            CiHeadSnapshot snapshot)
    {
        List<FailureEvidence> failures = logReader.readFailures(task.id(), snapshot);
        checkArgument(!failures.isEmpty(), "CI snapshot has no readable actionable failure");
        WorktreeSnapshot workspace = inspectWorktree(task, run);
        checkArgument(!workspace.uncommitted() && !workspace.gitOperationInProgress(),
                "CI Autofix worktree is dirty or another Git operation is open before the fix attempt");
        Instant now = Instant.now(clock);
        CiAutofixAttempt attempt = CiAutofixAttempt.create(
                CiAutofixAttemptId.random(),
                run.id(),
                run.attempts().size() + 1,
                workspace.head(),
                failures,
                now);
        return refreshMemory(store.update(run.addAttempt(attempt, now)));
    }

    private CiAutofixRun startAttemptTurn(CiAutofixRun run, AgentTask task, CiHeadSnapshot snapshot)
    {
        CiAutofixAttempt attempt = run.attempts().getLast();
        inspectWorktree(task, run);
        requireWriteAuthority(run);
        AgentTurn turn = turnService.start(new StartAgentTurnCommand(
                task.id(),
                promptBuilder.build(snapshot, attempt),
                logReader.attachments(task.id(), attempt.failures()),
                attempt.id().toString()));
        return store.update(run.linkAttemptTurn(attempt.id(), turn.id().toString(), Instant.now(clock)));
    }

    private Optional<CiAutofixRun> rejectChangedPullRequest(
            CiAutofixRun run,
            CiHeadSnapshot snapshot,
            Instant now)
    {
        if (!matchesPullRequestScope(run, snapshot)) {
            return Optional.of(finishRun(run,
                    FinishReason.STALE_HEAD,
                    "Pull request no longer matches the authorized CI Autofix scope",
                    now));
        }
        if (!snapshot.open()) {
            return Optional.of(finishRun(run,
                    FinishReason.ERROR,
                    "Pull request is no longer open",
                    now));
        }
        return Optional.empty();
    }

    private static boolean matchesPullRequestScope(CiAutofixRun run, CiHeadSnapshot snapshot)
    {
        return snapshot.pullRequest().equals(run.scope().pullRequest())
                && snapshot.headRepository().fullName().equalsIgnoreCase(run.scope().headRepository().fullName())
                && snapshot.author().equalsIgnoreCase(run.scope().author())
                && snapshot.headRef().equals(run.scope().headRef())
                && snapshot.baseRef().equals(run.scope().baseRef());
    }

    private static String completionSummary(CiAutofixRun run, TaskCompletion completion)
    {
        var metrics = completion.summary().metrics();
        return "GitHub Actions passed for " + run.expectedRemoteHead()
                + "; agent totals: " + metrics.inputTokens() + " input tokens, "
                + metrics.outputTokens() + " output tokens, " + metrics.elapsed().toSeconds() + " seconds";
    }

    private static String summary(String prefix, String detail)
    {
        return detail.isBlank() ? prefix : truncate(prefix + ": " + detail, MAXIMUM_SUMMARY_CHARACTERS, "…");
    }

    private CiAutofixRun startFixTurn(CiAutofixRun run, AgentTask task, CiHeadSnapshot snapshot)
    {
        return startAttemptTurn(prepareFixAttempt(run, task, snapshot), task, snapshot);
    }

    private CiAutofixRun copyFinalMessage(CiAutofixRun run, AgentTurn turn, Instant now)
    {
        Optional<String> note = logReader.readFinalMessage(turn.taskId(), turn.id());
        if (note.isEmpty() || run.attempts().isEmpty()) {
            return run;
        }
        CiAutofixAttempt attempt = run.attempts().getLast();
        if (attempt.untrustedAgentNote().equals(note)) {
            return run;
        }
        return run.updateAttempt(attempt.withAgentNote(note.orElseThrow(), now), now);
    }

    private AgentTask requireOpenTask(CiAutofixRun run)
    {
        AgentTask task = taskService.get(TaskId.valueOf(run.agentTaskId().orElseThrow()));
        if (task.state() != TaskState.OPEN
                || task.permissionPolicy() != AgentPermissionPolicy.CI_AUTOFIX
                || !task.id().toString().equals(run.agentTaskId().orElseThrow())) {
            throw new CiAutofixConflictException("CI Autofix agent task is not open for another turn");
        }
        return task;
    }

    private AgentTask ensureAgentTask(CiAutofixRun run)
    {
        ownership.require(run);
        TaskId taskId = TaskId.valueOf(run.agentTaskId().orElseThrow());
        RepositoryIdentity selectedRepository = repositoryIdentity(
                workspaces.requireReadyRepository(run.scope().workspaceId()).selectedRepository());
        RepositoryIdentity repository = repositoryIdentity(run.scope().physicalRepository());
        requireSelectedRepository(selectedRepository, repository, CiAutofixConflictException::new);
        AgentTask task;
        try {
            task = taskService.get(taskId);
        }
        catch (AgentTaskNotFoundException ignored) {
            task = createOrGet(taskService, taskId, () -> createAgentTask(run, repository, run.taskStartCommit()));
        }
        return requireAgentTask(run, task, repository);
    }

    private AgentTask createAgentTask(CiAutofixRun run, RepositoryIdentity repository, String startCommit)
    {
        requireWriteAuthority(run);
        TaskId taskId = TaskId.valueOf(run.agentTaskId().orElseThrow());
        String title = "CI Autofix " + run.scope().pullRequest().fullName();
        return taskService.create(new CreateAgentTaskCommand(
                taskId,
                run.scope().workspaceId(),
                new AgentLaneId(run.scope().laneId()),
                repository,
                startCommit,
                TaskBranches.generate(taskId, run.scope().author(), title),
                title,
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.CI_AUTOFIX)));
    }

    private static AgentTask requireAgentTask(
            CiAutofixRun run,
            AgentTask task,
            RepositoryIdentity repository)
    {
        TaskId taskId = TaskId.valueOf(run.agentTaskId().orElseThrow());
        TaskWorktree worktree = task.taskWorktree().orElseThrow(() ->
                new CiAutofixConflictException("CI Autofix agent task has no managed worktree"));
        if (!task.id().equals(taskId)
                || task.interactive()
                || task.workspaceId().stream().noneMatch(run.scope().workspaceId()::equals)
                || !task.laneId().equals(new AgentLaneId(run.scope().laneId()))
                || task.permissionPolicy() != AgentPermissionPolicy.CI_AUTOFIX
                || !task.repository().equals(repository)
                || !worktree.workspaceId().equals(run.scope().workspaceId())
                || !worktree.physicalRepository().equals(repository)
                || !worktree.startCommit().equals(run.taskStartCommit())) {
            throw new CiAutofixConflictException("CI Autofix agent task does not match the run");
        }
        return task;
    }

    private WorktreeSnapshot inspectWorktree(AgentTask task, CiAutofixRun run)
    {
        TaskWorktree worktree = requireTaskWorktree(task, run);
        return worktreeService.inspect(worktree);
    }

    private WorkspaceRepositoryContext requireWorkspace(CiAutofixRun run)
    {
        WorkspaceRepositoryContext workspace = workspaces.requireReadyRepository(run.scope().workspaceId());
        TaskWorktree worktree = requireTaskWorktree(requireOpenTask(run), run);
        if (!workspace.monitoredRepository().owner().equalsIgnoreCase(run.scope().pullRequest().owner())
                || !workspace.monitoredRepository().repo().equalsIgnoreCase(run.scope().pullRequest().repo())
                || !workspace.selectedRepository().owner().equalsIgnoreCase(worktree.physicalRepository().owner())
                || !workspace.selectedRepository().repo().equalsIgnoreCase(worktree.physicalRepository().name())
                || (!run.scope().headRepository().fullName().equalsIgnoreCase(run.scope().pullRequest().repoFullName())
                        && !run.scope().headRepository().fullName().equalsIgnoreCase(workspace.selectedRepository().fullName()))) {
            throw new CiAutofixConflictException("Workspace repository routing no longer matches the CI Autofix run");
        }
        return workspace;
    }

    private static TaskWorktree requireTaskWorktree(AgentTask task, CiAutofixRun run)
    {
        TaskWorktree worktree = task.taskWorktree().orElseThrow(() ->
                new CiAutofixConflictException("CI Autofix agent task has no managed worktree"));
        if (!worktree.workspaceId().equals(run.scope().workspaceId())
                || !worktree.physicalRepository().equals(task.repository())
                || !task.id().toString().equals(run.agentTaskId().orElseThrow())) {
            throw new CiAutofixConflictException("CI Autofix task worktree does not match the run");
        }
        return worktree;
    }

    private boolean matchesCurrentMessage(CiAutofixRun run, String idempotencyKey)
    {
        if (run.currentTurnId().isEmpty()) {
            return false;
        }
        AgentTurn turn = turnService.get(TurnId.valueOf(run.currentTurnId().orElseThrow()));
        return turn.idempotencyKey().equals(idempotencyKey);
    }

    private String currentUser()
    {
        String login = credentials.resolveGitHubCredential().profile().login();
        checkArgument(login != null && !login.isBlank(), "Authenticated GitHub account has no login");
        return login;
    }

    private static void validateStartCommand(StartCiAutofixCommand command)
    {
        requireNonNull(command, "command is null");
        checkArgument(command.maxAttempts() > 0
                        && command.maxAttempts() <= CiAutofixRun.MAX_FIX_ATTEMPTS,
                "maxAttempts must be between 1 and 3");
        checkArgument(command.allowFailedJobReruns()
                        && command.allowHistoryRewrites()
                        && command.allowExactLeasePush(),
                "All CI Autofix consent flags must be true");
        checkText(command.idempotencyKey(), "idempotencyKey", 200);
    }

    private static void requireStartWorkspace(CiAutofixRun run, String workspaceId)
    {
        if (!run.scope().workspaceId().equals(workspaceId)) {
            throw new CiAutofixConflictException("CI Autofix idempotency key belongs to another workspace");
        }
    }

    private void requireWriteAuthority(CiAutofixRun run)
    {
        ownership.require(run);
        if (run.scope().parentTaskId().isPresent() && !clock.instant().isBefore(run.authorization().expiresAt())) {
            throw new CiAutofixConflictException("Development CI authorization expired");
        }
    }

    private void requireStartOwnership(CiAutofixRun run, Optional<DevelopmentStart> development)
    {
        if (!run.scope().parentTaskId().equals(development.map(value -> value.parentTaskId().toString()))) {
            throw new CiAutofixConflictException("CI Autofix start belongs to another owner");
        }
        development.ifPresent(value -> {
            ownership.requireParent(value.parentTaskId(), value.sourceTurnId());
            ownership.require(run);
            checkArgument(run.scope().pullRequest().equals(value.expectedPr().pullRequest())
                            && run.scope().headRepository().fullName().equalsIgnoreCase(value.expectedPr().headRepository().fullName())
                            && run.scope().headRef().equals(value.expectedPr().headBranch())
                            && run.scope().baseRef().equals(value.expectedPr().baseBranch())
                            && run.taskStartCommit().equals(value.expectedPr().headSha()),
                    "CI Autofix start belongs to another published revision");
        });
    }

    private static void requireExpectedPullRequest(CiHeadSnapshot snapshot, GitHubPullRequestIdentity expected)
    {
        checkArgument(snapshot.pullRequest().equals(expected.pullRequest())
                        && snapshot.pullRequest().repoFullName().equalsIgnoreCase(expected.baseRepository().fullName())
                        && snapshot.headRepository().fullName().equalsIgnoreCase(expected.headRepository().fullName())
                        && snapshot.headRef().equals(expected.headBranch()) && snapshot.headSha().equals(expected.headSha())
                        && snapshot.baseRef().equals(expected.baseBranch()) && snapshot.baseSha().equals(expected.baseSha()),
                "The published pull request changed before CI attachment");
    }

    private static void validateMessageCommand(SendCiAutofixMessageCommand command)
    {
        requireNonNull(command, "command is null");
        checkText(command.message(), "message", MAXIMUM_MESSAGE_CHARACTERS);
        checkText(command.idempotencyKey(), "idempotencyKey", 200);
    }

    private static void validatePullRequest(
            CiHeadSnapshot snapshot,
            PullRequestRef expectedPullRequest,
            String currentUser)
    {
        checkArgument(snapshot.pullRequest().equals(expectedPullRequest), "GitHub returned another pull request");
        checkArgument(snapshot.open(), "Pull request is not open");
        checkArgument(snapshot.author().equalsIgnoreCase(currentUser), "CI Autofix is available only to the pull request author");
    }

    private static void validateStartWorkspace(
            WorkspaceRepositoryContext workspace,
            CiHeadSnapshot snapshot)
    {
        PullRequestRef pullRequest = snapshot.pullRequest();
        checkArgument(workspace.monitoredRepository().owner().equalsIgnoreCase(pullRequest.owner())
                        && workspace.monitoredRepository().repo().equalsIgnoreCase(pullRequest.repo()),
                "Workspace repository does not match the pull request");
        checkArgument(snapshot.headRepository().fullName().equalsIgnoreCase(pullRequest.repoFullName())
                        || snapshot.headRepository().fullName().equalsIgnoreCase(workspace.selectedRepository().fullName()),
                "Pull request fork does not match the workspace's selected repository");
    }

    private static RepositoryIdentity repository(PullRequestRef pullRequest)
    {
        return new RepositoryIdentity(pullRequest.owner(), pullRequest.repo());
    }

    private static String terminalSummary(String prefix, AgentTurn turn)
    {
        return summary(prefix, turn.terminalReason().orElse(""));
    }

    private static String failureSummary(String prefix, RuntimeException failure)
    {
        String detail = failure.getMessage();
        return summary(prefix, detail == null || detail.isBlank() ? failure.getClass().getSimpleName() : detail);
    }

    private static String failureSummary(RuntimeException failure)
    {
        return failureSummary("Task completion failed", failure);
    }

    private record DevelopmentStart(TaskId parentTaskId, TurnId sourceTurnId, GitHubPullRequestIdentity expectedPr, Instant activatedAt) {}

    private record RebaseGate(CiAutofixRun run, boolean ready)
    {
        private static RebaseGate ready(CiAutofixRun run)
        {
            return new RebaseGate(run, true);
        }

        private static RebaseGate waiting(CiAutofixRun run)
        {
            return new RebaseGate(run, false);
        }
    }
}

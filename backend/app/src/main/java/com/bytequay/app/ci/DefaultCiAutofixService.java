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
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.AgentWorkspaceService;
import com.bytequay.agent.core.CompleteAgentTaskCommand;
import com.bytequay.agent.core.CreateAgentTaskCommand;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.StartAgentTurnCommand;
import com.bytequay.agent.core.TaskCompletion;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.WorkspaceSnapshot;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.pr.PullRequestTimelineService;
import com.bytequay.app.rebase.BranchRebaseService;
import com.bytequay.app.rebase.StartBranchRebaseCommand;
import com.bytequay.app.service.credentials.CredentialService;
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
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.rebase.BranchRebase;
import com.bytequay.core.rebase.BranchRebaseId;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Striped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.Lock;

import static com.google.common.base.Ascii.truncate;
import static com.google.common.base.Preconditions.checkArgument;
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
    private final AgentTaskService taskService;
    private final AgentTurnService turnService;
    private final AgentCompletionService completionService;
    private final AgentWorkspaceService workspaceService;
    private final CiAutofixLogReader logReader;
    private final CiMemoryService memoryService;
    private final CiGitService gitService;
    private final BranchRebaseService rebaseService;
    private final PullRequestTimelineService timelineService;
    private final CredentialService credentials;
    private final Clock clock;
    private final CiAutofixPromptBuilder promptBuilder = new CiAutofixPromptBuilder();
    private final Striped<Lock> advanceLocks = Striped.lock(64);

    DefaultCiAutofixService(
            CiAutofixStore store,
            GitHubActionsClient gitHub,
            AgentTaskService taskService,
            AgentTurnService turnService,
            AgentCompletionService completionService,
            AgentWorkspaceService workspaceService,
            CiAutofixLogReader logReader,
            CiMemoryService memoryService,
            CiGitService gitService,
            BranchRebaseService rebaseService,
            PullRequestTimelineService timelineService,
            CredentialService credentials,
            Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.taskService = requireNonNull(taskService, "taskService is null");
        this.turnService = requireNonNull(turnService, "turnService is null");
        this.completionService = requireNonNull(completionService, "completionService is null");
        this.workspaceService = requireNonNull(workspaceService, "workspaceService is null");
        this.logReader = requireNonNull(logReader, "logReader is null");
        this.memoryService = requireNonNull(memoryService, "memoryService is null");
        this.gitService = requireNonNull(gitService, "gitService is null");
        this.rebaseService = requireNonNull(rebaseService, "rebaseService is null");
        this.timelineService = requireNonNull(timelineService, "timelineService is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public CiAutofixRun start(StartCiAutofixCommand command)
    {
        validateStartCommand(command);
        Optional<CiAutofixRun> existing = store.findByIdempotencyKey(command.idempotencyKey());
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }

        CiHeadSnapshot snapshot = gitHub.inspect(new InspectCiHeadQuery(command.pullRequest()));
        String currentUser = currentUser();
        validatePullRequest(snapshot, command.pullRequest(), currentUser);

        RepositoryIdentity repository = repository(command.pullRequest());
        WorkspaceSnapshot workspace = workspaceService.inspect(command.worktree());
        validateStartWorkspace(workspace, repository, snapshot);

        Instant now = Instant.now(clock);
        CiAutofixRun run = CiAutofixRun.create(
                CiAutofixRunId.random(),
                command.idempotencyKey(),
                new Scope(
                        command.pullRequest(),
                        currentUser,
                        snapshot.headRef(),
                        snapshot.baseRef(),
                        workspace.worktree(),
                        command.laneId().toString()),
                snapshot.headSha(),
                snapshot.baseSha(),
                new Authorization(
                        true,
                        true,
                        true,
                        command.maxAttempts(),
                        now,
                        now.plus(AUTHORIZATION_PERIOD)),
                now);
        try {
            store.insert(run);
        }
        catch (CiAutofixConflictException conflict) {
            return store.findByIdempotencyKey(command.idempotencyKey()).orElseThrow(() -> conflict);
        }

        AgentTask task;
        try {
            task = taskService.create(new CreateAgentTaskCommand(
                    command.laneId(),
                    workspace.worktree(),
                    repository,
                    "CI Autofix " + command.pullRequest().fullName(),
                    AgentPermissionPolicy.CI_AUTOFIX,
                    Optional.empty(),
                    false));
        }
        catch (RuntimeException failure) {
            log.error("Cannot create agent task for CI Autofix run {}", run.id(), failure);
            return store.update(run.finish(
                    FinishReason.ERROR,
                    failureSummary("Agent task creation failed", failure),
                    Instant.now(clock)));
        }

        run = store.update(run.assignAgentTask(task.id().toString(), Instant.now(clock)));
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

    @Override
    public List<CiAutofixRun> list(int limit)
    {
        checkArgument(limit > 0 && limit <= 200, "limit must be between 1 and 200");
        return store.listRecent(limit);
    }

    @Override
    public CiAutofixRun sendMessage(SendCiAutofixMessageCommand command)
    {
        validateMessageCommand(command);
        Lock lock = advanceLocks.get(command.runId());
        lock.lock();
        try {
            return sendMessageLocked(command);
        }
        finally {
            lock.unlock();
        }
    }

    private CiAutofixRun sendMessageLocked(SendCiAutofixMessageCommand command)
    {
        CiAutofixRun run = get(command.runId());
        if (matchesCurrentMessage(run, command.idempotencyKey())) {
            return run;
        }
        if (run.status() == Status.ACTIVE && run.currentRebaseId().isPresent()) {
            return run;
        }
        if (run.status() != Status.PAUSED) {
            throw new CiAutofixConflictException("Only a paused CI Autofix run accepts a message");
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

        CiHeadSnapshot snapshot = gitHub.inspect(new InspectCiHeadQuery(run.scope().pullRequest()));
        validatePullRequest(snapshot, run.scope().pullRequest(), currentUser());
        if (!snapshot.headRef().equals(run.scope().headRef())
                || !snapshot.headSha().equals(run.expectedRemoteHead())) {
            throw new CiAutofixConflictException("Pull request head changed outside this CI Autofix run");
        }
        AgentTask task = requireOpenTask(run);
        workspaceService.restore(task.id(), task.worktree(), task.repository());

        if (run.currentRebaseId().isPresent()) {
            return resumeBranchRebase(run, command.message());
        }
        validateMessageWorkspace(task, run, workspaceService.inspect(task.worktree()));

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
        Lock lock = advanceLocks.get(runId);
        lock.lock();
        try {
            return cancelLocked(runId);
        }
        finally {
            lock.unlock();
        }
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
        Lock lock = advanceLocks.get(runId);
        lock.lock();
        try {
            return advanceLocked(runId);
        }
        finally {
            lock.unlock();
        }
    }

    private CiAutofixRun advanceLocked(CiAutofixRunId runId)
    {
        CiAutofixRun run = get(runId);
        Instant now = Instant.now(clock);
        if (run.status() == Status.FINISHED) {
            completeFinishedTask(run);
            return run;
        }
        if (run.status() == Status.PAUSED) {
            return now.isBefore(run.authorization().expiresAt())
                    ? run
                    : finishRun(run,
                            FinishReason.AUTHORIZATION_EXPIRED,
                            "CI Autofix authorization expired",
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

        CiHeadSnapshot snapshot = gitHub.inspect(new InspectCiHeadQuery(run.scope().pullRequest()));
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
                return restorePushJournal(run, attempt, now);
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
                    PauseReason.TURN_FAILED,
                    terminalSummary("Agent turn failed", turn),
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

    private CiAutofixRun inspectCompletedAttempt(
            CiAutofixRun run,
            CiAutofixAttempt attempt,
            CiHeadSnapshot snapshot,
            Instant now)
    {
        String inspectionBaseline = attempt.inputSha().equals(run.expectedRemoteHead())
                ? attempt.inputSha()
                : run.expectedRemoteHead();
        CiGitInspection inspection = gitService.inspectAgentResult(new InspectAgentResultCommand(
                run.scope().worktree(),
                repository(run.scope().pullRequest()),
                run.scope().headRef(),
                inspectionBaseline,
                snapshot.baseSha()));
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
        PushJournal journal = new PushJournal(
                attempt.id(),
                run.expectedRemoteHead(),
                gitResult.localHead(),
                gitResult.fingerprint(),
                now,
                0);
        CiAutofixRun stored = store.update(run
                .updateAttempt(attempt.withGitResult(gitResult, now), now)
                .recordPushJournal(journal, now));
        return refreshMemory(stored);
    }

    private CiAutofixRun restorePushJournal(CiAutofixRun run, CiAutofixAttempt attempt, Instant now)
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

        CiWorkflowRun workflow = failedWorkflow.orElseThrow();
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
        CiPushResult result = gitService.push(new PushCiHeadCommand(
                run.scope().worktree(),
                repository(run.scope().pullRequest()),
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
        CiHeadSnapshot snapshot = gitHub.inspect(new InspectCiHeadQuery(run.scope().pullRequest()));
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
            FixupPositionCheck check = gitService.checkFixupPositions(new CheckFixupPositionsCommand(
                    run.scope().worktree(),
                    repository(run.scope().pullRequest()),
                    run.scope().headRef(),
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
        TaskCompletion completion = completionService.complete(new CompleteAgentTaskCommand(
                TaskId.valueOf(run.agentTaskId().orElseThrow()),
                Optional.of("CI Autofix verified GitHub Actions at " + run.expectedRemoteHead())));
        CiAutofixRun finished = store.update(run.finish(
                FinishReason.GREEN, completionSummary(run, completion), now));
        cleanupSynchronizedBase(finished);
        return finished;
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
        CiBaseSynchronization synchronization = gitService.synchronizeBase(syncCommand);
        if (synchronization.outcome() != CiBaseSynchronization.Outcome.READY) {
            return RebaseGate.waiting(store.update(run.pause(
                    PauseReason.REBASE_PAUSED,
                    summary("The pull request base could not be synchronized", synchronization.diagnostics()),
                    now)));
        }
        WorkspaceSnapshot workspace = workspaceService.inspect(task.worktree());
        validateMessageWorkspace(task, run, workspace);
        if (workspace.dirty()) {
            return RebaseGate.waiting(store.update(run.pause(
                    PauseReason.REBASE_PAUSED,
                    "The worktree is dirty before the target rebase",
                    now)));
        }
        try {
            BranchRebase rebase = rebaseService.start(new StartBranchRebaseCommand(
                    task.id(),
                    RepoRef.of(run.scope().pullRequest().owner(), run.scope().pullRequest().repo()),
                    run.scope().worktree(),
                    run.scope().headRef(),
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
        WorkspaceSnapshot workspace = workspaceService.inspect(task.worktree());
        validateMessageWorkspace(task, run, workspace);
        checkArgument(!workspace.dirty(), "CI Autofix worktree is dirty after the target rebase");
        CiAutofixAttempt attempt = run.attempts().getLast();
        return store.update(run.updateAttempt(attempt.withPreparedInput(workspace.head(), now), now));
    }

    private static void verifyBranchRebaseScope(CiAutofixRun run, BranchRebase rebase, String baseSha)
    {
        if (!rebase.agentTaskId().equals(run.agentTaskId().orElseThrow())
                || !rebase.repository().equals(RepoRef.of(
                        run.scope().pullRequest().owner(), run.scope().pullRequest().repo()))
                || !rebase.worktree().equals(run.scope().worktree())
                || !rebase.workBranch().equals(run.scope().headRef())
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

    private static SynchronizeCiBaseCommand synchronizeBaseCommand(CiAutofixRun run, String baseSha)
    {
        return new SynchronizeCiBaseCommand(
                run.scope().worktree(),
                repository(run.scope().pullRequest()),
                run.scope().headRef(),
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
        cancelBranchRebase(run);
        run.currentTurnId().ifPresent(value -> {
            AgentTurn turn = turnService.get(TurnId.valueOf(value));
            if (turn.state().active()) {
                turnService.cancel(turn.id());
            }
        });
        CiAutofixRun finished = store.update(run.finish(reason, message, now));
        cleanupSynchronizedBase(finished);
        completeFinishedTask(finished);
        return finished;
    }

    private void completeFinishedTask(CiAutofixRun run)
    {
        if (run.agentTaskId().isEmpty()) {
            return;
        }
        cleanupSynchronizedBase(run);
        TaskId taskId = TaskId.valueOf(run.agentTaskId().orElseThrow());
        try {
            AgentTask task = taskService.get(taskId);
            if (task.state().terminal()) {
                return;
            }
            completionService.complete(new CompleteAgentTaskCommand(
                    taskId,
                    Optional.of("CI Autofix finished with " + run.finishReason().orElseThrow()
                            + ": " + run.summary().orElse("No summary"))));
        }
        catch (AgentConflictException deferred) {
            log.debug("CI Autofix task {} is not ready to close: {}", taskId, deferred.getMessage());
        }
        catch (RuntimeException failure) {
            log.warn("Cannot close finished CI Autofix task {}: {}", taskId, failureSummary(failure));
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
        WorkspaceSnapshot workspace = workspaceService.inspect(task.worktree());
        validateMessageWorkspace(task, run, workspace);
        checkArgument(!workspace.dirty(), "CI Autofix worktree is dirty before the fix attempt");
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
        workspaceService.restore(task.id(), task.worktree(), task.repository());
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
        if (!snapshot.pullRequest().equals(run.scope().pullRequest())
                || !snapshot.sameRepository()
                || !snapshot.author().equalsIgnoreCase(run.scope().author())
                || !snapshot.headRef().equals(run.scope().headRef())) {
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
        checkArgument(!command.pullRequest().owner().isBlank() && !command.pullRequest().repo().isBlank(), "pullRequest repository is blank");
        checkArgument(command.worktree().isAbsolute(), "worktree is not absolute");
        checkArgument(command.maxAttempts() > 0
                        && command.maxAttempts() <= CiAutofixRun.MAX_FIX_ATTEMPTS,
                "maxAttempts must be between 1 and 3");
        checkArgument(command.allowFailedJobReruns()
                        && command.allowHistoryRewrites()
                        && command.allowExactLeasePush(),
                "All CI Autofix consent flags must be true");
        validateText(command.idempotencyKey(), "idempotencyKey", 200);
    }

    private static void validateMessageCommand(SendCiAutofixMessageCommand command)
    {
        requireNonNull(command, "command is null");
        validateText(command.message(), "message", MAXIMUM_MESSAGE_CHARACTERS);
        validateText(command.idempotencyKey(), "idempotencyKey", 200);
    }

    private static void validatePullRequest(
            CiHeadSnapshot snapshot,
            PullRequestRef expectedPullRequest,
            String currentUser)
    {
        checkArgument(snapshot.pullRequest().equals(expectedPullRequest), "GitHub returned another pull request");
        checkArgument(snapshot.open(), "Pull request is not open");
        checkArgument(snapshot.sameRepository(), "Pull request is from a fork");
        checkArgument(snapshot.author().equalsIgnoreCase(currentUser), "CI Autofix is available only to the pull request author");
    }

    private static void validateStartWorkspace(
            WorkspaceSnapshot workspace,
            RepositoryIdentity repository,
            CiHeadSnapshot snapshot)
    {
        checkArgument(workspace.repository().toString().equalsIgnoreCase(repository.toString()), "Worktree repository does not match the pull request");
        checkArgument(!workspace.dirty(), "Worktree must be clean before CI Autofix starts");
        checkArgument(workspace.branch().filter(snapshot.headRef()::equals).isPresent(), "Worktree branch does not match the pull request head branch");
        checkArgument(workspace.head().equals(snapshot.headSha()), "Worktree HEAD does not match the pull request head SHA");
    }

    private static void validateMessageWorkspace(
            AgentTask task,
            CiAutofixRun run,
            WorkspaceSnapshot workspace)
    {
        if (!workspace.worktree().equals(task.worktree())
                || !workspace.repository().toString().equalsIgnoreCase(task.repository().toString())
                || workspace.branch().filter(run.scope().headRef()::equals).isEmpty()) {
            throw new CiAutofixConflictException("CI Autofix worktree lease no longer matches the run");
        }
    }

    private static RepositoryIdentity repository(PullRequestRef pullRequest)
    {
        return new RepositoryIdentity(pullRequest.owner(), pullRequest.repo());
    }

    private static String terminalSummary(String prefix, AgentTurn turn)
    {
        return truncate(
                prefix + turn.terminalReason().map(reason -> ": " + reason).orElse(""),
                MAXIMUM_SUMMARY_CHARACTERS,
                "…");
    }

    private static String failureSummary(String prefix, RuntimeException failure)
    {
        String detail = failure.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = failure.getClass().getSimpleName();
        }
        return truncate(prefix + ": " + detail, MAXIMUM_SUMMARY_CHARACTERS, "…");
    }

    private static String failureSummary(RuntimeException failure)
    {
        return failureSummary("Task completion failed", failure);
    }

    private static void validateText(String value, String name, int maximumLength)
    {
        requireNonNull(value, name + " is null");
        checkArgument(!value.isBlank(), name + " is blank");
        checkArgument(value.length() <= maximumLength, name + " exceeds " + maximumLength + " characters");
    }

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

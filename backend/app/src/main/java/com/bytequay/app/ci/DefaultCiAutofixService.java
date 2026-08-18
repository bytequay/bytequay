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

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.AgentWorkspaceService;
import com.bytequay.agent.core.CreateAgentTaskCommand;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.StartAgentTurnCommand;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.WorkspaceSnapshot;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.repository.github.GitHubAccountClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.core.ci.CiAutofixAttempt;
import com.bytequay.core.ci.CiAutofixAttempt.FailureEvidence;
import com.bytequay.core.ci.CiAutofixAttemptId;
import com.bytequay.core.ci.CiAutofixConflictException;
import com.bytequay.core.ci.CiAutofixNotFoundException;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixRun.Authorization;
import com.bytequay.core.ci.CiAutofixRun.FinishReason;
import com.bytequay.core.ci.CiAutofixRun.PauseReason;
import com.bytequay.core.ci.CiAutofixRun.Scope;
import com.bytequay.core.ci.CiAutofixRun.Status;
import com.bytequay.core.ci.CiAutofixRunId;
import com.bytequay.core.ci.CiAutofixStore;
import com.bytequay.core.domain.PullRequestRef;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Ascii.truncate;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

@Service
public class DefaultCiAutofixService
        implements CiAutofixService
{
    static final Duration AUTHORIZATION_PERIOD = Duration.ofHours(8);
    static final int MAXIMUM_MESSAGE_CHARACTERS = 16 * 1024;

    private static final int MAXIMUM_SUMMARY_CHARACTERS = 8 * 1024;

    private final CiAutofixStore store;
    private final GitHubActionsClient gitHub;
    private final AgentTaskService taskService;
    private final AgentTurnService turnService;
    private final AgentWorkspaceService workspaceService;
    private final CiAutofixLogReader logReader;
    private final CiMemoryService memoryService;
    private final CredentialService credentials;
    private final GitHubAccountClient accounts;
    private final Clock clock;
    private final CiAutofixPromptBuilder promptBuilder = new CiAutofixPromptBuilder();

    DefaultCiAutofixService(
            CiAutofixStore store,
            GitHubActionsClient gitHub,
            AgentTaskService taskService,
            AgentTurnService turnService,
            AgentWorkspaceService workspaceService,
            CiAutofixLogReader logReader,
            CiMemoryService memoryService,
            CredentialService credentials,
            GitHubAccountClient accounts,
            Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.taskService = requireNonNull(taskService, "taskService is null");
        this.turnService = requireNonNull(turnService, "turnService is null");
        this.workspaceService = requireNonNull(workspaceService, "workspaceService is null");
        this.logReader = requireNonNull(logReader, "logReader is null");
        this.memoryService = requireNonNull(memoryService, "memoryService is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.accounts = requireNonNull(accounts, "accounts is null");
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
        checkArgument(!snapshot.actionableFailures().isEmpty(),
                "Pull request has no actionable GitHub Actions failure");

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
                    AgentPermissionPolicy.CI_AUTOFIX));
        }
        catch (RuntimeException failure) {
            return store.update(run.finish(
                    FinishReason.ERROR,
                    failureSummary("Agent task creation failed", failure),
                    Instant.now(clock)));
        }

        run = store.update(run.assignAgentTask(task.id().toString(), Instant.now(clock)));
        try {
            return startFixTurn(run, task, snapshot);
        }
        catch (RuntimeException failure) {
            CiAutofixRun current = store.find(run.id()).orElse(run);
            if (current.status() == Status.ACTIVE) {
                return store.update(current.pause(
                        PauseReason.ERROR,
                        failureSummary("CI fix turn could not start", failure),
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
        checkArgument(limit > 0, "limit must be positive");
        return store.listRecent(limit);
    }

    @Override
    public CiAutofixRun sendMessage(SendCiAutofixMessageCommand command)
    {
        validateMessageCommand(command);
        CiAutofixRun run = get(command.runId());
        if (matchesCurrentMessage(run, command.idempotencyKey())) {
            return run;
        }
        if (run.status() != Status.PAUSED) {
            throw new CiAutofixConflictException("Only a paused CI Autofix run accepts a message");
        }

        Instant now = Instant.now(clock);
        if (!now.isBefore(run.authorization().expiresAt())) {
            return store.update(run.finish(
                    FinishReason.AUTHORIZATION_EXPIRED,
                    "CI Autofix authorization expired",
                    now));
        }
        if (run.attempts().isEmpty() || run.agentTaskId().isEmpty()) {
            throw new CiAutofixConflictException("CI Autofix run has no resumable fix attempt");
        }

        CiHeadSnapshot snapshot = gitHub.inspect(new InspectCiHeadQuery(run.scope().pullRequest()));
        validatePullRequest(snapshot, run.scope().pullRequest(), currentUser());
        if (!snapshot.headRef().equals(run.scope().headRef())
                || !snapshot.headSha().equals(run.expectedRemoteHead())) {
            throw new CiAutofixConflictException("Pull request head changed outside this CI Autofix run");
        }
        AgentTask task = requireReadyTask(run);
        workspaceService.restore(task.id(), task.worktree(), task.repository());
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
        CiAutofixRun run = get(runId);
        if (run.status() == Status.FINISHED) {
            return run;
        }
        run.currentTurnId().ifPresent(value -> {
            AgentTurn turn = turnService.get(TurnId.valueOf(value));
            if (turn.state().active()) {
                turnService.cancel(turn.id());
            }
        });
        return store.update(run.finish(FinishReason.CANCELLED, "CI Autofix cancelled by user", Instant.now(clock)));
    }

    @Override
    public CiAutofixRun advance(CiAutofixRunId runId)
    {
        CiAutofixRun run = get(runId);
        if (run.status() != Status.ACTIVE || run.currentTurnId().isEmpty()) {
            return run;
        }
        AgentTurn turn = turnService.get(TurnId.valueOf(run.currentTurnId().orElseThrow()));
        if (turn.state().active()) {
            return run;
        }

        Instant now = Instant.now(clock);
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

    private CiAutofixRun startFixTurn(CiAutofixRun run, AgentTask task, CiHeadSnapshot snapshot)
    {
        CiAutofixLogReader.FixTurnInput input = logReader.readFixTurn(task.id(), snapshot);
        List<FailureEvidence> failures = input.failures();
        Instant now = Instant.now(clock);
        CiAutofixAttempt attempt = CiAutofixAttempt.create(
                CiAutofixAttemptId.random(),
                run.id(),
                1,
                snapshot.headSha(),
                failures,
                now);
        CiAutofixRun stored = store.update(run.addAttempt(attempt, now));
        memoryService.update(stored.id());
        stored = get(stored.id());

        AgentTurn turn = turnService.start(new StartAgentTurnCommand(
                task.id(),
                promptBuilder.build(snapshot, failures),
                input.attachments(),
                attempt.id().toString()));
        return store.update(stored.linkAttemptTurn(attempt.id(), turn.id().toString(), Instant.now(clock)));
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

    private AgentTask requireReadyTask(CiAutofixRun run)
    {
        AgentTask task = taskService.get(TaskId.valueOf(run.agentTaskId().orElseThrow()));
        if (task.state() != TaskState.ACTIVE
                || task.permissionPolicy() != AgentPermissionPolicy.CI_AUTOFIX
                || !task.id().toString().equals(run.agentTaskId().orElseThrow())) {
            throw new CiAutofixConflictException("CI Autofix agent task is not ready for another turn");
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
        String login = accounts.fetchUserProfile(credentials.resolveGitHubToken()).login();
        checkArgument(login != null && !login.isBlank(), "Authenticated GitHub account has no login");
        return login;
    }

    private static void validateStartCommand(StartCiAutofixCommand command)
    {
        requireNonNull(command, "command is null");
        checkArgument(!command.pullRequest().owner().isBlank() && !command.pullRequest().repo().isBlank(),
                "pullRequest repository is blank");
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
        checkArgument(
                snapshot.author().equalsIgnoreCase(currentUser),
                "CI Autofix is available only to the pull request author");
    }

    private static void validateStartWorkspace(
            WorkspaceSnapshot workspace,
            RepositoryIdentity repository,
            CiHeadSnapshot snapshot)
    {
        checkArgument(workspace.repository().toString().equalsIgnoreCase(repository.toString()),
                "Worktree repository does not match the pull request");
        checkArgument(!workspace.dirty(), "Worktree must be clean before CI Autofix starts");
        checkArgument(workspace.branch().filter(snapshot.headRef()::equals).isPresent(),
                "Worktree branch does not match the pull request head branch");
        checkArgument(workspace.head().equals(snapshot.headSha()),
                "Worktree HEAD does not match the pull request head SHA");
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

    private static void validateText(String value, String name, int maximumLength)
    {
        requireNonNull(value, name + " is null");
        checkArgument(!value.isBlank(), name + " is blank");
        checkArgument(value.length() <= maximumLength,
                name + " exceeds " + maximumLength + " characters");
    }
}

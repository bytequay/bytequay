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
package com.bytequay.app.review;

import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventSource;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.BlobRef;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.ProvisionWorktreeCommand;
import com.bytequay.agent.core.QueuedAgentInput;
import com.bytequay.agent.core.QueuedInputId;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.ProviderAgentEvent.ActivityState;
import com.bytequay.app.agent.runtime.AgentProperties;
import com.bytequay.app.git.ReviewExperiments;
import com.bytequay.app.git.command.GitCommandRunner;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.settings.ClaudeAccounts;
import com.bytequay.app.workspace.ManagedWorktreeService;
import com.bytequay.app.workspace.WorkspacePaths;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.LocalRun;
import com.bytequay.core.review.LocalRun.Status;
import com.bytequay.core.review.LocalRunStore;
import com.bytequay.core.review.ReviewConflictException;
import com.bytequay.core.review.ReviewNotFoundException;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.PullRequestTaskStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static com.bytequay.app.git.GitTestSupport.git;
import static com.bytequay.app.git.GitTestSupport.write;
import static com.bytequay.app.review.ReviewTestData.pullRequestReview;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestLocalRunService
{
    private static final String WORKSPACE_ID = "local-runs";
    private static final String REVIEW_ID = "review-1";
    private static final RepoRef REPOSITORY = RepoRef.of("owner", "repo");
    private static final String ORIGIN = "https://github.com/owner/repo.git";
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00.123456Z");
    private static final Instant STARTED_AT = Instant.parse("2026-09-01T00:00:00.123Z");
    private static final String TEST_PATH = "core/src/test/java/demo/TestCode.java";
    private static final String ROOT_TEST_PATH = "src/test/java/demo/TestRoot.java";
    private static final String PASSING_REPORT = """
            <testsuite name="demo.TestCode"><testcase name="passes" classname="demo.TestCode" time="0.002"/></testsuite>
            """;

    @TempDir
    private Path directory;

    private final AgentReviewStore reviewStore = mock(AgentReviewStore.class);
    private final AgentReviewService reviews = mock(AgentReviewService.class);
    private final AgentTurnService turns = mock(AgentTurnService.class);
    private final AgentTaskService tasks = mock(AgentTaskService.class);
    private final InMemoryLocalRunStore runs = new InMemoryLocalRunStore();
    private final IdeExecutions executions = mock(IdeExecutions.class);
    private Path checkout;
    private TaskWorktree worktree;
    private String head;
    private ManagedWorktreeService worktrees;
    private ReviewCodeService code;
    private LocalRunService service;

    @BeforeEach
    void setUp()
            throws IOException
    {
        WorkspacePaths paths = new WorkspacePaths(directory.resolve("managed").toString());
        Path repository = paths.repository(WORKSPACE_ID);
        Files.createDirectories(repository);
        Files.writeString(paths.workspace(WORKSPACE_ID).resolve(".bytequay-workspace"), WORKSPACE_ID + "\n1\n");
        git(repository, "init", "--initial-branch=main");
        git(repository, "config", "user.name", "ByteQuay Test");
        git(repository, "config", "user.email", "test@example.invalid");
        git(repository, "config", "core.hooksPath", "/dev/null");
        git(repository, "remote", "add", "origin", ORIGIN);
        write(repository, "mvnw", "#!/bin/sh\n");
        write(repository, "core/pom.xml", "<project/>\n");
        write(repository, TEST_PATH, "/* package other; */\npackage demo;\n\nclass TestCode {}\n");
        write(repository, ROOT_TEST_PATH, "package demo;\nclass TestRoot {}\n");
        write(repository, ".gitignore", "target/\n");
        Files.setPosixFilePermissions(repository.resolve("mvnw"), PosixFilePermissions.fromString("rwxr-xr-x"));
        git(repository, "add", ".");
        git(repository, "commit", "-m", "Reviewed revision");
        head = git(repository, "rev-parse", "HEAD");

        Workspace workspace = new Workspace(WORKSPACE_ID, "Local runs", 1, REPOSITORY, Optional.empty(), Optional.empty(),
                1, REPOSITORY, Workspace.Topology.DIRECT, "main", ORIGIN, Optional.empty(), Optional.empty(), repository.toString(),
                Optional.of(NOW), Optional.of(NOW), 0, NOW, NOW);
        WorkspaceStore workspaceStore = mock(WorkspaceStore.class);
        when(workspaceStore.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace));
        GitCommandRunner runner = new GitCommandRunner();
        worktrees = new ManagedWorktreeService(paths, workspaceStore, runner, mock(IdeRuntimeService.class));
        TaskId taskId = TaskId.random();
        worktree = worktrees.provision(new ProvisionWorktreeCommand(taskId, WORKSPACE_ID, RepositoryIdentity.valueOf(REPOSITORY.fullName()), head, "bytequay/task/" + taskId));
        checkout = worktree.canonicalWorktree();

        when(reviewStore.findReview(REVIEW_ID)).thenReturn(Optional.of(review(true)));
        when(reviewStore.listRuns(REVIEW_ID)).thenReturn(ImmutableList.of(reviewRun()));
        when(reviews.task(REVIEW_ID)).thenReturn(task(Optional.empty()));
        when(tasks.get(worktree.taskId())).thenReturn(developmentTask(Optional.empty()));
        code = new ReviewCodeService(reviewStore, reviews, worktrees, mock(WorkspaceRepositoryService.class), runner,
                new ReviewExperiments(runner, worktrees), tasks, turns, mock(PullRequestTaskStore.class), mock(PullRequestStore.class),
                new WorktreeDiffReader(runner), mock(PullRequestService.class), new AgentProperties(directory.resolve("runtime"), Duration.ofHours(1), "test-session",
                        "codex", "0.159.3", directory.resolve("codex-home"), "claude", directory.resolve("claude-home")), mock(ClaudeAccounts.class));
        service = new LocalRunService(reviewStore, reviews, runs, worktrees, code, executions, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void refusesWhileTheReviewOrItsWorkingCopyIsBusy()
    {
        TurnId turnId = TurnId.random();
        when(reviews.task(REVIEW_ID)).thenReturn(task(Optional.of(WorktreeMutationClaim.localRun("other-run", NOW))));
        assertBusy("A run is using this working copy");
        when(reviews.task(REVIEW_ID)).thenReturn(task(Optional.of(new WorktreeMutationClaim(WorktreeMutationClaim.Kind.COMPLETION, "update", NOW))));
        assertBusy("The working copy is being updated");
        when(reviews.task(REVIEW_ID)).thenReturn(task(Optional.of(WorktreeMutationClaim.recovery("launch-1", NOW))));
        assertBusy(LocalRunService.RECOVERY_NEEDED);
        when(reviews.task(REVIEW_ID)).thenReturn(task(Optional.of(WorktreeMutationClaim.turn(turnId, NOW))));
        when(turns.get(turnId)).thenReturn(AgentTurn.accepted(turnId, worktree.taskId(), "review", settings(), NOW));
        assertBusy("The review agent is working");

        when(reviews.task(REVIEW_ID)).thenReturn(task(Optional.empty()));
        when(turns.queued(worktree.taskId())).thenReturn(ImmutableList.of(QueuedAgentInput.queued(
                QueuedInputId.random(), worktree.taskId(), 1, "queue-1", new BlobRef("blobs/queue-1", "text/plain; charset=utf-8", 5), "later", settings(), NOW)));
        assertBusy("Remove or resume the queued question");
        when(turns.queued(worktree.taskId())).thenReturn(ImmutableList.of());

        // A running review run is idle only while its follow-up is paused.
        when(reviewStore.findReview(REVIEW_ID)).thenReturn(Optional.of(review(false)));
        when(reviewStore.listRuns(REVIEW_ID)).thenReturn(ImmutableList.of(reviewRun()));
        assertBusy("The review agent is working");

        verify(turns, never()).claimLocalRun(any(), anyString());
        assertThat(runs.list()).isEmpty();
    }

    @Test
    void startupPreservesHistoricalOutputAndMarksInterruptedRuns()
    {
        LocalRun left = runs.insert(LocalRun.started("left", worktree.taskId().toString(), WORKSPACE_ID, 1, TEST_PATH, "demo.TestCode", Optional.empty(),
                Optional.of("core"), "./mvnw test", head, ImmutableList.of(), "fingerprint", "old-launch", STARTED_AT));
        LocalRun unverified = runs.update(runs.insert(LocalRun.started("unverified", worktree.taskId().toString(), WORKSPACE_ID, 2, TEST_PATH, "demo.TestCode", Optional.empty(),
                        Optional.of("core"), "./mvnw test", head, ImmutableList.of(), "fingerprint", "older-launch", STARTED_AT))
                .finished(Status.PASSED, NOW, OptionalInt.of(0), Optional.empty(), ImmutableList.of(), false, OptionalLong.of(0))
                .withTreeUnverified(true), "done");

        service.start();

        assertThat(service.isRunning()).isTrue();
        LocalRun interrupted = runs.find(left.id()).orElseThrow();
        assertThat(interrupted.status()).isEqualTo(Status.INTERRUPTED);
        assertThat(interrupted.endedAt()).contains(NOW);
        // Startup recovery owns that launch now, through the task's recovery claim.
        assertThat(runs.find(unverified.id()).orElseThrow().treeUnverified()).isFalse();
        assertThat(service.output(code.reviewHost(REVIEW_ID), unverified.id(), 2)).isEqualTo(new LocalRunService.OutputChunk("ne", 4, 0, true));

        service.stop();
        assertThat(service.isRunning()).isFalse();
    }

    @Test
    void mirrorsACodexMavenCommandWithTheSourceItStartedOnAndItsFreshRows()
            throws IOException
    {
        String argv = "/bin/zsh -lc './mvnw -B -pl core test -Dtest=demo.TestCode'";
        String fingerprint = code.workingCopySource(checkout).fingerprint();
        Instant started = Instant.now().minusSeconds(30);
        AgentEventEnvelope start = command(ActivityState.STARTED, argv, Optional.empty(), Optional.empty(), Optional.empty(), false, "exec-1", started);
        service.recordAgentCommand(REVIEW_ID, start);
        LocalRun running = runs.list().getFirst();
        assertThat(running.status()).isEqualTo(Status.RUNNING);
        assertThat(running.testClass()).contains("demo.TestCode");
        report(PASSING_REPORT, Instant.now().minusSeconds(10));
        // A report an earlier command wrote is not this command's.
        Path stale = checkout.resolve("target/surefire-reports/TEST-demo.Older.xml");
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, TestSurefireReports.REPORT);
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minusSeconds(60)));

        AgentEventEnvelope completed = command(ActivityState.COMPLETED, argv, Optional.of("[INFO] BUILD SUCCESS"), Optional.of(0), Optional.of(20_000L), false, "exec-1",
                Instant.now());
        service.recordAgentCommand(REVIEW_ID, completed);

        LocalRun run = runs.list().getFirst();
        assertThat(runs.list()).hasSize(1);
        assertThat(run.id()).isEqualTo(running.id());
        assertThat(run.initiator()).isEqualTo(LocalRun.Initiator.AGENT);
        assertThat(run.scope()).isEqualTo(LocalRun.Scope.CLASS);
        assertThat(run.testClass()).contains("demo.TestCode");
        assertThat(run.command()).isEqualTo("./mvnw -B -pl core test -Dtest=demo.TestCode");
        assertThat(run.status()).isEqualTo(Status.PASSED);
        assertThat(run.counts()).contains(new LocalRun.Counts(1, 0, 0, 0));
        assertThat(run.exitCode()).hasValue(0);
        assertThat(run.sourceHeadSha()).contains(head);
        assertThat(run.sourceFingerprint()).contains(fingerprint);
        assertThat(run.startedAt()).isEqualTo(started);
        assertThat(run.agentEventId()).contains(start.eventId().toString());
        assertThat(run.treeUnverified()).isFalse();
        assertThat(runs.findOutput(run.id())).contains("[INFO] BUILD SUCCESS");
        verify(turns, never()).claimLocalRun(any(), anyString());
    }

    @Test
    void mirrorsAClaudeCommandFromItsStartButNeverAsPassed()
    {
        when(reviews.task(REVIEW_ID)).thenReturn(task(Optional.empty(), AgentLaneId.CLAUDE_CLI));
        String script = "cd core && ../mvnw test -Dtest=demo.TestCode | tail -40";
        service.recordAgentCommand(REVIEW_ID, command(ActivityState.STARTED, script, Optional.empty(), Optional.empty(), Optional.empty(), false, "toolu-1",
                Instant.now().minusSeconds(30)));
        report(PASSING_REPORT, Instant.now().minusSeconds(10));
        service.recordAgentCommand(REVIEW_ID, command(ActivityState.COMPLETED, script, Optional.of("Tests run: 1"), Optional.empty(), Optional.empty(), false, "toolu-1",
                Instant.now()));

        LocalRun passing = runs.list().getFirst();
        assertThat(passing.status()).isEqualTo(Status.RESULTS_UNAVAILABLE);
        assertThat(passing.counts()).contains(new LocalRun.Counts(1, 0, 0, 0));
        assertThat(passing.exitCode()).isEmpty();

        // A failed Bash call still ran, and a failed case outranks the unknown exit.
        Instant secondStart = Instant.now().minusSeconds(5);
        service.recordAgentCommand(REVIEW_ID, command(ActivityState.STARTED, script, Optional.empty(), Optional.empty(), Optional.empty(), false, "toolu-2", secondStart));
        report(TestSurefireReports.REPORT, Instant.now().minusSeconds(2));
        service.recordAgentCommand(REVIEW_ID, command(ActivityState.FAILED, script, Optional.of("FAILURE"), Optional.empty(), Optional.empty(), false, "toolu-2", Instant.now()));

        LocalRun failing = runs.list().stream().filter(run -> run.ordinal() == 2).findFirst().orElseThrow();
        assertThat(failing.status()).isEqualTo(Status.FAILED_ASSERTION);
        assertThat(failing.startedAt()).isEqualTo(secondStart);
    }

    @Test
    void skipsDeclinedBackgroundAndOtherCommandsAndReadsNoRowsWithoutAKnownStart()
    {
        String argv = "./mvnw test -Dtest=demo.TestCode";
        report(PASSING_REPORT, Instant.now().minusSeconds(1));
        service.recordAgentCommand(REVIEW_ID, command(ActivityState.FAILED, argv, Optional.empty(), Optional.empty(), Optional.empty(), false, "declined", Instant.now()));
        service.recordAgentCommand(REVIEW_ID, command(ActivityState.COMPLETED, argv, Optional.of("started"), Optional.of(0), Optional.of(5L), true, "background", Instant.now()));
        service.recordAgentCommand(REVIEW_ID, command(ActivityState.COMPLETED, "rg mvnw", Optional.of("mvnw"), Optional.of(0), Optional.of(5L), false, "search", Instant.now()));
        assertThat(runs.list()).isEmpty();

        service.recordAgentCommand(REVIEW_ID, command(ActivityState.COMPLETED, argv, Optional.of("x".repeat(10)), Optional.of(1), Optional.empty(), false, "unseen", Instant.now(),
                true));

        LocalRun run = runs.list().getFirst();
        assertThat(run.results()).isEmpty();
        assertThat(run.status()).isEqualTo(Status.BUILD_FAILED);
        assertThat(run.sourceHeadSha()).isEmpty();
        assertThat(run.outputTruncated()).isTrue();
        assertThat(run.omittedBytes()).isEmpty();
    }

    @Test
    void stopsAnAgentRunWhoseCommandNeverRanOrWhoseTurnEndedWithoutItsResult()
    {
        String argv = "./mvnw test -Dtest=demo.TestCode";
        service.recordAgentCommand(REVIEW_ID, command(ActivityState.STARTED, argv, Optional.empty(), Optional.empty(), Optional.empty(), false, "declined", NOW));
        service.recordAgentCommand(REVIEW_ID, command(ActivityState.FAILED, argv, Optional.empty(), Optional.empty(), Optional.empty(), false, "declined", NOW));
        service.recordAgentCommand(REVIEW_ID, command(ActivityState.STARTED, argv, Optional.empty(), Optional.empty(), Optional.empty(), false, "cut-short", NOW));
        assertThat(runs.list()).extracting(LocalRun::status).containsExactlyInAnyOrder(Status.RUNNING, Status.STOPPED);

        service.settleAgentRuns(REVIEW_ID);
        assertThat(runs.list()).allSatisfy(run -> {
            assertThat(run.status()).isEqualTo(Status.STOPPED);
            assertThat(run.endedAt()).isPresent();
        });
    }

    @Test
    void theObserverOnlyForwardsForegroundMavenCommandsThatStartOrEnd()
    {
        assertThat(ReviewTaskObserver.mirrorsAgentCommand(command(ActivityState.STARTED, "./mvnw test", Optional.empty(), Optional.empty(), Optional.empty(), false, "a", NOW)))
                .isTrue();
        assertThat(ReviewTaskObserver.mirrorsAgentCommand(command(ActivityState.PROGRESS, "./mvnw test", Optional.empty(), Optional.empty(), Optional.empty(), false, "a", NOW)))
                .isFalse();
        assertThat(ReviewTaskObserver.mirrorsAgentCommand(command(ActivityState.COMPLETED, "./mvnw test", Optional.empty(), Optional.of(0), Optional.empty(), true, "a", NOW)))
                .isFalse();
        assertThat(ReviewTaskObserver.mirrorsAgentCommand(command(ActivityState.COMPLETED, "npm test", Optional.empty(), Optional.of(0), Optional.empty(), false, "a", NOW)))
                .isFalse();
    }

    private AgentEventEnvelope command(ActivityState state, String argv, Optional<String> output, Optional<Integer> exitCode, Optional<Long> durationMs, boolean background,
            String itemId, Instant occurredAt)
    {
        return command(state, argv, output, exitCode, durationMs, background, itemId, occurredAt, false);
    }

    private AgentEventEnvelope command(ActivityState state, String argv, Optional<String> output, Optional<Integer> exitCode, Optional<Long> durationMs, boolean background,
            String itemId, Instant occurredAt, boolean truncated)
    {
        AgentEventSource source = new AgentEventSource(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(itemId), Optional.empty());
        return new AgentEventEnvelope(AgentEventEnvelope.CURRENT_SCHEMA_VERSION, EventId.random(), 1, worktree.taskId(), Optional.empty(), source, occurredAt,
                new AgentEventPayload.CommandActivity(
                        state,
                        ImmutableList.of(argv),
                        output,
                        exitCode,
                        truncated,
                        Optional.empty(),
                        durationMs,
                        ImmutableList.of(),
                        background,
                        Optional.empty()));
    }

    @Test
    void historyReadsAndStopsStayBoundToTheOwningTask()
    {
        LocalRun run = runs.insert(LocalRun.started("live", worktree.taskId().toString(), WORKSPACE_ID, 1, TEST_PATH, "demo.TestCode", Optional.empty(),
                Optional.of("core"), "retained run", head, ImmutableList.of(), "fingerprint", "launch", STARTED_AT));
        LocalRunService.OutputChunk chunk = new LocalRunService.OutputChunk("live output", 11, 0, false);
        when(executions.output(run, 0)).thenReturn(Optional.of(chunk));
        when(executions.stop(run)).thenReturn(run);
        assertThat(service.output(taskHost(), run.id(), 0)).isEqualTo(chunk);
        assertThat(service.stop(code.reviewHost(REVIEW_ID), run.id())).isEqualTo(run);
        verify(executions).stop(run);
        assertThat(service.list(taskHost())).containsExactly(run);
        assertThatThrownBy(() -> service.output(taskHost(), run.id(), -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.stop(new TaskCodeHost("other", worktree.taskId().toString()), run.id())).isInstanceOf(ReviewNotFoundException.class);
    }

    private void assertBusy(String reason)
    {
        assertThatThrownBy(() -> LocalRunService.requireIdle(turns, reviewStore.listRuns(REVIEW_ID), reviewStore.findReview(REVIEW_ID).orElseThrow(), reviews.task(REVIEW_ID)))
                .isInstanceOf(ReviewConflictException.class).hasMessage(reason);
    }

    private TaskCodeHost taskHost()
    {
        return new TaskCodeHost(WORKSPACE_ID, worktree.taskId().toString());
    }

    private AgentTask developmentTask(Optional<WorktreeMutationClaim> claim)
    {
        AgentTask task = AgentTask.created(worktree.taskId(), true, Optional.empty(), "Develop", TitleSource.USER, AgentLaneId.CODEX_CLI, worktree,
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), NOW);
        return claim.map(held -> task.claimWorktreeMutation(held, NOW)).orElse(task);
    }

    private void report(String content, Instant modifiedAt)
    {
        try {
            Path report = checkout.resolve("core/target/surefire-reports/TEST-demo.TestCode.xml");
            Files.createDirectories(report.getParent());
            Files.writeString(report, content);
            Files.setLastModifiedTime(report, FileTime.from(modifiedAt));
        }
        catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private AgentTask task(Optional<WorktreeMutationClaim> claim)
    {
        return task(claim, AgentLaneId.CODEX_CLI);
    }

    private AgentTask task(Optional<WorktreeMutationClaim> claim, AgentLaneId lane)
    {
        AgentTask task = AgentTask.created(worktree.taskId(), false, Optional.empty(), "Review", TitleSource.USER, lane, worktree,
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), NOW);
        return claim.map(held -> task.claimWorktreeMutation(held, NOW)).orElse(task);
    }

    private AgentReview review(boolean followUpPaused)
    {
        return pullRequestReview(REVIEW_ID, WORKSPACE_ID, REPOSITORY.fullName(), 7, worktree.taskId().toString(), NOW)
                .withFollowUpPaused(followUpPaused, NOW);
    }

    private ReviewRun reviewRun()
    {
        return new ReviewRun("run-" + head, REVIEW_ID, 1, ReviewRun.Scope.FULL, "main", head, head, head, Optional.empty(), Optional.empty(), ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of(), ReviewRun.State.RUNNING, Optional.empty(), Optional.empty(), Optional.empty(), ImmutableList.of(), false,
                ImmutableMap.of(), ImmutableMap.of(), Optional.empty(), 0, NOW, NOW, Optional.empty());
    }

    private static AgentTurnSettings settings()
    {
        return new AgentTurnSettings(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO, Duration.ofMinutes(5), ImmutableList.of(), Optional.empty(), Optional.empty());
    }

    /** Keeps rows as given: the service always writes the version it last read, which this store never advances. */
    private static final class InMemoryLocalRunStore
            implements LocalRunStore
    {
        private final Map<String, LocalRun> rows = new LinkedHashMap<>();
        private final Map<String, String> outputs = new LinkedHashMap<>();

        @Override
        public synchronized LocalRun insert(LocalRun run)
        {
            if (rows.putIfAbsent(run.id(), run) != null) {
                throw new ReviewConflictException("Local run already exists: " + run.id());
            }
            return run;
        }

        @Override
        public synchronized Optional<LocalRun> find(String runId)
        {
            return Optional.ofNullable(rows.get(runId));
        }

        @Override
        public synchronized List<LocalRun> listByTask(String taskId, int limit)
        {
            return rows.values().stream()
                    .filter(run -> run.taskId().equals(taskId))
                    .sorted(Comparator.comparingInt(LocalRun::ordinal).reversed())
                    .limit(limit)
                    .collect(toImmutableList());
        }

        @Override
        public synchronized LocalRun update(LocalRun run)
        {
            if (rows.replace(run.id(), run) == null) {
                throw new ReviewConflictException("Local run not found: " + run.id());
            }
            return run;
        }

        @Override
        public synchronized LocalRun update(LocalRun run, String output)
        {
            LocalRun updated = update(run);
            outputs.put(run.id(), output);
            return updated;
        }

        @Override
        public synchronized Optional<String> findOutput(String runId)
        {
            return Optional.ofNullable(outputs.get(runId));
        }

        @Override
        public synchronized List<LocalRun> listUnsettled()
        {
            return rows.values().stream().filter(run -> run.status() == Status.RUNNING || run.treeUnverified()).collect(toImmutableList());
        }

        private synchronized List<LocalRun> list()
        {
            return ImmutableList.copyOf(rows.values());
        }
    }
}

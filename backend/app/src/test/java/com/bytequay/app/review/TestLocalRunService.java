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

import com.bytequay.agent.cli.process.ManagedProcess;
import com.bytequay.agent.cli.process.OwnedProcessRecovery;
import com.bytequay.agent.cli.process.PreparedProcess;
import com.bytequay.agent.cli.process.ProcessExit;
import com.bytequay.agent.cli.process.ProcessOutputSink;
import com.bytequay.agent.cli.process.ProcessOwner;
import com.bytequay.agent.cli.process.ProcessSpec;
import com.bytequay.agent.cli.process.ProcessStream;
import com.bytequay.agent.cli.process.ProcessSupervisor;
import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventSource;
import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.AgentResourceService;
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
import com.bytequay.agent.core.TaskSummary;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.agent.spi.ProviderAgentEvent.ActivityState;
import com.bytequay.app.agent.TaskPullRequestOwnership;
import com.bytequay.app.agent.codegraph.FakeCodeGraphService;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.git.ReviewExperiments;
import com.bytequay.app.service.pr.PullRequestService;
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
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static com.bytequay.app.git.GitTestSupport.git;
import static com.bytequay.app.git.GitTestSupport.write;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
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
    private static final String SKIPPED_REPORT = """
            <testsuite name="demo.TestCode"><testcase name="later" classname="demo.TestCode"><skipped/></testcase></testsuite>
            """;

    @TempDir
    private Path directory;

    private final AgentReviewStore reviewStore = mock(AgentReviewStore.class);
    private final AgentReviewService reviews = mock(AgentReviewService.class);
    private final AgentTurnService turns = mock(AgentTurnService.class);
    private final AgentTaskService tasks = mock(AgentTaskService.class);
    private final TaskPullRequestOwnership pullRequestOwnership = mock(TaskPullRequestOwnership.class);
    private final InMemoryLocalRunStore runs = new InMemoryLocalRunStore();
    private final FakeSupervisor supervisor = new FakeSupervisor();
    private Path checkout;
    private Path jdk;
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
        worktrees = new ManagedWorktreeService(paths, workspaceStore, runner);
        TaskId taskId = TaskId.random();
        worktree = worktrees.provision(new ProvisionWorktreeCommand(taskId, WORKSPACE_ID, RepositoryIdentity.valueOf(REPOSITORY.fullName()), head, "bytequay/task/" + taskId));
        checkout = worktree.canonicalWorktree();
        jdk = directory.resolve("jdk");
        script(jdk.resolve("bin/java"), "#!/bin/sh\n");

        when(reviewStore.findReview(REVIEW_ID)).thenReturn(Optional.of(review(true)));
        when(reviewStore.listRuns(REVIEW_ID)).thenReturn(ImmutableList.of(reviewRun()));
        when(reviews.task(REVIEW_ID)).thenReturn(task(Optional.empty()));
        when(tasks.getInteractive(WORKSPACE_ID, worktree.taskId())).thenReturn(developmentTask(Optional.empty()));
        when(pullRequestOwnership.owns(any(), eq(REPOSITORY.fullName()), eq(7), eq(false))).thenReturn(true);
        code = new ReviewCodeService(reviewStore, reviews, worktrees, mock(WorkspaceRepositoryService.class), runner, new FakeCodeGraphService(), mock(AgentResourceService.class),
                new ReviewExperiments(runner, worktrees), tasks, turns, pullRequestOwnership, mock(PullRequestService.class));
        service = service(ImmutableMap.of("JAVA_HOME", jdk.toString(), "PATH", "/usr/bin:/bin", "BYTEQUAY_SESSION_TOKEN", "secret"));
    }

    @Test
    void refusesARunTheWorkingCopyOrLoginEnvironmentCannotStart()
            throws IOException
    {
        assertThatThrownBy(() -> service.start(REVIEW_ID, "core/src/main/java/demo/Code.java", Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a Surefire test source");
        assertThatThrownBy(() -> service.start(REVIEW_ID, "core/src/test/java/demo/Helper.java", Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a Surefire test source");
        assertThatThrownBy(() -> service.start(REVIEW_ID, TEST_PATH, Optional.of("fails; rm")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a Java identifier");
        assertThatThrownBy(() -> service.start(REVIEW_ID, "../" + TEST_PATH, Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        // Surefire reads commas and wildcards in -Dtest as patterns that select other tests.
        assertThatThrownBy(() -> service.start(REVIEW_ID, "core/src/test/java/demo/Test,demo.Other*.java", Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a Surefire test source");

        assertRefused(ROOT_TEST_PATH, "No Maven module for this test", false);
        assertRefused(TEST_PATH, "No Java runtime in your login shell environment", true,
                service(ImmutableMap.of("PATH", "/usr/bin:/bin")));
        Files.delete(checkout.resolve("mvnw"));
        assertRefused(TEST_PATH, "No Maven wrapper (mvnw) in this working copy", true);

        verify(turns, never()).claimLocalRun(any(), anyString());
        assertThat(runs.list()).isEmpty();
        assertThat(supervisor.specs).isEmpty();
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
    void aDevelopmentTaskRunsUnderItsClaimAndListsItsRunsPerTask()
    {
        runs.insert(LocalRun.started("other-task", TaskId.random().toString(), WORKSPACE_ID, 5, TEST_PATH, "demo.TestCode", Optional.empty(), Optional.of("core"),
                "./mvnw test", head, ImmutableList.of(), "fingerprint", "other-launch", STARTED_AT));

        LocalRun run = service.start(taskHost(), TEST_PATH, Optional.empty());

        verify(turns).claimLocalRun(worktree.taskId(), run.id());
        assertThat(run.ordinal()).isEqualTo(1);
        assertThat(run.taskId()).isEqualTo(worktree.taskId().toString());
        assertThat(run.workspaceId()).isEqualTo(WORKSPACE_ID);
        assertThat(supervisor.specs.getFirst().owner()).isEqualTo(new ProcessOwner(ProcessOwner.Kind.LOCAL_RUN, worktree.taskId() + "/" + run.id()));
        assertThat(service.list(taskHost())).containsExactly(run);
        assertThat(service.get(taskHost(), run.id())).isEqualTo(run);
        assertThatThrownBy(() -> service.get(taskHost(), "other-task")).isInstanceOf(ReviewNotFoundException.class);
        // A review's runs are the runs of the task it owns.
        assertThat(service.list(REVIEW_ID)).containsExactly(run);

        supervisor.process().sink.onOutput(ProcessStream.STDOUT, ByteBuffer.wrap("partial".getBytes(UTF_8)));
        assertThat(service.output(taskHost(), run.id(), 0)).isEqualTo(new LocalRunService.OutputChunk("partial", 7, 0, false));
        assertThat(service.stop(taskHost(), run.id()).status()).isEqualTo(Status.STOPPED);
        verify(turns).releaseLocalRun(worktree.taskId(), run.id());
        assertThat(service.start(taskHost(), TEST_PATH, Optional.empty()).ordinal()).isEqualTo(2);
    }

    @Test
    void aDevelopmentTaskRefusesRunsWhileBusyEndedOrNoLongerOwningThePullRequest()
    {
        TurnId turnId = TurnId.random();
        when(tasks.getInteractive(WORKSPACE_ID, worktree.taskId())).thenReturn(developmentTask(Optional.of(WorktreeMutationClaim.turn(turnId, NOW))));
        when(turns.get(turnId)).thenReturn(AgentTurn.accepted(turnId, worktree.taskId(), "develop", settings(), NOW));
        assertTaskBusy("The task's agent is working");
        when(tasks.getInteractive(WORKSPACE_ID, worktree.taskId())).thenReturn(developmentTask(Optional.of(WorktreeMutationClaim.recovery("launch-1", NOW))));
        assertTaskBusy(LocalRunService.TASK_RECOVERY_NEEDED);
        when(tasks.getInteractive(WORKSPACE_ID, worktree.taskId())).thenReturn(developmentTask(Optional.empty()).withDevelopmentContinuation(true, NOW));
        assertTaskBusy("Development automation is working");
        AgentTask completed = developmentTask(Optional.empty()).complete(new TaskSummary("done", Optional.empty(), AgentMetrics.empty(), Optional.empty()), NOW, NOW.plusSeconds(60));
        when(tasks.getInteractive(WORKSPACE_ID, worktree.taskId())).thenReturn(completed);
        assertTaskBusy("The task has ended");
        when(tasks.getInteractive(WORKSPACE_ID, worktree.taskId())).thenReturn(completed.withWorktreeRemovedAt(NOW));
        assertTaskBusy("The task's worktree was removed");
        assertThatThrownBy(() -> service.list(taskHost())).isInstanceOf(ReviewConflictException.class).hasMessage("The task's worktree was removed");

        when(tasks.getInteractive(WORKSPACE_ID, worktree.taskId())).thenReturn(developmentTask(Optional.empty()));
        when(pullRequestOwnership.owns(any(), eq(REPOSITORY.fullName()), eq(7), eq(false))).thenReturn(false);
        assertThatThrownBy(() -> service.start(taskHost(), TEST_PATH, Optional.empty())).isInstanceOf(ReviewNotFoundException.class);
        assertThatThrownBy(() -> service.list(taskHost())).isInstanceOf(ReviewNotFoundException.class);

        verify(turns, never()).claimLocalRun(any(), anyString());
        assertThat(runs.list()).isEmpty();
    }

    @Test
    void startsARunningRecordUnderTheClaimAndLaunchesTheWrapperWithTheLoginEnvironment()
            throws IOException
    {
        write(checkout, "core/src/test/java/demo/TestLocal.java", "class TestLocal {}\n");

        LocalRun run = service.start(REVIEW_ID, TEST_PATH, Optional.of("fails"));

        InOrder order = inOrder(turns);
        order.verify(turns).claimLocalRun(worktree.taskId(), run.id());
        verify(turns, never()).releaseLocalRun(any(), anyString());
        assertThat(run.status()).isEqualTo(Status.RUNNING);
        assertThat(run.ordinal()).isEqualTo(1);
        assertThat(run.scope()).isEqualTo(LocalRun.Scope.METHOD);
        assertThat(run.testClass()).contains("demo.TestCode");
        assertThat(run.testMethod()).contains("fails");
        assertThat(run.module()).contains("core");
        assertThat(run.command()).isEqualTo("./mvnw -B -pl core -Dtest=demo.TestCode#fails -Dsurefire.failIfNoSpecifiedTests=false test");
        assertThat(run.sourceHeadSha()).contains(head);
        assertThat(run.localPaths()).containsExactly("core/src/test/java/demo/TestLocal.java");
        assertThat(run.sourceFingerprint()).contains(code.workingCopySource(checkout).fingerprint());
        assertThat(run.startedAt()).isEqualTo(STARTED_AT);
        assertThat(run.launchId()).contains("launch-1");
        assertThat(runs.find(run.id())).contains(run);

        ProcessSpec spec = supervisor.specs.getFirst();
        assertThat(spec.owner()).isEqualTo(new ProcessOwner(ProcessOwner.Kind.LOCAL_RUN, worktree.taskId() + "/" + run.id()));
        assertThat(spec.argv()).containsExactly("/bin/bash", "-c", "\"$0\" \"$@\"; exit $?", checkout.resolve("mvnw").toString(),
                "-B", "-pl", "core", "-Dtest=demo.TestCode#fails", "-Dsurefire.failIfNoSpecifiedTests=false", "test");
        assertThat(spec.workingDirectory()).isEqualTo(checkout);
        assertThat(spec.environment()).containsEntry("JAVA_HOME", jdk.toString()).containsEntry("LANG", "en_US.UTF-8");
        assertThat(spec.environment().keySet()).noneMatch(name -> name.startsWith("BYTEQUAY_"));
        assertThat(spec.stdoutCaptureLimit()).isEmpty();
        assertThat(spec.maximumStderrBytes()).isEqualTo(64 * 1024);
        assertThat(spec.maximumRuntime()).isEqualTo(Duration.ofMinutes(60));
        assertThat(spec.cancellationGrace()).isEqualTo(Duration.ofSeconds(10));

        supervisor.process().sink.onOutput(ProcessStream.STDOUT, ByteBuffer.wrap("[INFO] Scanning\n".getBytes(UTF_8)));
        assertThat(service.output(REVIEW_ID, run.id(), 0)).isEqualTo(new LocalRunService.OutputChunk("[INFO] Scanning\n", 16, 0, false));

        // A root-module project runs without -pl.
        write(checkout, "pom.xml", "<project/>\n");
        supervisor.process().complete(0, ProcessExit.Reason.EXITED, ProcessExit.TreeTermination.VERIFIED_STOPPED);
        awaitFinished(run.id());
        LocalRun rootRun = service.start(REVIEW_ID, ROOT_TEST_PATH, Optional.empty());
        assertThat(rootRun.ordinal()).isEqualTo(2);
        assertThat(rootRun.module()).isEmpty();
        assertThat(rootRun.command()).isEqualTo("./mvnw -B -Dtest=demo.TestRoot -Dsurefire.failIfNoSpecifiedTests=false test");
    }

    @ParameterizedTest
    @EnumSource(Finish.class)
    void derivesTheStatusFromFreshReportsAndTheExit(Finish finish)
            throws IOException
    {
        LocalRun run = service.start(REVIEW_ID, TEST_PATH, Optional.empty());
        finish.report.ifPresent(report -> report(report, finish.fresh ? Instant.now() : STARTED_AT.minusMillis(1)));

        supervisor.process().complete(finish.exitCode, finish.reason, ProcessExit.TreeTermination.VERIFIED_STOPPED);

        LocalRun finished = awaitFinished(run.id());
        assertThat(finished.status()).isEqualTo(finish.status);
        assertThat(finished.counts()).isEqualTo(finish.counts);
        assertThat(finished.exitCode()).hasValue(finish.exitCode);
        assertThat(finished.treeUnverified()).isFalse();
        InOrder order = inOrder(turns);
        order.verify(turns, timeout(5_000)).releaseLocalRun(worktree.taskId(), run.id());
        assertThat(supervisor.acknowledged).containsExactly("launch-1");
    }

    @Test
    void keepsTheClaimForAnUnverifiableTreeUntilStopVerifiesIt()
    {
        LocalRun run = service.start(REVIEW_ID, TEST_PATH, Optional.empty());
        report(PASSING_REPORT, Instant.now());
        FakeProcess process = supervisor.process();

        process.complete(0, ProcessExit.Reason.EXITED, ProcessExit.TreeTermination.UNVERIFIABLE);

        LocalRun finished = awaitFinished(run.id());
        assertThat(finished.status()).isEqualTo(Status.PASSED);
        assertThat(finished.treeUnverified()).isTrue();
        verify(turns, never()).releaseLocalRun(any(), anyString());
        assertThat(supervisor.acknowledged).isEmpty();

        process.stopResult = ProcessExit.TreeTermination.UNVERIFIABLE;
        assertThat(service.stop(REVIEW_ID, run.id()).treeUnverified()).isTrue();
        verify(turns, never()).releaseLocalRun(any(), anyString());

        process.stopResult = ProcessExit.TreeTermination.VERIFIED_STOPPED;
        LocalRun settled = service.stop(REVIEW_ID, run.id());

        assertThat(settled.treeUnverified()).isFalse();
        assertThat(settled.status()).isEqualTo(Status.PASSED);
        assertThat(runs.find(run.id()).orElseThrow().treeUnverified()).isFalse();
        InOrder order = inOrder(turns);
        order.verify(turns).releaseLocalRun(worktree.taskId(), run.id());
        assertThat(supervisor.acknowledged).containsExactly("launch-1");
    }

    @Test
    void stopRecordsAStoppedRunWithItsPartialOutputAndNoAggregate()
    {
        LocalRun run = service.start(REVIEW_ID, TEST_PATH, Optional.empty());
        report(PASSING_REPORT, Instant.now());
        supervisor.process().sink.onOutput(ProcessStream.STDOUT, ByteBuffer.wrap("partial".getBytes(UTF_8)));

        LocalRun stopped = service.stop(REVIEW_ID, run.id());

        assertThat(stopped.status()).isEqualTo(Status.STOPPED);
        assertThat(stopped.counts()).isEmpty();
        assertThat(stopped.results()).isEmpty();
        assertThat(runs.findOutput(run.id())).contains("partial");
        assertThat(stopped.omittedBytes()).hasValue(0);
        verify(turns).releaseLocalRun(worktree.taskId(), run.id());
        assertThat(service.output(REVIEW_ID, run.id(), 0)).isEqualTo(new LocalRunService.OutputChunk("partial", 7, 0, true));
        // Stopping a settled run changes nothing.
        assertThat(service.stop(REVIEW_ID, run.id())).isEqualTo(stopped);
    }

    @Test
    void aFailedStartKeepsTheClaimForStartupRecoveryAndAFailureBeforeItReleasesIt()
    {
        supervisor.startFailure = Optional.of(new AgentUnavailableException("Gated launcher did not report its identity"));

        LocalRun run = service.start(REVIEW_ID, TEST_PATH, Optional.empty());

        assertThat(run.status()).isEqualTo(Status.RESULTS_UNAVAILABLE);
        assertThat(run.treeUnverified()).isTrue();
        String output = "Cannot start the run: Gated launcher did not report its identity\nRestart ByteQuay to check its processes and free the working copy.";
        assertThat(runs.findOutput(run.id())).contains(output);
        assertThat(runs.find(run.id())).contains(run);
        verify(turns, never()).releaseLocalRun(any(), anyString());
        assertThat(service.output(REVIEW_ID, run.id(), 22)).isEqualTo(new LocalRunService.OutputChunk(output.substring(22), output.length(), 0, true));

        supervisor.startFailure = Optional.empty();
        supervisor.prepareFailure = Optional.of(new AgentUnavailableException("Launch marker could not be written"));
        assertThatThrownBy(() -> service.start(REVIEW_ID, TEST_PATH, Optional.empty())).hasMessage("Launch marker could not be written");
        verify(turns).releaseLocalRun(eq(worktree.taskId()), anyString());
    }

    @Test
    void aFailedFinalSaveLeavesTheRunStoppableAndStopRecordsItAndReleasesTheClaim()
    {
        LocalRun run = service.start(REVIEW_ID, TEST_PATH, Optional.empty());
        report(PASSING_REPORT, Instant.now());
        supervisor.process().sink.onOutput(ProcessStream.STDOUT, ByteBuffer.wrap("done".getBytes(UTF_8)));
        // The final save and the unverified mark both fail, as when the one database connection times out.
        runs.failingUpdates = 2;

        supervisor.process().complete(0, ProcessExit.Reason.EXITED, ProcessExit.TreeTermination.VERIFIED_STOPPED);

        awaitOutputComplete(run.id());
        assertThat(runs.find(run.id()).orElseThrow().status()).isEqualTo(Status.RUNNING);
        verify(turns, never()).releaseLocalRun(any(), anyString());

        LocalRun settled = service.stop(REVIEW_ID, run.id());

        assertThat(settled.status()).isEqualTo(Status.PASSED);
        assertThat(settled.treeUnverified()).isFalse();
        assertThat(runs.find(run.id())).contains(settled);
        assertThat(runs.findOutput(run.id())).contains("done");
        verify(turns).releaseLocalRun(worktree.taskId(), run.id());
        assertThat(supervisor.acknowledged).containsExactly("launch-1");
    }

    @Test
    void aFailedReleaseShowsTheRunUnverifiedUntilStopReleasesTheClaim()
    {
        LocalRun run = service.start(REVIEW_ID, TEST_PATH, Optional.empty());
        report(PASSING_REPORT, Instant.now());
        AtomicInteger releases = new AtomicInteger();
        doAnswer(invocation -> {
            if (releases.incrementAndGet() == 1) {
                throw new ReviewConflictException("Task version changed");
            }
            return null;
        }).when(turns).releaseLocalRun(worktree.taskId(), run.id());

        supervisor.process().complete(0, ProcessExit.Reason.EXITED, ProcessExit.TreeTermination.VERIFIED_STOPPED);

        awaitOutputComplete(run.id());
        LocalRun stored = runs.find(run.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(Status.PASSED);
        // Unverified keeps Stop available in the tab.
        assertThat(stored.treeUnverified()).isTrue();

        LocalRun settled = service.stop(REVIEW_ID, run.id());

        assertThat(settled.treeUnverified()).isFalse();
        assertThat(runs.find(run.id())).contains(settled);
        assertThat(releases).hasValue(2);
    }

    @Test
    void startupMarksRunsTheLastBackendLeftAsInterruptedAndShutdownStopsLiveRuns()
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
        assertThat(service.output(REVIEW_ID, unverified.id(), 2)).isEqualTo(new LocalRunService.OutputChunk("ne", 4, 0, true));

        LocalRun live = service.start(REVIEW_ID, TEST_PATH, Optional.empty());
        service.stop();

        assertThat(service.isRunning()).isFalse();
        assertThat(runs.find(live.id()).orElseThrow().status()).isEqualTo(Status.STOPPED);
        verify(turns).releaseLocalRun(eq(worktree.taskId()), eq(live.id()));
        assertThatThrownBy(() -> service.start(REVIEW_ID, TEST_PATH, Optional.empty())).isInstanceOf(ReviewConflictException.class).hasMessage("ByteQuay is shutting down");
    }

    @Test
    void mirrorsACodexMavenCommandWithTheSourceItStartedOnAndItsFreshRows()
            throws IOException
    {
        String argv = "/bin/zsh -lc './mvnw -B -pl core test -Dtest=demo.TestCode'";
        String fingerprint = code.workingCopySource(checkout).fingerprint();
        Instant started = Instant.now().minusSeconds(30);
        service.recordAgentCommand(REVIEW_ID, command(ActivityState.STARTED, argv, Optional.empty(), Optional.empty(), Optional.empty(), false, "exec-1", started));
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
        assertThat(run.initiator()).isEqualTo(LocalRun.Initiator.AGENT);
        assertThat(run.scope()).isEqualTo(LocalRun.Scope.CLASS);
        assertThat(run.testClass()).contains("demo.TestCode");
        assertThat(run.command()).isEqualTo("./mvnw -B -pl core test -Dtest=demo.TestCode");
        assertThat(run.status()).isEqualTo(Status.PASSED);
        assertThat(run.counts()).contains(new LocalRun.Counts(1, 0, 0, 0));
        assertThat(run.exitCode()).hasValue(0);
        assertThat(run.sourceHeadSha()).contains(head);
        assertThat(run.sourceFingerprint()).contains(fingerprint);
        assertThat(run.startedAt()).isEqualTo(completed.occurredAt().minusMillis(20_000));
        assertThat(run.agentEventId()).contains(completed.eventId().toString());
        assertThat(run.treeUnverified()).isFalse();
        assertThat(runs.findOutput(run.id())).contains("[INFO] BUILD SUCCESS");
        verify(turns, never()).claimLocalRun(any(), anyString());
        assertThat(supervisor.specs).isEmpty();
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
        AgentEventSource source = new AgentEventSource(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(itemId), Optional.empty(), Optional.empty());
        return new AgentEventEnvelope(AgentEventEnvelope.CURRENT_SCHEMA_VERSION, EventId.random(), 1, worktree.taskId(), Optional.empty(), source, occurredAt,
                new AgentEventPayload.CommandActivity(state, ImmutableList.of(argv), output, exitCode, truncated, Optional.empty(), durationMs, ImmutableList.of(), background, Optional.empty()));
    }

    private enum Finish
    {
        PASSED(Optional.of(PASSING_REPORT), true, 0, ProcessExit.Reason.EXITED, Status.PASSED, Optional.of(new LocalRun.Counts(1, 0, 0, 0))),
        FAILED(Optional.of(TestSurefireReports.REPORT), true, 1, ProcessExit.Reason.EXITED, Status.FAILED_ASSERTION, Optional.of(new LocalRun.Counts(4, 1, 1, 1))),
        PASSED_BUT_EXIT_FAILED(Optional.of(PASSING_REPORT), true, 1, ProcessExit.Reason.EXITED, Status.BUILD_FAILED, Optional.of(new LocalRun.Counts(1, 0, 0, 0))),
        NO_REPORT_EXIT_FAILED(Optional.empty(), true, 1, ProcessExit.Reason.EXITED, Status.BUILD_FAILED, Optional.empty()),
        STALE_REPORT(Optional.of(PASSING_REPORT), false, 0, ProcessExit.Reason.EXITED, Status.NO_TESTS_RAN, Optional.empty()),
        ONLY_SKIPPED(Optional.of(SKIPPED_REPORT), true, 0, ProcessExit.Reason.EXITED, Status.NO_TESTS_RAN, Optional.of(new LocalRun.Counts(1, 0, 0, 1))),
        MALFORMED_REPORT(Optional.of("<testsuite"), true, 0, ProcessExit.Reason.EXITED, Status.RESULTS_UNAVAILABLE, Optional.empty()),
        TIMED_OUT(Optional.of(PASSING_REPORT), true, 143, ProcessExit.Reason.TIMED_OUT, Status.TIMED_OUT, Optional.empty());

        private final Optional<String> report;
        private final boolean fresh;
        private final int exitCode;
        private final ProcessExit.Reason reason;
        private final Status status;
        private final Optional<LocalRun.Counts> counts;

        Finish(Optional<String> report, boolean fresh, int exitCode, ProcessExit.Reason reason, Status status, Optional<LocalRun.Counts> counts)
        {
            this.report = report;
            this.fresh = fresh;
            this.exitCode = exitCode;
            this.reason = reason;
            this.status = status;
            this.counts = counts;
        }
    }

    private LocalRunService service(Map<String, String> backendEnvironment)
    {
        try {
            Map<String, String> shellEnvironment = ImmutableMap.<String, String>builder()
                    .putAll(backendEnvironment)
                    .put("SHELL", script(directory.resolve("shell-" + backendEnvironment.hashCode()), "#!/bin/sh\nexec /usr/bin/env -0\n").toString())
                    .buildOrThrow();
            LoginShellEnvironment login = new LoginShellEnvironment(shellEnvironment, directory.resolve("no-java-home"));
            return new LocalRunService(reviewStore, reviews, turns, runs, supervisor, login, worktrees, code, new ReviewLocks(), Clock.fixed(NOW, ZoneOffset.UTC));
        }
        catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private void assertRefused(String path, String reason, boolean openTerminal)
    {
        assertRefused(path, reason, openTerminal, service);
    }

    private static void assertRefused(String path, String reason, boolean openTerminal, LocalRunService service)
    {
        assertThatThrownBy(() -> service.start(REVIEW_ID, path, Optional.empty()))
                .isInstanceOfSatisfying(LocalRunRefusedException.class, refusal -> assertThat(refusal.openTerminal()).isEqualTo(openTerminal))
                .hasMessage(reason);
    }

    private void assertBusy(String reason)
    {
        assertThatThrownBy(() -> service.start(REVIEW_ID, TEST_PATH, Optional.empty())).isInstanceOf(ReviewConflictException.class).hasMessage(reason);
    }

    private void assertTaskBusy(String reason)
    {
        assertThatThrownBy(() -> service.start(taskHost(), TEST_PATH, Optional.empty())).isInstanceOf(ReviewConflictException.class).hasMessage(reason);
    }

    private TaskCodeHost taskHost()
    {
        return new TaskCodeHost(WORKSPACE_ID, worktree.taskId().toString(), REPOSITORY.fullName(), 7);
    }

    private AgentTask developmentTask(Optional<WorktreeMutationClaim> claim)
    {
        AgentTask task = AgentTask.created(worktree.taskId(), true, Optional.empty(), "Develop", TitleSource.USER, AgentLaneId.CODEX_CLI, worktree,
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), false, NOW);
        return claim.map(held -> task.claimWorktreeMutation(held, NOW)).orElse(task);
    }

    private void awaitOutputComplete(String runId)
    {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!service.output(REVIEW_ID, runId, 0).complete()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Run output did not complete: " + runId);
            }
            Thread.onSpinWait();
        }
    }

    private LocalRun awaitFinished(String runId)
    {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            LocalRun run = runs.find(runId).orElseThrow();
            if (run.status() != Status.RUNNING) {
                return run;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("Run did not finish: " + runId);
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

    private static Path script(Path file, String content)
            throws IOException
    {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
        return file;
    }

    private AgentTask task(Optional<WorktreeMutationClaim> claim)
    {
        return task(claim, AgentLaneId.CODEX_CLI);
    }

    private AgentTask task(Optional<WorktreeMutationClaim> claim, AgentLaneId lane)
    {
        AgentTask task = AgentTask.created(worktree.taskId(), false, Optional.empty(), "Review", TitleSource.USER, lane, worktree,
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), false, NOW);
        return claim.map(held -> task.claimWorktreeMutation(held, NOW)).orElse(task);
    }

    private AgentReview review(boolean followUpPaused)
    {
        return new AgentReview(REVIEW_ID, WORKSPACE_ID, REPOSITORY.fullName(), 7, OptionalLong.empty(), worktree.taskId().toString(), AgentReview.State.OPEN, followUpPaused,
                Optional.empty(), Optional.empty(), 0, NOW, NOW, Optional.empty());
    }

    private ReviewRun reviewRun()
    {
        return new ReviewRun("run-" + head, REVIEW_ID, 1, ReviewRun.Scope.FULL, "main", head, head, head, Optional.empty(), ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of(), ReviewRun.State.RUNNING, Optional.empty(), Optional.empty(), Optional.empty(), ImmutableList.of(), false,
                ImmutableMap.of(), ImmutableMap.of(), Optional.empty(), 0, NOW, NOW, Optional.empty());
    }

    private static AgentTurnSettings settings()
    {
        return new AgentTurnSettings(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO, Duration.ofMinutes(5), ImmutableList.of());
    }

    /** Keeps rows as given: the service always writes the version it last read, which this store never advances. */
    private static final class InMemoryLocalRunStore
            implements LocalRunStore
    {
        private final Map<String, LocalRun> rows = new LinkedHashMap<>();
        private final Map<String, String> outputs = new LinkedHashMap<>();
        private int failingUpdates;

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
            if (failingUpdates > 0) {
                failingUpdates--;
                throw new IllegalStateException("Connection is not available, request timed out");
            }
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

    private static final class FakeSupervisor
            implements ProcessSupervisor
    {
        private final List<ProcessSpec> specs = new ArrayList<>();
        private final List<String> acknowledged = new ArrayList<>();
        private final List<FakeProcess> processes = new ArrayList<>();
        private Optional<RuntimeException> startFailure = Optional.empty();
        private Optional<RuntimeException> prepareFailure = Optional.empty();

        @Override
        public synchronized PreparedProcess prepare(ProcessSpec spec)
        {
            prepareFailure.ifPresent(failure -> {
                throw failure;
            });
            specs.add(spec);
            String launchId = "launch-" + specs.size();
            return new PreparedProcess()
            {
                @Override
                public String launchId()
                {
                    return launchId;
                }

                @Override
                public ManagedProcess start(ProcessOutputSink output)
                {
                    startFailure.ifPresent(failure -> {
                        throw failure;
                    });
                    FakeProcess process = new FakeProcess(launchId, output);
                    synchronized (FakeSupervisor.this) {
                        processes.add(process);
                    }
                    return process;
                }

                @Override
                public void abandon() {}
            };
        }

        @Override
        public List<OwnedProcessRecovery> recoverOwnedProcesses()
        {
            return ImmutableList.of();
        }

        @Override
        public synchronized void acknowledge(String launchId)
        {
            acknowledged.add(launchId);
        }

        private synchronized FakeProcess process()
        {
            return processes.getLast();
        }
    }

    private static final class FakeProcess
            implements ManagedProcess
    {
        private final String launchId;
        private final ProcessOutputSink sink;
        private final CompletableFuture<ProcessExit> exit = new CompletableFuture<>();
        private volatile ProcessExit.TreeTermination stopResult = ProcessExit.TreeTermination.VERIFIED_STOPPED;

        private FakeProcess(String launchId, ProcessOutputSink sink)
        {
            this.launchId = launchId;
            this.sink = sink;
        }

        private void complete(int exitCode, ProcessExit.Reason reason, ProcessExit.TreeTermination termination)
        {
            exit.complete(new ProcessExit(exitCode, reason, termination, Duration.ofSeconds(1), false, false));
        }

        @Override
        public String launchId()
        {
            return launchId;
        }

        @Override
        public long pid()
        {
            return 42;
        }

        @Override
        public CompletionStage<Void> write(ByteBuffer bytes)
        {
            throw new UnsupportedOperationException("A local run has no input");
        }

        @Override
        public void closeInput() {}

        @Override
        public CompletionStage<ProcessExit> exit()
        {
            return exit;
        }

        @Override
        public void cancel()
        {
            complete(143, ProcessExit.Reason.CANCELLED, stopResult);
        }

        @Override
        public CompletionStage<ProcessExit.TreeTermination> stopAndVerify()
        {
            cancel();
            return CompletableFuture.completedFuture(stopResult);
        }

        @Override
        public void close()
        {
            cancel();
        }
    }
}

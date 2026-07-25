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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.Actor;
import com.bytequay.app.domain.CreatePullRequestCommand;
import com.bytequay.app.domain.Notification;
import com.bytequay.app.domain.NotificationKind;
import com.bytequay.app.domain.NotificationStatus;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskPhaseEvent;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.repository.ValidationPassStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.checks.ValidationExecutorRegistry;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.localpr.PrPushedEvent;
import com.bytequay.app.service.review.BrainReviewService;
import com.bytequay.app.service.workspaces.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatusCode;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused unit test for {@link TaskService#shipAndContinue} — the
 * reap step in particular. Without it, every shipped task leaves a
 * {@code .worktrees/<task-id>/} directory on disk forever; the
 * design row says "once a Task's PR is open, its worktree can be
 * pruned to reclaim disk". This test pins both halves of the fix:
 * the persisted shipped row drops its stale worktree pointer, and
 * the on-disk worktree + local branch are removed.
 */
class TestTaskServiceShipAndContinue
{
    /** Drops every published event on the floor; the production
     *  listener (ShipEventMemoryTrigger) is exercised in its own
     *  test, so suppressing it here keeps these tests focused on
     *  the ship plumbing. */
    private static final ApplicationEventPublisher NOOP_PUBLISHER = new ApplicationEventPublisher()
    {
        @Override public void publishEvent(ApplicationEvent event) {}
        @Override public void publishEvent(Object event) {}
    };

    private final ThreadStore threadStore = mock(ThreadStore.class);
    private final TaskStore taskStore = mock(TaskStore.class);
    private final StageStore stageStore = mock(StageStore.class);
    private final WatchedRepoStore watchedRepoStore = mock(WatchedRepoStore.class);
    private final WorktreeService worktreeService = mock(WorktreeService.class);
    private final GitRunner git = mock(GitRunner.class);
    private final PullRequestRepository pullRequests = mock(PullRequestRepository.class);
    private final PatResolver patResolver = mock(PatResolver.class);
    private final ThreadRegistry registry = mock(ThreadRegistry.class);
    private final WorkspaceService workspaces = mock(WorkspaceService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final TaskPhaseMachine taskPhaseMachine = mock(TaskPhaseMachine.class);
    private final TaskTerminalSealer sealer = mock(TaskTerminalSealer.class);
    private final PRService prService = mock(PRService.class);
    private final BrainReviewService brainReview = mock(BrainReviewService.class);
    private final ThreadTurnScheduler scheduler = mock(ThreadTurnScheduler.class);
    // Real reconciler over the same mocks: the pause/resume tests assert
    // its turn-cancel + agent-evict teardown exactly as they did when
    // TaskService owned that block inline.
    // The sweep's TaskService provider stays unused here; the interface
    // default throws if a test unexpectedly reaches it.
    private final TaskRuntimeStopReconciler stopReconciler = new TaskRuntimeStopReconciler(
            taskStore, stageStore, mock(ThreadTurnStore.class), registry, scheduler,
            mock(ValidationPassStore.class), mock(ValidationExecutorRegistry.class),
            new ObjectProvider<>() {});

    private final TaskService service = new TaskService(
            threadStore, taskStore, stageStore, watchedRepoStore, worktreeService,
            git, pullRequests, patResolver,
            registry, workspaces, notifications, mapper,
            NOOP_PUBLISHER,
            taskPhaseMachine, sealer, prService, brainReview, scheduler, stopReconciler);

    /** The machine mock emulates the real pause/resume intents against
     *  the mocked store: guard, write through saveTask (so state-backed
     *  stubs observe the move), and hand back the moved row. The real
     *  guards live in TestTaskPhaseMachine. */
    @BeforeEach
    void stubPauseResumeIntents()
    {
        when(taskPhaseMachine.pause(anyString(), any(), anyString())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            Task current = taskStore.findTaskById(id).orElseThrow();
            if (current.status() == TaskStatus.PAUSED) {
                return current;
            }
            if (current.status().isDone()
                    || current.status() == TaskStatus.NEEDS_ATTENTION
                    || current.status() == TaskStatus.ERRORED
                    || current.status() == TaskStatus.ARCHIVED) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                        "task " + id + " cannot be paused from status " + current.status());
            }
            Task paused = current.withStatus(TaskStatus.PAUSED).withProcessPid(null);
            taskStore.saveTask(paused);
            return paused;
        });
        when(taskPhaseMachine.completeResume(anyString(), any(), anyString())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            Task current = taskStore.findTaskById(id).orElseThrow();
            Task resumed = current
                    .withStatus(TaskPhaseMachine.resumedStatus(current.phase()))
                    .withEndedAt(null)
                    .withErrorMessage(null);
            taskStore.saveTask(resumed);
            return resumed;
        });
    }

    @Test
    void shipOpensADraftPrKeepsTheWorktreeAndCutsNoSuccessor()
            throws Exception
    {
        String workingDir = "/tmp/acme/widget";
        String shippedWorktreePath = "/tmp/acme/widget/.worktrees/task-1";
        String shippedBranchName = "dev/task-1-fix-the-thing";

        when(threadStore.findThreadById("thread-1")).thenReturn(Optional.of(thread("thread-1")));
        Task shipped = task("task-1", "thread-1", 1L, shippedBranchName,
                shippedWorktreePath, workingDir);
        when(taskStore.findTaskById("task-1")).thenReturn(Optional.of(shipped));
        when(taskStore.activeTasksForThread("thread-1")).thenReturn(List.of(shipped));
        when(watchedRepoStore.findAll()).thenReturn(List.of(
                new WatchedRepo(1L, "acme", "widget", 0,
                        workingDir, /* upstreamRemoteName */ null, /* viewFocus */ null)));
        when(workspaces.findDefaultBaseBranch(anyString(), anyString())).thenReturn(Optional.empty());
        when(git.defaultBranch(any(Path.class))).thenReturn(Optional.of("main"));
        when(git.hasUncommittedChanges(any(Path.class))).thenReturn(false);
        when(patResolver.resolve("acme/widget")).thenReturn("ghp_secret");
        ArgumentCaptor<CreatePullRequestCommand> command =
                ArgumentCaptor.forClass(CreatePullRequestCommand.class);
        when(pullRequests.createPullRequest(eq("ghp_secret"), any(RepoRef.class), command.capture()))
                .thenReturn(prWithNumber(42));
        when(registry.find("thread-1")).thenReturn(Optional.empty());

        Task result = service.shipAndContinue("thread-1", "task-1",
                new TaskService.ShipRequest("Next task", TaskService.BaseMode.MAIN));

        // 1. The PR opens as a DRAFT — ship parks the task on the post-ship
        //    loop, which un-drafts it only once CI is green.
        assertThat(command.getValue().draft()).contains(true);

        // 2. The worktree is KEPT — the loop still needs it to push CI fixes
        //    and address review comments. Nothing is reaped at ship time;
        //    the reconciler reaps only when the PR actually merges.
        verify(worktreeService, never()).remove(any(Path.class), anyString(), anyString());
        verify(worktreeService, never()).reap(any());

        ArgumentCaptor<Task> saved = ArgumentCaptor.forClass(Task.class);
        verify(taskStore, atLeastOnce()).saveTask(saved.capture());
        Task shippedAfter = saved.getAllValues().stream()
                .filter(t -> t.id().equals("task-1"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("shipped task was never persisted"));
        // Shipped, not yet done: the branch is pushed and a draft PR is open,
        // so the task parks at IN_REVIEW and only reaches COMPLETED once its
        // PR merges. Its worktree pointer survives — no stale-path clearing.
        assertThat(shippedAfter.status()).isEqualTo(TaskStatus.IN_REVIEW);
        assertThat(shippedAfter.worktreePath()).isEqualTo(shippedWorktreePath);
        assertThat(shippedAfter.branchName()).isEqualTo(shippedBranchName);
        assertThat(shippedAfter.prNumber()).isEqualTo(42);
        assertThat(shippedAfter.linkedPrNumber()).isEqualTo(42);

        // 3. No successor is cut — shipping enters the loop on this task; the
        //    trunk cuts the next task itself. The caller gets the shipped row.
        verify(worktreeService, never()).create(any(Path.class), anyString(), anyString());
        assertThat(saved.getAllValues()).noneMatch(t -> t.status() == TaskStatus.PENDING);
        assertThat(result.id()).isEqualTo("task-1");
        assertThat(result.status()).isEqualTo(TaskStatus.IN_REVIEW);

        // 4. The PR is linked so the reconciler can poll it by owner/repo#n,
        //    and the phase fast-forwards onto the CI-monitor spine.
        verify(taskStore).linkTaskToPr("task-1", "acme/widget#42");
        verify(taskPhaseMachine).observe(
                eq("task-1"), eq(TaskPhase.PUSHED_AWAITING_CI), anyString());
    }

    @Test
    void legacyShipAndNextCannotBypassATaskOriginLocalPrGate()
            throws Exception
    {
        when(threadStore.findThreadById("thread-1")).thenReturn(Optional.of(thread("thread-1")));
        Task current = task(
                "task-1", "thread-1", 1L, "dev/task-1", "/tmp/wt/task-1", "/tmp/repo");
        when(taskStore.findTaskById("task-1")).thenReturn(Optional.of(current));
        PR localPr = PR.create(
                "local-pr-1", "task-1", "dev/task-1", "main", "Change", "", Instant.now());
        when(prService.findByTask("task-1")).thenReturn(Optional.of(localPr));

        assertThatThrownBy(() -> service.shipAndContinue(
                "thread-1", "task-1", new TaskService.ShipRequest(null, TaskService.BaseMode.MAIN)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Approve & ship");
        assertThatThrownBy(() -> service.parkAndStartNext(
                "thread-1", "task-1", new TaskService.ShipRequest(null, TaskService.BaseMode.MAIN)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Approve & ship");

        verify(git, never()).push(any(Path.class));
        verify(pullRequests, never()).createPullRequest(anyString(), any(), any());
    }

    @Test
    void shipOpeningAPrPublishesAPrPushedEventSoItsPrRowLearnsAboutIt()
            throws Exception
    {
        // Ship pushes + opens the PR directly — not through a push/open_pr
        // gate — so without this event the task's PR row (a separate
        // tracking table) never learns the push happened and keeps offering
        // "ready to push" forever.
        String workingDir = "/tmp/acme/widget";
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        TaskService serviceWithMockPublisher = new TaskService(
                threadStore, taskStore, stageStore, watchedRepoStore, worktreeService,
                git, pullRequests, patResolver,
                registry, workspaces, notifications, mapper,
                eventPublisher,
                taskPhaseMachine, sealer, prService, brainReview, scheduler, stopReconciler);

        when(threadStore.findThreadById("thread-1")).thenReturn(Optional.of(thread("thread-1")));
        Task shipped = task("task-1", "thread-1", 1L, "dev/task-1-fix-the-thing",
                "/tmp/acme/widget/.worktrees/task-1", workingDir);
        when(taskStore.findTaskById("task-1")).thenReturn(Optional.of(shipped));
        when(taskStore.activeTasksForThread("thread-1")).thenReturn(List.of(shipped));
        when(watchedRepoStore.findAll()).thenReturn(List.of(
                new WatchedRepo(1L, "acme", "widget", 0,
                        workingDir, /* upstreamRemoteName */ null, /* viewFocus */ null)));
        when(workspaces.findDefaultBaseBranch(anyString(), anyString())).thenReturn(Optional.empty());
        when(git.defaultBranch(any(Path.class))).thenReturn(Optional.of("main"));
        when(git.hasUncommittedChanges(any(Path.class))).thenReturn(false);
        when(patResolver.resolve("acme/widget")).thenReturn("ghp_secret");
        when(pullRequests.createPullRequest(eq("ghp_secret"), any(RepoRef.class), any()))
                .thenReturn(prWithNumber(42));
        when(registry.find("thread-1")).thenReturn(Optional.empty());

        serviceWithMockPublisher.shipAndContinue("thread-1", "task-1",
                new TaskService.ShipRequest("Next task", TaskService.BaseMode.MAIN));

        verify(eventPublisher).publishEvent(
                new PrPushedEvent("task-1", "acme/widget", 42, "https://github.com/acme/widget/pull/42"));
    }

    @Test
    void shipOpensTheDraftPrWithTheProposedTitleAndBody()
            throws Exception
    {
        String workingDir = "/tmp/acme/widget";
        when(threadStore.findThreadById("thread-1")).thenReturn(Optional.of(thread("thread-1")));
        Task shipped = task("task-1", "thread-1", 1L, "dev/task-1",
                "/tmp/acme/widget/.worktrees/task-1", workingDir);
        when(taskStore.findTaskById("task-1")).thenReturn(Optional.of(shipped));
        when(taskStore.activeTasksForThread("thread-1")).thenReturn(List.of(shipped));
        when(watchedRepoStore.findAll()).thenReturn(List.of(
                new WatchedRepo(1L, "acme", "widget", 0, workingDir, null, null)));
        when(workspaces.findDefaultBaseBranch(anyString(), anyString())).thenReturn(Optional.empty());
        when(git.defaultBranch(any(Path.class))).thenReturn(Optional.of("main"));
        when(git.hasUncommittedChanges(any(Path.class))).thenReturn(false);
        when(patResolver.resolve("acme/widget")).thenReturn("ghp_secret");
        ArgumentCaptor<CreatePullRequestCommand> command =
                ArgumentCaptor.forClass(CreatePullRequestCommand.class);
        when(pullRequests.createPullRequest(eq("ghp_secret"), any(RepoRef.class), command.capture()))
                .thenReturn(prWithNumber(42));
        when(registry.find("thread-1")).thenReturn(Optional.empty());

        service.shipAndContinue("thread-1", "task-1",
                new TaskService.ShipRequest(
                        "Next task", TaskService.BaseMode.MAIN,
                        "Add cache layer", "## Summary\nCaches reads."));

        // The proposed title/body land on the draft PR; thread.title() is
        // only the fallback when prTitle is blank.
        assertThat(command.getValue().draft()).contains(true);
        assertThat(command.getValue().title()).isEqualTo("Add cache layer");
        assertThat(command.getValue().body()).contains("## Summary\nCaches reads.");
    }

    @Test
    void shipFallsBackToThreadTitleWhenNoProposedPrTitle()
            throws Exception
    {
        String workingDir = "/tmp/acme/widget";
        when(threadStore.findThreadById("thread-1")).thenReturn(Optional.of(thread("thread-1")));
        Task shipped = task("task-1", "thread-1", 1L, "dev/task-1",
                "/tmp/acme/widget/.worktrees/task-1", workingDir);
        when(taskStore.findTaskById("task-1")).thenReturn(Optional.of(shipped));
        when(taskStore.activeTasksForThread("thread-1")).thenReturn(List.of(shipped));
        when(watchedRepoStore.findAll()).thenReturn(List.of(
                new WatchedRepo(1L, "acme", "widget", 0, workingDir, null, null)));
        when(workspaces.findDefaultBaseBranch(anyString(), anyString())).thenReturn(Optional.empty());
        when(git.defaultBranch(any(Path.class))).thenReturn(Optional.of("main"));
        when(git.hasUncommittedChanges(any(Path.class))).thenReturn(false);
        when(patResolver.resolve("acme/widget")).thenReturn("ghp_secret");
        ArgumentCaptor<CreatePullRequestCommand> command =
                ArgumentCaptor.forClass(CreatePullRequestCommand.class);
        when(pullRequests.createPullRequest(eq("ghp_secret"), any(RepoRef.class), command.capture()))
                .thenReturn(prWithNumber(42));
        when(registry.find("thread-1")).thenReturn(Optional.empty());

        service.shipAndContinue("thread-1", "task-1",
                new TaskService.ShipRequest("Next task", TaskService.BaseMode.MAIN));

        assertThat(command.getValue().title()).isEqualTo("Test thread");
        assertThat(command.getValue().body()).isEmpty();
    }

    @Test
    void approvedParkedNextAdvancesWithoutReopeningCurrentTaskAsRunning()
            throws Exception
    {
        String workingDir = "/tmp/acme/widget";
        Task parked = task("task-parked", "thread-parked", 1L,
                "dev/task-parked", "/tmp/acme/widget/.worktrees/task-parked",
                workingDir, TaskStatus.AWAITING_REVIEW);
        when(threadStore.findThreadById("thread-parked"))
                .thenReturn(Optional.of(thread("thread-parked")));
        when(taskStore.findTaskById("task-parked")).thenReturn(Optional.of(parked));
        when(watchedRepoStore.findAll()).thenReturn(List.of(
                new WatchedRepo(1L, "acme", "widget", 0,
                        workingDir, /* upstreamRemoteName */ null, /* viewFocus */ null)));
        when(workspaces.findDefaultBaseBranch(anyString(), anyString())).thenReturn(Optional.empty());
        when(git.defaultBranch(any(Path.class))).thenReturn(Optional.of("main"));
        when(git.hasUncommittedChanges(any(Path.class))).thenReturn(false);
        when(patResolver.resolve("acme/widget")).thenReturn("ghp_secret");
        when(pullRequests.createPullRequest(eq("ghp_secret"), any(RepoRef.class), any(CreatePullRequestCommand.class)))
                .thenReturn(prWithNumber(43));
        when(registry.find("thread-parked")).thenReturn(Optional.empty());

        Task next = service.startNextFromApprovedParkedTask(
                "thread-parked", "task-parked",
                new TaskService.ShipRequest("Next task", TaskService.BaseMode.MAIN));

        ArgumentCaptor<Task> saved = ArgumentCaptor.forClass(Task.class);
        verify(taskStore, atLeastOnce()).saveTask(saved.capture());
        assertThat(saved.getAllValues().stream()
                .filter(t -> t.id().equals("task-parked"))
                .map(Task::status))
                .containsExactly(TaskStatus.AWAITING_REVIEW);
        // No successor is cut — Next parks the current task and returns it; the
        // trunk's create_task is the only way to start more work.
        verify(worktreeService, never()).create(any(Path.class), anyString(), anyString());
        assertThat(next.id()).isEqualTo("task-parked");
        assertThat(next.status()).isEqualTo(TaskStatus.AWAITING_REVIEW);
    }

    @Test
    void mergingTheShippedTasksPrCompletesIt()
    {
        String workingDir = "/tmp/acme/widget";
        Task inReview = task("task-1", "thread-1", 1L, "dev/task-1",
                /* worktreePath */ null, workingDir, TaskStatus.IN_REVIEW);
        when(taskStore.findByLinkedPrNumber(42)).thenReturn(List.of(inReview));
        when(watchedRepoStore.findAll()).thenReturn(List.of(
                new WatchedRepo(1L, "acme", "widget", 0, workingDir, null, null)));

        service.completeTasksForMergedPr("acme/widget", 42);

        verify(taskPhaseMachine).finishTerminal(
                eq("task-1"), eq(TaskStatus.COMPLETED), eq(Actor.WEBHOOK), eq("pr_merged"));
        verify(sealer).seal("task-1", "pr_merged");
    }

    @Test
    void mergedCompletionWaitsForTheTaskPushLockBeforeSealing()
            throws Exception
    {
        String workingDir = "/tmp/acme/widget";
        Task inReview = task("task-1", "thread-1", 1L, "dev/task-1",
                "/tmp/wt", workingDir, TaskStatus.IN_REVIEW);
        CountDownLatch completionStarted = new CountDownLatch(1);
        when(taskStore.findByLinkedPrNumber(42)).thenAnswer(ignored -> {
            completionStarted.countDown();
            return List.of(inReview);
        });
        when(taskStore.findTaskById("task-1")).thenReturn(Optional.of(inReview));
        when(watchedRepoStore.findAll()).thenReturn(List.of(
                new WatchedRepo(1L, "acme", "widget", 0, workingDir, null, null)));

        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        CompletableFuture<Void> push = CompletableFuture.runAsync(() ->
                TaskPhaseMachine.withTaskLock("task-1", () -> {
                    lockHeld.countDown();
                    try {
                        releaseLock.await();
                    }
                    catch (InterruptedException e) {
                        java.lang.Thread.currentThread().interrupt();
                    }
                    return null;
                }));
        assertThat(lockHeld.await(5, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Void> completion = CompletableFuture.runAsync(() ->
                service.completeTasksForMergedPr("acme/widget", 42));
        assertThat(completionStarted.await(5, TimeUnit.SECONDS)).isTrue();
        verify(taskPhaseMachine, never()).finishTerminal(
                anyString(), eq(TaskStatus.COMPLETED), any(), any());

        releaseLock.countDown();
        push.get(5, TimeUnit.SECONDS);
        completion.get(5, TimeUnit.SECONDS);
        verify(taskPhaseMachine).finishTerminal(
                eq("task-1"), eq(TaskStatus.COMPLETED), eq(Actor.WEBHOOK), eq("pr_merged"));
        verify(sealer).seal("task-1", "pr_merged");
    }

    @Test
    void mergingAPrInAnotherRepoLeavesTheTaskUntouched()
    {
        // Same PR number, different repo — must not complete the task,
        // since PR numbers aren't unique across repos.
        Task inReview = task("task-1", "thread-1", 1L, "dev/task-1",
                null, "/tmp/acme/widget", TaskStatus.IN_REVIEW);
        when(taskStore.findByLinkedPrNumber(42)).thenReturn(List.of(inReview));
        when(watchedRepoStore.findAll()).thenReturn(List.of(
                new WatchedRepo(1L, "acme", "widget", 0, "/tmp/acme/widget", null, null)));

        service.completeTasksForMergedPr("other/repo", 42);

        verify(taskPhaseMachine, never()).finishTerminal(
                anyString(), eq(TaskStatus.COMPLETED), any(), any());
        verify(taskPhaseMachine, never()).transition(anyString(), any(), anyString(), any());
    }

    @Test
    void closingTheShippedTasksPrSealsItAsRemoteClosed()
    {
        String workingDir = "/tmp/acme/widget";
        Task inReview = task("task-1", "thread-1", 1L, "dev/task-1",
                "/tmp/wt", workingDir, TaskStatus.IN_REVIEW);
        when(taskStore.findByLinkedPrNumber(42)).thenReturn(List.of(inReview));
        when(watchedRepoStore.findAll()).thenReturn(List.of(
                new WatchedRepo(1L, "acme", "widget", 0, workingDir, null, null)));

        service.closeTasksForRemotePr("acme/widget", 42);

        verify(taskPhaseMachine).finishTerminal(
                eq("task-1"), eq(TaskStatus.REMOTE_CLOSED), eq(Actor.WEBHOOK), eq("pr_closed"));
        verify(taskStore).linkPullRequest("task-1", 42, "closed");
        verify(sealer).seal("task-1", "pr_closed");
    }

    @Test
    void pauseStopsTheAgentAndParksAtPausedKeepingTheWorktree()
    {
        Task active = task("t1.k1", "t1", 1L, "dev/x", "/wt", "/clone", TaskStatus.RUNNING);
        taskState(active);
        ThreadAgent session = mock(ThreadAgent.class);
        when(registry.findStages(List.of("t1.k1"))).thenReturn(List.of(session));

        Task paused = service.pauseTask("t1", "t1.k1");

        // The task's stage agent(s) interrupted + evicted so the task frees
        // its lease; the thread's other tasks are untouched.
        verify(scheduler).cancelTaskTurns("t1.k1");
        verify(brainReview).pauseActiveReview("t1.k1", "user_paused_task");
        verify(session).interrupt();
        verify(registry).evictStages("t1", List.of("t1.k1"));
        // Pause keeps the work — the worktree is NOT reaped (unlike cancel).
        verify(worktreeService, never()).reap(any());
        ArgumentCaptor<Task> saved = ArgumentCaptor.forClass(Task.class);
        verify(taskStore).saveTask(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(TaskStatus.PAUSED);
        assertThat(saved.getValue().worktreePath()).isEqualTo("/wt");
        assertThat(paused.status()).isEqualTo(TaskStatus.PAUSED);
    }

    @Test
    void rolledBackPauseDoesNotCancelOrEvictItsRuntime()
    {
        Task active = task("t1.k1", "t1", 1L, "dev/x", "/wt", "/clone", TaskStatus.RUNNING);
        when(taskStore.findTaskById("t1.k1")).thenReturn(Optional.of(active));

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.pauseTask("t1", "t1.k1");

            verify(scheduler, never()).cancelTaskTurns(anyString());
            verify(registry, never()).findStages(any());
            verify(registry, never()).evictStages(anyString(), any());

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(synchronization -> synchronization.afterCompletion(
                            TransactionSynchronization.STATUS_ROLLED_BACK));
        }
        finally {
            // No afterCommit callback: model a transaction rollback.
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void resumeBeforeCommittedPauseTeardownMakesTheCallbackStale()
    {
        String taskId = "t1.k1";
        Task active = task(taskId, "t1", 1L, "dev/x", "/wt", "/clone", TaskStatus.RUNNING);
        AtomicReference<Task> state = taskState(active);
        AtomicReference<Runnable> pendingTeardown = new AtomicReference<>();
        TaskService controlled = serviceWithPauseDispatcher(action -> {
            assertThat(pendingTeardown.compareAndSet(null, action)).isTrue();
        });
        when(threadStore.findThreadById("t1")).thenReturn(Optional.of(thread("t1")));
        when(stageStore.findActiveStage(taskId)).thenReturn(Optional.empty());
        StageAgent resumedAgent = mock(StageAgent.class);
        when(registry.getOrCreateStageAgent(any(), any(), any())).thenReturn(resumedAgent);

        commitPause(controlled, "t1", taskId);
        assertThat(state.get().status()).isEqualTo(TaskStatus.PAUSED);

        Task resumed = controlled.resumeTask(taskId);
        pendingTeardown.get().run();

        assertThat(resumed.status()).isEqualTo(TaskStatus.IDLE);
        assertThat(state.get().status()).isEqualTo(TaskStatus.IDLE);
        verify(resumedAgent).resume();
        // Resume's own inline reconcile tore the pre-pause runtime down
        // exactly once; the late pause callback lost its token and must
        // not run a second teardown against the resumed runtime.
        verify(scheduler, times(1)).cancelTaskTurns(taskId);
        verify(registry, times(1)).evictStages(anyString(), any());
        verify(resumedAgent, never()).interrupt();
    }

    @Test
    void committedPauseTeardownFinishesBeforeALaterResumeRecreatesRuntime()
    {
        String taskId = "t1.k1";
        Task active = task(taskId, "t1", 1L, "dev/x", "/wt", "/clone", TaskStatus.RUNNING);
        taskState(active);
        AtomicReference<Runnable> pendingTeardown = new AtomicReference<>();
        TaskService controlled = serviceWithPauseDispatcher(pendingTeardown::set);
        when(threadStore.findThreadById("t1")).thenReturn(Optional.of(thread("t1")));
        when(stageStore.findActiveStage(taskId)).thenReturn(Optional.empty());
        when(registry.findStages(List.of(taskId))).thenReturn(List.of());
        StageAgent resumedAgent = mock(StageAgent.class);
        when(registry.getOrCreateStageAgent(any(), any(), any())).thenReturn(resumedAgent);

        commitPause(controlled, "t1", taskId);
        pendingTeardown.get().run();
        Task resumed = controlled.resumeTask(taskId);

        assertThat(resumed.status()).isEqualTo(TaskStatus.IDLE);
        InOrder order = inOrder(scheduler, registry, resumedAgent);
        order.verify(scheduler).cancelTaskTurns(taskId);
        order.verify(registry).evictStages("t1", List.of(taskId));
        order.verify(registry).getOrCreateStageAgent(any(), any(), any());
        order.verify(resumedAgent).resume();
    }

    @Test
    void rollbackRemovesThePauseTeardownToken()
    {
        String taskId = "t1.k1";
        Task active = task(taskId, "t1", 1L, "dev/x", "/wt", "/clone", TaskStatus.RUNNING);
        AtomicReference<Task> state = taskState(active);
        AtomicReference<Runnable> pendingTeardown = new AtomicReference<>();
        TaskService controlled = serviceWithPauseDispatcher(pendingTeardown::set);

        TransactionSynchronizationManager.initSynchronization();
        List<TransactionSynchronization> callbacks;
        try {
            controlled.pauseTask("t1", taskId);
            callbacks = List.copyOf(TransactionSynchronizationManager.getSynchronizations());
            callbacks.forEach(synchronization -> synchronization.afterCompletion(
                    TransactionSynchronization.STATUS_ROLLED_BACK));
            // Model the repository rollback; the Mockito save above is not a
            // transactional store, so restore its prior durable row manually.
            state.set(active);
            // Even if a buggy caller delivered the old afterCommit late, its
            // action must no longer own a teardown generation.
            callbacks.forEach(TransactionSynchronization::afterCommit);
        }
        finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        pendingTeardown.get().run();
        verify(scheduler, never()).cancelTaskTurns(taskId);
        verify(registry, never()).evictStages(anyString(), any());
    }

    @Test
    void pauseRejectsATerminalTask()
    {
        // A done task can't be paused — only live / parked work can.
        Task done = task("t1.k1", "t1", 1L, "dev/x", null, "/clone", TaskStatus.COMPLETED);
        when(taskStore.findTaskById("t1.k1")).thenReturn(Optional.of(done));

        assertThatThrownBy(() -> service.pauseTask("t1", "t1.k1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("cannot be paused");
        verify(taskStore, never()).saveTask(any());
    }

    @Test
    void pauseClearsTheTasksParkedApprovalNotification()
    {
        // A parked-review task (AWAITING_REVIEW) carries an approve/discard
        // notification. Pausing sets it aside, so that notification must not
        // keep showing "needs your approval".
        Task parked = task("t1.k1", "t1", 1L, "dev/x", "/wt", "/clone", TaskStatus.AWAITING_REVIEW);
        when(taskStore.findTaskById("t1.k1")).thenReturn(
                Optional.of(parked), Optional.of(parked.withStatus(TaskStatus.PAUSED)));
        when(registry.find("t1")).thenReturn(Optional.empty());
        Notification parkedNotif = new Notification(
                "notif-1", NotificationKind.AWAITING_REVIEW, "t1", "t1.k1",
                NotificationStatus.UNREAD, "{}", Instant.parse("2026-05-15T12:00:00Z"), null);
        when(notifications.listForThread("t1")).thenReturn(List.of(parkedNotif));

        Task result = service.pauseTask("t1", "t1.k1");

        assertThat(result.status()).isEqualTo(TaskStatus.PAUSED);
        verify(notifications).markRead("notif-1");
    }

    @Test
    void pauseAcceptsAShippedInReviewTask()
    {
        // Shipping moved off the task page; a shipped (IN_REVIEW) task can be
        // paused to set it aside while its PR rides to merge.
        Task shipped = task("t1.k1", "t1", 1L, "dev/x", "/wt", "/clone", TaskStatus.IN_REVIEW);
        when(taskStore.findTaskById("t1.k1")).thenReturn(
                Optional.of(shipped), Optional.of(shipped.withStatus(TaskStatus.PAUSED)));
        when(registry.find("t1")).thenReturn(Optional.empty());

        assertThat(service.pauseTask("t1", "t1.k1").status()).isEqualTo(TaskStatus.PAUSED);
    }

    @Test
    void resumeRevivesAPausedTaskToIdle()
    {
        Task paused = task("t1.k1", "t1", 1L, "dev/x", "/wt", "/clone", TaskStatus.PAUSED);
        when(taskStore.findTaskById("t1.k1")).thenReturn(Optional.of(paused));
        when(threadStore.findThreadById("t1")).thenReturn(Optional.of(thread("t1")));
        when(stageStore.findActiveStage("t1.k1")).thenReturn(Optional.empty());
        StageAgent agent = mock(StageAgent.class);
        when(registry.getOrCreateStageAgent(any(), any(), any())).thenReturn(agent);

        Task resumed = service.resumeTask("t1", "t1.k1");

        assertThat(resumed.status()).isEqualTo(TaskStatus.IDLE);
        ArgumentCaptor<Task> saved = ArgumentCaptor.forClass(Task.class);
        verify(taskStore).saveTask(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(TaskStatus.IDLE);
        // Worktree preserved through the pause→resume round-trip.
        assertThat(saved.getValue().worktreePath()).isEqualTo("/wt");
        verify(agent).resume();
    }

    @Test
    void resumeOfAPausedBrainRoundUsesTheCoordinatorInsteadOfTheStageAgent()
    {
        Task base = task("t1.k1", "t1", 1L, "dev/x", "/wt", "/clone", TaskStatus.PAUSED);
        Task paused = taskAtPhase(base, TaskPhase.INTERNAL_REVIEW);
        StageInstance activeStage = new StageInstance(
                UUID.fromString("22222222-2222-2222-2222-222222222222"), paused.id(),
                StageType.DEVELOPMENT_STAGE, StageState.OPEN, paused.createdAt(), null, null);
        when(taskStore.findTaskById(paused.id())).thenReturn(Optional.of(paused));
        when(threadStore.findThreadById(paused.threadId())).thenReturn(Optional.of(thread(paused.threadId())));
        when(stageStore.findActiveStage(paused.id())).thenReturn(Optional.of(activeStage));
        when(brainReview.ownsParkedResume(paused.id())).thenReturn(true);
        when(brainReview.resumeParkedReview(paused.id())).thenReturn(true);

        Task resumed = service.resumeTask(paused.id());

        assertThat(resumed.status()).isEqualTo(TaskStatus.IDLE);
        assertThat(resumed.phase()).isEqualTo(TaskPhase.INTERNAL_REVIEW);
        verify(brainReview).resumeParkedReview(paused.id());
        verify(registry, never()).getOrCreateStageAgent(any(), any(), any());
        verify(registry, never()).getOrCreateTaskBrainAgent(any());
    }

    @Test
    void resumeOfAPausedValidationWaitsForTheValidationDriver()
    {
        Task base = task("t1.k1", "t1", 1L, "dev/x", "/wt", "/clone", TaskStatus.PAUSED);
        Task paused = taskAtPhase(base, TaskPhase.VALIDATING);
        StageInstance activeStage = new StageInstance(
                UUID.fromString("33333333-3333-3333-3333-333333333333"), paused.id(),
                StageType.DEVELOPMENT_STAGE, StageState.OPEN, paused.createdAt(), null, null);
        when(taskStore.findTaskById(paused.id())).thenReturn(Optional.of(paused));
        when(threadStore.findThreadById(paused.threadId())).thenReturn(Optional.of(thread(paused.threadId())));
        when(stageStore.findActiveStage(paused.id())).thenReturn(Optional.of(activeStage));

        Task resumed = service.resumeTask(paused.id());

        assertThat(resumed.status()).isEqualTo(TaskStatus.IDLE);
        assertThat(resumed.phase()).isEqualTo(TaskPhase.VALIDATING);
        verify(registry, never()).getOrCreateStageAgent(any(), any(), any());
        verify(registry, never()).getOrCreateTaskBrainAgent(any());
    }

    @Test
    void resumeRevivesAPausedTaskEvenWhenASiblingIsActive()
    {
        // A thread can run several tasks at once, so reviving a paused task
        // no longer requires the others to be idle.
        Task paused = task("t1.k1", "t1", 1L, "dev/x", "/wt", "/clone", TaskStatus.PAUSED);
        when(taskStore.findTaskById("t1.k1")).thenReturn(Optional.of(paused));
        when(threadStore.findThreadById("t1")).thenReturn(Optional.of(thread("t1")));
        when(stageStore.findActiveStage("t1.k1")).thenReturn(Optional.empty());
        StageAgent agent = mock(StageAgent.class);
        when(registry.getOrCreateStageAgent(any(), any(), any())).thenReturn(agent);

        Task resumed = service.resumeTask("t1", "t1.k1");

        assertThat(resumed.status()).isEqualTo(TaskStatus.IDLE);
        verify(taskStore).saveTask(any());
        verify(agent).resume();
    }

    @Test
    void resumeRevivesAnErroredExactTaskAndThread()
    {
        Instant ended = Instant.parse("2026-05-15T12:00:00Z");
        Task errored = new Task(
                "t1.k1", "t1", 1L, TaskStatus.ERRORED,
                "dev/x", "/wt", "main", "/clone",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, "task-session",
                ended.minusSeconds(60), ended, "limit hit",
                null, null, null);
        Thread thread = new Thread(
                "t1", ThreadKind.CLI_AGENT, "claude-code", "trunk-session",
                "Errored thread", ThreadStatus.ERRORED, "test",
                0L, 0L, 0L,
                ended.minusSeconds(60), ended, ended, "limit hit",
                ThreadFlow.BUILD, "ws-default", null, null);
        when(taskStore.findTaskById("t1.k1")).thenReturn(Optional.of(errored));
        when(threadStore.findThreadById("t1")).thenReturn(Optional.of(thread));
        when(stageStore.findActiveStage("t1.k1")).thenReturn(Optional.empty());
        StageAgent agent = mock(StageAgent.class);
        when(registry.getOrCreateStageAgent(any(), any(), any())).thenReturn(agent);

        Task resumed = service.resumeTask("t1.k1");

        assertThat(resumed.status()).isEqualTo(TaskStatus.IDLE);
        assertThat(resumed.endedAt()).isNull();
        assertThat(resumed.errorMessage()).isNull();
        ArgumentCaptor<Thread> savedThread = ArgumentCaptor.forClass(Thread.class);
        verify(threadStore).saveThread(savedThread.capture());
        assertThat(savedThread.getValue().status()).isEqualTo(ThreadStatus.IDLE);
        assertThat(savedThread.getValue().endedAt()).isNull();
        assertThat(savedThread.getValue().errorMessage()).isNull();
        verify(registry).getOrCreateStageAgent(any(), any(), any());
        verify(registry, never()).getOrCreateTrunkAgent(any());
        verify(agent).resume();
    }

    @Test
    void resumePlanStageUsesTheTaskBrainAgent()
    {
        Task paused = task("t1.k1", "t1", 1L, "dev/x", "/wt", "/clone", TaskStatus.PAUSED);
        Thread parent = thread("t1");
        Thread brain = new Thread(
                "brain-1", ThreadKind.BRAIN_AGENT, "claude-code", null,
                "Brain", ThreadStatus.IDLE, "claude-sonnet-4.6",
                0L, 0L, 0L,
                paused.createdAt(), paused.createdAt(), null, null,
                ThreadFlow.BUILD, "ws-default", null, null, 1, "t1.k1");
        UUID planStageId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        StageInstance planStage = new StageInstance(
                planStageId, "t1.k1", StageType.PLAN_STAGE, StageState.OPEN,
                paused.createdAt(), null, null);
        when(taskStore.findTaskById("t1.k1")).thenReturn(Optional.of(paused));
        when(threadStore.findThreadById("t1")).thenReturn(Optional.of(parent));
        when(threadStore.findBrainThreadByTask("t1.k1")).thenReturn(Optional.of(brain));
        when(stageStore.findActiveStage("t1.k1")).thenReturn(Optional.of(planStage));
        TaskBrainAgent agent = mock(TaskBrainAgent.class);
        when(registry.getOrCreateTaskBrainAgent(brain)).thenReturn(agent);

        Task resumed = service.resumeTask("t1.k1");

        assertThat(resumed.status()).isEqualTo(TaskStatus.IDLE);
        verify(registry).getOrCreateTaskBrainAgent(brain);
        verify(registry, never()).getOrCreateStageAgent(any(), any(), any());
        verify(agent).resume();
    }

    @Test
    void resumeNeedsAttentionRestoresRemoteDraftToCiWithoutStartingDev()
    {
        Task parked = parkedTask(TaskPhase.NEEDS_ATTENTION);
        PR remoteDraft = PR.create(
                        "pr1", parked.id(), parked.branchName(), parked.baseBranch(),
                        "Title", "", parked.createdAt())
                .withStatus(PR.STATUS_LOCAL_OPEN, parked.createdAt())
                .withStatus(PR.STATUS_REMOTE_DRAFTED, parked.createdAt())
                .withRemote("acme/widget", 42, "https://github.com/acme/widget/pull/42", parked.createdAt());
        Notification notice = new Notification(
                "notice-1", NotificationKind.NEEDS_ATTENTION, parked.threadId(), parked.id(),
                NotificationStatus.UNREAD, "{}", parked.createdAt(), null);
        when(taskStore.findTaskById(parked.id())).thenReturn(Optional.of(parked));
        when(prService.findByTask(parked.id())).thenReturn(Optional.of(remoteDraft));
        when(threadStore.findThreadById(parked.threadId()))
                .thenReturn(Optional.of(thread(parked.threadId())));
        when(stageStore.findActiveStage(parked.id())).thenReturn(Optional.empty());
        when(notifications.listForThread(parked.threadId())).thenReturn(List.of(notice));

        Task resumed = service.resumeTask(parked.id());

        assertThat(resumed.status()).isEqualTo(TaskStatus.IDLE);
        assertThat(resumed.phase()).isEqualTo(TaskPhase.PUSHED_AWAITING_CI);
        verify(taskPhaseMachine).recover(
                parked.id(), TaskPhase.PUSHED_AWAITING_CI, "user_resumed_task");
        verify(registry, never()).getOrCreateStageAgent(any(), any(), any());
        verify(notifications).markRead("notice-1");
    }

    @Test
    void resumeNeedsAttentionAfterValidationReturnsToValidationWithoutRestartingDevelopment()
    {
        Task parked = parkedTask(TaskPhase.NEEDS_ATTENTION);
        when(taskStore.findTaskById(parked.id())).thenReturn(Optional.of(parked));
        when(prService.findByTask(parked.id())).thenReturn(Optional.empty());
        when(taskStore.listPhaseEvents(parked.id())).thenReturn(List.of(new TaskPhaseEvent(
                1L, parked.id(), TaskPhase.VALIDATING, TaskPhase.NEEDS_ATTENTION,
                parked.createdAt(), "validation_failed", Actor.AGENT)));
        when(threadStore.findThreadById(parked.threadId()))
                .thenReturn(Optional.of(thread(parked.threadId())));
        when(stageStore.findActiveStage(parked.id())).thenReturn(Optional.empty());
        Task resumed = service.resumeTask(parked.id());

        assertThat(resumed.phase()).isEqualTo(TaskPhase.VALIDATING);
        verify(taskPhaseMachine).recover(
                parked.id(), TaskPhase.VALIDATING, "user_resumed_task");
        verify(registry, never()).getOrCreateStageAgent(any(), any(), any());
    }

    @Test
    void resumeNeedsAttentionDuringInternalReviewReturnsToBrainCoordinator()
    {
        Task parked = parkedTask(TaskPhase.NEEDS_ATTENTION);
        when(taskStore.findTaskById(parked.id())).thenReturn(Optional.of(parked));
        when(taskStore.listPhaseEvents(parked.id())).thenReturn(List.of(new TaskPhaseEvent(
                1L, parked.id(), TaskPhase.INTERNAL_REVIEW, TaskPhase.NEEDS_ATTENTION,
                parked.createdAt(), "brain_review_turn_failed", Actor.AGENT)));
        when(threadStore.findThreadById(parked.threadId()))
                .thenReturn(Optional.of(thread(parked.threadId())));
        when(stageStore.findActiveStage(parked.id())).thenReturn(Optional.empty());
        when(brainReview.ownsParkedResume(parked.id())).thenReturn(true);
        when(brainReview.resumeParkedReview(parked.id())).thenReturn(true);

        Task resumed = service.resumeTask(parked.id());

        assertThat(resumed.phase()).isEqualTo(TaskPhase.INTERNAL_REVIEW);
        verify(taskPhaseMachine).recover(
                parked.id(), TaskPhase.INTERNAL_REVIEW, "user_resumed_task");
        verify(brainReview).ownsParkedResume(parked.id());
        verify(brainReview).resumeParkedReview(parked.id());
        verify(registry, never()).getOrCreateStageAgent(any(), any(), any());
    }

    @Test
    void resumeNeedsAttentionDuringExternalReviewReturnsToBrainCoordinator()
    {
        Task parked = parkedTask(TaskPhase.NEEDS_ATTENTION);
        when(taskStore.findTaskById(parked.id())).thenReturn(Optional.of(parked));
        when(taskStore.listPhaseEvents(parked.id())).thenReturn(List.of(new TaskPhaseEvent(
                1L, parked.id(), TaskPhase.AWAITING_REMOTE_REVIEW, TaskPhase.NEEDS_ATTENTION,
                parked.createdAt(), "review_fixes_validation_failed", Actor.AGENT)));
        when(threadStore.findThreadById(parked.threadId()))
                .thenReturn(Optional.of(thread(parked.threadId())));
        when(stageStore.findActiveStage(parked.id())).thenReturn(Optional.empty());
        when(brainReview.ownsParkedResume(parked.id())).thenReturn(true);
        when(brainReview.resumeParkedReview(parked.id())).thenReturn(true);

        Task resumed = service.resumeTask(parked.id());

        assertThat(resumed.phase()).isEqualTo(TaskPhase.AWAITING_REMOTE_REVIEW);
        verify(taskPhaseMachine).recover(
                parked.id(), TaskPhase.AWAITING_REMOTE_REVIEW, "user_resumed_task");
        verify(brainReview).resumeParkedReview(parked.id());
        verify(registry, never()).getOrCreateStageAgent(any(), any(), any());
    }

    @Test
    void resumeNeedsAttentionDuringPlanReviewReturnsToBrainCoordinator()
    {
        Task parked = parkedTask(TaskPhase.NEEDS_ATTENTION);
        when(taskStore.findTaskById(parked.id())).thenReturn(Optional.of(parked));
        when(taskStore.listPhaseEvents(parked.id())).thenReturn(List.of(new TaskPhaseEvent(
                1L, parked.id(), TaskPhase.PLANNING, TaskPhase.NEEDS_ATTENTION,
                parked.createdAt(), "plan_self_review_failed", Actor.AGENT)));
        when(threadStore.findThreadById(parked.threadId()))
                .thenReturn(Optional.of(thread(parked.threadId())));
        when(stageStore.findActiveStage(parked.id())).thenReturn(Optional.empty());
        when(brainReview.ownsParkedResume(parked.id())).thenReturn(true);
        when(brainReview.resumeParkedReview(parked.id())).thenReturn(true);

        Task resumed = service.resumeTask(parked.id());

        assertThat(resumed.phase()).isEqualTo(TaskPhase.PLANNING);
        verify(taskPhaseMachine).recover(parked.id(), TaskPhase.PLANNING, "user_resumed_task");
        verify(brainReview).resumeParkedReview(parked.id());
        verify(registry, never()).getOrCreateStageAgent(any(), any(), any());
    }

    @Test
    void resumeUsesTheParkedPhaseBeforeTheRemotePrState()
    {
        Task parked = parkedTask(TaskPhase.NEEDS_ATTENTION);
        PR remoteOpen = PR.create(
                        "pr1", parked.id(), parked.branchName(), parked.baseBranch(),
                        "Title", "", parked.createdAt())
                .withStatus(PR.STATUS_LOCAL_OPEN, parked.createdAt())
                .withStatus(PR.STATUS_REMOTE_DRAFTED, parked.createdAt())
                .withStatus(PR.STATUS_REMOTE_OPEN, parked.createdAt());
        when(taskStore.findTaskById(parked.id())).thenReturn(Optional.of(parked));
        when(prService.findByTask(parked.id())).thenReturn(Optional.of(remoteOpen));
        when(taskStore.listPhaseEvents(parked.id())).thenReturn(List.of(new TaskPhaseEvent(
                1L, parked.id(), TaskPhase.PUSHED_AWAITING_CI, TaskPhase.NEEDS_ATTENTION,
                parked.createdAt(), "ci_fix_attempts_exhausted", Actor.AGENT)));
        when(threadStore.findThreadById(parked.threadId()))
                .thenReturn(Optional.of(thread(parked.threadId())));
        when(stageStore.findActiveStage(parked.id())).thenReturn(Optional.empty());

        Task resumed = service.retryFailedCi(parked.threadId(), parked.id());

        assertThat(resumed.phase()).isEqualTo(TaskPhase.PUSHED_AWAITING_CI);
        verify(taskPhaseMachine).recover(
                parked.id(), TaskPhase.PUSHED_AWAITING_CI, "user_retried_ci");
    }

    @Test
    void ordinaryResumeRefusesAnExhaustedCiTask()
    {
        Task parked = parkedTask(TaskPhase.NEEDS_ATTENTION);
        when(taskStore.findTaskById(parked.id())).thenReturn(Optional.of(parked));
        when(taskStore.listPhaseEvents(parked.id())).thenReturn(List.of(new TaskPhaseEvent(
                1L, parked.id(), TaskPhase.PUSHED_AWAITING_CI, TaskPhase.NEEDS_ATTENTION,
                parked.createdAt(), "ci_fix_attempts_exhausted", Actor.AGENT)));

        assertThatThrownBy(() -> service.resumeTask(parked.id()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("explicit Retry CI");
        verify(taskPhaseMachine, never()).recover(anyString(), any(), anyString());
    }

    private static Thread thread(String id)
    {
        Instant now = Instant.parse("2026-05-15T12:00:00Z");
        return new Thread(
                id, ThreadKind.CLI_AGENT, "claude-code", /* agentSessionId */ null,
                "Test thread", ThreadStatus.RUNNING, "claude-sonnet-4.6",
                0L, 0L, 0L,
                now, now, null, null,
                ThreadFlow.BUILD, "ws-default", null, null);
    }

    @Test
    void cancelTaskStopsTheAgentSealsCanceledAndReapsTheWorktree()
    {
        Task t = task("t1.k1", "t1", 1L, "dev/x", "/wt", "/clone");
        when(taskStore.findTaskById("t1.k1")).thenReturn(Optional.of(t));
        ThreadAgent session = mock(ThreadAgent.class);
        when(registry.findStages(List.of("t1.k1"))).thenReturn(List.of(session));

        service.cancelTask("t1", "t1.k1");

        verify(scheduler).cancelTaskTurns("t1.k1");
        verify(session).interrupt();
        verify(taskPhaseMachine).finishTerminal(
                eq("t1.k1"), eq(TaskStatus.CANCELED), eq(Actor.HUMAN), eq("task_cancelled"));
        // Worktree + branch reaped.
        verify(worktreeService).reap(t);
    }

    @Test
    void cancelTaskToleratesNoLiveSession()
    {
        Task t = task("t1.k1", "t1", 1L, "dev/x", "/wt", "/clone");
        when(taskStore.findTaskById("t1.k1")).thenReturn(Optional.of(t));
        when(registry.find("t1")).thenReturn(Optional.empty());

        service.cancelTask("t1", "t1.k1");

        // No session to interrupt, but the task is still sealed + reaped.
        verify(scheduler).cancelTaskTurns("t1.k1");
        verify(taskPhaseMachine).finishTerminal(
                eq("t1.k1"), eq(TaskStatus.CANCELED), eq(Actor.HUMAN), eq("task_cancelled"));
        verify(worktreeService).reap(t);
    }

    @Test
    void cancelSerializesAgainstResumeAndInvalidatesPendingPauseTeardown()
            throws Exception
    {
        String taskId = "t1.k1";
        Task active = task(taskId, "t1", 1L, "dev/x", "/wt", "/clone", TaskStatus.RUNNING);
        AtomicReference<Task> state = taskState(active);
        AtomicReference<Runnable> pendingTeardown = new AtomicReference<>();
        TaskService controlled = serviceWithPauseDispatcher(pendingTeardown::set);
        commitPause(controlled, "t1", taskId);

        CountDownLatch cancelEntered = new CountDownLatch(1);
        CountDownLatch releaseCancel = new CountDownLatch(1);
        doAnswer(ignored -> {
            cancelEntered.countDown();
            assertThat(releaseCancel.await(5, TimeUnit.SECONDS)).isTrue();
            return 0;
        }).when(scheduler).cancelTaskTurns(taskId);
        doAnswer(invocation -> {
            state.updateAndGet(task -> task.withStatus(TaskStatus.CANCELED));
            return null;
        }).when(taskPhaseMachine).finishTerminal(
                eq(taskId), eq(TaskStatus.CANCELED), any(), any());

        CompletableFuture<Task> cancel = CompletableFuture.supplyAsync(
                () -> controlled.cancelTask("t1", taskId));
        assertThat(cancelEntered.await(5, TimeUnit.SECONDS)).isTrue();

        CountDownLatch resumeAttempted = new CountDownLatch(1);
        CompletableFuture<Task> resume = CompletableFuture.supplyAsync(() -> {
            resumeAttempted.countDown();
            return controlled.resumeTask(taskId);
        });
        assertThat(resumeAttempted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(resume.isDone()).isFalse();

        releaseCancel.countDown();
        assertThat(cancel.get(5, TimeUnit.SECONDS).status()).isEqualTo(TaskStatus.CANCELED);
        assertThatThrownBy(() -> resume.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ResponseStatusException.class);

        pendingTeardown.get().run();
        assertThat(state.get().status()).isEqualTo(TaskStatus.CANCELED);
        verify(scheduler, times(1)).cancelTaskTurns(taskId);
        verify(registry, times(1)).evictStages("t1", List.of(taskId));
        verify(registry, never()).getOrCreateStageAgent(any(), any(), any());
    }

    private AtomicReference<Task> taskState(Task initial)
    {
        AtomicReference<Task> state = new AtomicReference<>(initial);
        when(taskStore.findTaskById(initial.id())).thenAnswer(ignored -> Optional.of(state.get()));
        doAnswer(invocation -> {
            state.set(invocation.getArgument(0));
            return null;
        }).when(taskStore).saveTask(any(Task.class));
        return state;
    }

    private TaskService serviceWithPauseDispatcher(Executor dispatcher)
    {
        return new TaskService(
                threadStore, taskStore, stageStore, watchedRepoStore, worktreeService,
                git, pullRequests, patResolver, registry, workspaces, notifications, mapper,
                NOOP_PUBLISHER, taskPhaseMachine, sealer, prService, brainReview, scheduler,
                stopReconciler, dispatcher);
    }

    private static void commitPause(TaskService service, String threadId, String taskId)
    {
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.pauseTask(threadId, taskId);
            List<TransactionSynchronization> callbacks =
                    List.copyOf(TransactionSynchronizationManager.getSynchronizations());
            callbacks.forEach(TransactionSynchronization::afterCommit);
            callbacks.forEach(synchronization -> synchronization.afterCompletion(
                    TransactionSynchronization.STATUS_COMMITTED));
        }
        finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static Task task(
            String id, String threadId, long seq,
            String branchName, String worktreePath, String workingDir)
    {
        return task(id, threadId, seq, branchName, worktreePath, workingDir, TaskStatus.RUNNING);
    }

    private static Task task(
            String id, String threadId, long seq,
            String branchName, String worktreePath, String workingDir, TaskStatus status)
    {
        Instant now = Instant.parse("2026-05-15T12:00:00Z");
        return new Task(
                id, threadId, seq, status,
                branchName, worktreePath, /* baseBranch */ "main", workingDir,
                /* processPid */ null, /* logPath */ null,
                /* prNumber */ null, /* prState */ null, /* ciState */ null,
                /* taskType */ "DEVELOP",
                /* linkedPrNumber */ null, /* linkedIssueNumber */ null,
                0L, 0L, 0L,
                /* agentSessionId */ null,
                now, /* endedAt */ null, /* errorMessage */ null,
                /* name */ null, /* roleSkill */ null, /* workModel */ null);
    }

    private static Task parkedTask(TaskPhase phase)
    {
        Task task = task(
                "t1.k1", "t1", 1L, "dev/x", "/wt", "/clone", TaskStatus.NEEDS_ATTENTION);
        return taskAtPhase(task, phase);
    }

    private static Task taskAtPhase(Task task, TaskPhase phase)
    {
        return new Task(
                task.id(), task.threadId(), task.seq(), task.status(), task.branchName(),
                task.worktreePath(), task.baseBranch(), task.workingDir(), task.processPid(),
                task.logPath(), task.prNumber(), task.prState(), task.ciState(), task.taskType(),
                task.linkedPrNumber(), task.linkedIssueNumber(), task.costUsdMilli(),
                task.tokensIn(), task.tokensOut(), task.agentSessionId(), task.createdAt(),
                task.endedAt(), task.errorMessage(), task.name(), task.roleSkill(), task.workModel(),
                task.pushedAt(), phase, task.agendaJson(), task.consecutiveAutoPushes(),
                task.linkedPrRef(), task.openingPrompt(), task.origin());
    }

    private static PullRequest prWithNumber(int number)
    {
        return new PullRequest(
                /* id */ 1L, "acme/widget", number, "Test PR", "alice",
                "https://github.com/acme/widget/pull/" + number,
                Instant.parse("2026-05-22T12:00:00Z"),
                Instant.parse("2026-05-22T12:00:00Z"),
                PullRequest.Origin.AUTHORED,
                List.of(), Map.of(), /* draft */ false,
                /* viewedAt */ null, /* reviewedAt */ null,
                /* handledAction */ null,
                List.of(),
                /* ciStatus */ null,
                /* additions */ 0, /* deletions */ 0, /* commentCount */ 0,
                /* attentionReason */ null,
                /* state */ "open",
                /* closedAt */ null, /* mergedAt */ null,
                /* mergeable */ null, /* mergeableState */ null,
                /* headPushedAt */ null,
                /* reviewerVerdicts */ Map.of(),
                /* snoozedUntil */ null, /* snoozeWakeReason */ null,
                /* headRef */ "dev/task-1");
    }
}

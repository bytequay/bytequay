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
package com.bytequay.app.cherrypick;

import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.app.ci.CiAutofixService;
import com.bytequay.app.git.command.GitCommandRunner;
import com.bytequay.app.rebase.BranchRebaseService;
import com.bytequay.app.rebase.StartBranchRebaseCommand;
import com.bytequay.app.repository.github.GitHubPullRequestIdentity;
import com.bytequay.app.workspace.WorkspaceException;
import com.bytequay.core.cherrypick.CherryPickExecution;
import com.bytequay.core.cherrypick.CherryPickExecution.FinishReason;
import com.bytequay.core.cherrypick.CherryPickExecution.PullRequestDraft;
import com.bytequay.core.cherrypick.CherryPickExecution.Range;
import com.bytequay.core.cherrypick.CherryPickExecution.Status;
import com.bytequay.core.cherrypick.CherryPickExecution.Workspace;
import com.bytequay.core.cherrypick.CherryPickExecutionId;
import com.bytequay.core.cherrypick.CherryPickItem;
import com.bytequay.core.cherrypick.CherryPickItem.Disposition;
import com.bytequay.core.cherrypick.CherryPickLifecycle;
import com.bytequay.core.cherrypick.CherryPickLifecycle.BranchCommit;
import com.bytequay.core.cherrypick.CherryPickLifecycle.BranchCommit.Origin;
import com.bytequay.core.cherrypick.CherryPickLifecycle.Hold;
import com.bytequay.core.cherrypick.CherryPickLifecycle.Rebase;
import com.bytequay.core.cherrypick.CherryPickLifecycle.Squash;
import com.bytequay.core.cherrypick.CherryPickLifecycle.Stage;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixRunId;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.rebase.BranchRebase;
import com.bytequay.core.rebase.BranchRebaseId;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

import static com.bytequay.app.git.GitTestSupport.commitFile;
import static com.bytequay.app.git.GitTestSupport.git;
import static com.bytequay.app.git.GitTestSupport.head;
import static com.bytequay.app.git.GitTestSupport.initRepository;
import static com.bytequay.app.git.GitTestSupport.write;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestCherryPickDelivery
{
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC);
    private static final String SOURCE = "1".repeat(40);
    private static final String TASK_ID = TaskId.random().toString();
    private static final String WORK = "bytequay/task/" + TASK_ID;

    private final InMemoryCherryPickExecutionStore store = new InMemoryCherryPickExecutionStore();
    private final WorkspacePullRequestService publication = mock(WorkspacePullRequestService.class);
    private final CiAutofixService ci = mock(CiAutofixService.class);
    private final BranchRebaseService rebases = mock(BranchRebaseService.class);
    private final CherryPickPreviewService previews = mock(CherryPickPreviewService.class);

    @TempDir
    private Path temporaryDirectory;

    private Path repository;
    private String base;
    private CherryPickDelivery delivery;

    @BeforeEach
    void setUp()
            throws IOException
    {
        repository = initRepository(temporaryDirectory.resolve(TASK_ID));
        base = commitFile(repository, "seed.txt", "seed\n", "Seed");
        git(repository, "checkout", "--quiet", "-b", WORK);
        delivery = new CherryPickDelivery(store, publication, ci, rebases, previews, new GitCommandRunner(), CLOCK);
        CiAutofixRun started = run(Optional.empty(), base);
        when(publication.startCi(any(), anyInt())).thenReturn(started);
        when(previews.synchronizeTarget(any(), any())).thenReturn(base);
    }

    @Test
    void publishesAndStartsTheFirstCiRound()
    {
        String copy = copy();
        CherryPickExecution publishing = save(execution(copy).withLifecycle(lifecycle -> lifecycle.inStage(Stage.PUBLISH), CLOCK.instant()));
        when(publication.publish(publishing.id())).thenAnswer(invocation -> store.save(store.find(publishing.id()).orElseThrow()
                .recordPush("origin", "work", copy, CLOCK.instant())
                .recordPullRequest(7, 17, "https://github.com/acme/demo/pull/17", CLOCK.instant())));

        CherryPickExecution published = delivery.advance(publishing);

        assertThat(published.lifecycle().stage()).isEqualTo(Stage.CI_FIX);
        assertThat(published.lifecycle().ciRunIds()).hasSize(1);
        assertThat(published.delivery().flatMap(CherryPickExecution.Delivery::pullRequestNumber)).contains(17);
        verify(publication).startCi(any(), eq(1));
    }

    @Test
    void aFailedPublicationHoldsUntilTheUserResumes()
    {
        CherryPickExecution publishing = save(execution(copy()).withLifecycle(lifecycle -> lifecycle.inStage(Stage.PUBLISH), CLOCK.instant()));
        when(publication.publish(publishing.id())).thenThrow(new IllegalStateException("origin is read-only"));

        CherryPickExecution held = delivery.advance(publishing);

        assertThat(held.lifecycle().hold().map(Hold::reason)).contains(Hold.Reason.PUBLISH_FAILED);
        assertThat(held.lifecycle().hold().map(Hold::summary).orElseThrow()).contains("origin is read-only");
        assertThat(delivery.resume(held, Optional.empty()).lifecycle().hold()).isEmpty();
    }

    @Test
    void greenCiWithFixupsWaitsForApprovalThenFoldsAndReplacesTheBranch()
    {
        String copy = copy();
        String fixup = fixup();
        CiAutofixRun green = run(Optional.of(CiAutofixRun.FinishReason.GREEN), fixup);
        CherryPickExecution watching = save(delivered(execution(copy).withTip(fixup, CLOCK.instant()), Stage.CI_FIX, green));

        CherryPickExecution waiting = delivery.advance(watching);
        assertThat(waiting.lifecycle().stage()).isEqualTo(Stage.SQUASH);
        assertThat(waiting.lifecycle().fixups()).extracting(BranchCommit::fixupOf).containsExactly(Optional.of(SOURCE));
        assertThat(delivery.advance(waiting).lifecycle().squash()).as("nothing folds before the user approves").isEmpty();

        CherryPickExecution approved = store.save(store.find(watching.id()).orElseThrow().withLifecycle(
                lifecycle -> lifecycle.withSquash(Optional.of(new Squash(fixup, CLOCK.instant(), Optional.empty(), ImmutableList.of()))), CLOCK.instant()));
        String tree = git(repository, "rev-parse", fixup + "^{tree}");

        CherryPickExecution squashed = delivery.advance(approved);

        String folded = head(repository);
        assertThat(squashed.lifecycle().stage()).isEqualTo(Stage.MERGE);
        assertThat(squashed.currentTargetSha()).isEqualTo(folded);
        assertThat(git(repository, "rev-parse", folded + "^{tree}")).as("folding never changes the result").isEqualTo(tree);
        assertThat(squashed.lifecycle().commits()).extracting(BranchCommit::sourceSha).containsExactly(Optional.of(SOURCE));
        assertThat(squashed.lifecycle().squash().flatMap(Squash::resultHead)).contains(folded);
        assertThat(squashed.lifecycle().squash().orElseThrow().fixups()).hasSize(1);
        verify(publication).replaceBranch(any(), eq(folded), eq(fixup));
        verify(publication).startCi(any(), eq(2));
    }

    @Test
    void greenSquashedHeadFinishesWhenTheUserMerged()
    {
        String copy = copy();
        CiAutofixRun green = run(Optional.of(CiAutofixRun.FinishReason.GREEN), copy);
        CherryPickExecution merging = save(delivered(execution(copy), Stage.MERGE, green));
        RepoRef repository = RepoRef.of("acme", "demo");
        when(publication.pullRequest(any())).thenReturn(new GitHubPullRequestIdentity(new PullRequestRef("acme", "demo", 17), "https://github.com/acme/demo/pull/17",
                repository, WORK, copy, repository, "main", base, GitHubPullRequestIdentity.State.MERGED, false, "user"));
        when(publication.withdraw(any(), eq(false))).thenReturn(ImmutableList.of());

        CherryPickExecution merged = delivery.advance(merging);

        verify(publication, never()).markReady(any());
        assertThat(merged.status()).isEqualTo(Status.FINISHED);
        assertThat(merged.finishReason()).contains(FinishReason.COMPLETED);
        assertThat(merged.summary()).contains("Merged into main");
    }

    @Test
    void aRetargetedPullRequestHoldsCompletionAndPreservesTheBranch()
    {
        String copy = copy();
        CiAutofixRun green = run(Optional.of(CiAutofixRun.FinishReason.GREEN), copy);
        CherryPickExecution merging = save(delivered(execution(copy), Stage.MERGE, green));
        when(publication.pullRequest(any())).thenThrow(WorkspaceException.deliveryUnavailable("The pull request was retargeted"));

        CherryPickExecution held = delivery.advance(merging);

        assertThat(held.status()).isEqualTo(Status.RUNNING);
        assertThat(held.lifecycle().hold().orElseThrow().summary()).contains("retargeted");
        verify(publication, never()).markReady(any());
        verify(publication, never()).withdraw(any(), anyBoolean());
    }

    @Test
    void aGreenSquashedDraftIsMarkedReadyOnceAndWaitsForTheMerge()
    {
        String copy = copy();
        CiAutofixRun green = run(Optional.of(CiAutofixRun.FinishReason.GREEN), copy);
        CherryPickExecution merging = save(delivered(execution(copy), Stage.MERGE, green));
        RepoRef repository = RepoRef.of("acme", "demo");
        when(publication.pullRequest(any())).thenReturn(new GitHubPullRequestIdentity(new PullRequestRef("acme", "demo", 17), "https://github.com/acme/demo/pull/17",
                repository, WORK, copy, repository, "main", base, GitHubPullRequestIdentity.State.OPEN, true, "user"));

        CherryPickExecution waiting = delivery.advance(merging);

        assertThat(waiting.status()).isEqualTo(Status.RUNNING);
        assertThat(waiting.lifecycle().readyForReview()).isTrue();
        verify(publication).markReady(any());
    }

    @Test
    void aMovedTargetStopsCiAndRebasesOnTheTasksOwnAgent()
    {
        String copy = copy();
        CiAutofixRun active = run(Optional.empty(), copy);
        CherryPickExecution watching = save(delivered(execution(copy), Stage.CI_FIX, active));
        String target = moveTarget();
        when(previews.synchronizeTarget(any(), any())).thenReturn(target);
        CiAutofixRun cancelled = run(Optional.of(CiAutofixRun.FinishReason.CANCELLED), copy);
        when(ci.cancel(active.id())).thenReturn(cancelled);
        TaskWorktree worktree = new TaskWorktree(TaskId.valueOf(TASK_ID), "workspace-id", RepositoryIdentity.valueOf("acme/demo"), repository, WORK, base);
        when(rebases.start(any())).thenReturn(rebase(copy, target));

        CherryPickExecution rebasing = delivery.advance(watching);

        verify(ci).cancel(active.id());
        ArgumentCaptor<StartBranchRebaseCommand> command = ArgumentCaptor.forClass(StartBranchRebaseCommand.class);
        verify(rebases).start(command.capture());
        assertThat(command.getValue().baseBranch()).isEqualTo("main-target");
        assertThat(command.getValue().taskWorktree()).isEqualTo(worktree);
        Rebase entry = rebasing.lifecycle().liveRebase().orElseThrow();
        assertThat(entry.number()).isEqualTo(1);
        assertThat(entry.targetMoved()).isEqualTo(1);
        assertThat(entry.baseSha()).isEqualTo(target);
    }

    @Test
    void aFinishedRebaseIsCheckedReplacedAndSentBackThroughCi()
    {
        String copy = copy();
        String target = moveTarget();
        git(repository, "-c", "core.editor=true", "rebase", "--quiet", "main");
        String rebased = head(repository);
        git(repository, "checkout", "--quiet", "-B", WORK, copy);
        BranchRebase finished = rebase(copy, target).finish(BranchRebase.FinishReason.COMPLETED, "Rebased", Optional.of(rebased), CLOCK.instant());
        BranchRebaseId rebaseId = finished.id();
        Rebase entry = new Rebase(1, target, 1, Optional.of(rebaseId.toString()), Rebase.State.RUNNING, Optional.empty(), CLOCK.instant(), Optional.empty());
        CiAutofixRun cancelled = run(Optional.of(CiAutofixRun.FinishReason.CANCELLED), copy);
        CherryPickExecution rebasing = save(delivered(execution(copy), Stage.CI_FIX, cancelled).withLifecycle(lifecycle -> lifecycle.withRebase(entry), CLOCK.instant()));
        when(rebases.get(rebaseId)).thenReturn(finished);

        CherryPickExecution resumed = delivery.advance(rebasing);

        verify(publication).replaceBranch(any(), eq(rebased), eq(copy));
        assertThat(resumed.currentTargetSha()).isEqualTo(rebased);
        assertThat(resumed.lifecycle().baseSha()).isEqualTo(target);
        assertThat(resumed.lifecycle().rebases()).extracting(Rebase::state).containsExactly(Rebase.State.DONE);
        assertThat(resumed.lifecycle().rebases().getFirst().summary()).contains("Replayed 1 commits cleanly");
        assertThat(resumed.lifecycle().ciRunIds()).hasSize(2);
    }

    @Test
    void withdrawingStopsCiAndAsksToClosePullRequestAndBranch()
    {
        String copy = copy();
        CiAutofixRun active = run(Optional.empty(), copy);
        CherryPickExecution watching = save(delivered(execution(copy), Stage.CI_FIX, active));
        when(publication.withdraw(any(), eq(true))).thenReturn(ImmutableList.of("Could not close pull request #17: offline"));

        List<String> problems = delivery.withdraw(watching);

        verify(ci).cancel(active.id());
        assertThat(problems).containsExactly("Could not close pull request #17: offline");
    }

    private String copy()
    {
        write(repository, "change.txt", "change\n");
        git(repository, "add", "-A");
        git(repository, "commit", "--quiet", "-m", "Port change", "-m", "(cherry picked from commit " + SOURCE + ")");
        return head(repository);
    }

    private String fixup()
    {
        String target = head(repository);
        write(repository, "change.txt", "change, repaired\n");
        git(repository, "add", "-A");
        git(repository, "commit", "--quiet", "--fixup=" + target);
        return head(repository);
    }

    /** Lands one more commit on the target, which the work branch does not contain. */
    private String moveTarget()
    {
        String work = head(repository);
        git(repository, "checkout", "--quiet", "main");
        String target = commitFile(repository, "target.txt", "target\n", "Target moved");
        git(repository, "checkout", "--quiet", WORK);
        assertThat(head(repository)).isEqualTo(work);
        return target;
    }

    private CherryPickExecution execution(String copy)
    {
        CherryPickExecution created = CherryPickExecution.create(
                CherryPickExecutionId.random(),
                "key-" + copy,
                new Workspace("workspace-id", RepoRef.of("acme", "demo"), RepoRef.of("acme", "demo"), "upstream", "origin", "main", "main-target", WORK,
                        repository, "claude", Optional.of(RepoRef.of("acme", "upstream")), Optional.empty(), Optional.empty()),
                Optional.of(new PullRequestDraft("Cherry-pick changes", "")),
                new Range("2".repeat(40), SOURCE, base),
                ImmutableList.of(CherryPickItem.planned(1, SOURCE, "2".repeat(40), "Port change", Optional.empty(), Disposition.TO_CHERRY_PICK)),
                true,
                CLOCK.instant());
        CherryPickExecution applied = store.createOrGet(created.withAgentTask(TASK_ID, CLOCK.instant()).recordApplied(SOURCE, copy, CLOCK.instant()));
        UnaryOperator<CherryPickLifecycle> built = lifecycle -> lifecycle.inStage(Stage.BUILD).withBranch(base, branchCommits());
        return applied.withLifecycle(built, CLOCK.instant());
    }

    /** Publishes the execution and points it at one CI round. */
    private CherryPickExecution delivered(CherryPickExecution execution, Stage stage, CiAutofixRun run)
    {
        when(ci.get(run.id())).thenReturn(run);
        return execution.withLifecycle(lifecycle -> lifecycle.inStage(Stage.PUBLISH), CLOCK.instant())
                .recordPush("origin", "work", execution.currentTargetSha(), CLOCK.instant())
                .recordPullRequest(7, 17, "https://github.com/acme/demo/pull/17", CLOCK.instant())
                .withLifecycle(lifecycle -> lifecycle.inStage(stage).withCiRun(run.id().toString()).withBranch(base, branchCommits()), CLOCK.instant());
    }

    private List<BranchCommit> branchCommits()
    {
        return new CherryPickBranch(new GitCommandRunner()).inspect(repository, base, head(repository), ImmutableList.of(SOURCE), ImmutableList.of(), Origin.CONFLICT).commits();
    }

    private CherryPickExecution save(CherryPickExecution execution)
    {
        CherryPickExecution stored = store.find(execution.id()).orElseThrow();
        return store.save(execution.withVersion(stored.version()));
    }

    private static CiAutofixRun run(Optional<CiAutofixRun.FinishReason> finishReason, String head)
    {
        RepoRef repository = RepoRef.of("acme", "demo");
        CiAutofixRun active = CiAutofixRun.create(CiAutofixRunId.random(), "ci-" + head, new CiAutofixRun.Scope(new PullRequestRef("acme", "demo", 17), repository,
                "workspace-id", repository, "user", "work", "main", "claude", Optional.empty()), head, head, head,
                new CiAutofixRun.Authorization(CLOCK.instant()), CLOCK.instant());
        return finishReason.map(reason -> active.finish(reason, "Finished", CLOCK.instant())).orElse(active);
    }

    private BranchRebase rebase(String tip, String target)
    {
        return BranchRebase.create(BranchRebaseId.random(), "rebase-" + target, TASK_ID, RepoRef.of("acme", "demo"), repository, WORK, "main-target", tip, target, CLOCK.instant());
    }
}

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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.DiffFile;
import com.bytequay.app.domain.InvestigationReviewData;
import com.bytequay.app.domain.InvestigationReviewData.AgentReviewRow;
import com.bytequay.app.domain.InvestigationReviewData.CriterionRow;
import com.bytequay.app.domain.InvestigationReviewData.FindingRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewObjectiveRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewRoundMessageRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewRoundRow;
import com.bytequay.app.domain.InvestigationReviewData.RoundBudget;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRComment;
import com.bytequay.app.domain.PRCommit;
import com.bytequay.app.repository.PRStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.repository.sqlite.InvestigationReviewStore;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.review.InvestigationReviewRunner.ProviderChoice;
import com.bytequay.app.service.review.InvestigationReviewRunner.RunOutcome;
import com.bytequay.app.service.runs.AgentRunService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestInvestigationReviewGuidance
{
    private static final ProviderChoice API =
            new ProviderChoice("openai-test", "api", "openai");
    private static final ProviderChoice CLI =
            new ProviderChoice("claude-cli", "cli", "anthropic");

    @Autowired
    private InvestigationReviewStore reviews;
    @Autowired
    private PRStore prs;
    @Autowired
    private PRService localPrs;
    @Autowired
    private PullRequestService pullRequests;
    @Autowired
    private TaskStore tasks;
    @Autowired
    private ThreadStore threads;
    @Autowired
    private WatchedRepoStore watchedRepos;
    @Autowired
    private GitRunner git;
    @Autowired
    private AgentRunService runs;
    @Autowired
    private ObjectMapper mapper;

    @Test
    void seedIsExplicitlyAddedToTheInvestigatorContext()
    {
        assertThat(InvestigationReviewService.guidanceContext(
                "mandatory coverage", "Prioritize retry safety"))
                .contains("mandatory coverage")
                .contains("User seed for this round")
                .contains("Prioritize retry safety")
                .contains("preserving every evidence rule");
        assertThat(InvestigationReviewService.guidanceContext(
                "mandatory coverage", null)).isEqualTo("mandatory coverage");
    }

    @Test
    void answerContinuesAResolvedFindingAsALocalCommentThread()
            throws Exception
    {
        String prId = UUID.randomUUID().toString();
        String repo = "acme/threaded-answer-" + prId;
        prs.save(PR.createExternal(
                prId, repo, 47, "https://example.test/47", "octocat",
                "feature", "main", "Explain the boundary", "", PR.STATUS_REMOTE_OPEN,
                Instant.parse("2026-07-01T00:00:00Z"), null, null));
        prs.addCommit(new PRCommit(
                UUID.randomUUID().toString(), prId, "new-head", "Boundary change",
                1, 0, Instant.parse("2026-07-02T00:00:00Z"), Instant.now()));

        String reviewId = UUID.randomUUID().toString();
        String priorRoundId = UUID.randomUUID().toString();
        String criterionId = UUID.randomUUID().toString();
        String objectiveId = UUID.randomUUID().toString();
        String findingId = UUID.randomUUID().toString();
        AgentRun priorRun = runs.openDetached(
                AgentRun.KIND_PANEL_REVIEW, null, priorRoundId, 50);
        runs.transition(priorRun.id(), AgentRun.STATUS_SUCCEEDED, "fixture complete");
        reviews.insertReview(new AgentReviewRow(
                reviewId, repo, prId, "old-head", "old-head", "ACTIVE",
                null, null, null), Instant.now());
        reviews.insertRound(new ReviewRoundRow(
                priorRoundId, reviewId, priorRun.id(), "initial", "full", "old-head", "old-head",
                "COMPLETED", new RoundBudget(50, 10), 1), Instant.now());
        reviews.insertCriterion(new CriterionRow(
                criterionId, repo, "hard-invariant", "Preserve the boundary", "test", null));
        reviews.insertObjective(new ReviewObjectiveRow(
                objectiveId, priorRoundId, criterionId, "Preserve the boundary", "test",
                "applicable", "finding"));
        reviews.insertFinding(new FindingRow(
                findingId, reviewId, priorRoundId, objectiveId, null, "hard-invariant",
                "The boundary rejects the configured value.", 3, "SUPPORTED", "unknown",
                "Confirm whether that rejection is intentional.", "NEEDS_AUTHOR_INPUT", "old-head"));
        PRComment root = localPrs.addComment(
                prId, PRComment.ORIGIN_LOCAL, PRComment.SCOPE_PR,
                null, null, null, null, null, "agent", "Original finding", null);
        localPrs.attachFinding(root.id(), findingId);
        localPrs.resolveComment(root.id());
        PRComment userReply = localPrs.addComment(
                prId, PRComment.ORIGIN_LOCAL, PRComment.SCOPE_PR,
                null, null, null, null, null, "you",
                "The rejection is intentional for legacy callers.", root.id());

        ReplyModel model = new ReplyModel();
        InvestigationReviewService service = new InvestigationReviewService(
                reviews, new FixedContext(localPrs, pullRequests, tasks, watchedRepos, git),
                model, runs, localPrs, tasks, threads, mapper);

        InvestigationReviewData started = service.answer(
                findingId, "The rejection is intentional for legacy callers.");
        String roundId = started.rounds().get(started.rounds().size() - 1).id();
        assertThat(started.prComments()).extracting(comment -> comment.id())
                .contains(root.id(), userReply.id());
        assertThat(started.prComments().stream()
                .filter(comment -> comment.id().equals(root.id()))
                .findFirst().orElseThrow().resolvedAt()).isNotNull();

        awaitTerminal(roundId);
        InvestigationReviewData finished = service.roundLog(roundId);
        assertThat(model.contexts).isNotEmpty();
        assertThat(model.contexts.get(0))
                .contains("Original finding:")
                .contains("The boundary rejects the configured value.")
                .contains("Confirm whether that rejection is intentional.")
                .contains("User reply:")
                .contains("The rejection is intentional for legacy callers.");
        assertThat(finished.prComments()).anySatisfy(comment -> {
            assertThat(comment.parentCommentId()).isEqualTo(root.id());
            assertThat(comment.author()).isEqualTo("agent");
            assertThat(comment.body()).isEqualTo(
                    "Understood. I re-checked the boundary and the rejection matches that intent.");
        });
        assertThat(finished.prComments().stream()
                .filter(comment -> comment.id().equals(root.id()))
                .findFirst().orElseThrow().resolvedAt()).isNotNull();
    }

    @Test
    void acceptedGuidanceIsConsumedBeforeFinishAndLaterTurnsReadTheRaisedCap()
            throws Exception
    {
        String prId = UUID.randomUUID().toString();
        String repo = "acme/guidance-" + prId;
        prs.save(PR.createExternal(
                prId, repo, 42, "https://example.test/42", "octocat",
                "feature", "main", "Guide this review", "", PR.STATUS_REMOTE_OPEN,
                Instant.parse("2026-07-01T00:00:00Z"), null, null));
        prs.addCommit(new PRCommit(
                UUID.randomUUID().toString(), prId, "old-head", "Existing change",
                1, 0, Instant.parse("2026-07-01T00:00:00Z"), Instant.now()));
        prs.addCommit(new PRCommit(
                UUID.randomUUID().toString(), prId, "new-head", "Retry boundary",
                1, 0, Instant.parse("2026-07-02T00:00:00Z"), Instant.now()));
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch allCalls = new CountDownLatch(3);
        List<Integer> caps = new CopyOnWriteArrayList<>();
        List<String> contexts = new CopyOnWriteArrayList<>();
        InvestigationReviewModel model = new RecordingModel(
                firstStarted, releaseFirst, allCalls, caps, contexts);
        InvestigationReviewContext context = new FixedContext(
                localPrs, pullRequests, tasks, watchedRepos, git);
        InvestigationReviewService service = new InvestigationReviewService(
                reviews, context, model, runs, localPrs, tasks, threads, mapper);

        String reviewId = UUID.randomUUID().toString();
        reviews.insertReview(new AgentReviewRow(
                reviewId, repo, prId, "old-head", "old-head", "ACTIVE",
                null, null, null), Instant.now());

        InvestigationReviewData created = service.createRound(
                reviewId, "continuation", List.of(), null,
                "Prioritize retry safety", 150);
        String roundId = created.rounds().get(0).id();
        assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(created.rounds().get(0).scope()).isEqualTo("delta");
        assertThat(created.objectives()).singleElement()
                .extracting(ReviewObjectiveRow::statement)
                .isEqualTo("Prioritize retry safety");
        assertThat(created.roundMessages()).singleElement()
                .extracting(ReviewRoundMessageRow::body)
                .isEqualTo("Prioritize retry safety");
        assertThat(created.reviewedCommits())
                .extracting(commit -> commit.sha() + ":" + commit.message())
                .containsExactly("new-head:Retry boundary");

        service.postRoundMessage(roundId, "panel", "Inspect the retry cancellation path");
        assertThatThrownBy(() -> service.updateRoundBudget(roundId, 50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("in-flight reservation");
        releaseFirst.countDown();

        assertThat(allCalls.await(5, TimeUnit.SECONDS)).isTrue();
        awaitTerminal(roundId);

        assertThat(contexts).hasSize(3);
        assertThat(contexts.get(0)).contains("Prioritize retry safety");
        assertThat(contexts.get(1))
                .contains("User guidance checkpoint")
                .contains("Guidance: Inspect the retry cancellation path");
        assertThat(caps).containsExactly(75, 25, 140);
        assertThat(reviews.roundMessages(reviewId)).filteredOn(
                message -> message.body().equals("Inspect the retry cancellation path"))
                .singleElement().satisfies(message -> {
                    assertThat(message.status()).isEqualTo("completed");
                    assertThat(message.response()).contains("reviewer response 2");
                    assertThat(message.completedAt()).isNotNull();
                    assertThat(message.assignmentId()).isNotBlank();
                    assertThat(reviews.assignments(reviewId))
                            .extracting(assignment -> assignment.id())
                            .contains(message.assignmentId());
                });
        assertThat(reviews.findRound(roundId)).get().satisfies(round -> {
            assertThat(round.status()).startsWith("COMPLETED");
            assertThat(round.budgetJson().costCapCents()).isEqualTo(150);
        });
    }

    @Test
    void failedGuidanceStillConsumesBudgetBeforeTheNextPanelSeat()
            throws Exception
    {
        String prId = UUID.randomUUID().toString();
        String repo = "acme/guidance-cost-" + prId;
        prs.save(PR.createExternal(
                prId, repo, 45, "https://example.test/45", "octocat",
                "feature", "main", "Charge failed guidance", "", PR.STATUS_REMOTE_OPEN,
                Instant.parse("2026-07-01T00:00:00Z"), null, null));
        prs.addCommit(new PRCommit(
                UUID.randomUUID().toString(), prId, "new-head", "Guidance accounting",
                1, 0, Instant.parse("2026-07-02T00:00:00Z"), Instant.now()));
        GuidanceErrorModel model = new GuidanceErrorModel();
        InvestigationReviewService service = new InvestigationReviewService(
                reviews, new FixedContext(localPrs, pullRequests, tasks, watchedRepos, git),
                model, runs, localPrs, tasks, threads, mapper);
        String reviewId = UUID.randomUUID().toString();
        reviews.insertReview(new AgentReviewRow(
                reviewId, repo, prId, "old-head", "old-head", "ACTIVE",
                null, null, null), Instant.now());

        InvestigationReviewData created = service.createRound(
                reviewId, "continuation", List.of(), null, "Initial focus", 50);
        String roundId = created.rounds().get(0).id();
        assertThat(model.firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
        service.postRoundMessage(roundId, "panel", "This turn fails after billing");
        model.releaseFirst.countDown();

        assertThat(model.allCalls.await(5, TimeUnit.SECONDS)).isTrue();
        awaitTerminal(roundId);
        assertThat(model.caps).containsExactly(25, 25, 42);
        assertThat(reviews.findRound(roundId)).get()
                .extracting(round -> round.costCents()).isEqualTo(9);
        assertThat(reviews.roundMessages(reviewId)).filteredOn(
                message -> message.body().equals("This turn fails after billing"))
                .singleElement().extracting(ReviewRoundMessageRow::status).isEqualTo("failed");
    }

    @Test
    void laterRoundsWaitForTheActiveRoundBeforeStarting()
            throws Exception
    {
        String prId = UUID.randomUUID().toString();
        String repo = "acme/queue-" + prId;
        prs.save(PR.createExternal(
                prId, repo, 43, "https://example.test/43", "octocat",
                "feature", "main", "Queue review rounds", "", PR.STATUS_REMOTE_OPEN,
                Instant.parse("2026-07-01T00:00:00Z"), null, null));
        prs.addCommit(new PRCommit(
                UUID.randomUUID().toString(), prId, "old-head", "Existing change",
                1, 0, Instant.parse("2026-07-01T00:00:00Z"), Instant.now()));
        prs.addCommit(new PRCommit(
                UUID.randomUUID().toString(), prId, "new-head", "Queued review",
                1, 0, Instant.parse("2026-07-02T00:00:00Z"), Instant.now()));
        QueueModel model = new QueueModel();
        InvestigationReviewService service = new InvestigationReviewService(
                reviews, new FixedContext(localPrs, pullRequests, tasks, watchedRepos, git),
                model, runs, localPrs, tasks, threads, mapper);
        String reviewId = UUID.randomUUID().toString();
        reviews.insertReview(new AgentReviewRow(
                reviewId, repo, prId, "old-head", "old-head", "ACTIVE",
                null, null, null), Instant.now());

        InvestigationReviewData first = service.createRound(
                reviewId, "continuation", List.of(), null, "First focus", 100);
        assertThat(model.firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
        InvestigationReviewData second = service.createRound(
                reviewId, "continuation", List.of(), null, "Second focus", 100);
        String firstRoundId = first.rounds().get(0).id();
        String secondRoundId = second.rounds().get(1).id();

        assertThat(reviews.findRound(secondRoundId)).get()
                .extracting(round -> round.status()).isEqualTo("QUEUED");
        assertThat(model.calls).hasValue(1);

        model.releaseFirst.countDown();
        assertThat(model.secondRoundStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(reviews.findRound(firstRoundId)).get()
                .extracting(round -> round.status()).isNotIn("QUEUED", "RUNNING");
        awaitTerminal(secondRoundId);
    }

    @Test
    void cancellingARunningRoundLetsItsInterruptedWorkerFinalize()
            throws Exception
    {
        String prId = UUID.randomUUID().toString();
        String repo = "acme/cancel-running-" + prId;
        prs.save(PR.createExternal(
                prId, repo, 46, "https://example.test/46", "octocat",
                "feature", "main", "Cancel a running review", "", PR.STATUS_REMOTE_OPEN,
                Instant.parse("2026-07-01T00:00:00Z"), null, null));
        prs.addCommit(new PRCommit(
                UUID.randomUUID().toString(), prId, "new-head", "Running review",
                1, 0, Instant.parse("2026-07-02T00:00:00Z"), Instant.now()));
        QueueModel model = new QueueModel();
        InvestigationReviewService service = new InvestigationReviewService(
                reviews, new FixedContext(localPrs, pullRequests, tasks, watchedRepos, git),
                model, runs, localPrs, tasks, threads, mapper);
        String reviewId = UUID.randomUUID().toString();
        reviews.insertReview(new AgentReviewRow(
                reviewId, repo, prId, "old-head", "old-head", "ACTIVE",
                null, null, null), Instant.now());

        InvestigationReviewData created = service.createRound(
                reviewId, "continuation", List.of(), null, "First", 100);
        String roundId = created.rounds().get(0).id();
        assertThat(model.firstStarted.await(5, TimeUnit.SECONDS)).isTrue();

        service.cancelRound(roundId);

        awaitTerminal(roundId);
        assertThat(reviews.findRound(roundId)).get()
                .extracting(round -> round.status()).isEqualTo("CANCELLED");
        assertThat(reviews.isRoundFinalized(roundId)).isTrue();
    }

    @Test
    void cancellingAMiddleQueuedRoundCannotReleaseItsSuccessor()
            throws Exception
    {
        String prId = UUID.randomUUID().toString();
        String repo = "acme/cancel-queue-" + prId;
        prs.save(PR.createExternal(
                prId, repo, 44, "https://example.test/44", "octocat",
                "feature", "main", "Cancel a queued review", "", PR.STATUS_REMOTE_OPEN,
                Instant.parse("2026-07-01T00:00:00Z"), null, null));
        prs.addCommit(new PRCommit(
                UUID.randomUUID().toString(), prId, "new-head", "Queued review",
                1, 0, Instant.parse("2026-07-02T00:00:00Z"), Instant.now()));
        QueueModel model = new QueueModel();
        InvestigationReviewService service = new InvestigationReviewService(
                reviews, new FixedContext(localPrs, pullRequests, tasks, watchedRepos, git),
                model, runs, localPrs, tasks, threads, mapper);
        String reviewId = UUID.randomUUID().toString();
        reviews.insertReview(new AgentReviewRow(
                reviewId, repo, prId, "old-head", "old-head", "ACTIVE",
                null, null, null), Instant.now());

        InvestigationReviewData first = service.createRound(
                reviewId, "continuation", List.of(), null, "First", 100);
        assertThat(model.firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
        InvestigationReviewData second = service.createRound(
                reviewId, "continuation", List.of(), null, "Second", 100);
        InvestigationReviewData third = service.createRound(
                reviewId, "continuation", List.of(), null, "Third", 100);
        String secondRoundId = second.rounds().get(1).id();
        String thirdRoundId = third.rounds().get(2).id();

        service.cancelRound(secondRoundId);
        Thread.sleep(100);
        assertThat(model.calls).hasValue(1);
        assertThat(reviews.findRound(thirdRoundId)).get()
                .extracting(round -> round.status()).isEqualTo("QUEUED");

        model.releaseFirst.countDown();
        assertThat(model.secondRoundStarted.await(5, TimeUnit.SECONDS)).isTrue();
        awaitTerminal(first.rounds().get(0).id());
        awaitTerminal(thirdRoundId);
        assertThat(reviews.findRound(secondRoundId)).get()
                .extracting(round -> round.status()).isEqualTo("CANCELLED");
    }

    @Test
    void failedTerminalRecoveryRemainsFencedForStartupRetry()
    {
        ManualRound manual = insertManualRound("terminal-retry");
        assertThat(reviews.finishRunningRound(
                manual.roundId(), "COMPLETED", "new-head", 3)).isTrue();
        PRService failingPrs = failingReviewEvents();
        InvestigationReviewService service = new InvestigationReviewService(
                reviews, new FixedContext(localPrs, pullRequests, tasks, watchedRepos, git),
                new QueueModel(), runs, failingPrs, tasks, threads, mapper);

        service.recoverTerminalLifecycleAfterWorker(manual.roundId());

        assertThat(reviews.isRoundFinalized(manual.roundId())).isFalse();
    }

    @Test
    void startupRecoveryPreservesAnAlreadyCancelledRoundAsCancellation()
    {
        ManualRound manual = insertManualRound("cancel-recovery");
        assertThat(reviews.cancelLiveRound(manual.roundId(), 0)).isTrue();
        InvestigationReviewService service = new InvestigationReviewService(
                reviews, new FixedContext(localPrs, pullRequests, tasks, watchedRepos, git),
                new QueueModel(), runs, localPrs, tasks, threads, mapper);

        service.reconcileInterruptedRounds();

        assertThat(reviews.isRoundFinalized(manual.roundId())).isTrue();
        assertThat(localPrs.timeline(manual.prId())).anySatisfy(event -> {
            try {
                assertThat(mapper.readTree(event.payloadJson()).path("reviewEvent").asText())
                        .isEqualTo("round-cancelled");
            }
            catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    @Test
    void cancellationSideEffectFailureDoesNotClaimLifecycleFinality()
    {
        ManualRound manual = insertManualRound("cancel-side-effect");
        PRService failingPrs = failingReviewEvents();
        InvestigationReviewService service = new InvestigationReviewService(
                reviews, new FixedContext(localPrs, pullRequests, tasks, watchedRepos, git),
                new QueueModel(), runs, failingPrs, tasks, threads, mapper);

        assertThatThrownBy(() -> service.cancelRound(manual.roundId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("timeline unavailable");
        assertThat(reviews.isRoundFinalized(manual.roundId())).isFalse();
    }

    private ManualRound insertManualRound(String name)
    {
        String prId = UUID.randomUUID().toString();
        String reviewId = UUID.randomUUID().toString();
        String roundId = UUID.randomUUID().toString();
        prs.save(PR.createExternal(
                prId, "acme/" + name + "-" + prId, 46, "https://example.test/46", "octocat",
                "feature", "main", name, "", PR.STATUS_REMOTE_OPEN,
                Instant.parse("2026-07-01T00:00:00Z"), null, null));
        reviews.insertReview(new AgentReviewRow(
                reviewId, "acme/" + name, prId, "old-head", "old-head", "ACTIVE",
                null, null, null), Instant.now());
        AgentRun run = runs.openDetached(
                AgentRun.KIND_PANEL_REVIEW, null, roundId, 50);
        reviews.insertLiveRound(new ReviewRoundRow(
                roundId, reviewId, run.id(), "continuation", "full", "new-head", null,
                "RUNNING", new RoundBudget(50, 10), 0), Instant.now());
        return new ManualRound(prId, roundId);
    }

    private PRService failingReviewEvents()
    {
        return (PRService) Proxy.newProxyInstance(
                PRService.class.getClassLoader(), new Class<?>[] {PRService.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("recordReviewEvent")) {
                        throw new IllegalStateException("timeline unavailable");
                    }
                    try {
                        return method.invoke(localPrs, args);
                    }
                    catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private record ManualRound(String prId, String roundId) {}

    private void awaitTerminal(String roundId)
            throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline
                && reviews.findRound(roundId)
                        .map(round -> Set.of("QUEUED", "RUNNING").contains(round.status())
                                || !reviews.isRoundFinalized(roundId))
                        .orElse(false)) {
            Thread.sleep(10);
        }
        assertThat(reviews.findRound(roundId)).get()
                .extracting(round -> round.status()).isNotIn("QUEUED", "RUNNING");
    }

    private static final class FixedContext
            extends InvestigationReviewContext
    {
        private static final String DIFF = """
                diff --git a/src/A.java b/src/A.java
                @@ -1 +1 @@
                -oldA
                +newA
                diff --git a/src/B.java b/src/B.java
                @@ -1 +1 @@
                -oldB
                +newB
                """;

        private FixedContext(
                PRService prs, PullRequestService pullRequests, TaskStore tasks,
                WatchedRepoStore watchedRepos, GitRunner git)
        {
            super(prs, pullRequests, tasks, watchedRepos, git);
        }

        @Override
        public Snapshot load(PR pr)
        {
            return new Snapshot(pr, "old-head", "new-head", DIFF,
                    List.of(
                            new DiffFile("src/A.java", "modified", 1, 1, null),
                            new DiffFile("src/B.java", "modified", 1, 1, null)),
                    null);
        }

        @Override
        public Snapshot load(PR pr, boolean allowWorkspaceSource)
        {
            return load(pr);
        }

        @Override
        public String headCommit(PR pr)
        {
            return "new-head";
        }

        @Override
        public int fileLineCount(Snapshot snapshot, String path)
        {
            return 100;
        }
    }

    private static final class RecordingModel
            implements InvestigationReviewModel
    {
        private final CountDownLatch firstStarted;
        private final CountDownLatch releaseFirst;
        private final CountDownLatch allCalls;
        private final List<Integer> caps;
        private final List<String> contexts;
        private int calls;

        private RecordingModel(
                CountDownLatch firstStarted, CountDownLatch releaseFirst,
                CountDownLatch allCalls, List<Integer> caps, List<String> contexts)
        {
            this.firstStarted = firstStarted;
            this.releaseFirst = releaseFirst;
            this.allCalls = allCalls;
            this.caps = caps;
            this.contexts = contexts;
        }

        @Override
        public ProviderChoice choose(String requestedRunner, String requestedProvider)
        {
            return "cli".equals(requestedRunner) ? CLI : API;
        }

        @Override
        public ProviderChoice chooseVerifier(ProviderChoice investigator, String requiredRunner)
        {
            return API;
        }

        @Override
        public RunOutcome planGuidance(
                ProviderChoice provider, InvestigationReviewContext.Snapshot snapshot,
                List<ReviewObjectiveRow> objectives, String guidance, int costCapCents)
        {
            throw new AssertionError("panel guidance should use an investigator turn");
        }

        @Override
        public RunOutcome verifyGuidance(
                ProviderChoice provider, InvestigationReviewContext.Snapshot snapshot,
                List<ReviewObjectiveRow> objectives, String guidance, int costCapCents)
        {
            throw new AssertionError("panel guidance should not use verifier guidance");
        }

        @Override
        public synchronized RunOutcome investigate(
                ProviderChoice provider, String reviewId, String assignmentId,
                InvestigationReviewContext.Snapshot snapshot,
                List<ReviewObjectiveRow> objectives, String coverageContext,
                String persona, int costCapCents)
        {
            int call = ++calls;
            contexts.add(coverageContext);
            caps.add(costCapCents);
            if (call == 1) {
                firstStarted.countDown();
                try {
                    if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test did not release first review turn");
                    }
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("review turn interrupted", e);
                }
            }
            allCalls.countDown();
            return new RunOutcome(
                    provider, 5, "reviewer response " + call, 10, 5, 1, "COMPLETED");
        }

        @Override
        public RunOutcome selfRefute(
                ProviderChoice provider, String reviewId, String assignmentId,
                InvestigationReviewContext.Snapshot snapshot, String findingBundles,
                int costCapCents)
        {
            throw new AssertionError("no finding should require self-refutation");
        }

        @Override
        public RunOutcome reconstruct(
                ProviderChoice provider, String reviewId, String assignmentId,
                InvestigationReviewContext.Snapshot snapshot, String locations,
                String persona, int costCapCents)
        {
            throw new AssertionError("no finding should require reconstruction");
        }

        @Override
        public RunOutcome verify(
                ProviderChoice provider, String reviewId, String assignmentId,
                InvestigationReviewContext.Snapshot snapshot, String verifierRunId,
                String findingBundle, String blindReconstruction,
                String persona, int costCapCents)
        {
            throw new AssertionError("no finding should require verification");
        }

        @Override
        public String suggestPlanAmendment(
                ProviderChoice provider, InvestigationReviewContext.Snapshot snapshot,
                List<ReviewObjectiveRow> objectives)
        {
            return null;
        }
    }

    private static final class ReplyModel
            implements InvestigationReviewModel
    {
        private final List<String> contexts = new CopyOnWriteArrayList<>();

        @Override
        public ProviderChoice choose(String requestedRunner, String requestedProvider)
        {
            return "cli".equals(requestedRunner) ? CLI : API;
        }

        @Override
        public ProviderChoice chooseVerifier(ProviderChoice investigator, String requiredRunner)
        {
            return API;
        }

        @Override
        public RunOutcome investigate(
                ProviderChoice provider, String reviewId, String assignmentId,
                InvestigationReviewContext.Snapshot snapshot,
                List<ReviewObjectiveRow> objectives, String coverageContext,
                String persona, int costCapCents)
        {
            contexts.add(coverageContext);
            return new RunOutcome(
                    provider, 1,
                    "Understood. I re-checked the boundary and the rejection matches that intent.",
                    1, 1, 1, "COMPLETED");
        }

        @Override
        public RunOutcome planGuidance(
                ProviderChoice provider, InvestigationReviewContext.Snapshot snapshot,
                List<ReviewObjectiveRow> objectives, String guidance, int costCapCents)
        {
            throw new AssertionError("answer test does not send planner guidance");
        }

        @Override
        public RunOutcome verifyGuidance(
                ProviderChoice provider, InvestigationReviewContext.Snapshot snapshot,
                List<ReviewObjectiveRow> objectives, String guidance, int costCapCents)
        {
            throw new AssertionError("answer test does not send verifier guidance");
        }

        @Override
        public RunOutcome selfRefute(
                ProviderChoice provider, String reviewId, String assignmentId,
                InvestigationReviewContext.Snapshot snapshot, String findingBundles,
                int costCapCents)
        {
            throw new AssertionError("answer test does not create findings");
        }

        @Override
        public RunOutcome reconstruct(
                ProviderChoice provider, String reviewId, String assignmentId,
                InvestigationReviewContext.Snapshot snapshot, String locations,
                String persona, int costCapCents)
        {
            throw new AssertionError("answer test does not reconstruct findings");
        }

        @Override
        public RunOutcome verify(
                ProviderChoice provider, String reviewId, String assignmentId,
                InvestigationReviewContext.Snapshot snapshot, String verifierRunId,
                String findingBundle, String blindReconstruction,
                String persona, int costCapCents)
        {
            throw new AssertionError("answer test does not verify findings");
        }

        @Override
        public String suggestPlanAmendment(
                ProviderChoice provider, InvestigationReviewContext.Snapshot snapshot,
                List<ReviewObjectiveRow> objectives)
        {
            return null;
        }
    }

    private static final class QueueModel
            implements InvestigationReviewModel
    {
        private final CountDownLatch firstStarted = new CountDownLatch(1);
        private final CountDownLatch releaseFirst = new CountDownLatch(1);
        private final CountDownLatch secondRoundStarted = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public ProviderChoice choose(String requestedRunner, String requestedProvider)
        {
            return "cli".equals(requestedRunner) ? CLI : API;
        }

        @Override
        public ProviderChoice chooseVerifier(ProviderChoice investigator, String requiredRunner)
        {
            return API;
        }

        @Override
        public RunOutcome planGuidance(
                ProviderChoice provider, InvestigationReviewContext.Snapshot snapshot,
                List<ReviewObjectiveRow> objectives, String guidance, int costCapCents)
        {
            throw new AssertionError("queue test does not send planner guidance");
        }

        @Override
        public RunOutcome verifyGuidance(
                ProviderChoice provider, InvestigationReviewContext.Snapshot snapshot,
                List<ReviewObjectiveRow> objectives, String guidance, int costCapCents)
        {
            throw new AssertionError("queue test does not send verifier guidance");
        }

        @Override
        public RunOutcome investigate(
                ProviderChoice provider, String reviewId, String assignmentId,
                InvestigationReviewContext.Snapshot snapshot,
                List<ReviewObjectiveRow> objectives, String coverageContext,
                String persona, int costCapCents)
        {
            int call = calls.incrementAndGet();
            if (call == 1) {
                firstStarted.countDown();
                try {
                    if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test did not release the first round");
                    }
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("review turn interrupted", e);
                }
            }
            if (call == 3) {
                secondRoundStarted.countDown();
            }
            return new RunOutcome(provider, 1, "complete", 1, 1, 1, "COMPLETED");
        }

        @Override
        public RunOutcome selfRefute(
                ProviderChoice provider, String reviewId, String assignmentId,
                InvestigationReviewContext.Snapshot snapshot, String findingBundles,
                int costCapCents)
        {
            throw new AssertionError("no finding should require self-refutation");
        }

        @Override
        public RunOutcome reconstruct(
                ProviderChoice provider, String reviewId, String assignmentId,
                InvestigationReviewContext.Snapshot snapshot, String locations,
                String persona, int costCapCents)
        {
            throw new AssertionError("no finding should require reconstruction");
        }

        @Override
        public RunOutcome verify(
                ProviderChoice provider, String reviewId, String assignmentId,
                InvestigationReviewContext.Snapshot snapshot, String verifierRunId,
                String findingBundle, String blindReconstruction,
                String persona, int costCapCents)
        {
            throw new AssertionError("no finding should require verification");
        }

        @Override
        public String suggestPlanAmendment(
                ProviderChoice provider, InvestigationReviewContext.Snapshot snapshot,
                List<ReviewObjectiveRow> objectives)
        {
            return null;
        }
    }

    private static final class GuidanceErrorModel
            implements InvestigationReviewModel
    {
        private final CountDownLatch firstStarted = new CountDownLatch(1);
        private final CountDownLatch releaseFirst = new CountDownLatch(1);
        private final CountDownLatch allCalls = new CountDownLatch(3);
        private final List<Integer> caps = new CopyOnWriteArrayList<>();
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public ProviderChoice choose(String requestedRunner, String requestedProvider)
        {
            return "cli".equals(requestedRunner) ? CLI : API;
        }

        @Override
        public ProviderChoice chooseVerifier(ProviderChoice investigator, String requiredRunner)
        {
            return API;
        }

        @Override
        public RunOutcome investigate(
                ProviderChoice provider, String reviewId, String assignmentId,
                InvestigationReviewContext.Snapshot snapshot,
                List<ReviewObjectiveRow> objectives, String coverageContext,
                String persona, int costCapCents)
        {
            int call = calls.incrementAndGet();
            caps.add(costCapCents);
            if (call == 1) {
                firstStarted.countDown();
                try {
                    if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test did not release first review turn");
                    }
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("review turn interrupted", e);
                }
            }
            allCalls.countDown();
            return call == 2
                    ? new RunOutcome(provider, 7, "provider failed", 1, 1, 1, "ERRORED")
                    : new RunOutcome(provider, 1, "complete", 1, 1, 1, "COMPLETED");
        }

        @Override
        public RunOutcome planGuidance(
                ProviderChoice provider, InvestigationReviewContext.Snapshot snapshot,
                List<ReviewObjectiveRow> objectives, String guidance, int costCapCents)
        {
            throw new AssertionError("panel guidance should use an investigator turn");
        }

        @Override
        public RunOutcome verifyGuidance(
                ProviderChoice provider, InvestigationReviewContext.Snapshot snapshot,
                List<ReviewObjectiveRow> objectives, String guidance, int costCapCents)
        {
            throw new AssertionError("panel guidance should not use verifier guidance");
        }

        @Override
        public RunOutcome selfRefute(
                ProviderChoice provider, String reviewId, String assignmentId,
                InvestigationReviewContext.Snapshot snapshot, String findingBundles,
                int costCapCents)
        {
            throw new AssertionError("no finding should require self-refutation");
        }

        @Override
        public RunOutcome reconstruct(
                ProviderChoice provider, String reviewId, String assignmentId,
                InvestigationReviewContext.Snapshot snapshot, String locations,
                String persona, int costCapCents)
        {
            throw new AssertionError("no finding should require reconstruction");
        }

        @Override
        public RunOutcome verify(
                ProviderChoice provider, String reviewId, String assignmentId,
                InvestigationReviewContext.Snapshot snapshot, String verifierRunId,
                String findingBundle, String blindReconstruction,
                String persona, int costCapCents)
        {
            throw new AssertionError("no finding should require verification");
        }

        @Override
        public String suggestPlanAmendment(
                ProviderChoice provider, InvestigationReviewContext.Snapshot snapshot,
                List<ReviewObjectiveRow> objectives)
        {
            return null;
        }
    }
}

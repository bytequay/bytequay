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

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler.LaunchInput;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession;
import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.DiffFile;
import com.bytequay.app.domain.InvestigationReviewData;
import com.bytequay.app.domain.InvestigationReviewData.AgentReviewRow;
import com.bytequay.app.domain.InvestigationReviewData.CriterionRow;
import com.bytequay.app.domain.InvestigationReviewData.FindingRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewObjectiveRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewRoundRow;
import com.bytequay.app.domain.InvestigationReviewData.RoundBudget;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRComment;
import com.bytequay.app.domain.PRCommit;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.repository.sqlite.InvestigationReviewStore;
import com.bytequay.app.repository.sqlite.SqlitePRStore;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.review.InvestigationReviewModel.ReviewTurnPrompt;
import com.bytequay.app.service.review.InvestigationReviewRunner.ProviderChoice;
import com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.FlowPhase;
import com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.FollowUpSeat;
import com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.RoundFlow;
import com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.Seat;
import com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.TurnState;
import com.bytequay.app.service.review.ReviewProviderEndpoints.AgentLaunch;
import com.bytequay.app.service.runs.AgentRunServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.INVESTIGATE;
import static com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.ROUND_GUIDANCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestInvestigationReviewGuidance
{
    private static final ProviderChoice API =
            new ProviderChoice("openai-test", "api", "openai");

    @Autowired
    private InvestigationReviewStore reviews;
    @Autowired
    private SqlitePRStore prs;
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
    private AgentRunServiceImpl runs;
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
    void typedGuidanceIsFrozenBeforeDurableSnapshotDispatch()
    {
        String prId = savePr("typed-guidance", 42);
        String reviewId = UUID.randomUUID().toString();
        reviews.insertReview(new AgentReviewRow(
                reviewId, "acme/typed-guidance", prId, "old-head", "old-head", "ACTIVE",
                null, null, null), Instant.now());
        InvestigationReviewModel model = promptModel();
        TypedRuntimeHarness typed = new TypedRuntimeHarness(mapper);
        ReviewSessionSnapshotRuntime snapshots = mock(ReviewSessionSnapshotRuntime.class);
        InvestigationReviewService service = service(model, typed.runtime(), snapshots);

        InvestigationReviewData created = service.createRound(
                reviewId, "continuation", List.of(), null,
                "Prioritize retry safety", 150);
        ArgumentCaptor<ReviewSessionSnapshotRuntime.SnapshotCommand> command =
                ArgumentCaptor.forClass(ReviewSessionSnapshotRuntime.SnapshotCommand.class);
        verify(snapshots).request(
                any(AgentReviewRow.class), eq(ReviewSessionSnapshotRuntime.Scope.QUICK),
                command.capture());

        assertThat(command.getValue()).satisfies(request -> {
            assertThat(request.commandId()).isNotBlank();
            assertThat(request.kind()).isEqualTo("continuation");
            assertThat(request.findingIds()).isEmpty();
            assertThat(request.seed()).isEqualTo("Prioritize retry safety");
            assertThat(request.costCapCents()).isEqualTo(150);
        });
        assertThat(created.rounds()).isEmpty();
        assertThat(reviews.rounds(reviewId)).isEmpty();
    }

    @Test
    void answerPreservesTheFindingThreadAndFreezesTheUserReplyIntoTheTypedTurn()
    {
        String prId = savePr("typed-answer", 47);
        String reviewId = UUID.randomUUID().toString();
        String priorRoundId = UUID.randomUUID().toString();
        String criterionId = UUID.randomUUID().toString();
        String objectiveId = UUID.randomUUID().toString();
        String findingId = UUID.randomUUID().toString();
        AgentRun priorRun = runs.createReviewCompatibilityHeader(
                priorRoundId, 50);
        reviews.insertReview(new AgentReviewRow(
                reviewId, "acme/typed-answer", prId, "old-head", "old-head", "ACTIVE",
                null, null, null), Instant.now());
        reviews.insertRound(new ReviewRoundRow(
                priorRoundId, reviewId, priorRun.id(), "initial", "full", "old-head", "old-head",
                "COMPLETED", new RoundBudget(50, 10), 1), Instant.now());
        reviews.insertCriterion(new CriterionRow(
                criterionId, "acme/typed-answer", "hard-invariant",
                "Preserve the boundary", "test", null));
        reviews.insertObjective(new ReviewObjectiveRow(
                objectiveId, priorRoundId, criterionId, "Preserve the boundary", "test",
                "applicable", "finding"));
        reviews.insertFinding(new FindingRow(
                findingId, reviewId, priorRoundId, objectiveId, null, "hard-invariant",
                null, null, null,
                "The boundary rejects the configured value.", 3, "SUPPORTED", "unknown",
                "Confirm whether that rejection is intentional.",
                "NEEDS_AUTHOR_INPUT", "old-head"));
        PRComment root = localPrs.addComment(
                prId, PRComment.ORIGIN_LOCAL, PRComment.SCOPE_PR,
                null, null, null, null, null, "agent", "Original finding", null);
        localPrs.attachFinding(root.id(), findingId);
        localPrs.resolveComment(root.id());
        PRComment userReply = localPrs.addComment(
                prId, PRComment.ORIGIN_LOCAL, PRComment.SCOPE_PR,
                null, null, null, null, null, "you",
                "The rejection is intentional for legacy callers.", root.id());

        InvestigationReviewModel model = promptModel();
        TypedRuntimeHarness typed = new TypedRuntimeHarness(mapper);
        ReviewSessionSnapshotRuntime snapshots = mock(ReviewSessionSnapshotRuntime.class);
        InvestigationReviewService service = service(model, typed.runtime(), snapshots);

        InvestigationReviewData started = service.answer(
                findingId, "The rejection is intentional for legacy callers.");
        ArgumentCaptor<ReviewSessionSnapshotRuntime.SnapshotCommand> command =
                ArgumentCaptor.forClass(ReviewSessionSnapshotRuntime.SnapshotCommand.class);
        verify(snapshots).request(
                any(AgentReviewRow.class), eq(ReviewSessionSnapshotRuntime.Scope.QUICK),
                command.capture());

        assertThat(command.getValue()).satisfies(request -> {
            assertThat(request.commandId()).isNotBlank();
            assertThat(request.kind()).isEqualTo("continuation");
            assertThat(request.findingIds()).containsExactly(findingId);
            assertThat(request.answerFindingId()).isEqualTo(findingId);
            assertThat(request.answerText())
                    .isEqualTo("The rejection is intentional for legacy callers.");
        });
        assertThat(started.prComments()).extracting(comment -> comment.id())
                .contains(root.id(), userReply.id());
        assertThat(started.rounds()).extracting(ReviewRoundRow::id)
                .containsExactly(priorRoundId);
    }

    private String savePr(String name, int number)
    {
        String prId = UUID.randomUUID().toString();
        prs.save(PR.createExternal(
                prId, "acme/" + name, number, "https://example.test/" + number, "octocat",
                "feature", "main", "Review " + name, "", PR.STATUS_REMOTE_OPEN,
                Instant.parse("2026-07-01T00:00:00Z"), null, null));
        prs.addCommit(new PRCommit(
                UUID.randomUUID().toString(), prId, "old-head", "Existing change",
                1, 0, Instant.parse("2026-07-01T00:00:00Z"), Instant.now()));
        prs.addCommit(new PRCommit(
                UUID.randomUUID().toString(), prId, "new-head", "Change typed guidance",
                1, 0, Instant.parse("2026-07-02T00:00:00Z"), Instant.now()));
        return prId;
    }

    private InvestigationReviewService service(
            InvestigationReviewModel model, ReviewAssignmentTurnRuntime typed,
            ReviewSessionSnapshotRuntime snapshots)
    {
        InvestigationReviewService service = new InvestigationReviewService(
                reviews, new FixedContext(
                        localPrs, pullRequests, tasks, watchedRepos, git),
                model, runs, localPrs, tasks, threads, mapper);
        service.setReviewAssignmentTurnRuntime(typed);
        service.setReviewSessionSnapshots(snapshots);
        return service;
    }

    private static InvestigationReviewModel promptModel()
    {
        InvestigationReviewModel model = mock(InvestigationReviewModel.class);
        when(model.reviewKnowledge(any())).thenReturn(List.of());
        when(model.choose(anyString(), nullable(String.class))).thenReturn(API);
        when(model.chooseVerifier(any(), anyString())).thenReturn(API);
        when(model.investigationPrompt(
                anyString(), any(), anyList(), anyString(), nullable(String.class)))
                .thenAnswer(invocation -> new ReviewTurnPrompt(
                        "review system", invocation.getArgument(3)));
        return model;
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
                    Path.of("/tmp/bytequay-review-guidance"));
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

    private static final class TypedRuntimeHarness
    {
        private static final AgentLaunch PROVIDER = new AgentLaunch(
                AgentTurnProviderSession.Transport.API,
                "openai", "account-1", "gpt-test");

        private final ObjectMapper mapper;
        private final ReviewAssignmentTurnRuntime runtime =
                mock(ReviewAssignmentTurnRuntime.class);
        private final List<TurnState> turns = new ArrayList<>();
        private final AtomicReference<String> roundId = new AtomicReference<>();
        private final AtomicReference<FlowPhase> phase =
                new AtomicReference<>(FlowPhase.PRIMARY);
        private final AtomicReference<FollowUpSeat> followUp = new AtomicReference<>();
        private final AtomicInteger ids = new AtomicInteger();
        private final AtomicLong remainingCostUsdMilli = new AtomicLong();
        private final AtomicInteger protectedCostCents = new AtomicInteger();

        private TypedRuntimeHarness(ObjectMapper mapper)
        {
            this.mapper = mapper;
            when(runtime.freezeProvider(any())).thenReturn(PROVIDER);
            doAnswer(invocation -> {
                roundId.set(invocation.getArgument(0));
                List<Seat> seats = invocation.getArgument(2);
                for (Seat seat : seats) {
                    String suffix = Integer.toString(ids.incrementAndGet());
                    turns.add(turn(
                            "primary-turn-" + suffix, seat.assignmentId(),
                            INVESTIGATE, seat.assignmentId(), null, "RUNNING",
                            seat.workingDirectory(), seat.prompt(), null, 0));
                }
                return null;
            }).when(runtime).admit(anyString(), anyString(), anyList(), anyInt());
            when(runtime.ownsRound(anyString())).thenAnswer(invocation ->
                    invocation.getArgument(0).equals(roundId.get()));
            when(runtime.flow(anyString())).thenAnswer(invocation ->
                    invocation.getArgument(0).equals(roundId.get())
                            ? Optional.of(new RoundFlow(
                                    roundId.get(), "new-head", phase.get(), null, null, 0))
                            : Optional.empty());
            when(runtime.turns(anyString())).thenAnswer(invocation -> List.copyOf(turns));
            when(runtime.remainingCostUsdMilli(anyString()))
                    .thenAnswer(invocation -> remainingCostUsdMilli.get());
            when(runtime.protectedCostCents(anyString()))
                    .thenAnswer(invocation -> protectedCostCents.get());
            when(runtime.movePhase(anyString(), any(), any())).thenAnswer(invocation -> {
                FlowPhase expected = invocation.getArgument(1);
                FlowPhase next = invocation.getArgument(2);
                return phase.compareAndSet(expected, next);
            });
            when(runtime.admitFollowUp(anyString(), anyString(), any()))
                    .thenAnswer(invocation -> {
                        FollowUpSeat seat = invocation.getArgument(2);
                        followUp.compareAndSet(null, seat);
                        String turnId = "guidance-turn-" + ids.incrementAndGet();
                        turns.add(turn(
                                turnId, seat.assignmentId(), seat.purpose(),
                                seat.subjectKey(), seat.verifierRunId(), "REQUESTED",
                                seat.workingDirectory(), seat.prompt(), null, 0));
                        return turnId;
                    });
            when(runtime.roundId(anyString())).thenAnswer(invocation -> turns.stream()
                    .anyMatch(turn -> turn.turnId().equals(invocation.getArgument(0)))
                            ? Optional.of(roundId.get()) : Optional.empty());
        }

        private ReviewAssignmentTurnRuntime runtime()
        {
            return runtime;
        }

        private FollowUpSeat followUp()
        {
            return followUp.get();
        }

        private String primaryTurnId()
        {
            return turns.stream()
                    .filter(turn -> INVESTIGATE.equals(turn.purpose()))
                    .findFirst().orElseThrow().turnId();
        }

        private String primaryPrompt()
        {
            return turns.stream()
                    .filter(turn -> INVESTIGATE.equals(turn.purpose()))
                    .findFirst().map(this::prompt).orElseThrow();
        }

        private String guidanceTurnId()
        {
            return turns.stream()
                    .filter(turn -> ROUND_GUIDANCE.equals(turn.purpose()))
                    .findFirst().orElseThrow().turnId();
        }

        private void succeedPrimaries(String finalText, int costCents)
        {
            replace(INVESTIGATE, "SUCCEEDED", finalText, (long) costCents * 10);
        }

        private void succeedGuidance(String finalText, int costCents)
        {
            replace(ROUND_GUIDANCE, "SUCCEEDED", finalText, (long) costCents * 10);
        }

        private void replace(
                String purpose, String status, String finalText, long costUsdMilli)
        {
            for (int index = 0; index < turns.size(); index++) {
                TurnState current = turns.get(index);
                if (purpose.equals(current.purpose())) {
                    turns.set(index, new TurnState(
                            current.turnId(), current.assignmentId(), current.purpose(),
                            current.subjectKey(), current.verifierRunId(), current.attempt(),
                            status, current.launchInput(), finalText, 10, 5, costUsdMilli));
                }
            }
        }

        private TurnState turn(
                String turnId, String assignmentId, String purpose,
                String subjectKey, String verifierRunId, String status,
                Path workingDirectory, ReviewTurnPrompt prompt,
                String finalText, long costUsdMilli)
        {
            String operationId = turnId + "-operation";
            AgentTurnProviderSession.OwnerToolEndpoint endpoint =
                    new AgentTurnProviderSession.OwnerToolEndpoint(
                            "bytequay", "http://127.0.0.1:53123/api/v2/"
                                    + "review-assignment-turns/" + turnId
                                    + "/operations/" + operationId + "/mcp",
                            DispatchTicket.OwnerKind.REVIEW_ASSIGNMENT_TURN,
                            turnId, operationId,
                            AgentTurnProviderSession.ToolProfile.REVIEW_ASSIGNMENT_READ_ONLY,
                            "mcp__bytequay__approval_prompt");
            try {
                String launch = mapper.writeValueAsString(new LaunchInput(
                        1, PROVIDER.transport(), PROVIDER.provider(),
                        PROVIDER.credentialAccount(), PROVIDER.model(), null,
                        workingDirectory.toString(), prompt.systemPrompt(),
                        prompt.prompt(), endpoint));
                return new TurnState(
                        turnId, assignmentId, purpose, subjectKey, verifierRunId,
                        1, status, launch, finalText, 0, 0, costUsdMilli);
            }
            catch (Exception e) {
                throw new AssertionError(e);
            }
        }

        private String prompt(TurnState turn)
        {
            try {
                return mapper.readValue(turn.launchInput(), LaunchInput.class).prompt();
            }
            catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    }
}

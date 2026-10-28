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
package com.bytequay.app.web.review;

import com.bytequay.agent.cli.process.OwnedProcessRecovery;
import com.bytequay.agent.cli.process.ProcessSupervisor;
import com.bytequay.agent.core.AgentApprovalService;
import com.bytequay.agent.core.AgentCompletionService;
import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentMetricsService;
import com.bytequay.agent.core.AgentRuntimeStopImpact;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.SteerAgentTurnCommand;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.impl.AgentMaintenance;
import com.bytequay.agent.core.impl.AgentRuntimeRegistry;
import com.bytequay.agent.core.store.AgentEventOutboxStore;
import com.bytequay.agent.core.store.AgentProcessLaunchStore;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.AgentUnitOfWork;
import com.bytequay.agent.core.store.QueuedInputStore;
import com.bytequay.agent.core.store.SteerDeliveryStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.agent.runtime.AgentServingGate;
import com.bytequay.app.agent.runtime.AgentStartupRecovery;
import com.bytequay.app.config.WriteVersion;
import com.bytequay.app.memory.MemoryService;
import com.bytequay.app.review.AgentReviewProjection;
import com.bytequay.app.review.AgentReviewService;
import com.bytequay.app.review.ReviewPublicationService;
import com.bytequay.app.service.hosting.HostingReadSession;
import com.bytequay.app.service.pr.PullRequestRead;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.web.GlobalExceptionHandler;
import com.bytequay.app.web.agent.AgentStreamRegistry;
import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.memory.MemorySource;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.ReviewAnchor;
import com.bytequay.core.review.ReviewCoverage;
import com.bytequay.core.review.ReviewDraftItem;
import com.bytequay.core.review.ReviewEvidence;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.review.ReviewInvalidRequestException;
import com.bytequay.core.review.ReviewMessage;
import com.bytequay.core.review.ReviewNotFoundException;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.review.ReviewSummary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static com.bytequay.agent.core.AgentTaskFixtures.created;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestReviewRuntimeRecovery
{
    private static final String REVIEW_ID = "review-1";
    private static final TaskId TASK_ID = TaskId.valueOf("11111111-1111-4111-8111-111111111111");
    private static final Instant NOW = Instant.parse("2026-09-07T15:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final AgentReviewService reviews = mock(AgentReviewService.class);
    private final AgentTurnService turns = mock(AgentTurnService.class);
    private final ProcessSupervisor supervisor = mock(ProcessSupervisor.class);
    private final AgentTaskStore taskStore = mock(AgentTaskStore.class, CALLS_REAL_METHODS);
    private final AgentTurnStore turnStore = mock(AgentTurnStore.class);
    private final AgentProcessLaunchStore launchStore = mock(AgentProcessLaunchStore.class);
    private final AgentStartupRecovery recovery = new AgentStartupRecovery(
            mock(AgentEventOutboxStore.class), mock(AgentEventService.class), maintenance(), supervisor,
            new AgentServingGate(), launchStore, taskStore, turnStore, mock(AgentTurnService.class));
    private final MemoryService memories = mock(MemoryService.class);
    private final ReviewPublicationService publications = mock(ReviewPublicationService.class);
    private final PullRequestService pullRequests = mock(PullRequestService.class);
    private final ReviewController controller = new ReviewController(
            reviews, publications, pullRequests, turns, mock(AgentEventService.class), mock(AgentApprovalService.class), recovery, new AgentStreamRegistry(),
            memories, new WriteVersion());

    @Test
    void reviewValuesKeepTheirExactJsonFieldsWithoutExposingStoredRunInternals()
            throws Exception
    {
        ReviewEvidence evidence = new ReviewEvidence("e1", "src/App.java:1", "git", "return value;", "Value is returned");
        ReviewRun run = new ReviewRun("run-1", REVIEW_ID, 1, ReviewRun.Scope.FULL, "main", "base", "merge-base", "head", Optional.empty(), Optional.empty(),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ReviewRun.State.RUNNING,
                Optional.of(new ReviewSummary(2, "Purpose", ImmutableList.of(new ReviewSummary.CommitExplanation("head", "Change")), "Result", Optional.empty(),
                        ImmutableList.of("finding-1"))),
                Optional.of(new ReviewCoverage(true, ImmutableList.of(), ImmutableList.of("Limited tests"), NOW)), Optional.empty(), ImmutableList.of(), false,
                ImmutableMap.of(), ImmutableMap.of("e1", evidence), Optional.empty(), 0, NOW, NOW, Optional.empty());
        ReviewFinding finding = new ReviewFinding("finding-1", REVIEW_ID, "run-1", 1, "Title", "Body", Optional.empty(), Optional.empty(),
                ImmutableList.of(evidence), 1, ReviewFinding.State.OPEN, Optional.empty(), "head", false, 0, NOW, NOW, ImmutableList.of(), ReviewFinding.Origin.AUTOMATIC,
                Optional.empty());
        AgentReviewProjection original = projection(ImmutableList.of(run), ImmutableList.of(finding));
        AgentReviewProjection projection = new AgentReviewProjection(original.review(), original.runs(), original.findings(), original.messages(), original.task(),
                Optional.of(new AgentReviewProjection.TurnView("turn-1", "RUNNING", Optional.of(NOW), Optional.empty())), original.queuedInputIds(), original.queuePause(),
                original.pendingApprovals(), original.byteQuayTurnIds(), original.draft(), original.pullRequest(), Optional.of(new AgentReviewProjection.WorktreeSyncView(
                        AgentReviewProjection.WorktreeSyncView.State.BLOCKED, Optional.of("head"), Optional.of("next"), true, Optional.of("Local edits"))));
        JsonNode json = mapper.readTree(mapper.writeValueAsString(ReviewHttpModels.review(projection)));

        assertThat(json.path("task")).isEqualTo(mapper.readTree("""
                {"id":"%s","workspaceId":"workspace-1","state":"OPEN","version":8,"laneId":"claude","laneLabel":"Claude Code",
                 "model":null,"effort":null,"serviceTier":null,"selectionLaneId":"claude","mutationClaim":null,"steering":false,"nestedAgents":false}
                """.formatted(TASK_ID)));
        assertThat(json.path("activeTurn")).isEqualTo(mapper.readTree("""
                {"id":"turn-1","state":"RUNNING","startedAt":"2026-09-07T15:00:00Z","cancellationRequestedAt":null}
                """));
        assertThat(json.path("worktreeSync")).isEqualTo(mapper.readTree("""
                {"state":"BLOCKED","headSha":"head","targetHeadSha":"next","dirty":true,"reason":"Local edits"}
                """));
        assertThat(json.at("/runs/0/summary")).isEqualTo(mapper.readTree("""
                {"revision":2,"purpose":"Purpose","commits":[{"sha":"head","explanation":"Change"}],"combinedResult":"Result",
                 "evidence":null,"criticalFindingIds":["finding-1"]}
                """));
        assertThat(json.at("/runs/0/coverage")).isEqualTo(mapper.readTree("""
                {"complete":true,"omissions":[],"warnings":["Limited tests"],"recordedAt":"2026-09-07T15:00:00Z"}
                """));
        JsonNode expectedEvidence = mapper.readTree("""
                [{"ref":"e1","source":"src/App.java:1","provenance":"git","excerpt":"return value;","explanation":"Value is returned"}]
                """);
        assertThat(json.at("/runs/0/evidence")).isEqualTo(expectedEvidence);
        assertThat(json.at("/findings/0/evidence")).isEqualTo(expectedEvidence);
        assertThat(json.at("/runs/0").properties()).extracting(Map.Entry::getKey)
                .doesNotContain("diff", "operations", "completionRepairUsed", "version", "updatedAt", "reviewId");
    }

    @Test
    void workspaceListingAndPullRequestLookupKeepTheirOwnResponseShapes()
            throws Exception
    {
        AgentReviewProjection projection = projection();
        when(reviews.list("workspace-1")).thenReturn(ImmutableList.of(projection));
        when(reviews.find("workspace-1", "owner/repo", 7)).thenReturn(Optional.of(projection));
        MockMvc mvc = standaloneSetup(controller).build();

        String etag = mvc.perform(get("/api/agent-review").param("workspaceId", "workspace-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(REVIEW_ID))
                .andExpect(jsonPath("$[0].task.laneLabel").value("Claude Code"))
                .andExpect(jsonPath("$[0].messages[0].body").value("Explain this limitation"))
                .andExpect(jsonPath("$[0].messages[0].cardId").value("run-1:limitations"))
                .andExpect(jsonPath("$[0].messages[0].turnId").value("turn-2"))
                .andExpect(jsonPath("$[0].messages[0].queuedInputId").value("queue-1"))
                .andExpect(jsonPath("$[0].messages[0].messageEventId").value("event-1"))
                .andReturn().getResponse().getHeader("ETag");
        // Nothing was written since, so the poll's copy is current.
        mvc.perform(get("/api/agent-review").param("workspaceId", "workspace-1").header("If-None-Match", etag))
                .andExpect(status().isNotModified());
        verify(reviews).list("workspace-1");
        mvc.perform(get("/api/agent-review").param("workspaceId", "workspace-1").param("repo", "owner/repo").param("number", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(REVIEW_ID));

        verify(reviews).list("workspace-1");
        verify(reviews).find("workspace-1", "owner/repo", 7);
        verifyNoMoreInteractions(reviews);
        verifyNoInteractions(turns, supervisor, taskStore, turnStore, launchStore);
    }

    @Test
    void explanationRefreshBindsTheDisplayedRunAndRevisionWithoutUpdatingTheReview()
            throws Exception
    {
        when(reviews.refreshExplanation(REVIEW_ID, "run-1", 3)).thenReturn(projection());
        MockMvc mvc = standaloneSetup(controller).build();

        mvc.perform(post("/api/agent-review/{reviewId}/explanation/refresh", REVIEW_ID)
                        .contentType("application/json")
                        .content("{\"runId\":\"run-1\",\"expectedRevision\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(REVIEW_ID));

        verify(reviews).refreshExplanation(REVIEW_ID, "run-1", 3);
        verifyNoMoreInteractions(reviews);
        verifyNoInteractions(turns);
    }

    @Test
    void resolvingRemembersTheReasonForTheFindingsFileOnlyWhenAsked()
            throws Exception
    {
        ReviewFinding finding = new ReviewFinding("finding-1", REVIEW_ID, "run-1", 1, "Unchecked input", "Body", Optional.empty(),
                Optional.of(new ReviewAnchor("src/App.java", "RIGHT", 3, 4, ImmutableList.of("parse(input)"))), ImmutableList.of(), 1, ReviewFinding.State.OPEN,
                Optional.empty(), "a".repeat(40), false, 0, NOW, NOW, ImmutableList.of(), ReviewFinding.Origin.AUTOMATIC, Optional.empty());
        when(reviews.get(REVIEW_ID)).thenReturn(projection(ImmutableList.of(), ImmutableList.of(finding)));
        MockMvc mvc = standaloneSetup(controller).build();

        mvc.perform(post("/api/agent-review/{reviewId}/findings/finding-1/resolve", REVIEW_ID).contentType("application/json")
                        .content("{\"expectedVersion\":0,\"reason\":\"We validate input at the API boundary.\",\"remember\":true}"))
                .andExpect(status().isOk());
        verify(reviews).resolveFinding(REVIEW_ID, "finding-1", 0, "We validate input at the API boundary.");
        verify(memories).rememberReason("workspace-1", "Unchecked input", "We validate input at the API boundary.", "src/App.java",
                new MemorySource.UserPart(MemorySource.Where.RESOLVE, REVIEW_ID, "finding-1"));

        assertThatThrownBy(() -> mvc.perform(post("/api/agent-review/{reviewId}/findings/finding-1/resolve", REVIEW_ID).contentType("application/json")
                .content("{\"expectedVersion\":0,\"reason\":\" \",\"remember\":true}"))).hasMessageContaining("Write the reason to remember it");
        mvc.perform(post("/api/agent-review/{reviewId}/findings/finding-1/resolve", REVIEW_ID).contentType("application/json")
                        .content("{\"expectedVersion\":0,\"reason\":\"\"}"))
                .andExpect(status().isOk());
        verify(reviews).resolveFinding(REVIEW_ID, "finding-1", 0, "");
        verifyNoMoreInteractions(memories);
    }

    @Test
    void conversationInputUsesReviewAdmissionRatherThanStartingAnUncoordinatedTurn()
    {
        when(reviews.task(REVIEW_ID)).thenReturn(task());
        when(reviews.get(REVIEW_ID)).thenReturn(projection());
        controller.message(REVIEW_ID, "send", new ReviewHttpModels.MessageRequest("Explain the change", "TURN", null));
        controller.message(REVIEW_ID, "queue", new ReviewHttpModels.MessageRequest("Check the next line", "QUEUE", null));
        verify(reviews).sendMessage(REVIEW_ID, task().version(), "Explain the change", "send", false);
        verify(reviews).sendMessage(REVIEW_ID, task().version(), "Check the next line", "queue", true);
        verifyNoInteractions(turns);
    }

    @Test
    void listingRequiresANonBlankWorkspace()
            throws Exception
    {
        MockMvc mvc = standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/api/agent-review")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/agent-review").param("workspaceId", " ")).andExpect(status().isBadRequest());
        verifyNoInteractions(reviews, turns, supervisor, taskStore, turnStore, launchStore);
    }

    @Test
    void postingACommentNowNeedsItsBoxKeyAndPassesTheCommentThrough()
            throws Exception
    {
        MockMvc mvc = standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
        HostingInstance server = new HostingInstance(1, HostingProvider.GITHUB, URI.create("https://github.com"), URI.create("https://api.github.com"));
        URI repositoryUrl = URI.create("https://github.com/owner/repo");
        PullRequestRead route = new PullRequestRead(new HostingReadSession(1, "account", new HostingAccess(server, "token")),
                new HostingRepository(1, 1, "repository", "owner/repo", repositoryUrl, repositoryUrl, Optional.empty(), Optional.of("main")), 7, "viewer");
        when(pullRequests.prepareRead(null, "owner/repo", 7)).thenReturn(route);
        String key = "6f1c2e3a-5b7d-4c8e-9a0b-1c2d3e4f5a6b";
        // The comment box's own shape, startSide included.
        String comment = """
                {"path":"src/Main.java","body":"Please simplify this","line":18,"side":"RIGHT","startLine":null,"startSide":null,"commitId":"head-sha"}
                """;

        mvc.perform(post("/api/agent-review/drafts/items/{key}/post", key).param("repo", "owner/repo").param("number", "7").contentType("application/json").content(comment))
                .andExpect(status().isOk());
        mvc.perform(post("/api/agent-review/drafts/items/{key}/post", "new").param("repo", "owner/repo").param("number", "7").contentType("application/json").content(comment))
                .andExpect(status().isBadRequest());

        verify(publications).postComment(route, key, new ReviewDraftItem("new", ReviewDraftItem.Source.MANUAL, Optional.empty(), OptionalInt.empty(),
                "src/Main.java", "RIGHT", 18, OptionalInt.empty(), "Please simplify this", true, Optional.empty(), Optional.of("head-sha"), OptionalLong.empty()));
        verifyNoMoreInteractions(publications);
    }

    @Test
    void scopesTheImpactToTheReviewOwnedTask()
    {
        AgentTask task = task();
        when(reviews.task(REVIEW_ID)).thenReturn(task);
        when(turns.stopImpact(TASK_ID)).thenReturn(new AgentRuntimeStopImpact(TASK_ID, "task:7", Optional.empty(), false));

        var impact = controller.runtimeStopImpact(REVIEW_ID);

        assertThat(task.interactive()).isFalse();
        assertThat(impact.taskId()).isEqualTo(TASK_ID.toString());
        assertThat(impact.generation()).isEqualTo("task:7");
        verify(turns).stopImpact(TASK_ID);
        verifyNoInteractions(supervisor, taskStore, turnStore, launchStore);
    }

    @Test
    void recoversAStartupClaimAndKeepsTheSameReviewOpen()
    {
        AgentTask blocked = task().claimWorktreeMutation(WorktreeMutationClaim.recovery("launch-1", NOW), NOW);
        when(reviews.task(REVIEW_ID)).thenReturn(blocked);
        when(reviews.get(REVIEW_ID)).thenReturn(projection());
        when(taskStore.find(TASK_ID)).thenReturn(Optional.of(blocked));
        when(taskStore.update(any())).thenAnswer(invocation -> invocation.getArgument(0, AgentTask.class).withVersion(blocked.version() + 1));
        when(launchStore.list()).thenReturn(ImmutableList.of());
        when(supervisor.recoverOwnedProcess("launch-1")).thenReturn(new OwnedProcessRecovery(
                "launch-1", Optional.empty(), Optional.empty(), OwnedProcessRecovery.Outcome.VERIFIED_STOPPED, "Runtime verified stopped"));

        var result = controller.stopRuntime(REVIEW_ID, new ReviewHttpModels.StopRuntimeRequest("task:" + blocked.version()));

        assertThat(result.id()).isEqualTo(REVIEW_ID);
        assertThat(result.state()).isEqualTo("OPEN");
        assertThat(result.task().id()).isEqualTo(TASK_ID.toString());
        assertThat(result.followUpPaused()).isTrue();
        assertThat(result.task().mutationClaim()).isEmpty();
        verify(supervisor).recoverOwnedProcess("launch-1");
        verify(supervisor).acknowledge("launch-1");
        verify(taskStore).update(argThat(task -> task.worktreeMutationClaim().isEmpty() && task.state() == blocked.state()));
        verify(reviews).task(REVIEW_ID);
        verify(reviews).get(REVIEW_ID);
        verifyNoMoreInteractions(reviews);
        verifyNoInteractions(turns);
    }

    @Test
    void stopsTheCurrentBackendRuntimeThroughTheTurnService()
    {
        TurnId turnId = TurnId.valueOf("22222222-2222-4222-8222-222222222222");
        when(reviews.task(REVIEW_ID)).thenReturn(task().claimWorktreeMutation(WorktreeMutationClaim.turn(turnId, NOW), NOW));
        when(reviews.get(REVIEW_ID)).thenReturn(projection());

        controller.stopRuntime(REVIEW_ID, new ReviewHttpModels.StopRuntimeRequest("task:8"));

        verify(turns).stopRuntime(TASK_ID, "task:8");
        verifyNoInteractions(supervisor, taskStore, turnStore, launchStore);
    }

    @Test
    void steersOnlyTheTurnTheUserSawRunning()
    {
        AgentTurnSettings settings = new AgentTurnSettings(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO, Duration.ofMinutes(5),
                ImmutableList.of(), Optional.empty(), Optional.empty());
        TurnId shown = TurnId.valueOf("22222222-2222-4222-8222-222222222222");
        TurnId foreign = TurnId.valueOf("33333333-3333-4333-8333-333333333333");
        when(reviews.task(REVIEW_ID)).thenReturn(task());
        when(reviews.get(REVIEW_ID)).thenReturn(projection());
        when(turns.get(shown)).thenReturn(AgentTurn.accepted(shown, TASK_ID, "shown", settings, NOW));
        when(turns.get(foreign)).thenReturn(AgentTurn.accepted(foreign, TaskId.valueOf("44444444-4444-4444-8444-444444444444"), "foreign", settings, NOW));

        controller.message(REVIEW_ID, "steer-1", new ReviewHttpModels.MessageRequest("Check cancellation too", "STEER", shown.toString()));

        verify(turns).steer(new SteerAgentTurnCommand(shown, "Check cancellation too", ImmutableList.of(), "steer-1"));
        assertThatThrownBy(() -> controller.message(REVIEW_ID, "steer-2", new ReviewHttpModels.MessageRequest("Elsewhere", "STEER", foreign.toString())))
                .isInstanceOf(ReviewInvalidRequestException.class).hasMessageContaining("does not belong");
        assertThatThrownBy(() -> controller.message(REVIEW_ID, "steer-3", new ReviewHttpModels.MessageRequest("Whichever", "STEER", null)))
                .isInstanceOf(ReviewInvalidRequestException.class).hasMessageContaining("turnId");
    }

    @Test
    void resumesTheReviewsPausedQueue()
    {
        when(reviews.task(REVIEW_ID)).thenReturn(task());
        when(reviews.get(REVIEW_ID)).thenReturn(projection());

        controller.resumeQueue(REVIEW_ID);

        verify(turns).resumeQueued(TASK_ID);
    }

    @Test
    void preservesTheRuntimeServicesStaleGenerationRejection()
    {
        AgentTask blocked = task().claimWorktreeMutation(WorktreeMutationClaim.recovery("launch-1", NOW), NOW);
        when(reviews.task(REVIEW_ID)).thenReturn(blocked);
        when(taskStore.find(TASK_ID)).thenReturn(Optional.of(blocked));

        assertThatThrownBy(() -> controller.stopRuntime(REVIEW_ID, new ReviewHttpModels.StopRuntimeRequest("task:old")))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("runtime changed");
        verify(reviews).task(REVIEW_ID);
        verifyNoMoreInteractions(reviews);
        verifyNoInteractions(turns, supervisor, turnStore, launchStore);
    }

    @Test
    void rejectsAnUnknownReviewBeforeAccessingAnyTaskRuntime()
    {
        when(reviews.task("unknown-review")).thenThrow(new ReviewNotFoundException("Review not found"));

        assertThatThrownBy(() -> controller.runtimeStopImpact("unknown-review")).isInstanceOf(ReviewNotFoundException.class);
        assertThatThrownBy(() -> controller.stopRuntime("unknown-review", new ReviewHttpModels.StopRuntimeRequest("task:7")))
                .isInstanceOf(ReviewNotFoundException.class);
        verifyNoInteractions(turns, supervisor, taskStore, turnStore, launchStore);
    }

    private AgentMaintenance maintenance()
    {
        return new AgentMaintenance(
                taskStore, turnStore, mock(QueuedInputStore.class), mock(SteerDeliveryStore.class), mock(AgentUnitOfWork.class),
                mock(AgentEventOutboxStore.class), mock(AgentApprovalService.class), mock(AgentMetricsService.class), mock(AgentCompletionService.class),
                new AgentRuntimeRegistry(mock(AgentTaskStore.class), ignored -> {}, ignored -> false, true), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static AgentTask task()
    {
        return created(
                TASK_ID, Optional.of("workspace-1"), false, "Review owner/repo#7", new AgentLaneId("claude"), new RepositoryIdentity("owner", "repo"),
                Path.of("/tmp/review-worktree"), new AgentTaskDefaults(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO, Optional.empty()), NOW);
    }

    private static AgentReviewProjection projection()
    {
        return projection(ImmutableList.of());
    }

    private static AgentReviewProjection projection(List<ReviewRun> runs)
    {
        return projection(runs, ImmutableList.of());
    }

    private static AgentReviewProjection projection(List<ReviewRun> runs, List<ReviewFinding> findings)
    {
        AgentReview review = new AgentReview(
                REVIEW_ID,
                "workspace-1",
                "owner/repo",
                OptionalInt.of(7),
                OptionalLong.empty(),
                TASK_ID.toString(),
                Optional.empty(),
                AgentReview.State.OPEN,
                true,
                Optional.empty(),
                Optional.of("head"),
                Optional.of("head"),
                0,
                NOW,
                NOW,
                Optional.empty(),
                Optional.empty());
        return new AgentReviewProjection(
                review, runs, findings, ImmutableList.of(new AgentReviewProjection.MessageView(
                        new ReviewMessage("message-1", REVIEW_ID, Optional.empty(), Optional.of("run-1:limitations"), ReviewMessage.Author.USER,
                                "Explain this limitation", 1, "run-1", Optional.empty(), Optional.of("queue-1"), 0, NOW, Optional.empty()),
                        Optional.of("turn-2"), Optional.of("event-1"))),
                new AgentReviewProjection.TaskView(TASK_ID.toString(), "workspace-1", "OPEN", 8, "claude", "Claude Code",
                        Optional.empty(), Optional.empty(), Optional.empty(), "claude", Optional.empty(), false, false),
                Optional.empty(), ImmutableList.of(), Optional.empty(), 0, ImmutableList.of(), Optional.empty(), Optional.empty(), Optional.empty());
    }
}

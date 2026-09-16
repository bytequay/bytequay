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
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentEventSource;
import com.bytequay.agent.core.AgentHistoryPage;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.spi.ProviderAgentEvent.ActivityState;
import com.bytequay.agent.spi.ReviewBridge;
import com.bytequay.agent.spi.ReviewTool;
import com.bytequay.app.context.SelectedCommitPullRequestReader;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.repository.github.GitHubIssuePreview;
import com.bytequay.app.service.RepoService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.pr.SelectedPullRequestDiffReader;
import com.bytequay.app.service.pr.SelectedPullRequestReader;
import com.bytequay.app.workspace.WorkspaceIssueRefreshService;
import com.bytequay.app.workspace.WorkspaceIssueService;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.context.ContextPacket;
import com.bytequay.core.context.ReviewContextSnapshot;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PrTimelinePayload;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.RepositoryIssueDetail;
import com.bytequay.core.domain.RepositoryIssuePage;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewAnchor;
import com.bytequay.core.review.ReviewCommit;
import com.bytequay.core.review.ReviewCoverage;
import com.bytequay.core.review.ReviewEvidence;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.review.ReviewInvestigation;
import com.bytequay.core.review.ReviewMessage;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.review.ReviewStep;
import com.bytequay.core.review.ReviewSummary;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.IntStream;

import static com.bytequay.core.review.ReviewStep.Kind.COLLECT_EVIDENCE;
import static com.bytequay.core.review.ReviewStep.Kind.VALIDATE;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestReviewTools
{
    private static final String REVIEW_ID = "review-1";
    private static final String RUN_ID = "run-1";
    private static final String TASK_ID = "e4469e62-6122-4c61-b279-459a6ad35f20";
    private static final String WORKSPACE_ID = "workspace-1";
    private static final String REPO = "acme/widgets";
    private static final String HEAD_SHA = "1111111111111111111111111111111111111111";
    private static final String BASE_SHA = "2222222222222222222222222222222222222222";
    private static final String COMMIT_SHA = "3333333333333333333333333333333333333333";
    private static final String DIFF_VERSION = "abcdef01".repeat(8);
    private static final String DIFF_PATH = "src/main/java/Widget.java";
    private static final String CANDIDATE = "The new input might bypass validation";
    private static final String PATCH = "@@ -1,2 +1,3 @@\n alpha\n+beta\n gamma\n";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    private Path repository;

    private InMemoryAgentReviewStore store;
    private ObjectMapper objectMapper;
    private ReviewTools tools;
    private ReviewBridge bridge;
    private AgentEventService events;
    private GitCommandRunner git;
    private RepoService repositories;
    private PullRequestService pullRequests;
    private SelectedPullRequestReader selectedPullRequests;
    private SelectedPullRequestDiffReader selectedPullRequestDiffs;
    private SelectedCommitPullRequestReader selectedCommitPullRequests;
    private WorkspaceIssueService issues;
    private WorkspaceIssueRefreshService issueRefresh;
    private WorkspaceRepositoryService workspaces;

    @BeforeEach
    void setUp()
    {
        store = new InMemoryAgentReviewStore();
        store.insertReview(review(AgentReview.State.OPEN));
        store.insertRun(run());

        pullRequests = mock(PullRequestService.class);
        selectedPullRequests = mock(SelectedPullRequestReader.class);
        selectedPullRequestDiffs = mock(SelectedPullRequestDiffReader.class);
        selectedCommitPullRequests = mock(SelectedCommitPullRequestReader.class);
        repositories = mock(RepoService.class);
        issues = mock(WorkspaceIssueService.class);
        issueRefresh = mock(WorkspaceIssueRefreshService.class);
        events = mock(AgentEventService.class);
        workspaces = mock(WorkspaceRepositoryService.class);
        git = mock(GitCommandRunner.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        ReviewToolAssignment assignments = mock(ReviewToolAssignment.class);
        TurnId turn = TurnId.random();
        when(assignments.capture(TASK_ID)).thenReturn(Optional.of(turn));
        when(assignments.resolve(REVIEW_ID, Optional.of(turn))).thenAnswer(_ -> {
            ReviewRun current = store.listRuns(REVIEW_ID).getLast();
            return new ReviewToolAssignment.Binding(current, current.currentStep(), Optional.empty());
        });
        tools = new ReviewTools(store, pullRequests, selectedPullRequests, selectedPullRequestDiffs, selectedCommitPullRequests,
                repositories, issues, issueRefresh, events, workspaces, git, objectMapper,
                new ReviewLocks(), assignments, Clock.fixed(NOW, ZoneOffset.UTC));

        when(workspaces.requireReadableRepository(WORKSPACE_ID)).thenReturn(workspaceRepository());
        when(git.run(repository, "show", HEAD_SHA + ":" + DIFF_PATH)).thenReturn(new GitCommandRunner.Result(0, "alpha\nbeta\ngamma\n", false));

        bridge = tools.bridge(TASK_ID).orElseThrow();
    }

    @Test
    void bridgeIsExposedOnlyToTheTaskOfAnOpenReview()
    {
        assertThat(tools.bridge(TASK_ID)).isPresent();
        assertThat(tools.bridge("task-of-some-other-work")).isEmpty();

        store.updateReview(review(AgentReview.State.OPEN).closed(NOW));

        assertThat(tools.bridge(TASK_ID)).isEmpty();
    }

    @Test
    void selectedCommitAssociationsUseTheBoundUpstreamAndRetainOneEvidenceBatch()
    {
        WorkspaceRepositoryContext workspace = new WorkspaceRepositoryContext(WORKSPACE_ID, repository, 5, RepoRef.of("fork", "widgets"),
                OptionalLong.of(7), RepoRef.of("acme", "widgets"), "upstream", "origin", "main", ImmutableList.of());
        when(workspaces.requireReadableRepository(WORKSPACE_ID)).thenReturn(workspace);
        String first = "a".repeat(40);
        String second = "b".repeat(64);
        List<String> shas = ImmutableList.of(first, second);
        List<SelectedCommitPullRequestReader.Item> items = ImmutableList.of(
                new SelectedCommitPullRequestReader.Item(first, Optional.of(19), Optional.of("Mapped title " + "x".repeat(1000)),
                        Optional.of("https://github.com/acme/widgets/pull/19/" + "x".repeat(4000)), Optional.of(NOW), true, false, true),
                new SelectedCommitPullRequestReader.Item(second, Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(NOW.minusSeconds(60)), false, false, true));
        List<String> gaps = IntStream.range(0, 12).mapToObj(index -> "Gap " + index + "x".repeat(2000)).collect(toImmutableList());
        when(selectedCommitPullRequests.read(workspace, shas)).thenReturn(new SelectedCommitPullRequestReader.Read(items, gaps));
        ReviewContextSnapshot snapshot = new ReviewContextSnapshot("frozen-context", true, 7, 7, Optional.of(BASE_SHA),
                Optional.of(ContextPacket.create("Frozen context")), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
        ReviewEvidence existing = new ReviewEvidence("E1", "Original source", "Frozen provenance", "Original evidence", "");
        ReviewRun before = store.updateRun(store.findRun(RUN_ID).orElseThrow().withContext(snapshot, ImmutableMap.of("E1", existing), NOW));
        int writes = store.runUpdates;

        JsonNode result = call("review_get_context", ImmutableMap.of("kind", "commit_prs", "shas", ImmutableList.of(first.toUpperCase(Locale.ROOT), second)));

        verify(selectedCommitPullRequests).read(workspace, shas);
        assertThat(result.path("repository").asText()).isEqualTo(REPO);
        assertThat(result.path("repositoryId").asLong()).isEqualTo(7);
        assertThat(result.path("maxCommits").asInt()).isEqualTo(10);
        assertThat(result.path("maxProviderCandidates").asInt()).isEqualTo(100);
        assertThat(result.path("readAt").asText()).isEqualTo(NOW.toString());
        assertThat(result.path("items")).hasSize(2);
        assertThat(result.at("/items/0/status").asText()).isEqualTo("MAPPED");
        assertThat(result.at("/items/0/pullRequestNumber").asInt()).isEqualTo(19);
        assertThat(result.at("/items/0/pullRequestTitle").asText()).hasSizeLessThanOrEqualTo(214);
        assertThat(result.at("/items/0/pullRequestUrl").asText()).hasSizeLessThanOrEqualTo(2014);
        assertThat(result.at("/items/0/lookupAttempted").asBoolean()).isTrue();
        assertThat(result.at("/items/0/persisted").asBoolean()).isTrue();
        assertThat(result.at("/items/1/status").asText()).isEqualTo("CHECKED_NO_SINGLE_MERGED_PR");
        assertThat(result.at("/items/1/pullRequestNumber").isNull()).isTrue();
        assertThat(result.at("/items/1/checkedAt").asText()).isEqualTo(NOW.minusSeconds(60).toString());
        assertThat(result.path("note").asText()).contains("no single merged PR", "ambiguous", "at most 100", "not proof");
        assertThat(result.path("gaps")).hasSize(10).allSatisfy(gap -> assertThat(gap.asText()).hasSizeLessThanOrEqualTo(1014));
        assertThat(result.path("omittedGaps").asInt()).isEqualTo(2);
        assertThat(store.runUpdates).isEqualTo(writes + 1);
        ReviewRun after = store.findRun(RUN_ID).orElseThrow();
        assertThat(after.evidence()).hasSize(3).containsEntry("E1", existing);
        assertThat(after.context()).contains(snapshot);
        assertThat(after.headSha()).isEqualTo(before.headSha());
        assertThat(after.baseSha()).isEqualTo(before.baseSha());
        assertThat(after.mergeBaseSha()).isEqualTo(before.mergeBaseSha());
        assertThat(after.commits()).isEqualTo(before.commits());
        assertThat(after.diff()).isEqualTo(before.diff());
        for (JsonNode item : result.path("items")) {
            ReviewEvidence evidence = after.evidence().get(item.path("evidenceRef").asText());
            assertThat(evidence.excerpt()).hasSizeLessThanOrEqualTo(4014).contains(item.path("sha").asText(), item.path("status").asText());
            assertThat(evidence.provenance()).contains("repositoryId=7", "exactSha=" + item.path("sha").asText(), "checkedAt=" + item.path("checkedAt").asText(),
                    "readAt=" + NOW, "lookupAttempted=" + item.path("lookupAttempted").asBoolean(), "lookupFailed=false", "persisted=true", "at most 100", "not proof");
        }
        assertThat(after.evidence().get(result.at("/items/0/evidenceRef").asText()).provenance()).contains("observation=selected lookup");
        assertThat(after.evidence().get(result.at("/items/1/evidenceRef").asText()).provenance()).contains("observation=stored enrichment read");
        verifyNoInteractions(git, pullRequests, selectedPullRequests, repositories, issues, issueRefresh);
    }

    @Test
    void commitAssociationInputsRejectMalformedDuplicateOrExcessiveShasBeforeRepositoryReads()
    {
        String sha = "a".repeat(40);
        List<Object> invalid = ImmutableList.of("not-an-array", ImmutableList.of(), ImmutableList.of(12), ImmutableList.of(objectMapper.nullNode()),
                ImmutableList.of("HEAD"), ImmutableList.of("--all"), ImmutableList.of("1234567"), ImmutableList.of("g".repeat(40)),
                ImmutableList.of(sha + "^{commit}"), ImmutableList.of(" " + sha), ImmutableList.of(sha + "\n"), ImmutableList.of(sha, sha),
                ImmutableList.of(sha, sha.toUpperCase(Locale.ROOT)), IntStream.range(0, 11).mapToObj(index -> String.format("%040x", index)).collect(toImmutableList()));
        assertThat(call("review_get_context", ImmutableMap.of("kind", "commit_prs")).at("/error/field").asText()).isEqualTo("shas");
        for (Object value : invalid) {
            assertThat(call("review_get_context", ImmutableMap.of("kind", "commit_prs", "shas", value)).at("/error/field").asText()).isEqualTo("shas");
        }
        assertThat(store.runUpdates).isZero();
        verifyNoInteractions(workspaces, selectedCommitPullRequests, git, repositories);
    }

    @ParameterizedTest
    @ValueSource(strings = {"workspace", "repository", "missing-id"})
    void commitAssociationRoutingCannotLeaveTheBoundReviewWorkspace(String mismatch)
    {
        WorkspaceRepositoryContext workspace = new WorkspaceRepositoryContext(mismatch.equals("workspace") ? "another-workspace" : WORKSPACE_ID,
                repository, 5, RepoRef.of("fork", "widgets"), mismatch.equals("missing-id") ? OptionalLong.empty() : OptionalLong.of(7),
                RepoRef.of(mismatch.equals("repository") ? "another-owner" : "acme", "widgets"), "upstream", "origin", "main", ImmutableList.of());
        when(workspaces.requireReadableRepository(WORKSPACE_ID)).thenReturn(workspace);

        JsonNode result = call("review_get_context", ImmutableMap.of("kind", "commit_prs", "shas", ImmutableList.of(COMMIT_SHA)));

        assertThat(result.at("/error/field").asText()).isEqualTo("shas");
        assertThat(store.runUpdates).isZero();
        verifyNoInteractions(selectedCommitPullRequests, git, repositories);
    }

    @Test
    void missingOrFailedCommitAssociationsNeverBecomeCheckedNegatives()
    {
        WorkspaceRepositoryContext workspace = workspaceRepository();
        List<String> shas = ImmutableList.of(HEAD_SHA, BASE_SHA);
        List<SelectedCommitPullRequestReader.Item> items = ImmutableList.of(
                new SelectedCommitPullRequestReader.Item(HEAD_SHA, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false, false, false),
                new SelectedCommitPullRequestReader.Item(BASE_SHA, Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(NOW.minusSeconds(600)), true, true, false));
        when(selectedCommitPullRequests.read(workspace, shas)).thenReturn(new SelectedCommitPullRequestReader.Read(items, ImmutableList.of("A commit was unavailable locally")));

        JsonNode result = call("review_get_context", ImmutableMap.of("kind", "commit_prs", "shas", shas));

        assertThat(result.path("repositoryId").asLong()).isEqualTo(1);
        assertThat(result.at("/items/0/status").asText()).isEqualTo("UNKNOWN");
        assertThat(result.at("/items/0/checkedAt").isNull()).isTrue();
        assertThat(result.at("/items/1/status").asText()).isEqualTo("LOOKUP_FAILED");
        assertThat(result.at("/items/1/lookupFailed").asBoolean()).isTrue();
        assertThat(result.at("/items/1/persisted").asBoolean()).isFalse();
        assertThat(store.runUpdates).isEqualTo(1);
    }

    @Test
    void incompleteCommitAssociationBatchCannotSilentlyOmitRequestedShas()
    {
        when(selectedCommitPullRequests.read(workspaceRepository(), ImmutableList.of(COMMIT_SHA)))
                .thenReturn(new SelectedCommitPullRequestReader.Read(ImmutableList.of(), ImmutableList.of("Selected commits were unavailable")));

        JsonNode result = call("review_get_context", ImmutableMap.of("kind", "commit_prs", "shas", ImmutableList.of(COMMIT_SHA)));

        assertThat(result.at("/error/field").asText()).isEqualTo("shas");
        assertThat(store.runUpdates).isZero();
    }

    @Test
    void refreshedCurrentPullRequestMetadataKeepsTheFrozenCodeSnapshot()
    {
        String observed = "d".repeat(40);
        PullRequestDetail detail = pullRequestDetail(42, "body " + "x".repeat(9000), ImmutableList.of(), ImmutableList.of(), ImmutableList.of("checks"));
        when(selectedPullRequests.read(REPO, 42, SelectedPullRequestReader.Section.PR)).thenReturn(new SelectedPullRequestReader.Read(detail,
                Optional.of(NOW.minusSeconds(30)), Optional.of(NOW.minusSeconds(10)), Optional.of(observed), true, false, false, false));
        ReviewRun frozen = store.findRun(RUN_ID).orElseThrow();

        JsonNode result = call("review_get_context", ImmutableMap.of("kind", "pr"));

        assertThat(result.path("title").asText()).isEqualTo(detail.title());
        assertThat(result.path("headSha").asText()).isEqualTo(frozen.headSha());
        assertThat(result.path("baseRef").asText()).isEqualTo(frozen.baseRef());
        assertThat(result.path("baseSha").asText()).isEqualTo(frozen.baseSha());
        assertThat(result.path("mergeBaseSha").asText()).isEqualTo(frozen.mergeBaseSha());
        assertThat(result.path("commits")).isEqualTo(objectMapper.valueToTree(frozen.commits()));
        assertThat(result.at("/changedFiles/0/path").asText()).isEqualTo(DIFF_PATH);
        assertThat(result.path("body").asText()).hasSizeLessThanOrEqualTo(8014);
        assertThat(result.path("bodyTruncated").asBoolean()).isTrue();
        assertThat(result.at("/freshness/observedHeadSha").asText()).isEqualTo(observed);
        assertThat(result.at("/freshness/matchesReviewedHead").asBoolean()).isFalse();
        assertThat(result.at("/freshness/sourceUpdatedAt").asText()).isEqualTo(detail.updatedAt().toString());
        assertThat(result.at("/freshness/rowUpdatedAt").asText()).isEqualTo(NOW.minusSeconds(30).toString());
        assertThat(result.at("/freshness/cacheUpdatedAt").asText()).isEqualTo(NOW.minusSeconds(10).toString());
        assertThat(result.at("/freshness/readAt").asText()).isEqualTo(NOW.toString());
        assertThat(result.at("/freshness/incomplete").asBoolean()).isFalse();
        assertThat(result.at("/freshness/unavailableSections/0").asText()).isEqualTo("checks");
        assertThat(result.at("/freshness/note").asText()).contains("requested section", "status-only", "remote currentness");
        ReviewEvidence evidence = store.findRun(RUN_ID).orElseThrow().evidence().get(result.path("evidenceRef").asText());
        assertThat(evidence.source()).isEqualTo(REPO + "#42 metadata");
        assertThat(evidence.excerpt()).hasSizeLessThanOrEqualTo(4014).contains(detail.title(), "body ");
        assertThat(evidence.provenance()).contains("refreshAttempted=true", "observedHeadSha=" + observed, "frozenReviewedHead=" + HEAD_SHA, "unavailableSections=[checks]");
        assertThat(store.findRun(RUN_ID).orElseThrow().headSha()).isEqualTo(HEAD_SHA);
        verifyNoInteractions(pullRequests, repositories, issues, issueRefresh);
    }

    @Test
    void relatedPullRequestMetadataUsesItsOwnIdentityAndNeverBorrowsFrozenCoordinates()
    {
        PullRequestDetail detail = pullRequestDetail(7, "Historical change", ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
        when(selectedPullRequests.read(REPO, 7, SelectedPullRequestReader.Section.PR)).thenReturn(selected(detail, Optional.of("d".repeat(40)), false));

        JsonNode result = call("review_get_context", ImmutableMap.of("kind", "pr", "number", 7));

        assertThat(result.path("repository").asText()).isEqualTo(REPO);
        assertThat(result.path("number").asInt()).isEqualTo(7);
        assertThat(result.path("pullRequest").asBoolean()).isTrue();
        assertThat(result.path("baseRef").asText()).isEqualTo("selected-base");
        assertThat(result.path("headRef").asText()).isEqualTo("selected-head");
        for (String field : ImmutableList.of("headSha", "baseSha", "mergeBaseSha", "previousReviewedHead", "commits", "changedFiles",
                "requiredReviewFiles", "assignment", "scope")) {
            assertThat(result.has(field)).as(field).isFalse();
        }
        assertThat(result.path("freshness").has("matchesReviewedHead")).isFalse();
        ReviewEvidence evidence = store.findRun(RUN_ID).orElseThrow().evidence().get(result.path("evidenceRef").asText());
        assertThat(evidence.source()).isEqualTo(REPO + "#7 metadata");
        assertThat(evidence.provenance()).contains("related historical source").doesNotContain("frozenReviewedHead");
    }

    @ParameterizedTest
    @ValueSource(strings = {"timeline", "checks"})
    void selectedActivityAndChecksArePagedAndRetainedWithOneWritePerPage(String kind)
    {
        List<PullRequestDetail.ActivityItem> activity = IntStream.range(0, 21).mapToObj(index -> new PullRequestDetail.ActivityItem("alice", "commented", NOW,
                "comment " + index + "x".repeat(2500), (long) index + 1,
                PrTimelinePayload.builder().path(DIFF_PATH).line(2).side("RIGHT").resolved(true).build())).collect(toImmutableList());
        List<PullRequestDetail.CheckRun> checks = IntStream.range(0, 21).mapToObj(index -> new PullRequestDetail.CheckRun((long) index + 1,
                "Check " + index + "x".repeat(400), "completed", "success", "https://example.com/check/" + index, "check-run", "test", NOW.minusSeconds(10), NOW))
                .collect(toImmutableList());
        PullRequestDetail detail = pullRequestDetail(42, "Metadata", activity, checks, ImmutableList.of("reviewThreads"));
        SelectedPullRequestReader.Section section = kind.equals("timeline") ? SelectedPullRequestReader.Section.TIMELINE : SelectedPullRequestReader.Section.CHECKS;
        when(selectedPullRequests.read(REPO, 42, section)).thenReturn(selected(detail, Optional.of("d".repeat(40)), true));
        String field = kind.equals("timeline") ? "items" : "checks";
        int writes = store.runUpdates;

        JsonNode result = call("review_get_context", ImmutableMap.of("kind", kind));

        assertThat(result.path(field)).hasSize(20);
        assertThat(result.path("totalItems").asInt()).isEqualTo(21);
        assertThat(result.path("omittedItems").asInt()).isEqualTo(1);
        assertThat(result.path("truncated").asBoolean()).isTrue();
        assertThat(result.at("/freshness/refreshFailed").asBoolean()).isTrue();
        assertThat(result.at("/freshness/stale").asBoolean()).isTrue();
        assertThat(result.at("/freshness/incomplete").asBoolean()).isTrue();
        assertThat(result.at("/freshness/matchesReviewedHead").asBoolean()).isFalse();
        assertThat(store.runUpdates).isEqualTo(writes + 1);
        assertThat(store.findRun(RUN_ID).orElseThrow().evidence()).hasSize(20);
        for (JsonNode item : result.path(field)) {
            ReviewEvidence evidence = store.findRun(RUN_ID).orElseThrow().evidence().get(item.path("evidenceRef").asText());
            assertThat(evidence).isNotNull();
            assertThat(evidence.excerpt()).hasSizeLessThanOrEqualTo(4014).contains("\"id\":" + item.path("id").asInt());
            assertThat(evidence.provenance()).contains("refreshFailed=true", "stale=true", "incomplete=true", "frozenReviewedHead=" + HEAD_SHA);
        }
        if (kind.equals("timeline")) {
            assertThat(result.at("/items/0/bodyTruncated").asBoolean()).isTrue();
            assertThat(result.at("/items/0/path").asText()).isEqualTo(DIFF_PATH);
            assertThat(result.at("/items/0/resolved").asBoolean()).isTrue();
        }

        JsonNode second = call("review_get_context", ImmutableMap.of("kind", kind, "page", 2));
        assertThat(second.path(field)).hasSize(1);
        assertThat(second.path(field).get(0).path("id").asInt()).isEqualTo(21);
        assertThat(store.runUpdates).isEqualTo(writes + 2);
        JsonNode beyondEnd = call("review_get_context", ImmutableMap.of("kind", kind, "page", Integer.MAX_VALUE));
        assertThat(beyondEnd.path(field)).isEmpty();
        assertThat(beyondEnd.path("page").asInt()).isEqualTo(Integer.MAX_VALUE);
        assertThat(store.runUpdates).isEqualTo(writes + 2);
        verifyNoInteractions(pullRequests, repositories, issues, issueRefresh);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pr", "timeline", "checks", "issue"})
    void selectedNumbersAndPagesMustBePositiveIntegralValues(String kind)
    {
        for (Object number : ImmutableList.of(0, -1, 1.5, (long) Integer.MAX_VALUE + 1, "42")) {
            assertThat(call("review_get_context", ImmutableMap.of("kind", kind, "number", number)).at("/error/field").asText()).isEqualTo("number");
        }
        if (!kind.equals("pr")) {
            for (Object page : ImmutableList.of(0, -1, 1.5, (long) Integer.MAX_VALUE + 1)) {
                assertThat(call("review_get_context", ImmutableMap.of("kind", kind, "number", 42, "page", page)).at("/error/field").asText()).isEqualTo("page");
            }
        }
        verifyNoInteractions(selectedPullRequests, pullRequests, repositories, issues, issueRefresh);
    }

    @Test
    void knownPullRequestRequestedAsIssueUsesOneSelectedPrRead()
    {
        PullRequest row = new PullRequest(7, REPO, 7, "Known pull request", "alice", "https://example.com/7", NOW, NOW, PullRequest.Origin.OTHER,
                ImmutableList.of(), ImmutableMap.of(), false, null, null, null, ImmutableList.of(), PullRequestDetail.CiStatus.NONE,
                1, 0, 0, null, "merged", null, NOW, null, null, null, null, null, null, null, null);
        when(pullRequests.findStored(REPO, 7)).thenReturn(Optional.of(row));
        PullRequestDetail detail = pullRequestDetail(7, "Cached PR body", ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
        when(selectedPullRequests.read(REPO, 7, SelectedPullRequestReader.Section.PR)).thenReturn(selected(detail, Optional.empty(), false));

        JsonNode result = call("review_get_context", ImmutableMap.of("kind", "issue", "number", 7));

        assertThat(result.path("pullRequest").asBoolean()).isTrue();
        assertThat(result.path("source").asText()).isEqualTo("selected-pull-request");
        assertThat(result.path("body").asText()).isEqualTo("Cached PR body");
        assertThat(result.path("number").asInt()).isEqualTo(7);
        verify(selectedPullRequests).read(REPO, 7, SelectedPullRequestReader.Section.PR);
        verifyNoInteractions(repositories, issues, issueRefresh, workspaces);
    }

    @Test
    void issueContextUsesTheCachedProjectionAndBoundedActivityWithoutRemoteReads()
    {
        List<PrTimelineEvent> timeline = IntStream.range(0, 21)
                .mapToObj(index -> new PrTimelineEvent((long) index + 1, "commented", "alice", NOW.minusSeconds(30), "comment " + index + "x".repeat(2100), Optional.empty()))
                .collect(toImmutableList());
        cacheIssue("Cached limitation " + "x".repeat(4100), new RepositoryIssueDetail(1, 42, timeline, "Bug", ImmutableList.of(), Optional.of(NOW)));

        JsonNode context = call("review_get_context", ImmutableMap.of("kind", "issue", "number", 42));

        assertThat(context.path("source").asText()).isEqualTo("workspace-issue-cache");
        assertThat(context.path("state").asText()).isEqualTo("open");
        assertThat(context.path("updatedAt").asText()).isEqualTo(NOW.minusSeconds(60).toString());
        assertThat(context.path("projectionSyncedAt").asText()).isEqualTo(NOW.minusSeconds(20).toString());
        assertThat(context.path("bodyTruncated").asBoolean()).isTrue();
        assertThat(context.at("/activity/refreshedAt").asText()).isEqualTo(NOW.toString());
        assertThat(context.at("/activity/missing").asBoolean()).isFalse();
        assertThat(context.at("/activity/stale").asBoolean()).isFalse();
        assertThat(context.at("/activity/completeness").asText()).isEqualTo("unknown");
        assertThat(context.at("/activity/items")).hasSize(20);
        assertThat(context.at("/activity/truncated").asBoolean()).isTrue();
        assertThat(context.at("/activity/omittedItems").asInt()).isEqualTo(1);
        assertThat(context.at("/activity/items/0/bodyTruncated").asBoolean()).isTrue();
        ReviewEvidence bodyEvidence = store.findRun(RUN_ID).orElseThrow().evidence().get(context.path("evidenceRef").asText());
        assertThat(bodyEvidence.provenance()).contains("Cached open issue projection", NOW.minusSeconds(20).toString());
        ReviewEvidence activityEvidence = store.findRun(RUN_ID).orElseThrow().evidence().get(context.at("/activity/items/0/evidenceRef").asText());
        assertThat(activityEvidence.excerpt()).contains("alice commented", "comment 0");

        JsonNode secondPage = call("review_get_context", ImmutableMap.of("kind", "issue", "number", 42, "page", 2));
        assertThat(secondPage.at("/activity/items")).hasSize(1);
        assertThat(secondPage.at("/activity/items/0/id").asLong()).isEqualTo(21);
        verifyNoInteractions(repositories, issueRefresh);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void issueContextRefreshesOnlyMissingOrKnownOutdatedSelectedActivity(boolean missing)
    {
        RepositoryIssueDetail cached = new RepositoryIssueDetail(1, 42, ImmutableList.of(), "Bug", ImmutableList.of(),
                missing ? Optional.empty() : Optional.of(NOW.minusSeconds(120)));
        cacheIssue("Cached issue body", cached);
        RepositoryIssueDetail refreshed = new RepositoryIssueDetail(1, 42,
                ImmutableList.of(new PrTimelineEvent(1L, "commented", "bob", NOW, "New activity", Optional.empty())), "Bug", ImmutableList.of(), Optional.of(NOW));
        when(issueRefresh.refreshDetail(WORKSPACE_ID, 42)).thenReturn(refreshed);

        JsonNode context = call("review_get_context", ImmutableMap.of("kind", "issue", "number", 42));

        assertThat(context.path("body").asText()).isEqualTo("Cached issue body");
        assertThat(context.at("/activity/items/0/body").asText()).isEqualTo("New activity");
        assertThat(context.at("/activity/stale").asBoolean()).isFalse();
        assertThat(context.at("/activity/refreshFailed").asBoolean()).isFalse();
        verify(issueRefresh).refreshDetail(WORKSPACE_ID, 42);
        verify(issueRefresh, never()).refreshIssues(WORKSPACE_ID);
        verifyNoInteractions(repositories);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedIssueActivityRefreshKeepsCachedEvidenceAndItsOriginalFreshness(boolean missing)
    {
        RepositoryIssueDetail cached = new RepositoryIssueDetail(1, 42,
                ImmutableList.of(new PrTimelineEvent(1L, "commented", "bob", NOW.minusSeconds(180), "Old cached activity", Optional.empty())),
                "Bug", ImmutableList.of(), missing ? Optional.empty() : Optional.of(NOW.minusSeconds(120)));
        cacheIssue("Cached issue body", cached);
        when(issueRefresh.refreshDetail(WORKSPACE_ID, 42)).thenThrow(new IllegalStateException("GitHub unavailable"));

        JsonNode context = call("review_get_context", ImmutableMap.of("kind", "issue", "number", 42));

        assertThat(context.path("body").asText()).isEqualTo("Cached issue body");
        assertThat(context.at("/activity/items/0/body").asText()).isEqualTo("Old cached activity");
        if (missing) {
            assertThat(context.at("/activity/refreshedAt").isNull()).isTrue();
        }
        else {
            assertThat(context.at("/activity/refreshedAt").asText()).isEqualTo(NOW.minusSeconds(120).toString());
        }
        assertThat(context.at("/activity/missing").asBoolean()).isEqualTo(missing);
        assertThat(context.at("/activity/stale").asBoolean()).isTrue();
        assertThat(context.at("/activity/refreshFailed").asBoolean()).isTrue();
        assertThat(context.at("/activity/note").asText()).contains("refresh failed", "retained cached activity");
        verifyNoInteractions(repositories);
    }

    @Test
    void uncachedIssueUsesOnlyTheExactSelectedPreview()
    {
        when(repositories.getIssuePreview("acme", "widgets", 42))
                .thenReturn(new GitHubIssuePreview("Closed issue", "Retained limitation", "alice", ImmutableList.of("bug"), NOW, "closed", false));

        JsonNode context = call("review_get_context", ImmutableMap.of("kind", "issue", "number", 42));

        assertThat(context.path("source").asText()).isEqualTo("github-selected-preview");
        assertThat(context.path("state").asText()).isEqualTo("closed");
        assertThat(context.path("updatedAt").asText()).isEqualTo(NOW.toString());
        assertThat(context.at("/activity/missing").asBoolean()).isTrue();
        assertThat(context.at("/activity/completeness").asText()).isEqualTo("unavailable");
        verify(repositories).getIssuePreview("acme", "widgets", 42);
        verify(issues).find(WORKSPACE_ID, 42);
        verify(issues, never()).detail(WORKSPACE_ID, 42);
        verifyNoInteractions(issueRefresh);
    }

    @Test
    void issueContextCannotUseAnotherMonitoredRepositorysSameNumber()
    {
        when(workspaces.requireReadableRepository(WORKSPACE_ID)).thenReturn(new WorkspaceRepositoryContext(WORKSPACE_ID, repository, 1,
                RepoRef.of("acme", "widgets"), OptionalLong.of(2), RepoRef.of("upstream", "widgets"), "upstream", "origin", "main", ImmutableList.of()));
        when(repositories.getIssuePreview("acme", "widgets", 42))
                .thenReturn(new GitHubIssuePreview("Selected issue", "Correct repository", "alice", ImmutableList.of(), NOW, "open", false));

        JsonNode context = call("review_get_context", ImmutableMap.of("kind", "issue", "number", 42));

        assertThat(context.path("body").asText()).isEqualTo("Correct repository");
        verify(repositories).getIssuePreview("acme", "widgets", 42);
        verifyNoInteractions(issues, issueRefresh);
    }

    private void cacheIssue(String body, RepositoryIssueDetail detail)
    {
        RepositoryIssue issue = new RepositoryIssue(42, 42, "Cached issue", body, "alice", "", "https://github.com/acme/widgets/issues/42",
                ImmutableList.of("bug"), ImmutableList.of(), detail.timeline().size(), NOW.minusSeconds(600), NOW.minusSeconds(60));
        when(issues.find(WORKSPACE_ID, 42)).thenReturn(Optional.of(issue));
        when(issues.detail(WORKSPACE_ID, 42)).thenReturn(detail);
        when(issues.list(WORKSPACE_ID, 0, 1)).thenReturn(new RepositoryIssuePage(1, RepoRef.of("acme", "widgets"), ImmutableList.of(issue),
                0, 1, 1, false, Optional.of(NOW.minusSeconds(20))));
    }

    private static PullRequestDetail pullRequestDetail(
            int number, String body, List<PullRequestDetail.ActivityItem> activity, List<PullRequestDetail.CheckRun> checks, List<String> unavailable)
    {
        return new PullRequestDetail(12, REPO, number, "Selected pull request " + number, "https://github.com/" + REPO + "/pull/" + number,
                "merged", false, null, "unknown", "alice", "", NOW.minusSeconds(600), NOW.minusSeconds(30), NOW.minusSeconds(20),
                "selected-base", "selected-head", body, ImmutableList.of(new PullRequestDetail.Label("bug", "ff0000")), ImmutableList.of(), null,
                ImmutableList.of(), 3, 1, 2, 1, PullRequestDetail.CiStatus.PASSING, activity, checks, unavailable, false);
    }

    private static SelectedPullRequestReader.Read selected(PullRequestDetail detail, Optional<String> head, boolean failed)
    {
        return new SelectedPullRequestReader.Read(detail, Optional.of(NOW.minusSeconds(30)), Optional.of(NOW.minusSeconds(10)), head, failed, failed, failed, failed);
    }

    @Test
    void bridgeExposesPackagedReviewPolicyAndNamespacedTools()
            throws IOException
    {
        assertThat(bridge.tools()).extracting(ReviewTool::name).containsExactly(
                "review_get_context",
                "review_save_summary",
                "review_save_investigation",
                "review_save_finding",
                "review_reply",
                "review_withdraw_finding",
                "review_finish");
        assertThat(bridge.tools()).allSatisfy(tool -> assertThat(tool.name()).startsWith(ReviewTool.NAME_PREFIX));
        try (InputStream resource = ReviewPrompts.class.getResourceAsStream("/agent-skills/bytequay-review.md")) {
            assertThat(resource).isNotNull();
            String policy = new String(resource.readAllBytes(), UTF_8).strip();
            assertThat(policy).isNotBlank();
            assertThat(bridge.instruction()).startsWith(policy + "\n\n");
        }
        assertThat(bridge.tools()).allSatisfy(tool -> assertThat(bridge.instruction()).contains(tool.name()));
        ReviewTool summary = bridge.tools().stream().filter(tool -> tool.name().equals("review_save_summary")).findFirst().orElseThrow();
        JsonNode schema = objectMapper.valueToTree(summary.inputSchema());
        assertThat(schema.path("properties").size()).isEqualTo(3);
        assertThat(schema.path("required")).isEqualTo(objectMapper.valueToTree(ImmutableList.of("operationKey", "purpose", "commits")));
        assertThat(summary.description()).doesNotContain("combined result", "critical findings", "supporting evidence");
        assertThat(bridge.instruction()).contains("only purpose", "design", "implementation", "exact replacement code", "RIGHT-side", "omit severity");
        JsonNode contextSchema = objectMapper.valueToTree(bridge.tool("review_get_context").orElseThrow().inputSchema());
        assertThat(contextSchema.at("/properties/diffVersion/pattern").asText()).isEqualTo("^[0-9a-f]{64}$");
        assertThat(contextSchema.at("/properties/refresh/type").asText()).isEqualTo("boolean");
        assertThat(contextSchema.at("/properties/shas/minItems").asInt()).isEqualTo(1);
        assertThat(contextSchema.at("/properties/shas/maxItems").asInt()).isEqualTo(10);
        assertThat(contextSchema.at("/properties/shas/uniqueItems").asBoolean()).isTrue();
        assertThat(contextSchema.path("properties").has("repositoryId")).isFalse();
        assertThat(contextSchema.path("properties").has("workspaceId")).isFalse();
        assertThat(contextSchema.path("required")).isEqualTo(objectMapper.valueToTree(ImmutableList.of("kind")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "context-source:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            "review:11111111-1111-4111-8111-111111111111"})
    void capturedContextReferencesCanBeReadAndCitedThroughoutReview(String ref)
    {
        store.updateRun(run(RUN_ID, 1, PATCH, false));
        readSource();
        ReviewEvidence captured = new ReviewEvidence(ref, "Captured reference", "Frozen repository context", "Caller contract", "");
        store.updateRun(store.findRun(RUN_ID).orElseThrow().withEvidence(ref, captured, NOW));
        assertThat(call("review_get_context", ImmutableMap.of("kind", "evidence", "ref", ref)))
                .isEqualTo(objectMapper.valueToTree(captured));
        call("review_save_summary", summary("summary", "Guard input"));
        List<String> refs = ImmutableList.of("E1", ref);

        assertThat(call("review_save_investigation", investigation("investigate", ImmutableList.of(DIFF_PATH), refs, ImmutableList.of(CANDIDATE)))
                .path("status").asText()).isEqualTo("accepted");
        advance(VALIDATE);
        JsonNode saved = call("review_save_finding", finding("finding", "Unchecked input", "A2", ImmutableList.of(evidence("E1"), evidence(ref))));
        assertThat(saved.path("status").asText()).isEqualTo("accepted");
        Map<String, Object> verdict = new LinkedHashMap<>(supported(1, saved.path("findingId").asText(), 1));
        verdict.put("evidenceRefs", refs);
        assertThat(call("review_finish", finish("finish", ImmutableList.of(verdict))).path("status").asText()).isEqualTo("accepted");

        ReviewRun run = store.findRun(RUN_ID).orElseThrow();
        assertThat(run.investigation().orElseThrow().contextEvidenceRefs()).containsExactlyElementsOf(refs);
        assertThat(run.currentStep().orElseThrow().validation().orElseThrow().candidates().getFirst().evidenceRefs()).containsExactlyElementsOf(refs);
        assertThat(store.listFindings(REVIEW_ID).getFirst().evidence()).extracting(ReviewEvidence::ref).containsExactlyElementsOf(refs);
        JsonNode readSchema = objectMapper.valueToTree(bridge.tool("review_get_context").orElseThrow().inputSchema());
        assertThat(readSchema.at("/properties/ref/maxLength").asInt()).isGreaterThanOrEqualTo(ref.length());
        JsonNode findingSchema = objectMapper.valueToTree(bridge.tool("review_save_finding").orElseThrow().inputSchema());
        assertThat(findingSchema.at("/properties/evidence/items/properties/ref/maxLength").asInt()).isGreaterThanOrEqualTo(ref.length());
    }

    @Test
    void saveFindingRejectsEvidenceAndAnchorsItCannotResolve()
    {
        readSource();

        JsonNode uncited = call("review_save_finding", finding("no-evidence", "Missing null check", "A2", ImmutableList.of()));
        assertThat(uncited.path("error").path("field").asText()).isEqualTo("evidence");
        assertThat(store.listFindings(REVIEW_ID)).isEmpty();

        JsonNode invented = call("review_save_finding", finding("invented-evidence", "Missing null check", "A2", ImmutableList.of(evidence("E7"))));
        assertThat(invented.path("error").path("field").asText()).isEqualTo("evidence");
        assertThat(invented.path("error").path("message").asText()).contains("E7");
        assertThat(store.listFindings(REVIEW_ID)).isEmpty();

        JsonNode unknownAnchor = call("review_save_finding", finding("unknown-anchor", "Missing null check", "A99", ImmutableList.of(evidence("E1"))));
        assertThat(unknownAnchor.path("error").path("field").asText()).isEqualTo("startAnchor");
        assertThat(store.listFindings(REVIEW_ID)).isEmpty();

        Map<String, Object> reversed = ImmutableMap.<String, Object>builder()
                .putAll(finding("reversed-range", "Missing null check", "A3", ImmutableList.of(evidence("E1"))))
                .put("endAnchor", "A2")
                .buildOrThrow();
        JsonNode crossed = call("review_save_finding", reversed);
        assertThat(crossed.path("error").path("field").asText()).isEqualTo("endAnchor");
        assertThat(store.listFindings(REVIEW_ID)).isEmpty();
    }

    @Test
    void saveFindingAnchorsCitedEvidenceOnTheFrozenDiff()
    {
        readSource();

        JsonNode saved = call("review_save_finding", finding("first", "Missing null check", "A2", ImmutableList.of(evidence("E1"))));

        assertThat(saved.path("status").asText()).isEqualTo("accepted");
        assertThat(saved.path("label").asText()).isEqualTo("F-1");
        assertThat(saved.path("revision").asInt()).isEqualTo(1);
        assertThat(saved.path("location").asText()).isEqualTo(DIFF_PATH + " R2");

        List<ReviewFinding> findings = store.listFindings(REVIEW_ID);
        assertThat(findings).hasSize(1);
        ReviewFinding stored = findings.getFirst();
        assertThat(stored.id()).isEqualTo(saved.path("findingId").asText());
        assertThat(stored.state()).isEqualTo(ReviewFinding.State.OPEN);
        assertThat(stored.anchor().path()).isEqualTo(DIFF_PATH);
        assertThat(stored.anchor().side()).isEqualTo("RIGHT");
        assertThat(stored.anchor().startLine()).isEqualTo(2);
        assertThat(stored.anchor().endLine()).isEqualTo(2);
        assertThat(stored.anchor().excerpt()).containsExactly("beta");
        assertThat(stored.evidence()).extracting(ReviewEvidence::ref).containsExactly("E1");
        assertThat(store.findRun(RUN_ID).orElseThrow().version()).isPositive();
    }

    @Test
    void suggestionPreservesExactReplacementAndValidatesItsDiffRange()
    {
        readSource();
        String body = "Optional improvement: combine these two steps.\n\n```suggestion\n    combined();\n```";
        Map<String, Object> suggestion = ImmutableMap.of("operationKey", "suggest", "title", "Suggestion: combine the steps", "body", body,
                "startAnchor", "A2", "endAnchor", "A3", "evidence", ImmutableList.of(evidence("E1")));
        assertThat(call("review_save_finding", validated(suggestion)).path("status").asText()).isEqualTo("accepted");
        ReviewFinding finding = store.listFindings(REVIEW_ID).getFirst();
        assertThat(finding.body()).isEqualTo(body);
        assertThat(finding.severity()).isEmpty();
        assertThat(finding.anchor()).isEqualTo(new ReviewAnchor(DIFF_PATH, "RIGHT", 2, 3, ImmutableList.of("beta", "gamma")));

        Map<String, Object> offset = ImmutableMap.of("operationKey", "offset", "title", "Suggestion: another replacement",
                "body", body.replace("```suggestion", "```suggestion:-1+2"), "startAnchor", "A2", "evidence", ImmutableList.of(evidence("E1")));
        assertThat(call("review_save_finding", validated(offset)).path("error").path("field").asText()).isEqualTo("body");
        assertThat(store.listFindings(REVIEW_ID)).hasSize(1);
    }

    @Test
    void suggestionCannotTargetDeletedLines()
    {
        store.updateRun(run(RUN_ID, 1, "@@ -1,3 +1,2 @@\n alpha\n-beta\n gamma\n"));
        readSource();
        Map<String, Object> suggestion = ImmutableMap.of("operationKey", "deleted", "title", "Suggestion: replace the old line",
                "body", "Use this replacement.\n\n```suggestion\nreplacement\n```", "startAnchor", "A2", "evidence", ImmutableList.of(evidence("E1")));
        assertThat(call("review_save_finding", validated(suggestion)).path("error").path("field").asText()).isEqualTo("startAnchor");
        assertThat(store.listFindings(REVIEW_ID)).isEmpty();
    }

    @Test
    void retainedSummarySectionsRemainAvailableToTheirSavedDiscussions()
    {
        ReviewSummary saved = new ReviewSummary(1, "Keep state", ImmutableList.of(), "Old combined result", Optional.of("Recorded evidence"), ImmutableList.of("old-finding"));
        store.updateRun(run().withSummary(saved, NOW));
        assertThat(call("review_save_summary", summary("revise-intention", "Explain the new intention")).path("revision").asInt()).isEqualTo(2);
        ReviewSummary revised = store.findRun(RUN_ID).orElseThrow().summary().orElseThrow();
        assertThat(revised.purpose()).isEqualTo("Explain the new intention");
        assertThat(revised.commits()).extracting(ReviewSummary.CommitExplanation::sha).containsExactly(COMMIT_SHA);
        assertThat(revised.criticalFindingIds()).containsExactly("old-finding");
        assertThat(call("review_get_context", ImmutableMap.of("kind", "summary", "target", RUN_ID + ":combined-result")).path("body").asText())
                .isEqualTo("Old combined result");
        assertThat(call("review_get_context", ImmutableMap.of("kind", "summary", "target", RUN_ID + ":evidence")).path("body").asText())
                .isEqualTo("Recorded evidence");
        String limitations = RUN_ID + ":limitations";
        assertThat(call("review_reply", ImmutableMap.of("operationKey", "retained-reply", "target", limitations, "body", "Earlier discussion answer"))
                .path("status").asText()).isEqualTo("accepted");
        assertThat(call("review_get_context", ImmutableMap.of("kind", "discussion", "target", limitations)).at("/messages/0/body").asText())
                .isEqualTo("Earlier discussion answer");
        assertThat(call("review_get_context", ImmutableMap.of("kind", "summary", "target", limitations)).path("body").asText())
                .contains("original card body is not retained", "saved discussion messages");
    }

    @Test
    void commandResultsCanBeCapturedAndCitedWithFrozenSourceEvidence()
    {
        readSource();
        TurnId turnId = TurnId.random();
        String command = "mvn -Dtest=TestWidget#rejectsNull test";
        String output = "Tests run: 1, Failures: 1\nExpected rejection but the null value was accepted.";
        AgentEventEnvelope completed = new AgentEventEnvelope(
                AgentEventEnvelope.CURRENT_SCHEMA_VERSION,
                EventId.random(),
                1,
                TaskId.valueOf(TASK_ID),
                Optional.of(turnId),
                AgentEventSource.root(),
                NOW,
                new AgentEventPayload.CommandActivity(
                        ActivityState.COMPLETED,
                        ImmutableList.of("mvn", "-Dtest=TestWidget#rejectsNull", "test"),
                        Optional.of(output),
                        Optional.of(1),
                        false));
        when(events.readHistory(TaskId.valueOf(TASK_ID), OptionalLong.empty(), OptionalLong.empty()))
                .thenReturn(new AgentHistoryPage(ImmutableList.of(completed), OptionalLong.empty(), OptionalLong.empty(), 1));

        JsonNode commands = call("review_get_context", ImmutableMap.of("kind", "command_output")).path("commands");
        assertThat(commands).hasSize(1);
        JsonNode recorded = commands.get(0);
        assertThat(recorded.path("command").asText()).isEqualTo(command);
        assertThat(recorded.path("exitCode").asText()).isEqualTo("1");
        assertThat(recorded.path("output").asText()).contains(output);
        String ref = recorded.path("evidenceRef").asText();
        JsonNode saved = call("review_save_finding", finding("reproduced", "Null input is accepted", "A2", ImmutableList.of(
                evidence("E1"),
                ImmutableMap.of("ref", ref, "explanation", "The focused test expected rejection and failed because null was accepted; exit code 1."))));

        assertThat(saved.path("status").asText()).isEqualTo("accepted");
        assertThat(store.listFindings(REVIEW_ID).getFirst().evidence()).hasSize(2).last().satisfies(evidence -> {
            assertThat(evidence.ref()).isEqualTo(ref);
            assertThat(evidence.excerpt()).contains(command, output);
            assertThat(evidence.provenance()).contains(turnId.toString());
            assertThat(evidence.explanation()).contains("exit code 1");
        });
    }

    @Test
    void repeatingAnOperationKeyReplaysWithinTheAssignmentAndItsRepair()
    {
        readSource();
        Map<String, Object> arguments = finding("save-1", "Missing null check", "A2", ImmutableList.of(evidence("E1")));

        JsonNode first = call("review_save_finding", arguments);
        JsonNode replayed = call("review_save_finding", arguments);

        assertThat(replayed.toString()).isEqualTo(first.toString());
        assertThat(store.listFindings(REVIEW_ID)).hasSize(1);

        JsonNode conflict = call("review_save_finding", finding("save-1", "A different claim entirely", "A2", ImmutableList.of(evidence("E1"))));

        assertThat(conflict.path("error").path("field").asText()).isEqualTo("operationKey");
        assertThat(conflict.path("error").path("message").asText()).contains("already used with different content");
        assertThat(store.listFindings(REVIEW_ID)).hasSize(1);

        ReviewRun current = store.findRun(RUN_ID).orElseThrow();
        store.updateRun(current.replaceCurrentStep(current.currentStep().orElseThrow().forRepair(), NOW));
        assertThat(call("review_save_finding", arguments)).isEqualTo(first);
        assertThat(store.listFindings(REVIEW_ID)).hasSize(1);
    }

    @Test
    void saveSummaryValidatesScopeAndRevisesOnEachAcceptedSave()
    {
        Map<String, Object> foreignCommit = ImmutableMap.<String, Object>of(
                "operationKey", "out-of-scope",
                "purpose", "Explains the change",
                "commits", ImmutableList.of(ImmutableMap.of("sha", "deadbeef", "explanation", "not a commit of this run")));
        JsonNode rejectedCommit = call("review_save_summary", foreignCommit);
        assertThat(rejectedCommit.path("error").path("field").asText()).isEqualTo("commits");
        assertThat(store.findRun(RUN_ID).orElseThrow().summary()).isEmpty();

        JsonNode accepted = call("review_save_summary", summary("summary-1", "Explains the change"));
        assertThat(accepted.path("status").asText()).isEqualTo("accepted");
        assertThat(accepted.path("revision").asInt()).isEqualTo(1);

        JsonNode revised = call("review_save_summary", summary("summary-2", "Explains the change more carefully"));
        assertThat(revised.path("revision").asInt()).isEqualTo(2);

        ReviewSummary stored = store.findRun(RUN_ID).orElseThrow().summary().orElseThrow();
        assertThat(stored.revision()).isEqualTo(2);
        assertThat(stored.purpose()).isEqualTo("Explains the change more carefully");
        assertThat(stored.commits()).extracting(ReviewSummary.CommitExplanation::sha).containsExactly(COMMIT_SHA);
        assertThat(stored.combinedResult()).isEmpty();
        assertThat(stored.evidence()).isEmpty();
        assertThat(stored.criticalFindingIds()).isEmpty();
    }

    @Test
    void finishRecordsPartialCoverageWithoutPretendingTheMissingSummaryIsComplete()
    {
        Map<String, Object> contradictory = ImmutableMap.<String, Object>of(
                "operationKey", "finish-contradictory",
                "complete", true,
                "omissions", ImmutableList.of("the generated sources were skipped"),
                "validations", ImmutableList.of());
        JsonNode rejected = call("review_finish", contradictory);
        assertThat(rejected.path("error").path("field").asText()).isEqualTo("complete");
        assertThat(store.findRun(RUN_ID).orElseThrow().coverage()).isEmpty();

        Map<String, Object> honest = ImmutableMap.<String, Object>of(
                "operationKey", "finish-1",
                "investigationStep", 1,
                "complete", false,
                "omissions", ImmutableList.of("the generated sources were skipped"),
                "validations", ImmutableList.of(assessment(1, "UNRESOLVED")));
        JsonNode accepted = call("review_finish", honest);

        assertThat(accepted.path("status").asText()).isEqualTo("accepted");
        ReviewCoverage coverage = store.findRun(RUN_ID).orElseThrow().coverage().orElseThrow();
        assertThat(coverage.complete()).isFalse();
        assertThat(coverage.omissions()).containsExactly("the generated sources were skipped");
    }

    @Test
    void withdrawFindingNeedsTheCurrentRevision()
    {
        readSource();
        String findingId = call("review_save_finding", finding("first", "Missing null check", "A2", ImmutableList.of(evidence("E1")))).path("findingId").asText();

        JsonNode stale = call("review_withdraw_finding", withdrawal("stale", findingId, 4));
        assertThat(stale.path("error").path("field").asText()).isEqualTo("expectedRevision");
        assertThat(stale.path("error").path("message").asText()).contains("revision 1");
        assertThat(store.findFinding(findingId).orElseThrow().state()).isEqualTo(ReviewFinding.State.OPEN);

        JsonNode withdrawn = call("review_withdraw_finding", withdrawal("withdraw", findingId, 1));

        assertThat(withdrawn.path("state").asText()).isEqualTo("WITHDRAWN");
        assertThat(store.findFinding(findingId).orElseThrow().state()).isEqualTo(ReviewFinding.State.WITHDRAWN);
        assertThat(store.listMessages(REVIEW_ID)).hasSize(1);
        assertThat(store.listMessages(REVIEW_ID).getFirst().body()).startsWith("Withdrawn: ");
    }

    @Test
    void intentionAndCommitCardsKeepIndependentDiscussions()
    {
        Map<String, Object> report = ImmutableMap.<String, Object>builder()
                .put("operationKey", "report").put("purpose", "Avoid losing parser state")
                .put("commits", ImmutableList.of(ImmutableMap.of("sha", COMMIT_SHA, "explanation", "Keep the parser snapshot"))).buildOrThrow();
        assertThat(call("review_save_summary", report).path("status").asText()).isEqualTo("accepted");
        assertThat(store.findRun(RUN_ID).orElseThrow().summary().orElseThrow().evidence()).isEmpty();
        assertThat(call("review_reply", ImmutableMap.of("operationKey", "old-summary", "target", "summary", "body", "Earlier summary answer"))
                .path("status").asText()).isEqualTo("accepted");
        store.insertRun(run("newer-run", 2));
        String purposeCard = RUN_ID + ":purpose";
        String commitCard = RUN_ID + ":commit:" + COMMIT_SHA;
        assertThat(call("review_reply", ImmutableMap.of("operationKey", "purpose-reply", "target", purposeCard, "body", "Purpose answer"))
                .path("status").asText()).isEqualTo("accepted");
        assertThat(call("review_reply", ImmutableMap.of("operationKey", "commit-reply", "target", commitCard, "body", "Commit answer"))
                .path("status").asText()).isEqualTo("accepted");
        assertThat(store.listMessages(REVIEW_ID)).extracting(ReviewMessage::runId).containsOnly(RUN_ID);
        assertThat(store.listMessages(REVIEW_ID)).extracting(ReviewMessage::cardId).containsExactly(Optional.empty(), Optional.of(purposeCard), Optional.of(commitCard));
        JsonNode discussion = call("review_get_context", ImmutableMap.of("kind", "discussion", "target", purposeCard));
        assertThat(discussion.path("messages")).hasSize(2);
        assertThat(discussion.at("/messages/0/body").asText()).isEqualTo("Earlier summary answer");
        assertThat(discussion.at("/messages/1/body").asText()).isEqualTo("Purpose answer");
        JsonNode context = call("review_get_context", ImmutableMap.of("kind", "summary", "target", commitCard));
        assertThat(context.path("body").asText()).contains("Add the beta line", "Keep the parser snapshot");
        assertThat(context.path("headSha").asText()).isEqualTo(HEAD_SHA);
        assertThat(call("review_reply", ImmutableMap.of("operationKey", "foreign", "target", "foreign-run:purpose", "body", "Wrong target"))
                .path("error").path("field").asText()).isEqualTo("cardId");
        assertThat(store.listMessages(REVIEW_ID)).hasSize(3);
    }

    @Test
    void withdrawFindingLeavesAUserResolutionStanding()
    {
        store.insertFinding(resolvedFinding());

        JsonNode withdrawn = call("review_withdraw_finding", withdrawal("withdraw", "finding-resolved", 1));

        assertThat(withdrawn.path("status").asText()).isEqualTo("accepted");
        assertThat(withdrawn.path("state").asText()).isEqualTo("RESOLVED");
        ReviewFinding stored = store.findFinding("finding-resolved").orElseThrow();
        assertThat(stored.state()).isEqualTo(ReviewFinding.State.RESOLVED);
        assertThat(stored.stateReason()).contains("the user resolved this locally");
        assertThat(store.listMessages(REVIEW_ID)).extracting(ReviewMessage::body).containsExactly("Withdrawn: the concern no longer holds");
    }

    @Test
    void getContextReadsDiffAnchorsAndRefusesPathsOutsideTheRepository()
    {
        JsonNode overview = call("review_get_context", ImmutableMap.<String, Object>of("kind", "diff"));
        assertThat(overview.path("anchors").asInt()).isEqualTo(3);
        assertThat(overview.path("files").size()).isEqualTo(1);
        assertThat(overview.path("files").get(0).path("path").asText()).isEqualTo(DIFF_PATH);
        assertThat(overview.path("files").get(0).path("hunks").asInt()).isEqualTo(1);

        JsonNode file = call("review_get_context", ImmutableMap.<String, Object>of("kind", "diff", "path", DIFF_PATH));
        assertThat(file.path("path").asText()).isEqualTo(DIFF_PATH);
        assertThat(file.path("pages").asInt()).isEqualTo(1);
        assertThat(file.path("hunks").asText()).startsWith("@@ -1,2 +1,3 @@");
        assertThat(file.path("hunks").asText()).contains("A2 +R2 beta");

        JsonNode traversal = call("review_get_context", ImmutableMap.<String, Object>of("kind", "source", "path", "../secrets.txt"));
        assertThat(traversal.path("error").path("field").asText()).isEqualTo("path");

        JsonNode absolute = call("review_get_context", ImmutableMap.<String, Object>of("kind", "source", "path", "/etc/passwd"));
        assertThat(absolute.path("error").path("field").asText()).isEqualTo("path");
    }

    @Test
    void relatedDiffIndexIsBoundedAndExplicitlyUnverified()
    {
        List<DiffFile> files = IntStream.range(0, 22)
                .mapToObj(index -> new DiffFile(index == 0 ? "x".repeat(1001) : "file-" + index, "modified", 1, 2, index == 1 ? null : PATCH))
                .collect(toImmutableList());
        List<String> gaps = IntStream.range(0, 12).mapToObj(index -> "Gap " + index + "x".repeat(2000)).collect(toImmutableList());
        SelectedPullRequestDiffReader.Read initial = new SelectedPullRequestDiffReader.Read(files, DIFF_VERSION, Optional.of("a".repeat(40)),
                Optional.of(NOW.minusSeconds(30)), Optional.of(30), NOW, true, true, gaps);
        SelectedPullRequestDiffReader.Read continuation = relatedDiff(files);
        when(selectedPullRequestDiffs.read(REPO, 19, Optional.empty(), true)).thenReturn(initial);
        when(selectedPullRequestDiffs.read(REPO, 19, Optional.of(DIFF_VERSION), false)).thenReturn(continuation);

        JsonNode index = call("review_get_context", ImmutableMap.of("kind", "diff", "number", 19, "refresh", true));

        verify(selectedPullRequestDiffs).read(REPO, 19, Optional.empty(), true);
        assertThat(index.path("repository").asText()).isEqualTo(REPO);
        assertThat(index.path("number").asInt()).isEqualTo(19);
        assertThat(index.path("diffVersion").asText()).isEqualTo(DIFF_VERSION);
        assertThat(index.path("readAt").asText()).isEqualTo(NOW.toString());
        assertThat(index.path("observedHeadSha").asText()).isEqualTo("a".repeat(40));
        assertThat(index.path("sourceUpdatedAt").asText()).isEqualTo(NOW.minusSeconds(30).toString());
        assertThat(index.path("reportedChangedFiles").asInt()).isEqualTo(30);
        assertThat(index.path("refreshAttempted").asBoolean()).isTrue();
        assertThat(index.path("refreshFailed").asBoolean()).isTrue();
        assertThat(index.path("freshnessKnown").asBoolean()).isFalse();
        assertThat(index.path("completeKnown").asBoolean()).isFalse();
        assertThat(index.path("finalMergedDiffVerified").asBoolean()).isFalse();
        assertThat(index.path("metadataVersionLinked").asBoolean()).isFalse();
        assertThat(index.path("files")).hasSize(20);
        assertThat(index.path("pages").asInt()).isEqualTo(2);
        assertThat(index.path("totalFiles").asInt()).isEqualTo(22);
        assertThat(index.at("/files/0/pathReadable").asBoolean()).isFalse();
        assertThat(index.at("/files/0/pathTruncated").asBoolean()).isTrue();
        assertThat(index.at("/files/0/pathPreview").asText()).hasSizeLessThanOrEqualTo(1014);
        assertThat(index.at("/files/0").has("path")).isFalse();
        assertThat(index.at("/files/1/patchAvailable").asBoolean()).isFalse();
        assertThat(index.path("gaps")).hasSize(10).allSatisfy(gap -> assertThat(gap.asText()).hasSizeLessThanOrEqualTo(1014));
        assertThat(index.path("omittedGaps").asInt()).isEqualTo(2);
        assertThat(index.path("note").asText()).contains("Metadata-only", "no completeness", "absent metadata is unknown");
        assertThat(index.has("anchors")).isFalse();
        assertThat(index.has("headSha")).isFalse();
        assertThat(index.has("baseSha")).isFalse();
        JsonNode later = call("review_get_context", ImmutableMap.of("kind", "diff", "number", 19, "diffVersion", DIFF_VERSION, "page", 2));
        assertThat(later.path("files")).hasSize(2);
        assertThat(later.at("/files/0/path").asText()).isEqualTo("file-20");
        assertThat(later.path("observedHeadSha").isNull()).isTrue();
        assertThat(later.path("sourceUpdatedAt").isNull()).isTrue();
        assertThat(later.path("reportedChangedFiles").isNull()).isTrue();
        JsonNode beyond = call("review_get_context", ImmutableMap.of("kind", "diff", "number", 19, "diffVersion", DIFF_VERSION, "page", Integer.MAX_VALUE));
        assertThat(beyond.path("files")).isEmpty();
        assertThat(store.runUpdates).isZero();
        verifyNoInteractions(selectedPullRequests, selectedCommitPullRequests, workspaces, git);
    }

    @Test
    void relatedPatchPagesRetainExactUnicodeFragmentsWithoutChangingTheFrozenRun()
    {
        String patch = "@@ -1 +1 @@\n+" + "😀".repeat(4200) + "tail\n";
        SelectedPullRequestDiffReader.Read read = relatedDiff(ImmutableList.of(new DiffFile(" related.txt ", "modified", 1, 1, patch)));
        when(selectedPullRequestDiffs.read(REPO, 19, Optional.of(DIFF_VERSION), false)).thenReturn(read);
        ReviewContextSnapshot snapshot = new ReviewContextSnapshot("frozen-context", true, 7, 1, Optional.of(BASE_SHA),
                Optional.of(ContextPacket.create("Frozen context")), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
        ReviewRun before = store.updateRun(store.findRun(RUN_ID).orElseThrow().withContext(snapshot, ImmutableMap.of(), NOW));
        int writes = store.runUpdates;
        StringBuilder reconstructed = new StringBuilder();
        for (int page = 1; page <= 3; page++) {
            JsonNode result = call("review_get_context", ImmutableMap.of("kind", "diff", "number", 19, "path", " related.txt ", "diffVersion", DIFF_VERSION, "page", page));
            String fragment = result.path("patch").asText();
            reconstructed.append(fragment);
            assertThat(fragment.codePointCount(0, fragment.length())).isLessThanOrEqualTo(2000);
            assertThat(fragment).hasSizeLessThanOrEqualTo(4000);
            assertThat(Character.isLowSurrogate(fragment.charAt(0))).isFalse();
            assertThat(Character.isHighSurrogate(fragment.charAt(fragment.length() - 1))).isFalse();
            assertThat(result.path("page").asInt()).isEqualTo(page);
            assertThat(result.path("pages").asInt()).isEqualTo(3);
            assertThat(result.path("totalCodePoints").asInt()).isEqualTo(patch.codePointCount(0, patch.length()));
            assertThat(result.path("format").asText()).contains("may start or end mid-line", "no current-review inline anchors");
            assertThat(result.has("hunks")).isFalse();
            assertThat(result.has("anchors")).isFalse();
            String ref = result.path("evidenceRef").asText();
            ReviewEvidence evidence = store.findRun(RUN_ID).orElseThrow().evidence().get(ref);
            assertThat(evidence.excerpt()).isEqualTo(fragment);
            assertThat(evidence.source()).isEqualTo(REPO + "#19  related.txt ");
            assertThat(evidence.provenance()).contains("diffVersion=" + DIFF_VERSION, "page=" + page, "readAt=" + NOW, "not a verified Git range");
            assertThat(call("review_get_context", ImmutableMap.of("kind", "evidence", "ref", ref)).path("excerpt").asText()).isEqualTo(fragment);
        }
        assertThat(reconstructed.toString()).isEqualTo(patch);
        JsonNode beyond = call("review_get_context", ImmutableMap.of(
                "kind", "diff", "number", 19, "path", " related.txt ", "diffVersion", DIFF_VERSION, "page", Integer.MAX_VALUE));
        assertThat(beyond.path("patch").asText()).isEmpty();
        assertThat(beyond.path("startCodePoint").asInt()).isEqualTo(patch.codePointCount(0, patch.length()));
        assertThat(store.runUpdates).isEqualTo(writes + 4);
        ReviewRun after = store.findRun(RUN_ID).orElseThrow();
        assertThat(after.context()).contains(snapshot);
        assertThat(after.diff()).isEqualTo(before.diff());
        assertThat(after.commits()).isEqualTo(before.commits());
        assertThat(after.baseSha()).isEqualTo(before.baseSha());
        assertThat(after.headSha()).isEqualTo(before.headSha());
        assertThat(after.mergeBaseSha()).isEqualTo(before.mergeBaseSha());
        JsonNode current = call("review_get_context", ImmutableMap.of("kind", "diff", "number", 42, "path", DIFF_PATH));
        assertThat(current.path("hunks").asText()).contains("A2 +R2 beta");
        assertThat(current.has("diffVersion")).isFalse();
        assertThat(current.has("evidenceRef")).isFalse();
        assertThat(store.runUpdates).isEqualTo(writes + 4);
    }

    @Test
    void relatedDiffKeepsUnavailableAndEmptyPatchArtifactsDistinct()
    {
        SelectedPullRequestDiffReader.Read read = relatedDiff(ImmutableList.of(
                new DiffFile("unavailable", "modified", 1, 1, null), new DiffFile("empty", "renamed", 0, 0, "")));
        when(selectedPullRequestDiffs.read(REPO, 19, Optional.of(DIFF_VERSION), false)).thenReturn(read);
        JsonNode unavailable = call("review_get_context", ImmutableMap.of("kind", "diff", "number", 19, "path", "unavailable", "diffVersion", DIFF_VERSION));
        assertThat(unavailable.path("patchAvailable").asBoolean()).isFalse();
        assertThat(unavailable.path("patch").isNull()).isTrue();
        assertThat(unavailable.path("note").asText()).contains("not evidence");
        assertThat(unavailable.has("evidenceRef")).isFalse();
        assertThat(store.runUpdates).isZero();
        JsonNode empty = call("review_get_context", ImmutableMap.of("kind", "diff", "number", 19, "path", "empty", "diffVersion", DIFF_VERSION));
        assertThat(empty.path("patchAvailable").asBoolean()).isTrue();
        assertThat(empty.path("patch").asText()).isEmpty();
        assertThat(empty.path("totalCodePoints").asInt()).isZero();
        assertThat(empty.path("pages").asInt()).isEqualTo(1);
        assertThat(store.findRun(RUN_ID).orElseThrow().evidence().get(empty.path("evidenceRef").asText()).excerpt()).isEmpty();
        assertThat(store.runUpdates).isEqualTo(1);
        SelectedPullRequestDiffReader.Read emptyArtifact = relatedDiff(ImmutableList.of());
        when(selectedPullRequestDiffs.read(REPO, 20, Optional.empty(), false)).thenReturn(emptyArtifact);
        JsonNode index = call("review_get_context", ImmutableMap.of("kind", "diff", "number", 20));
        assertThat(index.path("files")).isEmpty();
        assertThat(index.path("pages").asInt()).isEqualTo(1);
        assertThat(index.has("error")).isFalse();
    }

    @Test
    void relatedDiffRejectsInvalidSelectorsBeforeReading()
    {
        List<Map<String, Object>> invalid = ImmutableList.of(
                ImmutableMap.of("path", "file"), ImmutableMap.of("page", 2), ImmutableMap.of("page", Integer.MAX_VALUE),
                ImmutableMap.of("diffVersion", "a".repeat(40)), ImmutableMap.of("diffVersion", "A".repeat(64)), ImmutableMap.of("diffVersion", 1),
                ImmutableMap.of("refresh", "true"), ImmutableMap.of("refresh", 1), ImmutableMap.of("refresh", true, "diffVersion", DIFF_VERSION),
                ImmutableMap.of("refresh", true, "path", "file", "diffVersion", DIFF_VERSION), ImmutableMap.of("refresh", true, "page", 2),
                ImmutableMap.of("path", "x".repeat(1001), "diffVersion", DIFF_VERSION), ImmutableMap.of("path", "", "diffVersion", DIFF_VERSION),
                ImmutableMap.of("path", 1, "diffVersion", DIFF_VERSION), ImmutableMap.of("revision", "head"), ImmutableMap.of("page", 1.5));
        for (Map<String, Object> arguments : invalid) {
            Map<String, Object> request = new LinkedHashMap<>(arguments);
            request.put("kind", "diff");
            request.put("number", 19);
            assertThat(call("review_get_context", request).has("error")).describedAs("%s", request).isTrue();
        }
        for (String field : ImmutableList.of("path", "diffVersion", "refresh")) {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("kind", "diff");
            request.put("number", 19);
            request.put(field, null);
            assertThat(call("review_get_context", request).at("/error/field").asText()).isEqualTo(field);
        }
        for (Number number : ImmutableList.of(0, -1, 1.5)) {
            assertThat(call("review_get_context", ImmutableMap.of("kind", "diff", "number", number)).at("/error/field").asText()).isEqualTo("number");
        }
        assertThat(call("review_get_context", ImmutableMap.of("kind", "diff", "refresh", true)).at("/error/field").asText()).isEqualTo("refresh");
        assertThat(call("review_get_context", ImmutableMap.of("kind", "diff", "number", 42, "diffVersion", DIFF_VERSION))
                .at("/error/field").asText()).isEqualTo("diffVersion");
        assertThat(call("review_get_context", ImmutableMap.of("kind", "diff", "number", 42, "refresh", true))
                .at("/error/field").asText()).isEqualTo("refresh");
        verifyNoInteractions(selectedPullRequestDiffs, selectedPullRequests, workspaces, git);
        assertThat(store.runUpdates).isZero();
    }

    @Test
    void relatedDiffContinuationsAndUnavailableReadsReturnActionableErrors()
    {
        when(selectedPullRequestDiffs.read(REPO, 19, Optional.of(DIFF_VERSION), false))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Changed artifact"));
        JsonNode changed = call("review_get_context", ImmutableMap.of("kind", "diff", "number", 19, "diffVersion", DIFF_VERSION));
        assertThat(changed.at("/error/field").asText()).isEqualTo("diffVersion");
        assertThat(changed.at("/error/message").asText()).contains("restart");
        verify(selectedPullRequestDiffs).read(REPO, 19, Optional.of(DIFF_VERSION), false);
        when(selectedPullRequestDiffs.read(REPO, 20, Optional.empty(), false))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "No artifact"));
        JsonNode unavailable = call("review_get_context", ImmutableMap.of("kind", "diff", "number", 20));
        assertThat(unavailable.at("/error/field").asText()).isEqualTo("number");
        assertThat(unavailable.at("/error/message").asText()).contains("unavailable");
        assertThat(unavailable.has("files")).isFalse();
        assertThat(store.runUpdates).isZero();
    }

    private static SelectedPullRequestDiffReader.Read relatedDiff(List<DiffFile> files)
    {
        return new SelectedPullRequestDiffReader.Read(files, DIFF_VERSION, Optional.empty(), Optional.empty(), Optional.empty(), NOW, false, false, ImmutableList.of());
    }

    @Test
    void investigationCannotPublishFindingsOrDeclareSuccessfulCompletion()
    {
        store.updateRun(run(RUN_ID, 1, PATCH, false));
        readSource();
        assertThat(call("review_save_finding", finding("early", "Unchecked input", "A2", ImmutableList.of(evidence("E1"))))
                .at("/error/field").asText()).isEqualTo("validation");
        assertThat(store.listFindings(REVIEW_ID)).isEmpty();
        assertThat(call("review_finish", finish("early-finish", ImmutableList.of())).at("/error/field").asText()).isEqualTo("validation");
        call("review_save_summary", summary("summary", "Guard input"));
        assertThat(call("review_save_investigation", investigation("draft", ImmutableList.of(DIFF_PATH), ImmutableList.of("E1"), ImmutableList.of("Input bypasses a guard")))
                .path("next").asText()).contains("End this investigation turn");
        assertThat(store.findRun(RUN_ID).orElseThrow().investigation().orElseThrow().candidates()).containsExactly("Input bypasses a guard");
        assertThat(store.findRun(RUN_ID).orElseThrow().validationStarted()).isFalse();
    }

    @Test
    void completedValidationMustCoverCandidatesAndEveryNewFinding()
    {
        call("review_save_summary", summary("summary", "Reject unchecked input"));
        readSource();
        String findingId = call("review_save_finding", finding("finding", "Unchecked input", "A2", ImmutableList.of(evidence("E1")))).path("findingId").asText();
        assertThat(call("review_finish", finish("missing-candidate", ImmutableList.of())).at("/error/field").asText()).isEqualTo("validations");
        assertThat(call("review_finish", finish("unmapped-finding", ImmutableList.of(assessment(1, "REJECTED"))))
                .at("/error/field").asText()).isEqualTo("validations");
        List<Map<String, Object>> validations = ImmutableList.of(
                supported(1, findingId, 1));
        assertThat(call("review_finish", finish("finished", validations)).path("status").asText()).isEqualTo("accepted");
        assertThat(store.findRun(RUN_ID).orElseThrow().coverage().orElseThrow().complete()).isTrue();
        assertThat(store.findRun(RUN_ID).orElseThrow().investigation().orElseThrow().validations().getFirst().findingId()).contains(findingId);
        assertThat(store.findFinding(findingId).orElseThrow().evidence().getFirst().explanation()).contains("Validation:", "no caller guard");
    }

    @Test
    void rejectedHypothesisCanProduceACompleteZeroFindingReview()
    {
        call("review_save_summary", summary("summary", "Guarded input behavior"));
        readSource();
        List<Map<String, Object>> validations = ImmutableList.of(assessment(1, "REJECTED"));
        assertThat(call("review_finish", finish("finished", validations)).path("status").asText()).isEqualTo("accepted");
        assertThat(store.listFindings(REVIEW_ID)).isEmpty();
        assertThat(store.findRun(RUN_ID).orElseThrow().investigation().orElseThrow().validations().getFirst().findingId()).isEmpty();
    }

    @Test
    void fullCoverageUsesFrozenPathsWithoutOpeningTheWorkspace()
    {
        store.updateRun(run(RUN_ID, 1, PATCH, false));
        call("review_save_summary", summary("summary", "Input change"));

        JsonNode investigation = call("review_save_investigation", investigation("collect", ImmutableList.of(DIFF_PATH), ImmutableList.of(), ImmutableList.of()));

        assertThat(investigation.path("status").asText()).isEqualTo("accepted");
        verify(workspaces, never()).requireReadableRepository(WORKSPACE_ID);
        verifyNoInteractions(git);
    }

    @Test
    void completeCoverageRequiresEveryCommitAndRequiredChangedFile()
    {
        store.updateRun(run(RUN_ID, 1, PATCH, false));
        Map<String, Object> missing = ImmutableMap.of("operationKey", "missing", "purpose", "Input change", "commits", ImmutableList.of());
        assertThat(call("review_save_summary", missing).at("/error/field").asText()).isEqualTo("commits");
        Map<String, String> commit = ImmutableMap.of("sha", COMMIT_SHA, "explanation", "Changes input");
        assertThat(call("review_save_summary", ImmutableMap.of("operationKey", "duplicate", "purpose", "Input change", "commits", ImmutableList.of(commit, commit)))
                .at("/error/field").asText()).isEqualTo("commits");
        call("review_save_summary", summary("summary", "Input change"));
        assertThat(call("review_save_investigation", investigation("omitted", ImmutableList.of(), ImmutableList.of(), ImmutableList.of("The new input might bypass validation")))
                .path("status").asText()).isEqualTo("accepted");
        advance(VALIDATE);
        readSource();
        assertThat(call("review_finish", finish("missing-file", ImmutableList.of(assessment(1, "REJECTED"))))
                .at("/error/field").asText()).isEqualTo("reviewedFiles");
    }

    @Test
    void investigationValidatesContextAndCannotBeChangedDuringValidation()
    {
        assertThat(call("review_save_investigation", investigation("wrong-stage", ImmutableList.of(DIFF_PATH), ImmutableList.of(), ImmutableList.of()))
                .at("/error/field").asText()).isEqualTo("validation");
        store.updateRun(run(RUN_ID, 1, PATCH, false));
        assertThat(call("review_save_investigation", investigation("missing-summary", ImmutableList.of(DIFF_PATH), ImmutableList.of(), ImmutableList.of()))
                .at("/error/field").asText()).isEqualTo("summary");
        call("review_save_summary", summary("summary", "Input change"));
        assertThat(call("review_save_investigation", investigation("foreign-path", ImmutableList.of("elsewhere.txt"), ImmutableList.of(), ImmutableList.of()))
                .at("/error/field").asText()).isEqualTo("reviewedFiles");
        assertThat(call("review_save_investigation", investigation("foreign-ref", ImmutableList.of(DIFF_PATH), ImmutableList.of("E99"), ImmutableList.of()))
                .at("/error/field").asText()).isEqualTo("contextEvidenceRefs");
        assertThat(call("review_save_investigation", investigation("initial", ImmutableList.of(DIFF_PATH), ImmutableList.of(), ImmutableList.of(CANDIDATE)))
                .path("status").asText()).isEqualTo("accepted");
        advance(VALIDATE);
        assertThat(call("review_save_investigation", investigation("replaced", ImmutableList.of(DIFF_PATH), ImmutableList.of(), ImmutableList.of("Unrelated new claim")))
                .at("/error/field").asText()).isEqualTo("validation");
        assertThat(call("review_get_context", ImmutableMap.of("kind", "investigation")).path("candidates")).hasSize(1);
    }

    @Test
    void evidenceRequestsRequireAConcreteQuestionCheckAndResolutionCriterion()
    {
        readSource();
        for (String field : ImmutableList.of("question", "check", "resolvesWhen")) {
            Map<String, Object> request = new LinkedHashMap<>(evidenceRequest());
            request.remove(field);
            Map<String, Object> candidate = new LinkedHashMap<>(needsEvidence(1));
            candidate.put("request", request);
            assertThat(call("review_finish", incomplete("missing-" + field, ImmutableList.of(candidate))).at("/error/field").asText()).isEqualTo(field);
        }
        Map<String, Object> uncited = new LinkedHashMap<>(needsEvidence(1));
        uncited.put("evidenceRefs", ImmutableList.of());
        assertThat(call("review_finish", incomplete("uncited", ImmutableList.of(uncited))).at("/error/field").asText()).isEqualTo("evidenceRefs");
        assertThat(call("review_finish", finish("unsettled", ImmutableList.of(needsEvidence(1)))).at("/error/field").asText()).isEqualTo("complete");
        assertThat(store.findRun(RUN_ID).orElseThrow().currentStep().orElseThrow().sealed()).isFalse();
        assertThat(call("review_finish", incomplete("request", ImmutableList.of(needsEvidence(1)))).path("status").asText()).isEqualTo("accepted");
        ReviewRun current = store.findRun(RUN_ID).orElseThrow();
        assertThat(current.currentStep().orElseThrow().validation().orElseThrow().needsEvidence()).isTrue();
        assertThat(current.coverage()).isEmpty();
    }

    @Test
    void supportedVerdictMustNameTheExactInvestigationAndFindingRevision()
    {
        call("review_save_summary", summary("summary", "Input change"));
        readSource();
        String findingId = call("review_save_finding", finding("finding", "Unchecked input", "A2", ImmutableList.of(evidence("E1")))).path("findingId").asText();
        Map<String, Object> staleInvestigation = new LinkedHashMap<>(finish("stale-investigation", ImmutableList.of(supported(1, findingId, 1))));
        staleInvestigation.put("investigationStep", 3);
        assertThat(call("review_finish", staleInvestigation).at("/error/field").asText()).isEqualTo("investigationStep");
        Map<String, Object> revision = ImmutableMap.<String, Object>builder()
                .putAll(finding("revised", "Reachable unchecked input", "A2", ImmutableList.of(evidence("E1"))))
                .put("findingId", findingId).put("expectedRevision", 1).buildOrThrow();
        assertThat(call("review_save_finding", revision).path("revision").asInt()).isEqualTo(2);
        assertThat(call("review_finish", finish("stale-finding", ImmutableList.of(supported(1, findingId, 1)))).at("/error/field").asText()).isEqualTo("findingRevision");
        assertThat(call("review_finish", finish("current", ImmutableList.of(supported(1, findingId, 2)))).path("status").asText()).isEqualTo("accepted");
    }

    @Test
    void sealedResultsAllowExactReplayButRejectFurtherReportWrites()
    {
        store.updateRun(run(RUN_ID, 1, PATCH, false));
        call("review_save_summary", summary("summary", "Input change"));
        readSource();
        Map<String, Object> investigation = investigation("investigation", ImmutableList.of(DIFF_PATH), ImmutableList.of("E1"), ImmutableList.of(CANDIDATE));
        JsonNode first = call("review_save_investigation", investigation);
        assertThat(first.path("status").asText()).isEqualTo("accepted");
        assertThat(call("review_save_investigation", investigation)).isEqualTo(first);
        assertThat(call("review_save_summary", summary("changed-summary", "Another intention")).at("/error/field").asText()).isEqualTo("assignment");
        assertThat(call("review_save_investigation", investigation("changed", ImmutableList.of(), ImmutableList.of(), ImmutableList.of()))
                .at("/error/field").asText()).isEqualTo("assignment");
        advance(VALIDATE);
        Map<String, Object> validation = finish("finished", ImmutableList.of(assessment(1, "REJECTED")));
        JsonNode second = call("review_finish", validation);
        assertThat(second.path("status").asText()).isEqualTo("accepted");
        assertThat(call("review_finish", validation)).isEqualTo(second);
        assertThat(call("review_save_summary", summary("changed-summary", "Another intention")).at("/error/field").asText()).isEqualTo("assignment");
        assertThat(call("review_save_finding", finding("late", "Unchecked input", "A2", ImmutableList.of(evidence("E1"))))
                .at("/error/field").asText()).isEqualTo("assignment");
    }

    @Test
    void collectionAccountsForEachRequestedCheckAndPreservesCandidateIdentity()
    {
        requestEvidence();
        assertThat(call("review_save_finding", finding("wrong-stage", "Unchecked input", "A2", ImmutableList.of(evidence("E1"))))
                .at("/error/field").asText()).isEqualTo("validation");
        assertThat(call("review_finish", finish("wrong-stage", ImmutableList.of(assessment(1, "REJECTED")))).at("/error/field").asText()).isEqualTo("validation");
        Map<String, Object> answer = ImmutableMap.of("candidate", 1, "attempted", "Traced the caller guard", "evidenceRefs", ImmutableList.of("E1"));
        for (List<Map<String, Object>> answers : ImmutableList.<List<Map<String, Object>>>of(ImmutableList.of(), ImmutableList.of(answer, answer),
                ImmutableList.of(ImmutableMap.<String, Object>of("candidate", 1, "attempted", "No check completed", "evidenceRefs", ImmutableList.of())))) {
            assertThat(call("review_save_investigation", investigation("missing-answer", ImmutableList.of(DIFF_PATH), ImmutableList.of("E1"),
                    ImmutableList.of(CANDIDATE), answers)).at("/error/field").asText()).isEqualTo("answers");
        }
        assertThat(call("review_save_investigation", investigation("replaced", ImmutableList.of(DIFF_PATH), ImmutableList.of("E1"),
                ImmutableList.of("A different claim"), ImmutableList.of(answer))).at("/error/field").asText()).isEqualTo("candidates");
        assertThat(call("review_save_investigation", investigation("collected", ImmutableList.of(DIFF_PATH), ImmutableList.of("E1"),
                ImmutableList.of(CANDIDATE, "A newly discovered cleanup hypothesis"), ImmutableList.of(answer))).path("status").asText()).isEqualTo("accepted");
        ReviewRun collected = store.findRun(RUN_ID).orElseThrow();
        assertThat(collected.investigation().orElseThrow().candidates()).containsExactly(CANDIDATE, "A newly discovered cleanup hypothesis");
        assertThat(collected.investigation().orElseThrow().answers().getFirst().evidenceRefs()).containsExactly("E1");
        advance(VALIDATE);
        assertThat(call("review_finish", finish("old-input", ImmutableList.of(assessment(1, "REJECTED")))).at("/error/field").asText()).isEqualTo("investigationStep");
        Map<String, Object> missingCandidate = new LinkedHashMap<>(finish("missing-new", ImmutableList.of(assessment(1, "REJECTED"))));
        missingCandidate.put("investigationStep", 3);
        assertThat(call("review_finish", missingCandidate).at("/error/field").asText()).isEqualTo("validations");
        Map<String, Object> complete = new LinkedHashMap<>(finish("complete", ImmutableList.of(assessment(1, "REJECTED"), assessment(2, "REJECTED"))));
        complete.put("investigationStep", 3);
        assertThat(call("review_finish", complete).path("status").asText()).isEqualTo("accepted");
        assertThat(store.listFindings(REVIEW_ID)).isEmpty();
    }

    @Test
    void unavailableEvidenceIsRecordedAsALimitation()
    {
        requestEvidence();
        Map<String, Object> answer = ImmutableMap.of("candidate", 1, "attempted", "Attempted to run the integration test", "evidenceRefs", ImmutableList.of(),
                "limitation", "The required service is unavailable");
        assertThat(call("review_save_investigation", investigation("blocked-check", ImmutableList.of(DIFF_PATH), ImmutableList.of("E1"),
                ImmutableList.of(CANDIDATE), ImmutableList.of(answer))).path("status").asText()).isEqualTo("accepted");
        assertThat(store.findRun(RUN_ID).orElseThrow().investigation().orElseThrow().answers().getFirst().limitation()).contains("The required service is unavailable");
        advance(VALIDATE);
        Map<String, Object> unresolved = new LinkedHashMap<>(incomplete("unresolved", ImmutableList.of(assessment(1, "UNRESOLVED"))));
        unresolved.put("investigationStep", 3);
        assertThat(call("review_finish", unresolved).path("status").asText()).isEqualTo("accepted");
        assertThat(store.findRun(RUN_ID).orElseThrow().coverage().orElseThrow().complete()).isFalse();
        assertThat(store.listFindings(REVIEW_ID)).isEmpty();
    }

    @Test
    void validatorCanRequestInvestigationOfANewHypothesisWithoutChangingItsInput()
    {
        call("review_save_summary", summary("summary", "Input change"));
        readSource();
        Map<String, Object> invalid = ImmutableMap.<String, Object>builder()
                .putAll(incomplete("new-rejected", ImmutableList.of(assessment(1, "REJECTED"), assessment(2, "REJECTED"))))
                .put("newCandidates", ImmutableList.of("A cleanup path may retain memory")).buildOrThrow();
        assertThat(call("review_finish", invalid).at("/error/field").asText()).isEqualTo("newCandidates");
        Map<String, Object> report = ImmutableMap.<String, Object>builder()
                .putAll(incomplete("new-hypothesis", ImmutableList.of(assessment(1, "REJECTED"), needsEvidence(2))))
                .put("newCandidates", ImmutableList.of("A cleanup path may retain memory")).buildOrThrow();
        assertThat(call("review_finish", report).path("status").asText()).isEqualTo("accepted");
        assertThat(store.findRun(RUN_ID).orElseThrow().investigation().orElseThrow().candidates()).containsExactly(CANDIDATE);
        advance(COLLECT_EVIDENCE);
        Map<String, Object> answer = ImmutableMap.of("candidate", 2, "attempted", "Traced the cleanup path", "evidenceRefs", ImmutableList.of("E1"));
        assertThat(call("review_save_investigation", investigation("lost-new", ImmutableList.of(DIFF_PATH), ImmutableList.of("E1"),
                ImmutableList.of(CANDIDATE), ImmutableList.of(answer))).at("/error/field").asText()).isEqualTo("candidates");
        assertThat(call("review_save_investigation", investigation("investigated-new", ImmutableList.of(DIFF_PATH), ImmutableList.of("E1"),
                ImmutableList.of(CANDIDATE, "A cleanup path may retain memory"), ImmutableList.of(answer))).path("status").asText()).isEqualTo("accepted");
    }

    @Test
    void noCandidatesStillRequiresASeparateValidationReport()
    {
        store.updateRun(run(RUN_ID, 1, PATCH, false));
        call("review_save_summary", summary("summary", "Input change"));
        call("review_save_investigation", investigation("investigation", ImmutableList.of(DIFF_PATH), ImmutableList.of(), ImmutableList.of()));
        assertThat(store.findRun(RUN_ID).orElseThrow().coverage()).isEmpty();
        advance(VALIDATE);
        assertThat(call("review_finish", finish("finished", ImmutableList.of())).path("status").asText()).isEqualTo("accepted");
        assertThat(store.findRun(RUN_ID).orElseThrow().currentStep().orElseThrow().validation().orElseThrow().candidates()).isEmpty();
        assertThat(store.findRun(RUN_ID).orElseThrow().coverage().orElseThrow().complete()).isTrue();
    }

    @Test
    void sourceComparisonEvidenceIsBoundToTheSelectedFrozenRevision()
    {
        when(git.run(repository, "show", BASE_SHA + ":" + DIFF_PATH)).thenReturn(new GitCommandRunner.Result(0, "alpha\ngamma\n", false));
        JsonNode source = call("review_get_context", ImmutableMap.of("kind", "source", "path", DIFF_PATH, "revision", "base"));
        assertThat(source.path("commit").asText()).isEqualTo(BASE_SHA);
        assertThat(source.path("source").asText()).doesNotContain("beta");
        ReviewEvidence evidence = store.findRun(RUN_ID).orElseThrow().evidence().get(source.path("evidenceRef").asText());
        assertThat(evidence.provenance()).contains(BASE_SHA);
        assertThat(call("review_get_context", ImmutableMap.of("kind", "source", "path", DIFF_PATH, "revision", "arbitrary-ref"))
                .at("/error/field").asText()).isEqualTo("revision");
    }

    @Test
    void incrementalCoverageIncludesRevertedPathsAndPatchlessChanges()
    {
        ReviewRun previous = run().withSummary(new ReviewSummary(1, "Original change",
                ImmutableList.of(new ReviewSummary.CommitExplanation(COMMIT_SHA, "Changes input")), "", Optional.empty(), ImmutableList.of()), NOW)
                .withCoverage(new ReviewCoverage(true, ImmutableList.of(), NOW), NOW).ended(ReviewRun.State.ACCEPTED, Optional.empty(), NOW);
        store.updateRun(previous);
        String nextHead = "4444444444444444444444444444444444444444";
        ReviewRun next = new ReviewRun("incremental", REVIEW_ID, 2, ReviewRun.Scope.INCREMENTAL, "main", BASE_SHA, BASE_SHA, nextHead,
                Optional.of(HEAD_SHA), previous.commits(), ImmutableList.of(new DiffFile("image.png", "modified", 0, 0, null)), ImmutableList.of(),
                ReviewRun.State.RUNNING, previous.summary(), Optional.empty(), Optional.empty(), ImmutableList.of(ReviewStep.initial()), false,
                ImmutableMap.of(), ImmutableMap.of(), Optional.empty(), Optional.empty(), 0, NOW, NOW, Optional.empty());
        store.insertRun(next);
        when(git.run(repository, "diff", "--name-only", "--no-renames", "-z", HEAD_SHA, nextHead, "--"))
                .thenReturn(new GitCommandRunner.Result(0, DIFF_PATH + "\0image.png\0", false));
        assertThat(call("review_save_investigation", investigation("partial", ImmutableList.of("image.png"), ImmutableList.of(), ImmutableList.of()))
                .path("status").asText()).isEqualTo("accepted");
        advance(VALIDATE);
        assertThat(call("review_finish", finish("skipped-revert", ImmutableList.of())).at("/error/field").asText()).isEqualTo("reviewedFiles");
        store.updateRun(next);
        assertThat(call("review_save_investigation", investigation("all", ImmutableList.of(DIFF_PATH, "image.png"), ImmutableList.of(), ImmutableList.of()))
                .path("status").asText()).isEqualTo("accepted");
        advance(VALIDATE);
        assertThat(call("review_finish", finish("covered", ImmutableList.of())).path("status").asText()).isEqualTo("accepted");
    }

    private static Map<String, Object> validated(Map<String, Object> finding)
    {
        return ImmutableMap.<String, Object>builder().putAll(finding).put("validation", "Source comparison confirms equivalent behavior and the proposed benefit").buildOrThrow();
    }

    private static Map<String, Object> finish(String key, List<Map<String, Object>> validations)
    {
        return ImmutableMap.of("operationKey", key, "investigationStep", 1, "complete", true, "omissions", ImmutableList.of(), "validations", validations);
    }

    private static Map<String, Object> investigation(String key, List<String> files, List<String> refs, List<String> candidates)
    {
        return investigation(key, files, refs, candidates, ImmutableList.of());
    }

    private static Map<String, Object> investigation(String key, List<String> files, List<String> refs, List<String> candidates, List<Map<String, Object>> answers)
    {
        return ImmutableMap.of("operationKey", key, "changeMap", "Explain the changed input contract and its callers",
                "reviewedFiles", files, "contextEvidenceRefs", refs, "candidates", candidates, "answers", answers);
    }

    private static Map<String, Object> incomplete(String key, List<Map<String, Object>> validations)
    {
        return ImmutableMap.of("operationKey", key, "investigationStep", 1, "complete", false,
                "omissions", ImmutableList.of("The remaining hypothesis has not been settled"), "validations", validations);
    }

    private static Map<String, Object> evidenceRequest()
    {
        return ImmutableMap.of("question", "Can the caller reach the unchecked branch?", "check", "Trace the callers and reproduce the unchecked input",
                "resolvesWhen", "A caller guard disproves the hypothesis or a reachable failure establishes it");
    }

    private static Map<String, Object> needsEvidence(int candidate)
    {
        return ImmutableMap.<String, Object>builder().putAll(assessment(candidate, "NEEDS_EVIDENCE")).put("request", evidenceRequest()).buildOrThrow();
    }

    private void requestEvidence()
    {
        call("review_save_summary", summary("summary", "Input change"));
        readSource();
        assertThat(call("review_finish", incomplete("request", ImmutableList.of(needsEvidence(1)))).path("status").asText()).isEqualTo("accepted");
        advance(COLLECT_EVIDENCE);
    }

    private static Map<String, Object> assessment(int candidate, String outcome)
    {
        return ImmutableMap.of("candidate", candidate, "outcome", outcome, "conclusion", "The caller guard determines whether this path is reachable",
                "evidenceRefs", outcome.equals("UNRESOLVED") ? ImmutableList.of() : ImmutableList.of("E1"),
                "counterevidence", "The reported caller behavior was checked against the frozen source");
    }

    private static Map<String, Object> supported(int candidate, String findingId, int revision)
    {
        return ImmutableMap.<String, Object>builder().putAll(assessment(candidate, "SUPPORTED")).put("findingId", findingId).put("findingRevision", revision).buildOrThrow();
    }

    private void advance(ReviewStep.Kind kind)
    {
        ReviewRun current = store.listRuns(REVIEW_ID).getLast();
        store.updateRun(current.withStep(current.currentStep().orElseThrow().next(kind), NOW));
    }

    private void readSource()
    {
        JsonNode source = call("review_get_context", ImmutableMap.<String, Object>of("kind", "source", "path", DIFF_PATH, "startLine", 1, "endLine", 3));
        assertThat(source.path("totalLines").asInt()).isEqualTo(3);
        assertThat(source.path("evidenceRef").asText()).isEqualTo("E1");
    }

    private JsonNode call(String tool, Map<String, Object> arguments)
    {
        String result = bridge.tool(tool).orElseThrow().call().apply(write(arguments));
        try {
            return objectMapper.readTree(result);
        }
        catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String write(Map<String, Object> arguments)
    {
        try {
            return objectMapper.writeValueAsString(arguments);
        }
        catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Object> finding(String operationKey, String title, String startAnchor, List<Map<String, String>> evidence)
    {
        return ImmutableMap.<String, Object>of(
                "operationKey", operationKey,
                "title", title,
                "body", "The added line is used without any validation.",
                "validation", "The source establishes a reachable unchecked input and no caller guard prevents it.",
                "startAnchor", startAnchor,
                "evidence", evidence);
    }

    private static Map<String, String> evidence(String ref)
    {
        return ImmutableMap.of("ref", ref, "explanation", "the frozen source window shows the missing check");
    }

    private static Map<String, Object> summary(String operationKey, String purpose)
    {
        return ImmutableMap.<String, Object>of(
                "operationKey", operationKey,
                "purpose", purpose,
                "commits", ImmutableList.of(ImmutableMap.of("sha", COMMIT_SHA.substring(0, 7), "explanation", "adds the beta line")));
    }

    private static Map<String, Object> withdrawal(String operationKey, String findingId, int expectedRevision)
    {
        return ImmutableMap.<String, Object>of(
                "operationKey", operationKey,
                "findingId", findingId,
                "expectedRevision", expectedRevision,
                "explanation", "the concern no longer holds");
    }

    private static AgentReview review(AgentReview.State state)
    {
        return new AgentReview(
                REVIEW_ID,
                WORKSPACE_ID,
                REPO,
                42,
                OptionalLong.empty(),
                TASK_ID,
                state,
                false,
                Optional.empty(),
                Optional.empty(),
                0,
                NOW,
                NOW,
                Optional.empty());
    }

    private static ReviewRun run()
    {
        return run(RUN_ID, 1);
    }

    private static ReviewRun run(String id, int ordinal)
    {
        return run(id, ordinal, PATCH);
    }

    private static ReviewRun run(String id, int ordinal, String patch)
    {
        return run(id, ordinal, patch, true);
    }

    private static ReviewRun run(String id, int ordinal, String patch, boolean validating)
    {
        ReviewInvestigation investigation = new ReviewInvestigation("Account for changed input behavior", ImmutableList.of(DIFF_PATH), ImmutableList.of(),
                ImmutableList.of(CANDIDATE), ImmutableList.of(), ImmutableList.of());
        ReviewStep initial = ReviewStep.initial();
        return new ReviewRun(
                id,
                REVIEW_ID,
                ordinal,
                ReviewRun.Scope.FULL,
                "main",
                BASE_SHA,
                BASE_SHA,
                HEAD_SHA,
                Optional.empty(),
                ImmutableList.of(new ReviewCommit(COMMIT_SHA, "Add the beta line", "dev")),
                ImmutableList.of(new DiffFile(DIFF_PATH, "modified", 1, 0, patch)),
                ImmutableList.of(),
                ReviewRun.State.RUNNING,
                Optional.empty(),
                Optional.empty(),
                validating ? Optional.of(investigation) : Optional.empty(),
                validating ? ImmutableList.of(initial.withInvestigation(investigation), initial.next(VALIDATE)) : ImmutableList.of(initial),
                false,
                ImmutableMap.of(),
                ImmutableMap.of(),
                Optional.empty(),
                Optional.empty(),
                0,
                NOW,
                NOW,
                Optional.empty());
    }

    private static ReviewFinding resolvedFinding()
    {
        return new ReviewFinding(
                "finding-resolved",
                REVIEW_ID,
                RUN_ID,
                1,
                "Missing null check",
                "The added line is used without any validation.",
                Optional.of("Low"),
                new ReviewAnchor(DIFF_PATH, "RIGHT", 2, 2, ImmutableList.of("beta")),
                ImmutableList.of(new ReviewEvidence("E1", DIFF_PATH + " L1-L3", "source read from the frozen head", "2  beta", "shows the missing check")),
                1,
                ReviewFinding.State.RESOLVED,
                Optional.of("the user resolved this locally"),
                HEAD_SHA,
                false,
                0,
                NOW,
                NOW);
    }

    private WorkspaceRepositoryContext workspaceRepository()
    {
        return new WorkspaceRepositoryContext(
                WORKSPACE_ID,
                repository,
                1,
                RepoRef.of("acme", "widgets"),
                OptionalLong.empty(),
                RepoRef.of("acme", "widgets"),
                "origin",
                "origin",
                "main",
                ImmutableList.of());
    }

    /** Real state, so writes round trip, versions move, and a replayed operation is read back from the run it was committed with. */
    private static final class InMemoryAgentReviewStore
            implements AgentReviewStore
    {
        private int runUpdates;
        private final Map<String, AgentReview> reviews = new LinkedHashMap<>();
        private final Map<String, ReviewRun> runs = new LinkedHashMap<>();
        private final Map<String, ReviewFinding> findings = new LinkedHashMap<>();
        private final Map<String, ReviewMessage> messages = new LinkedHashMap<>();

        @Override
        public AgentReview insertReview(AgentReview review)
        {
            reviews.put(review.id(), review);
            return review;
        }

        @Override
        public AgentReview updateReview(AgentReview review)
        {
            AgentReview next = review.withVersion(review.version() + 1);
            reviews.put(next.id(), next);
            return next;
        }

        @Override
        public Optional<AgentReview> findReview(String reviewId)
        {
            return Optional.ofNullable(reviews.get(reviewId));
        }

        @Override
        public Optional<AgentReview> findOpenReview(String workspaceId, String repo, int number)
        {
            return reviews.values().stream()
                    .filter(review -> review.state() == AgentReview.State.OPEN)
                    .filter(review -> review.workspaceId().equals(workspaceId) && review.repo().equals(repo) && review.number() == number)
                    .findFirst();
        }

        @Override
        public Optional<AgentReview> findReviewByTask(String taskId)
        {
            return reviews.values().stream().filter(review -> review.taskId().equals(taskId)).findFirst();
        }

        @Override
        public List<AgentReview> listOpenReviews()
        {
            return reviews.values().stream().filter(review -> review.state() == AgentReview.State.OPEN).collect(toImmutableList());
        }

        @Override
        public List<AgentReview> listReviews(String workspaceId) { throw new UnsupportedOperationException(); }

        @Override
        public ReviewRun insertRun(ReviewRun run)
        {
            runs.put(run.id(), run);
            return run;
        }

        @Override
        public ReviewRun updateRun(ReviewRun run)
        {
            runUpdates++;
            ReviewRun next = run.withVersion(run.version() + 1);
            runs.put(next.id(), next);
            return next;
        }

        @Override
        public void acceptRun(AgentReview review, ReviewRun run)
        {
            updateReview(review);
            updateRun(run);
        }

        @Override
        public ReviewFinding saveFinding(ReviewFinding finding, boolean insert, ReviewRun run)
        {
            checkArgument(insert != findings.containsKey(finding.id()), "insert disagrees with the stored findings");
            updateRun(run);
            return insert ? insertFinding(finding) : updateFinding(finding);
        }

        @Override
        public ReviewMessage insertMessage(ReviewMessage message, ReviewRun run)
        {
            updateRun(run);
            return insertMessage(message);
        }

        @Override
        public Optional<ReviewRun> findRun(String runId)
        {
            return Optional.ofNullable(runs.get(runId));
        }

        @Override
        public List<ReviewRun> listRuns(String reviewId)
        {
            return runs.values().stream().filter(run -> run.reviewId().equals(reviewId)).collect(toImmutableList());
        }

        @Override
        public List<ReviewRun> listRecentRuns(String workspaceId, String repo, int limit)
        {
            checkArgument(limit >= 1 && limit <= 10, "Recent run limit must be between 1 and 10");
            return runs.values().stream()
                    .filter(run -> findReview(run.reviewId())
                            .filter(review -> review.workspaceId().equals(workspaceId) && review.repo().equalsIgnoreCase(repo)).isPresent())
                    .sorted(Comparator.comparing(ReviewRun::updatedAt).reversed().thenComparing(ReviewRun::id))
                    .limit(limit)
                    .collect(toImmutableList());
        }

        @Override
        public ReviewFinding insertFinding(ReviewFinding finding)
        {
            findings.put(finding.id(), finding);
            return finding;
        }

        @Override
        public ReviewFinding updateFinding(ReviewFinding finding)
        {
            ReviewFinding next = finding.withVersion(finding.version() + 1);
            findings.put(next.id(), next);
            return next;
        }

        @Override
        public Optional<ReviewFinding> findFinding(String findingId)
        {
            return Optional.ofNullable(findings.get(findingId));
        }

        @Override
        public List<ReviewFinding> listFindings(String reviewId)
        {
            return findings.values().stream().filter(finding -> finding.reviewId().equals(reviewId)).collect(toImmutableList());
        }

        @Override
        public ReviewMessage insertMessage(ReviewMessage message)
        {
            messages.put(message.id(), message);
            return message;
        }

        @Override
        public ReviewMessage updateMessage(ReviewMessage message)
        {
            ReviewMessage next = message.withVersion(message.version() + 1);
            messages.put(next.id(), next);
            return next;
        }

        @Override
        public List<ReviewMessage> listMessages(String reviewId)
        {
            return messages.values().stream().filter(message -> message.reviewId().equals(reviewId)).collect(toImmutableList());
        }
    }
}

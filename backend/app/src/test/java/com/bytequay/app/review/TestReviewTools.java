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

import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.spi.ReviewBridge;
import com.bytequay.agent.spi.ReviewTool;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.service.RepoService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewAnchor;
import com.bytequay.core.review.ReviewCommit;
import com.bytequay.core.review.ReviewCoverage;
import com.bytequay.core.review.ReviewEvidence;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.review.ReviewMessage;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.review.ReviewSummary;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestReviewTools
{
    private static final String REVIEW_ID = "review-1";
    private static final String RUN_ID = "run-1";
    private static final String TASK_ID = "task-1";
    private static final String WORKSPACE_ID = "workspace-1";
    private static final String REPO = "acme/widgets";
    private static final String HEAD_SHA = "1111111111111111111111111111111111111111";
    private static final String BASE_SHA = "2222222222222222222222222222222222222222";
    private static final String COMMIT_SHA = "3333333333333333333333333333333333333333";
    private static final String DIFF_PATH = "src/main/java/Widget.java";
    private static final String PATCH = "@@ -1,2 +1,3 @@\n alpha\n+beta\n gamma\n";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    private Path repository;

    private InMemoryAgentReviewStore store;
    private ObjectMapper objectMapper;
    private ReviewTools tools;
    private ReviewBridge bridge;

    @BeforeEach
    void setUp()
    {
        store = new InMemoryAgentReviewStore();
        store.insertReview(review(AgentReview.State.OPEN));
        store.insertRun(run());

        PullRequestService pullRequests = mock(PullRequestService.class);
        RepoService repositories = mock(RepoService.class);
        AgentEventService events = mock(AgentEventService.class);
        WorkspaceRepositoryService workspaces = mock(WorkspaceRepositoryService.class);
        GitCommandRunner git = mock(GitCommandRunner.class);
        objectMapper = new ObjectMapper();
        tools = new ReviewTools(store, pullRequests, repositories, events, workspaces, git, objectMapper, Clock.fixed(NOW, ZoneOffset.UTC));

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
    void bridgeExposesExactlySixNamespacedTools()
    {
        assertThat(bridge.tools()).extracting(ReviewTool::name).containsExactly(
                "review_get_context",
                "review_save_summary",
                "review_save_finding",
                "review_reply",
                "review_withdraw_finding",
                "review_finish");
        assertThat(bridge.tools()).allSatisfy(tool -> assertThat(tool.name()).startsWith(ReviewTool.NAME_PREFIX));
        assertThat(bridge.instruction()).isNotBlank();
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
    void repeatingAnOperationKeyReplaysInsteadOfWritingAgain()
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
    }

    @Test
    void saveSummaryValidatesScopeAndRevisesOnEachAcceptedSave()
    {
        Map<String, Object> foreignCommit = ImmutableMap.<String, Object>of(
                "operationKey", "out-of-scope",
                "purpose", "Explains the change",
                "commits", ImmutableList.of(ImmutableMap.of("sha", "deadbeef", "explanation", "not a commit of this run")),
                "combinedResult", "The change is small and self-contained.",
                "criticalFindingIds", ImmutableList.of(),
                "limitations", ImmutableList.of());
        JsonNode rejectedCommit = call("review_save_summary", foreignCommit);
        assertThat(rejectedCommit.path("error").path("field").asText()).isEqualTo("commits");
        assertThat(store.findRun(RUN_ID).orElseThrow().summary()).isEmpty();

        JsonNode rejectedCritical = call("review_save_summary", summary("unknown-critical", "Explains the change", ImmutableList.of("finding-that-never-existed")));
        assertThat(rejectedCritical.path("error").path("field").asText()).isEqualTo("criticalFindingIds");
        assertThat(store.findRun(RUN_ID).orElseThrow().summary()).isEmpty();

        JsonNode accepted = call("review_save_summary", summary("summary-1", "Explains the change", ImmutableList.of()));
        assertThat(accepted.path("status").asText()).isEqualTo("accepted");
        assertThat(accepted.path("revision").asInt()).isEqualTo(1);

        JsonNode revised = call("review_save_summary", summary("summary-2", "Explains the change more carefully", ImmutableList.of()));
        assertThat(revised.path("revision").asInt()).isEqualTo(2);

        ReviewSummary stored = store.findRun(RUN_ID).orElseThrow().summary().orElseThrow();
        assertThat(stored.revision()).isEqualTo(2);
        assertThat(stored.purpose()).isEqualTo("Explains the change more carefully");
        assertThat(stored.commits()).extracting(ReviewSummary.CommitExplanation::sha).containsExactly(COMMIT_SHA);
    }

    @Test
    void finishRecordsCoverageAndReportsTheMissingSummary()
    {
        Map<String, Object> contradictory = ImmutableMap.<String, Object>of(
                "operationKey", "finish-contradictory",
                "complete", true,
                "omissions", ImmutableList.of("the generated sources were skipped"),
                "limitations", ImmutableList.of());
        JsonNode rejected = call("review_finish", contradictory);
        assertThat(rejected.path("error").path("field").asText()).isEqualTo("complete");
        assertThat(store.findRun(RUN_ID).orElseThrow().coverage()).isEmpty();

        Map<String, Object> honest = ImmutableMap.<String, Object>of(
                "operationKey", "finish-1",
                "complete", false,
                "omissions", ImmutableList.of("the generated sources were skipped"),
                "limitations", ImmutableList.of("tests were not run"));
        JsonNode accepted = call("review_finish", honest);

        assertThat(accepted.path("status").asText()).contains("the summary is still missing");
        ReviewCoverage coverage = store.findRun(RUN_ID).orElseThrow().coverage().orElseThrow();
        assertThat(coverage.complete()).isFalse();
        assertThat(coverage.omissions()).containsExactly("the generated sources were skipped");
        assertThat(coverage.limitations()).containsExactly("tests were not run");
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
                "startAnchor", startAnchor,
                "evidence", evidence);
    }

    private static Map<String, String> evidence(String ref)
    {
        return ImmutableMap.of("ref", ref, "explanation", "the frozen source window shows the missing check");
    }

    private static Map<String, Object> summary(String operationKey, String purpose, List<String> criticalFindingIds)
    {
        return ImmutableMap.<String, Object>of(
                "operationKey", operationKey,
                "purpose", purpose,
                "commits", ImmutableList.of(ImmutableMap.of("sha", COMMIT_SHA.substring(0, 7), "explanation", "adds the beta line")),
                "combinedResult", "The change is small and self-contained.",
                "criticalFindingIds", criticalFindingIds,
                "limitations", ImmutableList.of("tests were not run"));
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
        return new ReviewRun(
                RUN_ID,
                REVIEW_ID,
                1,
                ReviewRun.Scope.FULL,
                "main",
                BASE_SHA,
                BASE_SHA,
                HEAD_SHA,
                Optional.empty(),
                ImmutableList.of(new ReviewCommit(COMMIT_SHA, "Add the beta line", "dev")),
                ImmutableList.of(new DiffFile(DIFF_PATH, "modified", 1, 0, PATCH)),
                ImmutableList.of(),
                ReviewRun.State.RUNNING,
                Optional.empty(),
                Optional.empty(),
                false,
                ImmutableMap.of(),
                ImmutableMap.of(),
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
        public ReviewRun insertRun(ReviewRun run)
        {
            runs.put(run.id(), run);
            return run;
        }

        @Override
        public ReviewRun updateRun(ReviewRun run)
        {
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

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
package com.bytequay.app.repository.github;

import com.bytequay.core.domain.PullRequest.Origin;
import com.bytequay.core.domain.PullRequestDetail.CiStatus;
import com.bytequay.core.domain.PullRequestRef;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TestGitHubPullRequestSummaryClient
        extends AbstractGitHubClientTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ENDPOINT = "https://api.github.test/graphql";
    private static final PullRequestRef REF = PullRequestRef.of("owner", "repo", 1);

    private final GitHubPullRequestSummaryClient client = new GitHubPullRequestSummaryClient(graphQlClient);

    @Test
    void readsCardAndNotificationFactsWithoutDetailEndpoints()
            throws Exception
    {
        ObjectNode row = row(1);
        row.withObject("/viewerReviews").withArray("nodes").add(MAPPER.readTree("""
                {"databaseId":42,"state":"APPROVED","submittedAt":"2026-09-20T10:00:00Z",
                  "author":{"login":"viewer","avatarUrl":"https://avatars/viewer"},"commit":{"oid":"reviewed-sha"}}
                """));
        graphServer.expect(requestTo(ENDPOINT)).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer pat"))
                .andExpect(request -> {
                    var payload = MAPPER.readTree(((MockClientHttpRequest) request).getBodyAsString());
                    assertThat(payload.path("query").asText()).contains("databaseId:fullDatabaseId", "reviews(last:1", "checkRunCountsByState", "reviewRequested:timelineItems(last:20")
                            .contains("activity:timelineItems(last:1)")
                            .doesNotContain("files(", "reviewThreads(");
                    assertThat(payload.path("variables").path("login").asText()).isEqualTo("viewer");
                }).andRespond(withSuccess(response(row), MediaType.APPLICATION_JSON));

        GitHubPullRequestSummary summary = client.fetchSummaries("pat", ImmutableList.of(REF), "viewer").getFirst();

        assertThat(summary.pullRequest().origin()).isEqualTo(Origin.REVIEW_REQUESTED);
        assertThat(summary.pullRequest().requestedReviewers()).containsExactly("viewer");
        assertThat(summary.pullRequest().labels()).containsExactly("bug");
        assertThat(summary.pullRequest().mergeable()).isTrue();
        assertThat(summary.pullRequest().mergeableState()).isEqualTo("clean");
        assertThat(summary.ciStatus()).isEqualTo(CiStatus.PENDING);
        assertThat(summary.additions()).isEqualTo(15);
        assertThat(summary.deletions()).isEqualTo(3);
        assertThat(summary.commentCount()).isEqualTo(4);
        assertThat(summary.headSha()).isEqualTo("new-head");
        assertThat(summary.headCommittedAt()).contains(Instant.parse("2026-09-21T10:00:00Z"));
        assertThat(summary.reviewDecision()).contains("APPROVED");
        assertThat(summary.viewerReview()).isPresent();
        assertThat(summary.viewerReview().orElseThrow().commitId()).isEqualTo("reviewed-sha");
        assertThat(summary.reviewRequestedAt()).contains(Instant.parse("2026-09-21T11:00:00Z"));
    }

    @Test
    void viewerReviewOnACollectedCommitKeepsTheSummary()
            throws Exception
    {
        ObjectNode row = row(1);
        row.withObject("/viewerReviews").withArray("nodes").add(MAPPER.readTree("""
                {"databaseId":42,"state":"COMMENTED","submittedAt":"2026-09-20T10:00:00Z",
                  "author":{"login":"viewer","avatarUrl":"https://avatars/viewer"},"commit":null}
                """));
        graphServer.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response(row), MediaType.APPLICATION_JSON));

        GitHubPullRequestSummary summary = client.fetchSummaries("pat", ImmutableList.of(REF), "viewer").getFirst();

        assertThat(summary.viewerReview().orElseThrow().commitId()).isNull();
    }

    @Test
    void cardActivityCarriesOneCommentWithoutClaimingCiIsDiscussionActivity()
            throws Exception
    {
        ObjectNode value = row(1);
        ObjectNode comment = value.withObject("/activity").putArray("nodes").addObject();
        comment.put("__typename", "IssueComment").put("createdAt", "2026-09-21T11:00:00Z").put("body", "A new reply");
        comment.putObject("author").put("login", "reviewer");
        graphServer.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response(value), MediaType.APPLICATION_JSON));
        GitHubPullRequestSummary summary = client.fetchSummaries("pat", ImmutableList.of(REF), "viewer").getFirst();
        assertThat(summary.discussionUpdatedAt()).isEqualTo(Instant.parse("2026-09-21T11:00:00Z"));
        assertThat(summary.latestActivity()).isPresent();
        assertThat(summary.latestActivity().orElseThrow().actor()).contains("reviewer");
        assertThat(summary.latestActivity().orElseThrow().excerpt()).contains("A new reply");
    }

    @Test
    void batchesFiveDistinctReferencesAndDoesNotFetchEmptySelections()
            throws Exception
    {
        List<PullRequestRef> refs = IntStream.rangeClosed(1, 31).mapToObj(number -> PullRequestRef.of("owner", "repo", number)).toList();
        for (int offset = 0; offset < refs.size(); offset += 5) {
            int firstNumber = offset + 1;
            int batchSize = Math.min(5, refs.size() - offset);
            ObjectNode response = MAPPER.createObjectNode();
            ObjectNode data = response.putObject("data");
            for (int index = 0; index < batchSize; index++) {
                data.putObject("r" + index).set("pullRequest", row(firstNumber + index));
            }
            graphServer.expect(requestTo(ENDPOINT)).andExpect(request -> {
                var payload = MAPPER.readTree(((MockClientHttpRequest) request).getBodyAsString());
                var variables = payload.path("variables");
                assertThat(variables.size()).isEqualTo(1 + batchSize * 3);
                assertThat(variables.path("number0").intValue()).isEqualTo(firstNumber);
                assertThat(variables.path("number" + (batchSize - 1)).intValue()).isEqualTo(firstNumber + batchSize - 1);
                assertThat(variables.has("number" + batchSize)).isFalse();
                assertThat(payload.path("query").asText()).doesNotContain("$number5");
            }).andRespond(withSuccess(response.toString(), MediaType.APPLICATION_JSON));
        }

        assertThat(client.fetchSummaries("pat", ImmutableList.of(), "viewer")).isEmpty();
        assertThat(client.fetchSummaries("pat", ImmutableList.<PullRequestRef>builder().addAll(refs).add(REF).build(), "viewer"))
                .extracting(summary -> summary.pullRequest().number()).containsExactlyElementsOf(IntStream.rangeClosed(1, 31).boxed().toList());
    }

    @Test
    void refusesPartialOrUnknownFactsInsteadOfOverwritingGoodLocalState()
            throws Exception
    {
        ObjectNode response = MAPPER.createObjectNode();
        ObjectNode data = response.putObject("data");
        ObjectNode partialLabels = row(1);
        partialLabels.withObject("/labels/pageInfo").put("hasNextPage", true);
        data.putObject("r0").set("pullRequest", partialLabels);
        ObjectNode missingChecks = row(2);
        missingChecks.remove("statusCheckRollup");
        data.putObject("r1").set("pullRequest", missingChecks);
        ObjectNode unknownChecks = row(3);
        unknownChecks.withObject("/statusCheckRollup").put("state", "UNRECOGNIZED");
        data.putObject("r2").set("pullRequest", unknownChecks);
        data.putObject("r3").set("pullRequest", row(4));
        data.putObject("r4").set("pullRequest", row(5));
        response.putArray("errors").addObject().put("message", "Unavailable").putArray("path").add("r3").add("pullRequest").add("statusCheckRollup");
        graphServer.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response.toString(), MediaType.APPLICATION_JSON));

        List<PullRequestRef> refs = IntStream.rangeClosed(1, 5).mapToObj(number -> PullRequestRef.of("owner", "repo", number)).toList();
        assertThat(client.fetchSummaries("pat", refs, "viewer")).extracting(summary -> summary.pullRequest().number()).containsExactly(5);
    }

    @Test
    void rejectsMalformedScalarAndCollectionFacts()
            throws Exception
    {
        ObjectNode nullCheck = row(1);
        nullCheck.withArray("/statusCheckRollup/contexts/checkRunCountsByState").addNull();
        ObjectNode missingAuthor = row(1);
        missingAuthor.withObject("/author").remove("login");
        List<ObjectNode> malformed = ImmutableList.of(row(1).put("isDraft", "false"), row(1).put("additions", "15"), nullCheck, missingAuthor);
        for (ObjectNode row : malformed) {
            graphServer.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response(row), MediaType.APPLICATION_JSON));
        }
        for (int index = 0; index < malformed.size(); index++) {
            assertThat(client.fetchSummaries("pat", ImmutableList.of(REF), "viewer")).isEmpty();
        }
    }

    @Test
    void mapsAllCheckStatesAndDistinguishesNoChecks()
            throws Exception
    {
        Map<String, CiStatus> states = ImmutableMap.of("SUCCESS", CiStatus.PASSING, "PENDING", CiStatus.PENDING, "EXPECTED", CiStatus.PENDING,
                "FAILURE", CiStatus.FAILING, "ERROR", CiStatus.FAILING, "NONE", CiStatus.NONE);
        for (String state : states.keySet()) {
            ObjectNode row = row(1);
            if (state.equals("NONE")) {
                row.putNull("statusCheckRollup");
            }
            else {
                row.withObject("/statusCheckRollup").put("state", state);
            }
            graphServer.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response(row), MediaType.APPLICATION_JSON));
        }
        for (Map.Entry<String, CiStatus> entry : states.entrySet()) {
            assertThat(client.fetchSummaries("pat", ImmutableList.of(REF), "viewer").getFirst().ciStatus()).isEqualTo(entry.getValue());
        }
    }

    @Test
    void normalizesReviewRequiredWithoutLosingDismissedReviewEvidence()
            throws Exception
    {
        ObjectNode noDecision = row(1).putNull("reviewDecision");
        noDecision.withObject("/viewerReviews").withArray("nodes").add(MAPPER.readTree("""
                {"databaseId":42,"state":"DISMISSED","submittedAt":"2026-09-20T10:00:00Z",
                  "author":{"login":"viewer","avatarUrl":"https://avatars/viewer"},"commit":{"oid":"reviewed-sha"}}
                """));
        ObjectNode required = noDecision.deepCopy().put("reviewDecision", "REVIEW_REQUIRED");
        ObjectNode approved = noDecision.deepCopy().put("reviewDecision", "APPROVED");
        ObjectNode changesRequested = noDecision.deepCopy().put("reviewDecision", "CHANGES_REQUESTED");
        for (ObjectNode row : ImmutableList.of(noDecision, required, approved, changesRequested)) {
            graphServer.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response(row), MediaType.APPLICATION_JSON));
        }

        GitHubPullRequestSummary baseline = client.fetchSummaries("pat", ImmutableList.of(REF), "viewer").getFirst();
        GitHubPullRequestSummary pending = client.fetchSummaries("pat", ImmutableList.of(REF), "viewer").getFirst();

        assertThat(baseline.reviewDecision()).isEmpty();
        assertThat(pending.reviewDecision()).isEmpty();
        assertThat(pending.activityVersion()).isEqualTo(baseline.activityVersion());
        assertThat(pending.viewerReview()).isEqualTo(baseline.viewerReview());
        assertThat(pending.viewerReview().orElseThrow().state()).isEqualTo("DISMISSED");
        assertThat(client.fetchSummaries("pat", ImmutableList.of(REF), "viewer").getFirst().reviewDecision()).contains("APPROVED");
        assertThat(client.fetchSummaries("pat", ImmutableList.of(REF), "viewer").getFirst().reviewDecision()).contains("CHANGES_REQUESTED");
    }

    @Test
    void tracksTimelineAndIndividualCheckProgressWithoutPullRequestUpdateChanges()
            throws Exception
    {
        ObjectNode original = row(1);
        ObjectNode checksChanged = original.deepCopy();
        checksChanged.withObject("/statusCheckRollup/contexts").withArray("checkRunCountsByState")
                .addObject().put("state", "SUCCESS").put("count", 1);
        ObjectNode timelineChanged = original.deepCopy();
        timelineChanged.withObject("/activity").put("updatedAt", "2026-09-21T12:00:00Z");
        ObjectNode headChanged = original.deepCopy().put("headRefOid", "changed-head");
        ObjectNode mergeChanged = original.deepCopy().put("mergeStateStatus", "BLOCKED");
        ObjectNode reviewChanged = original.deepCopy().put("reviewDecision", "CHANGES_REQUESTED");
        List<ObjectNode> changes = ImmutableList.of(checksChanged, timelineChanged, headChanged, mergeChanged, reviewChanged);
        for (ObjectNode row : ImmutableList.<ObjectNode>builder().add(original).addAll(changes).build()) {
            graphServer.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response(row), MediaType.APPLICATION_JSON));
        }
        String initial = client.fetchSummaries("pat", ImmutableList.of(REF), "viewer").getFirst().activityVersion();
        for (int index = 0; index < changes.size(); index++) {
            assertThat(client.fetchSummaries("pat", ImmutableList.of(REF), "viewer").getFirst().activityVersion()).isNotEqualTo(initial);
        }
    }

    @Test
    void rejectsAQueryLevelFailure()
    {
        graphServer.expect(requestTo(ENDPOINT)).andRespond(withSuccess("""
                {"data":{},"errors":[{"message":"Query failed"}]}
                """, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.fetchSummaries("pat", ImmutableList.of(REF), "viewer"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("GraphQL request failed");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rateLimitedGraphQlResponsesStopBeforeTheNextBatch(boolean includesData)
    {
        ObjectNode response = MAPPER.createObjectNode();
        if (includesData) {
            response.putNull("data");
        }
        response.putArray("errors").addObject().put("type", "RATE_LIMITED").put("message", "Rate limit exceeded")
                .putArray("path").add("r0").add("pullRequest");
        graphServer.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response.toString(), MediaType.APPLICATION_JSON));
        List<PullRequestRef> refs = IntStream.rangeClosed(1, 6).mapToObj(number -> PullRequestRef.of("owner", "repo", number)).toList();

        assertThatThrownBy(() -> client.fetchSummaries("pat", refs, "viewer"))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    @Test
    void findsAuthorRepliesBeyondTheFirstThreadAndNewestCommentPageThenCachesTheAnswer()
    {
        graphServer.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var payload = MAPPER.readTree(((MockClientHttpRequest) request).getBodyAsString());
            assertThat(payload.path("query").asText()).contains("reviewThreads(first:100", "root:comments(first:1)", "comments(last:100)")
                    .doesNotContain("body", "reactions", "diffHunk");
            assertThat(payload.path("variables").path("after").isNull()).isTrue();
        }).andRespond(withSuccess("""
                {"data":{"repository":{"pullRequest":{"reviewThreads":{
                  "nodes":[{"id":"OTHER_THREAD","root":{"nodes":[{"author":{"login":"someone-else"}}]}}],
                  "pageInfo":{"hasNextPage":true,"endCursor":"next-thread"}}}}}}
                """, MediaType.APPLICATION_JSON));
        graphServer.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var payload = MAPPER.readTree(((MockClientHttpRequest) request).getBodyAsString());
            assertThat(payload.path("variables").path("after").asText()).isEqualTo("next-thread");
        }).andRespond(withSuccess("""
                {"data":{"repository":{"pullRequest":{"reviewThreads":{
                  "nodes":[{"id":"VIEWER_THREAD","root":{"nodes":[{"author":{"login":"viewer"}}]},
                    "comments":{"nodes":[{"createdAt":"2026-09-23T10:00:00Z","author":{"login":"someone-else"},"replyTo":{"id":"ROOT"}}],
                      "pageInfo":{"hasPreviousPage":true,"startCursor":"older-comments"}}}],
                  "pageInfo":{"hasNextPage":true,"endCursor":"unneeded-next-thread"}}}}}}
                """, MediaType.APPLICATION_JSON));
        graphServer.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var payload = MAPPER.readTree(((MockClientHttpRequest) request).getBodyAsString());
            assertThat(payload.path("variables").path("id").asText()).isEqualTo("VIEWER_THREAD");
            assertThat(payload.path("variables").path("before").asText()).isEqualTo("older-comments");
        }).andRespond(withSuccess("""
                {"data":{"node":{"comments":{
                  "nodes":[{"createdAt":"2026-09-21T10:00:00Z","author":{"login":"author"},"replyTo":{"id":"ROOT"}}],
                  "pageInfo":{"hasPreviousPage":true,"startCursor":"unneeded-older-page"}}}}}
                """, MediaType.APPLICATION_JSON));

        Instant reviewedAt = Instant.parse("2026-09-20T10:00:00Z");
        assertThat(client.hasAuthorReplyAfter("pat", REF, "viewer", "author", reviewedAt, "activity-1")).isTrue();
        assertThat(client.hasAuthorReplyAfter("pat", REF, "VIEWER", "AUTHOR", reviewedAt, "activity-1")).isTrue();
    }

    @Test
    void cachesOnlyCompleteReplyAnswersAndChecksAgainWhenActivityChanges()
    {
        graphServer.expect(requestTo(ENDPOINT)).andRespond(withSuccess("""
                {"data":{},"errors":[{"message":"Unavailable","path":["repository","pullRequest","reviewThreads"]}]}
                """, MediaType.APPLICATION_JSON));
        graphServer.expect(requestTo(ENDPOINT)).andRespond(withSuccess("""
                {"data":{"repository":{"pullRequest":{"reviewThreads":{
                  "nodes":[{"id":"VIEWER_THREAD","root":{"nodes":[{"author":{"login":"viewer"}}]},
                    "comments":{"nodes":[{"createdAt":"2026-09-19T10:00:00Z","author":{"login":"author"},"replyTo":{"id":"ROOT"}}],
                      "pageInfo":{"hasPreviousPage":false,"startCursor":null}}}],
                  "pageInfo":{"hasNextPage":false,"endCursor":null}}}}}}
                """, MediaType.APPLICATION_JSON));
        graphServer.expect(requestTo(ENDPOINT)).andRespond(withSuccess("""
                {"data":{"repository":{"pullRequest":{"reviewThreads":{
                  "nodes":[{"id":"VIEWER_THREAD","root":{"nodes":[{"author":{"login":"viewer"}}]},
                    "comments":{"nodes":[{"createdAt":"2026-09-21T10:00:00Z","author":{"login":"author"},"replyTo":{"id":"ROOT"}}],
                      "pageInfo":{"hasPreviousPage":false,"startCursor":null}}}],
                  "pageInfo":{"hasNextPage":false,"endCursor":null}}}}}}
                """, MediaType.APPLICATION_JSON));

        Instant reviewedAt = Instant.parse("2026-09-20T10:00:00Z");
        assertThatThrownBy(() -> client.hasAuthorReplyAfter("pat", REF, "viewer", "author", reviewedAt, "activity-1"))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(client.hasAuthorReplyAfter("pat", REF, "viewer", "author", reviewedAt, "activity-1")).isFalse();
        assertThat(client.hasAuthorReplyAfter("pat", REF, "viewer", "author", reviewedAt, "activity-1")).isFalse();
        assertThat(client.hasAuthorReplyAfter("pat", REF, "viewer", "author", reviewedAt, "activity-2")).isTrue();
    }

    private static ObjectNode row(int number)
            throws Exception
    {
        return (ObjectNode) MAPPER.readTree("""
                {"databaseId":"123","number":%d,"title":"Summary","url":"https://github.com/owner/repo/pull/%d",
                 "createdAt":"2026-09-19T10:00:00Z","updatedAt":"2026-09-21T11:00:00Z","closedAt":null,"mergedAt":null,
                 "state":"OPEN","isDraft":false,"author":{"login":"author"},"repository":{"nameWithOwner":"owner/repo"},
                 "additions":15,"deletions":3,"comments":{"totalCount":4},"headRefName":"topic","headRefOid":"new-head",
                 "mergeable":"MERGEABLE","mergeStateStatus":"CLEAN","reviewDecision":"APPROVED",
                 "labels":{"nodes":[{"name":"bug","color":"ffffff"}],"pageInfo":{"hasNextPage":false}},
                 "reviewRequests":{"nodes":[{"requestedReviewer":{"__typename":"User","login":"viewer"}}],"pageInfo":{"hasNextPage":false}},
                 "viewerReviews":{"nodes":[]},"commits":{"nodes":[{"commit":{"committedDate":"2026-09-21T10:00:00Z"}}]},
                 "statusCheckRollup":{"state":"PENDING","contexts":{"checkRunCountsByState":[{"state":"IN_PROGRESS","count":1}],"statusContextCountsByState":[]}},
                 "activity":{"updatedAt":"2026-09-21T11:00:00Z","totalCount":5},
                 "reviewRequested":{"nodes":[{"createdAt":"2026-09-21T11:00:00Z","requestedReviewer":{"__typename":"User","login":"viewer"}}]}}
                """.formatted(number, number));
    }

    private static String response(ObjectNode row)
    {
        ObjectNode response = MAPPER.createObjectNode();
        response.putObject("data").putObject("r0").set("pullRequest", row);
        return response.toString();
    }
}

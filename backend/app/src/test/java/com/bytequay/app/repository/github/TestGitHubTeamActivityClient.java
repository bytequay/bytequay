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

import com.bytequay.app.repository.github.GitHubTeamActivityClient.Collector;
import com.bytequay.app.repository.github.GitHubTeamActivityClient.Identity;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PrTimelinePayload;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.domain.TeamActivityEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TestGitHubTeamActivityClient
{
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Identity IDENTITY = new Identity(1, "viewer", ImmutableMap.of(2L, "alice"));

    @Test
    void deduplicatesCommentAcrossSourcesAndRecognizesExactMentions()
            throws Exception
    {
        String time = Instant.now().toString();
        Collector collector = new Collector(IDENTITY);
        collector.publicEvent(MAPPER.readTree("""
                {"id":"event-1","type":"IssueCommentEvent","created_at":"%s","repo":{"name":"owner/repo"},
                 "actor":{"id":2,"login":"alice"},"payload":{"action":"created",
                 "issue":{"number":7,"title":"Question","user":{"id":3,"login":"other"}},
                 "comment":{"id":99,"created_at":"%s","user":{"id":2,"login":"alice"},"body":"@viewer please look"}}}
                """.formatted(time, time)));
        collector.comment("owner/repo", 7, MAPPER.readTree("""
                {"id":99,"created_at":"%s","user":{"id":2,"login":"alice"},"body":"@viewer please look"}
                """.formatted(time)), Optional.empty(), "ISSUE_COMMENT", "REPOSITORY");

        assertThat(collector.result().events()).singleElement().satisfies(event -> {
            assertThat(event.id()).isEqualTo("issue-comment:99");
            assertThat(event.actorId()).isEqualTo(2);
            assertThat(event.viewerInteractionMemberIds()).containsExactly(2L);
            assertThat(event.title()).isEqualTo("Question");
            assertThat(event.url()).isEqualTo("https://github.com/owner/repo/issues/7");
        });
        assertThat(GitHubTeamActivityClient.mentions("@viewer-other", "viewer")).isFalse();
        assertThat(GitHubTeamActivityClient.mentions("mail@viewer", "viewer")).isFalse();
        assertThat(GitHubTeamActivityClient.mentions("Hi @VIEWER!", "viewer")).isTrue();
    }

    @Test
    void preservesAuthorSeparatelyFromMergerAndRejectsUnrelatedOrUnsafeEvents()
            throws Exception
    {
        Collector collector = new Collector(IDENTITY);
        String time = Instant.now().toString();
        collector.pull("owner/repo", MAPPER.readTree("""
                {"number":7,"title":"PR","user":{"id":2,"login":"alice"},"created_at":"%s","merged_at":"%s",
                 "merged_by":{"id":3,"login":"maintainer"}}
                """.formatted(time, time)), "REPOSITORY");
        TeamActivityEvent merged = collector.result().events().stream().filter(event -> event.kind().equals("PR_MERGED")).findFirst().orElseThrow();
        assertThat(merged.actorId()).isEqualTo(3);
        assertThat(merged.subjectAuthorId()).contains(2L);
        assertThat(merged.viewerInteractionMemberIds()).isEmpty();
        collector.publicEvent(MAPPER.readTree("""
                {"type":"IssuesEvent","created_at":"%s","repo":{"name":"../private"},"actor":{"id":2,"login":"alice"},
                 "payload":{"action":"opened","issue":{"number":8}}}
                """.formatted(time)));
        assertThat(collector.result().events()).hasSize(2);
    }

    @Test
    void deduplicatesReviewRequestsAcrossPublicAndPrivateTimelineByActorsAndTime()
            throws Exception
    {
        Collector collector = new Collector(IDENTITY);
        String time = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        JsonNode subject = MAPPER.readTree("{\"number\":7,\"title\":\"Review this\",\"user\":{\"id\":2,\"login\":\"alice\"}}");
        collector.publicEvent(MAPPER.readTree("""
                {"id":"111","type":"PullRequestEvent","created_at":"%s","repo":{"name":"owner/repo"},"actor":{"id":2,"login":"alice"},
                 "payload":{"action":"review_requested","pull_request":%s,"requested_reviewer":{"id":1,"login":"viewer"}}}
                """.formatted(time, subject)));
        collector.timelineEvent("owner/repo", 7, subject, MAPPER.readTree("""
                {"id":222,"event":"review_requested","created_at":"%s","actor":{"id":2,"login":"alice"},
                 "requested_reviewer":{"id":1,"login":"viewer"}}
                """.formatted(time.replace("Z", ".000Z"))));
        assertThat(collector.result().events()).singleElement().satisfies(event -> {
            assertThat(event.kind()).isEqualTo("REVIEW_REQUESTED");
            assertThat(event.viewerInteractionMemberIds()).containsExactly(2L);
            assertThat(event.id()).isEqualTo("review-request:owner/repo#7:2:1:" + time);
        });
    }

    @Test
    void readsPrivateReviewRequestsFromFirstAndLastTimelinePagesAndRetainsValidRowsAfterMalformedRecord()
    {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String time = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        String summary = """
                {"number":7,"title":"Internal PR","user":{"id":2,"login":"alice"},"created_at":"%s","pull_request":{}}
                """.formatted(time);
        server.expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/repos/owner/repo/issues"))
                .andRespond(withSuccess("[" + summary + "]", MediaType.APPLICATION_JSON));
        server.expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/repos/owner/repo/issues/comments"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/repos/owner/repo/pulls/comments"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.test/repos/owner/repo/pulls/7"))
                .andRespond(withSuccess(summary, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.test/repos/owner/repo/pulls/7/reviews?per_page=100&page=1"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.test/repos/owner/repo/issues/7/timeline?per_page=100&page=1"))
                .andRespond(withSuccess("""
                        [{"event":"review_requested","actor":{"id":2,"login":"alice"},"requested_reviewer":{"id":1,"login":"viewer"},
                          "created_at":"invalid-time"}]
                        """, MediaType.APPLICATION_JSON).header("Link", "<https://untrusted.invalid/?page=3>; rel=\"last\""));
        server.expect(requestTo("https://api.github.test/repos/owner/repo/issues/7/timeline?per_page=100&page=3"))
                .andRespond(withSuccess("""
                        [{"id":333,"event":"review_requested","actor":{"id":1,"login":"viewer"},"requested_reviewer":{"id":2,"login":"alice"},
                          "created_at":"%s"}]
                        """.formatted(time), MediaType.APPLICATION_JSON));

        GitHubTeamActivityClient.Observation result = new GitHubTeamActivityClient(builder.build()).repository("pat", "owner/repo", IDENTITY, 0, (number, updatedAt) -> Optional.empty());
        assertThat(result.events()).filteredOn(event -> event.kind().equals("REVIEW_REQUESTED")).singleElement().satisfies(event -> {
            assertThat(event.source()).isEqualTo("REPOSITORY");
            assertThat(event.actorId()).isEqualTo(1);
            assertThat(event.viewerInteractionMemberIds()).containsExactly(2L);
        });
        assertThat(result.errors()).containsExactly("A malformed GitHub activity record was skipped");
        server.verify();
    }

    @Test
    void reusesCompleteSharedDetailsForReviewsRequestsAndMergeWithoutFetchingThoseGroups()
    {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Instant time = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String summary = """
                {"number":7,"title":"Internal PR","state":"closed","user":{"id":2,"login":"alice"},
                 "created_at":"%s","updated_at":"%s","pull_request":{}}
                """.formatted(time, time);
        server.expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/repos/owner/repo/issues"))
                .andRespond(withSuccess("[" + summary + "]", MediaType.APPLICATION_JSON));
        server.expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/repos/owner/repo/issues/comments"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/repos/owner/repo/pulls/comments"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        PrRawDetail raw = new PrRawDetail("Internal PR", "https://github.com/owner/repo/pull/7", "closed", "alice", null,
                time, time, time, "", ImmutableList.of(), ImmutableMap.of(), ImmutableList.of(), null, false, true, "clean",
                1, 0, 1, 0, ImmutableList.of(), "head", "feature", "main");
        StoredPrDetail cached = new StoredPrDetail(raw,
                ImmutableList.of(new PrReviewState(8L, "viewer", null, false, "APPROVED", time, "Looks good", null, null)),
                ImmutableList.of(
                        new PrTimelineEvent(9L, "review_requested", "alice", time, null, PrTimelinePayload.builder().requestedReviewer("viewer").build(), Optional.empty()),
                        new PrTimelineEvent(10L, "merged", "viewer", time, null, PrTimelinePayload.empty(), Optional.empty())),
                ImmutableList.of(), ImmutableList.of());

        GitHubTeamActivityClient.Observation result = new GitHubTeamActivityClient(builder.build()).repository("pat", "owner/repo", IDENTITY, 0, (number, updatedAt) -> {
            assertThat(number).isEqualTo(7);
            assertThat(updatedAt).isEqualTo(time);
            return Optional.of(cached);
        });

        assertThat(result.errors()).isEmpty();
        assertThat(result.events()).filteredOn(event -> event.source().equals("CACHED_DETAIL"))
                .extracting(TeamActivityEvent::kind).contains("PR_MERGED", "PR_REVIEWED", "REVIEW_REQUESTED");
        assertThat(result.events()).filteredOn(event -> event.kind().equals("PR_MERGED")).singleElement().satisfies(event -> {
            assertThat(event.actorId()).isEqualTo(1);
            assertThat(event.subjectAuthorId()).contains(2L);
        });
        server.verify();
    }

    @Test
    void readsPublicOnlyEndpointAndPropagatesPartialFailures()
    {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.github.test/users/alice/events/public?per_page=100&page=1"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        assertThat(new GitHubTeamActivityClient(builder.build()).publicEvents("pat", "alice", IDENTITY).errors()).isEmpty();
        server.verify();
        GitHubTeamActivityClient client = new GitHubTeamActivityClient(builder.build());
        assertThatThrownBy(() -> client.repository("pat", "owner/..", IDENTITY, 0, (number, updatedAt) -> Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.repository("pat", "owner/.", IDENTITY, 0, (number, updatedAt) -> Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        Collector collector = new Collector(IDENTITY);
        collector.read("one repository", () -> { throw new IllegalStateException("private response body"); });
        assertThat(collector.result().errors()).containsExactly("one repository: unavailable (IllegalStateException)");
    }
}

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
package com.bytequay.integration.gitlab.impl;

import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail.CiStatus;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.hosting.ActivityNotice;
import com.bytequay.core.hosting.ActivityNotice.Reason;
import com.bytequay.core.hosting.DiscoveryQuery;
import com.bytequay.core.hosting.DiscoveryQuery.Kind;
import com.bytequay.core.hosting.DiscoveryQuery.State;
import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.PullRequestCardSummary;
import com.bytequay.core.hosting.PullRequestListPage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.bytequay.core.hosting.HostingProvider.GITLAB;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class TestGitLabPullRequestDiscovery
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, String> replies = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private HttpClient http;
    private HostingAccess access;
    private GitLabPullRequestDiscovery discovery;
    private String base;

    @BeforeEach
    void setUp()
            throws IOException
    {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestURI().toASCIIString());
            byte[] bytes = replies.getOrDefault(exchange.getRequestURI().getRawPath(), "[]").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/gitlab";
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        access = new HostingAccess(new HostingInstance(1, GITLAB, URI.create(base), URI.create(base + "/api/v4")), "secret-token");
        discovery = new GitLabPullRequestDiscovery(http, MAPPER);
    }

    @AfterEach
    void tearDown()
    {
        http.close();
        server.stop(0);
    }

    @Test
    void findsTheAccountsRequestsAcrossProjects()
    {
        replies.put("/gitlab/api/v4/merge_requests", "[" + listed(17, "opened") + "," + listed(18, "merged") + "]");
        replies.put("/gitlab/api/v4/user", "{\"id\":5,\"username\":\"me\"}");
        Instant since = Instant.parse("2026-10-01T08:30:15.250Z");

        PullRequestListPage authored = discovery.find(access, DiscoveryQuery.of(Kind.AUTHORED, State.OPEN).updatedSince(since), 1, 100);
        PullRequestListPage closed = discovery.find(access, DiscoveryQuery.of(Kind.REVIEW_REQUESTED, State.CLOSED), 2, 50);
        PullRequestListPage reviewed = discovery.find(access, DiscoveryQuery.of(Kind.REVIEWED, State.OPEN), 1, 100);

        assertThat(authored.items()).extracting(FetchedPullRequest::repo, FetchedPullRequest::number)
                .containsExactly(tuple("group/subgroup/repo", 17), tuple("group/subgroup/repo", 18));
        assertThat(authored.items().getFirst().htmlUrl()).isEqualTo(base + "/group/subgroup/repo/-/merge_requests/17");
        // A closed query reads every state, since GitLab's closed excludes merged, then keeps the finished ones.
        assertThat(closed.items()).extracting(FetchedPullRequest::number).containsExactly(18);
        assertThat(reviewed.items()).isEmpty();
        assertThat(requests).containsExactly(
                "/gitlab/api/v4/merge_requests?scope=created_by_me&state=opened&updated_after=2026-10-01T08:30:15Z&order_by=updated_at&sort=desc&per_page=100&page=1",
                "/gitlab/api/v4/user",
                "/gitlab/api/v4/merge_requests?scope=all&reviewer_username=me&state=all&order_by=updated_at&sort=desc&per_page=50&page=2");
    }

    @Test
    void deliversEachPendingToDoOnceUntilItChanges()
    {
        replies.put("/gitlab/api/v4/todos", """
                [{"id":1,"action_name":"review_requested","target_type":"MergeRequest","target":{"iid":17},"project":{"path_with_namespace":"group/subgroup/repo"},
                  "created_at":"2026-10-01T10:00:00Z","updated_at":"2026-10-01T10:00:00Z"},
                 {"id":2,"action_name":"mentioned","target_type":"MergeRequest","target":{"iid":18},"project":{"path_with_namespace":"group/repo"},
                  "created_at":"2026-10-01T11:00:00Z"},
                 {"id":3,"action_name":"mentioned","target_type":"Issue","target":{"iid":4},"project":{"path_with_namespace":"group/repo"},
                  "created_at":"2026-10-01T12:00:00Z"},
                 {"id":4,"action_name":"mentioned","target_type":"Epic","target":{"iid":2},"created_at":"2026-10-01T13:00:00Z"}]
                """);
        List<ActivityNotice> notices = new ArrayList<>();

        discovery.poll(access, "Me", notices::add);
        discovery.poll(access, "Me", notices::add);

        assertThat(notices).extracting(ActivityNotice::ref, ActivityNotice::pullRequest, ActivityNotice::reason).containsExactly(
                tuple(PullRequestRef.of("group/subgroup", "repo", 17), true, Reason.REVIEW_REQUESTED),
                tuple(PullRequestRef.of("group", "repo", 18), true, Reason.MENTIONED),
                tuple(PullRequestRef.of("group", "repo", 4), false, Reason.MENTIONED));
        assertThat(notices.getFirst().latestComment().get()).isEmpty();
        assertThat(discovery.reviewRequests("me")).containsExactly(PullRequestRef.of("group/subgroup", "repo", 17));
        assertThat(discovery.mentions("ME")).containsExactly(PullRequestRef.of("group", "repo", 18));
        assertThat(requests).allMatch(request -> request.startsWith("/gitlab/api/v4/todos?state=pending&per_page=100&page=1"));
    }

    @Test
    void summarizesCardsFromEachRequestsDetail()
    {
        replies.put("/gitlab/api/v4/projects/group%2Fsubgroup%2Frepo/merge_requests/17", """
                {"id":9017,"iid":17,"project_id":42,"title":"Support nested projects","state":"opened","draft":false,
                 "web_url":"%s/group/subgroup/repo/-/merge_requests/17","author":{"username":"me"},"labels":[],
                 "created_at":"2026-10-01T10:00:00Z","updated_at":"2026-10-02T10:00:00Z","source_branch":"feature/nested",
                 "sha":"%s","head_pipeline":{"id":3,"status":"failed"},"user_notes_count":3}
                """.formatted(base, "b".repeat(40)));

        List<PullRequestCardSummary> cards = discovery.summaries(access, ImmutableList.of(PullRequestRef.of("group/subgroup", "repo", 17)), "me");

        assertThat(cards).singleElement().satisfies(card -> {
            assertThat(card.pullRequest().origin()).isEqualTo(PullRequest.Origin.AUTHORED);
            assertThat(card.ciStatus()).isEqualTo(CiStatus.FAILING);
            assertThat(card.commentCount()).isEqualTo(3);
            assertThat(card.headSha()).isEqualTo("b".repeat(40));
            assertThat(card.activityVersion()).isEqualTo("2026-10-02T10:00:00Z");
        });
    }

    private String listed(int number, String state)
    {
        return """
                {"id":%d,"iid":%d,"project_id":42,"title":"Support nested projects","state":"%s","draft":false,
                 "web_url":"%s/group/subgroup/repo/-/merge_requests/%d","author":{"username":"alice"},"labels":["bug"],
                 "created_at":"2026-10-01T10:00:00Z","updated_at":"2026-10-02T10:00:00Z","source_branch":"feature/nested",
                 "references":{"full":"group/subgroup/repo!%d"}}
                """.formatted(9000 + number, number, state, base, number, number);
    }
}

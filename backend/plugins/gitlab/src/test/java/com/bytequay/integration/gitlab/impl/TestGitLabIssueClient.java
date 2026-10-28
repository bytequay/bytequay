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

import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingRepository;
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
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.core.hosting.HostingProvider.GITLAB;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestGitLabIssueClient
{
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Map<String, Reply> replies = new HashMap<>();
    private final AtomicReference<String> requested = new AtomicReference<>();
    private final AtomicReference<String> token = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private HttpServer server;
    private HttpClient http;
    private String base;
    private HostingAccess access;
    private HostingRepository repository;
    private GitLabIssueClient client;

    @BeforeEach
    void setUp()
            throws IOException
    {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().toASCIIString();
            requested.set(path);
            token.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Reply reply = replies.getOrDefault(exchange.getRequestMethod() + " " + path, new Reply(404, "{}", false));
            if (reply.hasNext()) {
                exchange.getResponseHeaders().set("Link", "<" + base + "/api/v4/projects/42/issues?page=2>; rel=\"next\"");
            }
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/gitlab";
        http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        access = new HostingAccess(new HostingInstance(1, GITLAB, URI.create(base), URI.create(base + "/api/v4")), "selected-token");
        repository = new HostingRepository(900, 1, "42", "group/subgroup/repo", URI.create(base + "/group/subgroup/repo"),
                URI.create(base + "/group/subgroup/repo.git"), Optional.empty(), Optional.of("main"));
        client = new GitLabIssueClient(http, MAPPER);
        replies.put("GET /gitlab/api/v4/projects/42/issues/7", new Reply(200, issue("opened"), false));
    }

    @AfterEach
    void tearDown()
    {
        http.close();
        server.stop(0);
    }

    @Test
    void listsIncrementalIssuesUsingProjectIdsAndPreservesPagination()
    {
        replies.put("GET /gitlab/api/v4/projects/42/issues?scope=all&state=all&order_by=updated_at&sort=asc&per_page=100&page=2&updated_after=2026-10-01T00%3A00%3A00Z",
                new Reply(200, "[" + issue("opened") + "," + issue("closed") + "]", true));

        var page = client.list(access, repository, Optional.of(NOW), 2);

        assertThat(page.hasNext()).isTrue();
        assertThat(page.openIssues()).singleElement().satisfies(issue -> {
            assertThat(issue.id()).isEqualTo(70);
            assertThat(issue.number()).isEqualTo(7);
            assertThat(issue.body()).isEmpty();
            assertThat(issue.assignees()).containsExactly("bob");
        });
        assertThat(page.closedNumbers()).containsExactly(7);
        assertThat(token.get()).isEqualTo("Bearer selected-token");
    }

    @Test
    void returnsExistingSourceContractWithPrivateServerPrefixAndNestedNamespace()
    {
        var source = client.source(access, repository, 7, true);

        assertThat(source.host()).isEqualTo(base);
        assertThat(source.repo()).isEqualTo("group/subgroup/repo");
        assertThat(source.url()).isEqualTo(base + "/group/subgroup/repo/-/work_items/7");
        replies.put("GET /gitlab/api/v4/projects/42/issues/7", new Reply(200, issue("closed"), false));
        assertThat(client.find(access, repository, 7)).isEmpty();
        assertThatThrownBy(() -> client.source(access, repository, 7, true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void previewsClosedIssuesWithoutPuttingThemInTheOpenIssueCache()
    {
        replies.put("GET /gitlab/api/v4/projects/42/issues/7", new Reply(200, issue("closed"), false));
        var preview = client.preview(access, repository, 7);
        assertThat(preview.state()).isEqualTo("closed");
        assertThat(preview.pullRequest()).isFalse();
        assertThat(preview.htmlUrl()).isEqualTo(base + "/group/subgroup/repo/-/work_items/7");
        assertThat(preview.labels()).containsExactly("bug");
        assertThat(token.get()).isEqualTo("Bearer selected-token");
    }

    @Test
    void combinesNotesAndResourceEventsInTheExistingTimeline()
    {
        replies.put("GET /gitlab/api/v4/projects/42/issues/7/notes?sort=asc&order_by=created_at&per_page=100&page=1", new Reply(200, "[" + note() + "]", false));
        replies.put("GET /gitlab/api/v4/projects/42/issues/7/resource_state_events?&per_page=100&page=1", new Reply(200,
                "[{\"id\":9,\"state\":\"closed\",\"created_at\":\"2026-10-01T00:01:00Z\",\"user\":{\"username\":\"alice\"}}]", false));
        replies.put("GET /gitlab/api/v4/projects/42/issues/7/resource_label_events?&per_page=100&page=1", new Reply(200, "[]", false));
        replies.put("GET /gitlab/api/v4/projects/42/issues/7/resource_milestone_events?&per_page=100&page=1", new Reply(200, "[]", false));

        var detail = client.detail(access, repository, 7);

        assertThat(detail.repositoryId()).isEqualTo(900);
        assertThat(detail.timeline()).extracting(event -> event.event()).containsExactly("commented", "closed");
        assertThat(detail.timeline().getFirst().body()).isEqualTo("Please fix this");
        assertThat(detail.availableTypes()).containsExactly("issue", "incident");
    }

    @Test
    void resolvesAssigneeIdsAndWritesTypedMetadataAndComments()
            throws IOException
    {
        replies.put("GET /gitlab/api/v4/users?username=bob", new Reply(200, "[{\"id\":2,\"username\":\"bob\"}]", false));
        replies.put("PUT /gitlab/api/v4/projects/42/issues/7", new Reply(200, issue("opened"), false));
        var metadata = client.updateMetadata(access, repository, 7, ImmutableList.of("bob"), ImmutableList.of("bug"));

        assertThat(metadata.assignees()).containsExactly("bob");
        assertThat(MAPPER.readValue(body.get(), MetadataRequest.class)).isEqualTo(new MetadataRequest(ImmutableList.of(2L), "bug"));
        replies.put("POST /gitlab/api/v4/projects/42/issues/7/notes", new Reply(201, note(), false));
        assertThat(client.comment(access, repository, 7, "Please fix this").body()).isEqualTo("Please fix this");
        assertThat(MAPPER.readValue(body.get(), CommentRequest.class).body()).isEqualTo("Please fix this");
    }

    @Test
    void fetchesLabelsFromTheConfiguredServerPrefixAndNormalizesColors()
    {
        replies.put("GET /gitlab/api/v4/projects/42/labels?&per_page=100&page=1",
                new Reply(200, "[{\"id\":1,\"name\":\"bug\",\"color\":\"#aabbcc\"}]", false));
        assertThat(client.repositoryLabels(access, repository)).singleElement().satisfies(label -> {
            assertThat(label.name()).isEqualTo("bug");
            assertThat(label.color()).isEqualTo("aabbcc");
        });
        assertThat(token.get()).isEqualTo("Bearer selected-token");
    }

    @Test
    void failsAccessErrorsWithoutPretendingTheIssueListIsEmpty()
    {
        replies.put("GET /gitlab/api/v4/projects/42/issues?scope=all&state=opened&order_by=updated_at&sort=asc&per_page=100&page=1", new Reply(403, "{}", false));
        assertThatThrownBy(() -> client.list(access, repository, Optional.empty(), 1)).isInstanceOf(HostingFailure.class);
    }

    private String issue(String state)
    {
        return """
                {"id":70,"iid":7,"project_id":42,"state":"%s","title":"Fix selection","description":null,
                 "web_url":"%s/group/subgroup/repo/-/work_items/7","author":{"id":1,"username":"alice"},
                 "labels":["bug"],"assignees":[{"id":2,"username":"bob"}],"user_notes_count":1,
                 "created_at":"2026-10-01T00:00:00Z","updated_at":"2026-10-01T00:00:00Z","issue_type":"issue"}
                """.formatted(state, base);
    }

    private static String note()
    {
        return """
                {"id":5,"body":"Please fix this","author":{"id":2,"username":"bob"},"system":false,"created_at":"2026-10-01T00:00:00Z"}
                """;
    }

    private record Reply(int status, String body, boolean hasNext) {}

    private record MetadataRequest(@com.fasterxml.jackson.annotation.JsonProperty("assignee_ids") java.util.List<Long> assignees, String labels) {}

    private record CommentRequest(String body) {}
}

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

import com.bytequay.core.domain.TeamActivityEvent;
import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingPerson;
import com.bytequay.core.hosting.TeamIdentity;
import com.bytequay.core.hosting.TeamObservation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
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
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static com.bytequay.core.hosting.HostingProvider.GITLAB;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestGitLabTeamActivityReader
{
    private static final TeamIdentity IDENTITY = new TeamIdentity(1, "viewer", ImmutableMap.of(2L, "alice", 3L, "bob"));

    private final Map<String, String> responses = new ConcurrentHashMap<>();
    private final Map<String, Integer> statuses = new ConcurrentHashMap<>();
    private HttpServer server;
    private HttpClient http;
    private HostingAccess access;
    private String base;
    private GitLabTeamActivityReader reader;

    @BeforeEach
    void setUp()
            throws IOException
    {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().toASCIIString().replaceFirst("after=[0-9-]+", "after=DATE");
            String body = responses.get(path);
            byte[] bytes = (body == null ? "{}" : body).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statuses.getOrDefault(path, body == null ? 404 : 200), bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/gitlab";
        access = new HostingAccess(new HostingInstance(1, GITLAB, URI.create(base), URI.create(base + "/api/v4")), "token");
        http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        reader = new GitLabTeamActivityReader(http, new ObjectMapper());
    }

    @AfterEach
    void tearDown()
    {
        http.close();
        server.stop(0);
    }

    @Test
    void findsThePersonWithTheUsername()
    {
        responses.put("/gitlab/api/v4/users?username=first.last", """
                [{"id":44,"username":"First.Last","name":"First Last","avatar_url":"https://gitlab.example.com/avatar.png"}]
                """);

        assertThat(reader.person(access, "first.last")).isEqualTo(new HostingPerson(44, "First.Last", "First Last", "https://gitlab.example.com/avatar.png"));

        responses.put("/gitlab/api/v4/users?username=nobody", "[]");
        assertThatThrownBy(() -> reader.person(access, "nobody")).isInstanceOf(HostingFailure.class);
    }

    @Test
    void hasNoPerPersonStream()
    {
        assertThat(reader.personActivity(access, "alice", IDENTITY)).isEqualTo(new TeamObservation(ImmutableList.of(), ImmutableList.of(), false));
    }

    @Test
    void mapsProjectEventsWithRequestAuthorsAndDirectInteractions()
    {
        String at = Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();
        responses.put("/gitlab/api/v4/projects/group%2Fsub%2Fproject/events?after=DATE&per_page=100", """
                [{"id":10,"action_name":"accepted","target_type":"MergeRequest","target_iid":7,"target_title":"Add cache","created_at":"%1$s",
                  "author":{"id":3,"username":"bob"}},
                 {"id":11,"action_name":"commented on","target_type":"DiffNote","target_title":"Add cache","created_at":"%1$s",
                  "author":{"id":2,"username":"alice"},"note":{"body":"@viewer please look","noteable_type":"MergeRequest","noteable_iid":7}},
                 {"id":12,"action_name":"approved","target_type":"MergeRequest","target_iid":7,"target_title":"Add cache","created_at":"%1$s",
                  "author":{"id":1,"username":"viewer"}},
                 {"id":13,"action_name":"closed","target_type":"Issue","target_iid":3,"target_title":"Crash","created_at":"%1$s",
                  "author":{"id":2,"username":"alice"}},
                 {"id":14,"action_name":"pushed to","created_at":"%1$s","author":{"id":2,"username":"alice"}}]
                """.formatted(at));
        responses.put("/gitlab/api/v4/projects/group%2Fsub%2Fproject/merge_requests/7", "{\"author\":{\"id\":2,\"username\":\"alice\"}}");
        responses.put("/gitlab/api/v4/projects/group%2Fsub%2Fproject/issues/3", "{\"author\":{\"id\":3,\"username\":\"bob\"}}");

        TeamObservation observed = reader.repositoryActivity(access, "group/sub/project", IDENTITY, 0, (number, updatedAt) -> Optional.empty());

        assertThat(observed.errors()).isEmpty();
        assertThat(observed.events()).extracting(TeamActivityEvent::kind).containsExactly("PR_MERGED", "PR_REVIEW_COMMENT", "PR_REVIEWED", "ISSUE_CLOSED");
        TeamActivityEvent merged = observed.events().getFirst();
        assertThat(merged.subjectAuthorId()).contains(2L);
        assertThat(merged.url()).isEqualTo(base + "/group/sub/project/-/merge_requests/7");
        assertThat(merged.repository()).isEqualTo("group/sub/project");
        // Alice mentioned the viewer; the viewer approved Alice's request.
        assertThat(observed.events().get(1).viewerInteractionMemberIds()).containsExactly(2L);
        assertThat(observed.events().get(2).viewerInteractionMemberIds()).containsExactly(2L);
        assertThat(observed.events().get(3).url()).isEqualTo(base + "/group/sub/project/-/issues/3");
    }

    @Test
    void reportsARateLimitedPassAndStops()
    {
        String path = "/gitlab/api/v4/projects/group%2Fproject/events?after=DATE&per_page=100";
        responses.put(path, "{\"message\":\"429\"}");
        statuses.put(path, 429);

        TeamObservation observed = reader.repositoryActivity(access, "group/project", IDENTITY, 0, (number, updatedAt) -> Optional.empty());

        assertThat(observed.rateLimited()).isTrue();
        assertThat(observed.events()).isEmpty();
        assertThat(observed.errors()).singleElement().asString().contains("rate limit");
    }
}

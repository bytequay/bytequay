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

import com.bytequay.core.domain.PrDiffEtags;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.domain.StoredPrDiff;
import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingRepository;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.bytequay.core.hosting.HostingProvider.GITLAB;
import static org.assertj.core.api.Assertions.assertThat;

class TestGitLabPullRequestContentReader
{
    private static final String PREFIX = "/gitlab/api/v4/projects/42/merge_requests/7";
    private static final String HEAD = "a".repeat(40);
    private static final String BASE = "b".repeat(40);
    private final Map<String, String> responses = new HashMap<>();
    private HttpServer server;
    private HttpClient http;
    private HostingAccess access;
    private HostingRepository repository;
    private GitLabPullRequestContentReader reader;

    @BeforeEach
    void setUp()
            throws IOException
    {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer token");
            String path = exchange.getRequestURI().toASCIIString();
            String body = responses.get(path);
            byte[] bytes = (body == null ? "{}" : body).getBytes(StandardCharsets.UTF_8);
            if (path.equals(PREFIX + "/discussions?per_page=100&page=1")) {
                exchange.getResponseHeaders().set("Link", "<http://unused.invalid>; rel=\"next\"");
            }
            exchange.sendResponseHeaders(body == null ? 403 : 200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/gitlab";
        HostingInstance instance = new HostingInstance(1, GITLAB, URI.create(base), URI.create(base + "/api/v4"));
        access = new HostingAccess(instance, "token");
        repository = new HostingRepository(2, 1, "42", "group/nested/repo", URI.create(base + "/group/nested/repo"),
                URI.create(base + "/group/nested/repo.git"), Optional.empty(), Optional.of("main"));
        http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        reader = new GitLabPullRequestContentReader(http, new ObjectMapper());
    }

    @AfterEach
    void tearDown()
    {
        http.close();
        server.stop(0);
    }

    @Test
    void mapsPaginatedCommentsThreadsApprovalsAndReactionsIntoExistingContracts()
    {
        responses.put(PREFIX + "/approvals", "{\"approved_by\":[{\"user\":{\"id\":3,\"username\":\"reviewer\"}}]}");
        responses.put(PREFIX + "/discussions?per_page=100&page=1", """
                [{"id":"discussion-one","notes":[{"id":10,"body":"Please fix","created_at":"2026-01-01T00:00:00Z",
                "author":{"id":3,"username":"reviewer"},"position":{"new_path":"file.txt","old_path":"file.txt","new_line":3},"resolved":true}]}]
                """);
        responses.put(PREFIX + "/discussions?per_page=100&page=2", """
                [{"id":"discussion-two","notes":[{"id":11,"body":"Ready","created_at":"2026-01-01T01:00:00Z","author":{"id":4,"username":"author"}}]},
                {"id":"system-note","notes":[{"id":12,"body":"changed title","system":true,"created_at":"2026-01-01T02:00:00Z",
                "author":{"id":4,"username":"author"}}]}]
                """);
        responses.put(PREFIX + "/commits?per_page=100&page=1", "[]");
        responses.put(PREFIX + "/notes/10/award_emoji?per_page=100&page=1", "[]");
        responses.put(PREFIX + "/notes/11/award_emoji?per_page=100&page=1", "[]");
        responses.put(PREFIX + "/award_emoji?per_page=100&page=1", "[{\"name\":\"thumbsup\"},{\"name\":\"thumbsup\"}]");

        StoredPrDetail detail = reader.discussion(access, repository, 7, existing());

        assertThat(detail.reviews()).singleElement().satisfies(review -> {
            assertThat(review.login()).isEqualTo("reviewer");
            assertThat(review.state()).isEqualTo("APPROVED");
        });
        assertThat(detail.timeline()).extracting(event -> event.event()).containsExactly("review_thread", "commented", "system_note", "description_reactions");
        assertThat(detail.timeline().get(2).githubId()).isEqualTo(12L);
        assertThat(detail.timeline().get(2).body()).isEqualTo("changed title");
        assertThat(detail.timeline().getFirst().payload().resolved()).isTrue();
        assertThat(detail.timeline().getFirst().payload().line()).isEqualTo(3);
        assertThat(detail.timeline().getFirst().payload().comments()).singleElement().satisfies(comment -> assertThat(comment.githubId()).isEqualTo(10));
        assertThat(detail.timeline().getLast().payload().reactions()).singleElement().satisfies(reaction -> {
            assertThat(reaction.content()).isEqualTo("+1");
            assertThat(reaction.count()).isEqualTo(2);
        });
        assertThat(detail.unavailableSections()).isEmpty();
    }

    @Test
    void failedSourcesRemainExplicitlyUnavailable()
    {
        responses.put(PREFIX + "/commits?per_page=100&page=1", "[]");
        StoredPrDetail detail = reader.discussion(access, repository, 7, existing());
        assertThat(detail.unavailableSections()).contains("reviews", "timeline", "comments", "reviewThreads", "reactions").doesNotContain("commits");
        assertThat(detail.reviewsComplete()).isFalse();
        assertThat(detail.timelineComplete()).isFalse();
    }

    @Test
    void readsCurrentPipelineJobsIncludingTheirFailureState()
    {
        responses.put(PREFIX, "{\"sha\":\"" + HEAD + "\",\"head_pipeline\":{\"id\":9,\"project_id\":42,\"status\":\"failed\"}}");
        responses.put("/gitlab/api/v4/projects/42/pipelines/9/jobs?per_page=100&page=1", "[{\"id\":12,\"name\":\"test\",\"status\":\"failed\"}]");
        responses.put("/gitlab/api/v4/projects/42/pipelines/9/bridges?per_page=100&page=1", "[]");

        var result = reader.checks(access, repository, 7, HEAD, ImmutableList.of(), Optional.empty(), Optional.empty());

        assertThat(result.checkRuns()).singleElement().satisfies(check -> {
            assertThat(check.githubId()).isEqualTo(12);
            assertThat(check.status()).isEqualTo("completed");
            assertThat(check.conclusion()).isEqualTo("failure");
            assertThat(check.source()).isEqualTo("job");
        });
    }

    @Test
    void mapsDiffsAndDisclosesUnavailablePatchText()
    {
        responses.put(PREFIX + "/diffs?per_page=100&page=1", """
                [{"new_path":"file.txt","old_path":"file.txt","diff":"@@ -1 +1 @@\\n-old\\n+new"},
                {"new_path":"large.txt","old_path":"large.txt","diff":"","too_large":true}]
                """);
        responses.put(PREFIX + "/commits?per_page=100&page=1", "[{\"id\":\"" + HEAD + "\",\"parent_ids\":[\"" + BASE + "\"],\"message\":\"Fix\"}]");

        var result = reader.diff(access, repository, 7, Optional.empty(), Optional.empty(), new StoredPrDiff(ImmutableList.of(), ImmutableList.of()), PrDiffEtags.none());

        assertThat(result.files()).hasSize(2);
        assertThat(result.files().getFirst().additions()).isEqualTo(1);
        assertThat(result.files().getFirst().deletions()).isEqualTo(1);
        assertThat(result.files().getLast().patch()).isNull();
        assertThat(result.commits()).singleElement().satisfies(commit -> assertThat(commit.parentSha()).isEqualTo(BASE));
    }

    @Test
    void readsRenamedContentFromTargetAndSourceProjects()
    {
        responses.put(PREFIX, "{\"source_project_id\":43}");
        responses.put("/gitlab/api/v4/projects/42/repository/compare?from=" + BASE + "&to=" + HEAD + "&straight=true",
                "{\"diffs\":[{\"new_path\":\"new.txt\",\"old_path\":\"old.txt\",\"renamed_file\":true}],\"commits\":[]}");
        responses.put("/gitlab/api/v4/projects/42/repository/files/old.txt?ref=" + BASE, "{\"encoding\":\"base64\",\"content\":\"b2xk\"}");
        responses.put("/gitlab/api/v4/projects/43/repository/files/new.txt?ref=" + HEAD, "{\"encoding\":\"base64\",\"content\":\"bmV3\"}");

        var content = reader.fileContent(access, repository, 7, "new.txt", BASE, HEAD);

        assertThat(content.oldContent()).isEqualTo("old");
        assertThat(content.newContent()).isEqualTo("new");
    }

    @Test
    void publicationIdentityKeepsNestedSourceAndTargetRepositories()
    {
        responses.put(PREFIX, """
                {"iid":7,"sha":"%s","state":"opened","draft":false,"author":{"id":3,"username":"author"},
                "web_url":"%s/-/merge_requests/7","source_project_id":43,"target_project_id":42,"source_branch":"feature","target_branch":"main",
                "diff_refs":{"head_sha":"%s","start_sha":"%s"}}
                """.formatted(HEAD, repository.webUrl(), HEAD, BASE));
        responses.put("/gitlab/api/v4/projects/43", "{\"id\":43,\"path_with_namespace\":\"another/nested/fork\"}");

        var identity = reader.identity(access, repository, 7);

        assertThat(identity.headRepository().fullName()).isEqualTo("another/nested/fork");
        assertThat(identity.baseRepository().fullName()).isEqualTo("group/nested/repo");
        assertThat(identity.headSha()).isEqualTo(HEAD);
        assertThat(identity.pullRequest().number()).isEqualTo(7);
    }

    @Test
    void readsPlainTextTraceOnlyForCurrentFailedJob()
    {
        responses.put(PREFIX, "{\"sha\":\"" + HEAD + "\",\"head_pipeline\":{\"id\":9,\"project_id\":42}}");
        responses.put("/gitlab/api/v4/projects/42/pipelines/9/jobs?per_page=100&page=1", "[{\"id\":12,\"name\":\"test\",\"status\":\"failed\"}]");
        responses.put("/gitlab/api/v4/projects/42/jobs/12/trace", "test failed\n");

        assertThat(reader.readCheckLog(access, repository, 7, 12).text()).isEqualTo("test failed\n");
    }

    private static StoredPrDetail existing()
    {
        PrRawDetail raw = new PrRawDetail("Title", "https://gitlab.example/group/repo/-/merge_requests/7", "open", "author", null, null, null, null,
                "Body", ImmutableList.of(), ImmutableMap.of(), ImmutableList.of(), null, false, null, null, 0, 0, 0, 0, ImmutableList.of(), HEAD, "feature", "main", BASE);
        return new StoredPrDetail(raw, ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), true);
    }
}

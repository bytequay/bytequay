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

import com.bytequay.core.domain.InlineReviewComment;
import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingRepository;
import com.fasterxml.jackson.annotation.JsonProperty;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static com.bytequay.core.hosting.HostingProvider.GITLAB;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestGitLabPullRequestWriter
{
    private static final String PREFIX = "/gitlab/api/v4/projects/42/merge_requests/7";
    private static final String HEAD = "a".repeat(40);
    private static final String BASE = "b".repeat(40);
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, String> responses = new HashMap<>();
    private final List<Request> received = new ArrayList<>();
    private HttpServer server;
    private HttpClient http;
    private HostingAccess access;
    private HostingRepository repository;
    private GitLabPullRequestWriter writer;
    private String base;

    @BeforeEach
    void setUp()
            throws IOException
    {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer token");
            String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().toASCIIString();
            received.add(new Request(key, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            String body = responses.get(key);
            byte[] bytes = (body == null ? "{}" : body).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(body == null ? 403 : 200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/gitlab";
        HostingInstance instance = new HostingInstance(1, GITLAB, URI.create(base), URI.create(base + "/api/v4"));
        access = new HostingAccess(instance, "token");
        repository = new HostingRepository(2, 1, "42", "group/nested/repo", URI.create(base + "/group/nested/repo"),
                URI.create(base + "/group/nested/repo.git"), Optional.empty(), Optional.of("main"));
        http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        writer = new GitLabPullRequestWriter(http, mapper);
    }

    @AfterEach
    void tearDown()
    {
        http.close();
        server.stop(0);
    }

    @Test
    void createsDraftMergeRequestAndKeepsRemoteIdSeparateFromNumber()
            throws Exception
    {
        responses.put("POST /gitlab/api/v4/projects/42/merge_requests", "{\"id\":321,\"iid\":7,\"web_url\":\"" + base + "/group/nested/repo/-/merge_requests/7\"}");

        var created = writer.create(access, repository, "feature", "main", "Change", "Body", true);

        assertThat(created.id()).isEqualTo(321);
        assertThat(created.number()).isEqualTo(7);
        Create request = mapper.readValue(received.getFirst().body(), Create.class);
        assertThat(request.source()).isEqualTo("feature");
        assertThat(request.target()).isEqualTo("main");
        assertThat(request.title()).isEqualTo("Draft: Change");
    }

    @Test
    void mergeCarriesTheObservedHeadAndSquashChoice()
            throws Exception
    {
        responses.put("PUT " + PREFIX + "/merge", "{\"state\":\"merged\",\"merge_commit_sha\":\"" + HEAD + "\"}");

        assertThat(writer.merge(access, repository, 7, "squash", HEAD).merged()).isTrue();
        Merge request = mapper.readValue(received.getFirst().body(), Merge.class);
        assertThat(request.sha()).isEqualTo(HEAD);
        assertThat(request.squash()).isTrue();
    }

    @Test
    void defaultMergeKeepsTheProjectMergeAndSquashSettings()
            throws Exception
    {
        responses.put("PUT " + PREFIX + "/merge", "{\"state\":\"merged\",\"merge_commit_sha\":\"" + HEAD + "\"}");

        assertThat(writer.merge(access, repository, 7, "default", HEAD).merged()).isTrue();
        Merge request = mapper.readValue(received.getFirst().body(), Merge.class);
        assertThat(request.sha()).isEqualTo(HEAD);
        assertThat(request.squash()).isNull();
        assertThat(received.getFirst().body()).doesNotContain("squash");
    }

    @Test
    void explicitRebaseDoesNotSilentlyChooseAnotherMergeMethod()
    {
        assertThatThrownBy(() -> writer.merge(access, repository, 7, "rebase", HEAD))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("project");
        assertThat(received).isEmpty();
    }

    @Test
    void changedHeadRejectsReviewBeforePublishing()
    {
        responses.put("GET " + PREFIX, "{\"sha\":\"" + BASE + "\"}");

        assertThatThrownBy(() -> writer.submitReview(access, repository, 7, "APPROVE", "Fine", ImmutableList.of(), HEAD))
                .isInstanceOf(HostingFailure.class).hasMessageContaining("head changed");
        assertThat(received).allSatisfy(request -> assertThat(request.key()).startsWith("GET "));
    }

    @Test
    void unsupportedReviewStateCannotPublishCommentsOrOtherDrafts()
    {
        assertThatThrownBy(() -> writer.submitReview(access, repository, 7, "REQUEST_CHANGES", "Fix it", ImmutableList.of(), HEAD))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("request-changes");
        assertThat(received).isEmpty();
    }

    @Test
    void contextLineCommentUsesBothDiffCoordinates()
            throws Exception
    {
        responses.put("GET " + PREFIX, "{\"sha\":\"" + HEAD + "\",\"diff_refs\":{\"base_sha\":\"" + BASE + "\",\"start_sha\":\"" + BASE
                + "\",\"head_sha\":\"" + HEAD + "\"}}");
        responses.put("GET " + PREFIX + "/diffs?per_page=100&page=1",
                "[{\"new_path\":\"file.txt\",\"old_path\":\"file.txt\",\"diff\":\"@@ -3,2 +3,3 @@\\n same\\n+added\\n final\"}]");
        responses.put("POST " + PREFIX + "/discussions", "{\"id\":\"thread\",\"notes\":[" + note() + "]}");
        InlineReviewComment comment = new InlineReviewComment("file.txt", "Why?", 5, "RIGHT", null, null, HEAD, OptionalLong.empty());

        var result = writer.createReviewComment(access, repository, 7, comment);

        assertThat(result.event()).isEqualTo("review_thread");
        DiscussionBody request = mapper.readValue(received.getLast().body(), DiscussionBody.class);
        assertThat(request.position().oldLine()).isEqualTo(4);
        assertThat(request.position().newLine()).isEqualTo(5);
        assertThat(request.position().headSha()).isEqualTo(HEAD);
        assertThat(request.position().baseSha()).isEqualTo(BASE);
    }

    @Test
    void commentSubmissionReturnsTheCreatedNoteWithoutInventingAReview()
    {
        responses.put("GET " + PREFIX, "{\"sha\":\"" + HEAD + "\"}");
        responses.put("POST " + PREFIX + "/notes", note());

        var result = writer.submitReview(access, repository, 7, "COMMENT", "Comment", ImmutableList.of(), HEAD);

        assertThat(result.review()).isEmpty();
        assertThat(result.events()).singleElement().satisfies(event -> {
            assertThat(event.githubId()).isEqualTo(10L);
            assertThat(event.event()).isEqualTo("commented");
            assertThat(event.timestamp()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
            assertThat(event.payload().htmlUrl()).isEqualTo(base + "/group/nested/repo/-/merge_requests/7#note_10");
        });
    }

    @Test
    void approvalHasNoFabricatedRemoteSubmissionIdentity()
    {
        responses.put("GET " + PREFIX, "{\"sha\":\"" + HEAD + "\"}");
        responses.put("GET /gitlab/api/v4/user", "{\"id\":3,\"username\":\"reviewer\"}");
        responses.put("POST " + PREFIX + "/approve", "{}");

        var result = writer.submitReview(access, repository, 7, "APPROVE", "", ImmutableList.of(), HEAD);

        assertThat(result.review()).get().satisfies(review -> {
            assertThat(review.githubId()).isNull();
            assertThat(review.submittedAt()).isNull();
            assertThat(review.state()).isEqualTo("APPROVED");
            assertThat(review.login()).isEqualTo("reviewer");
        });
        assertThat(result.events()).isEmpty();
    }

    @Test
    void reviewReplyDoesNotRequireDiffCoordinates()
    {
        responses.put("GET " + PREFIX + "/discussions?per_page=100&page=1", "[{\"id\":\"thread\",\"notes\":[" + note() + "]}]");
        responses.put("POST " + PREFIX + "/discussions/thread/notes", note().replace("\"id\":10", "\"id\":11").replace("Comment", "Reply"));
        InlineReviewComment reply = new InlineReviewComment("", "Reply", 0, null, null, null, null, OptionalLong.of(10));

        var result = writer.createReviewComment(access, repository, 7, reply);

        assertThat(result.event()).isEqualTo("review_thread");
        assertThat(result.payload().comments()).hasSize(2);
        assertThat(result.payload().comments().getLast().githubId()).isEqualTo(11L);
        assertThat(result.payload().comments().getLast().body()).isEqualTo("Reply");
        assertThat(received).extracting(Request::key).containsExactly(
                "GET " + PREFIX + "/discussions?per_page=100&page=1", "POST " + PREFIX + "/discussions/thread/notes");
    }

    @Test
    void laterReviewFailureReportsAlreadyPublishedComments()
    {
        responses.put("GET " + PREFIX, "{\"sha\":\"" + HEAD + "\"}");
        responses.put("GET /gitlab/api/v4/user", "{\"id\":3,\"username\":\"reviewer\"}");
        responses.put("POST " + PREFIX + "/notes", note());

        assertThatThrownBy(() -> writer.submitReview(access, repository, 7, "APPROVE", "Fine", ImmutableList.of(), HEAD))
                .isInstanceOf(HostingFailure.class).hasMessageContaining("published 1 comment").hasMessageContaining("Refresh before retrying");
    }

    @Test
    void retriesOnlyFailedJobsFromCurrentPipeline()
    {
        responses.put("GET " + PREFIX, "{\"head_pipeline\":{\"id\":9,\"project_id\":42}}");
        responses.put("GET /gitlab/api/v4/projects/42/pipelines/9/jobs?per_page=100&page=1", "[{\"id\":12,\"status\":\"failed\"},{\"id\":13,\"status\":\"success\"}]");
        responses.put("POST /gitlab/api/v4/projects/42/jobs/12/retry", "{}");

        writer.rerunFailedChecks(access, repository, 7);

        assertThat(received.stream().filter(request -> request.key().startsWith("POST")).map(Request::key)).containsExactly("POST /gitlab/api/v4/projects/42/jobs/12/retry");
    }

    private static String note()
    {
        return "{\"id\":10,\"body\":\"Comment\",\"created_at\":\"2026-01-01T00:00:00Z\",\"author\":{\"id\":3,\"username\":\"reviewer\"}}";
    }

    private record Request(String key, String body) {}
    private record Create(@JsonProperty("source_branch") String source, @JsonProperty("target_branch") String target, String title, String description) {}
    private record Merge(String sha, Boolean squash) {}
    private record DiscussionBody(String body, Position position) {}
    private record Position(@JsonProperty("position_type") String type, @JsonProperty("base_sha") String baseSha, @JsonProperty("start_sha") String startSha,
            @JsonProperty("head_sha") String headSha, @JsonProperty("old_path") String oldPath, @JsonProperty("new_path") String newPath,
            @JsonProperty("old_line") Integer oldLine, @JsonProperty("new_line") Integer newLine) {}
}

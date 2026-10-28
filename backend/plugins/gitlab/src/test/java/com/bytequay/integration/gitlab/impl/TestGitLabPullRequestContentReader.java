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
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static com.bytequay.core.hosting.HostingFailure.Kind.REVISION_CONFLICT;
import static com.bytequay.core.hosting.HostingFailure.Kind.UNAVAILABLE;
import static com.bytequay.core.hosting.HostingProvider.GITLAB;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestGitLabPullRequestContentReader
{
    private static final String PREFIX = "/gitlab/api/v4/projects/42/merge_requests/7";
    private static final String HEAD = "a".repeat(40);
    private static final String BASE = "b".repeat(40);
    private final Map<String, String> responses = new ConcurrentHashMap<>();
    private final Map<String, Integer> statuses = new ConcurrentHashMap<>();
    private final List<String> received = new CopyOnWriteArrayList<>();
    private Consumer<String> beforeResponse = path -> {};
    private ExecutorService serverExecutor;
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
        serverExecutor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(serverExecutor);
        server.createContext("/", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer token");
            String path = exchange.getRequestURI().toASCIIString();
            received.add(path);
            beforeResponse.accept(path);
            String body = responses.get(path);
            byte[] bytes = (body == null ? "{}" : body).getBytes(StandardCharsets.UTF_8);
            if (path.equals(PREFIX + "/discussions?per_page=100&page=1")) {
                exchange.getResponseHeaders().set("Link", "<http://unused.invalid>; rel=\"next\"");
            }
            exchange.sendResponseHeaders(statuses.getOrDefault(path, body == null ? 403 : 200), bytes.length);
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
        serverExecutor.close();
    }

    @Test
    void mapsPaginatedCommentsThreadsApprovalsAndReactionsIntoExistingContracts()
    {
        responses.put(PREFIX + "/approvals", "{\"approved_by\":[{\"user\":{\"id\":3,\"username\":\"reviewer\"}}]}");
        responses.put(PREFIX + "/discussions?per_page=100&page=1", """
                [{"id":"discussion-one","notes":[{"id":10,"body":"Please fix","created_at":"2026-01-01T00:00:00Z",
                "author":{"id":3,"username":"reviewer"},"position":{"new_path":"file.txt","old_path":"file.txt","new_line":3,
                "head_sha":"%s","start_sha":"%s"},"resolved":true}]}]
                """.formatted(HEAD, BASE));
        responses.put(PREFIX + "/discussions?per_page=100&page=2", """
                [{"id":"discussion-two","individual_note":true,"notes":[{"id":11,"body":"Ready","created_at":"2026-01-01T01:00:00Z","author":{"id":4,"username":"author"}}]},
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
    void countsIncrementAndDecrementLinesAsChanges()
    {
        responses.put(PREFIX + "/diffs?per_page=100&page=1", """
                [{"new_path":"counter.cpp","old_path":"counter.cpp","diff":"@@ -1 +1 @@\\n---counter;\\n+++counter;"}]
                """);
        responses.put(PREFIX + "/commits?per_page=100&page=1", "[]");

        var result = reader.diff(access, repository, 7, Optional.empty(), Optional.empty(), new StoredPrDiff(ImmutableList.of(), ImmutableList.of()), PrDiffEtags.none());

        assertThat(result.files()).singleElement().satisfies(file -> {
            assertThat(file.additions()).isEqualTo(1);
            assertThat(file.deletions()).isEqualTo(1);
        });
    }

    @Test
    void smileAwardsUseTheAppsLaughReaction()
    {
        discussionData("[]");
        responses.put(PREFIX + "/award_emoji?per_page=100&page=1", "[{\"name\":\"smile\"},{\"name\":\"smile\"}]");

        var detail = reader.discussion(access, repository, 7, existing());

        assertThat(detail.timeline()).singleElement().satisfies(event -> assertThat(event.payload().reactions()).singleElement().satisfies(reaction -> {
            assertThat(reaction.content()).isEqualTo("laugh");
            assertThat(reaction.count()).isEqualTo(2);
        }));
    }

    @Test
    void readsRenamedContentFromTargetAndSourceProjects()
    {
        responses.put(PREFIX, "{\"source_project_id\":43}");
        responses.put("/gitlab/api/v4/projects/42/repository/compare?from=" + BASE + "&to=" + HEAD + "&straight=true",
                "{\"diffs\":[{\"new_path\":\"new.txt\",\"old_path\":\"old.txt\",\"renamed_file\":true}],\"commits\":[]}");
        responses.put("/gitlab/api/v4/projects/42/repository/files/old.txt?ref=" + BASE, "{\"encoding\":\"base64\",\"content\":\"b2xk\"}");
        responses.put("/gitlab/api/v4/projects/43/repository/files/new.txt?ref=" + HEAD, "{\"encoding\":\"base64\",\"content\":\"bmV3\"}");

        var content = reader.fileContent(access, repository, 7, "new.txt", BASE, HEAD, false);

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


    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void outdatedThreadsKeepOriginalCoordinatesWithoutAnchoringToCurrentCode(boolean changedHead)
    {
        String oldHead = changedHead ? "c".repeat(40) : HEAD;
        String oldStart = changedHead ? BASE : "c".repeat(40);
        discussionData("""
                [{"id":"thread","individual_note":false,"notes":[{"id":10,"body":"Old comment","created_at":"2026-01-01T00:00:00Z",
                "author":{"username":"reviewer"},"position":{"new_path":"file.txt","new_line":3,"head_sha":"%s","start_sha":"%s"}}]}]
                """.formatted(oldHead, oldStart));
        responses.put(PREFIX + "/notes/10/award_emoji?per_page=100&page=1", "[]");

        var thread = reader.discussion(access, repository, 7, existing()).timeline().getFirst();

        assertThat(thread.payload().outdated()).isTrue();
        assertThat(thread.payload().line()).isNull();
        assertThat(thread.payload().originalLine()).isEqualTo(3);
        assertThat(thread.payload().commitSha()).isEqualTo(oldHead);
    }

    @Test
    void overviewDiscussionKeepsRepliesAndResolutionWithoutDiffCoordinates()
    {
        discussionData("""
                [{"id":"thread","individual_note":false,"notes":[
                {"id":10,"body":"Question","resolved":true,"author":{"username":"reviewer"},"created_at":"2026-01-01T00:00:00Z"},
                {"id":11,"body":"Answer","resolved":true,"author":{"username":"author"},"created_at":"2026-01-01T00:01:00Z"}]}]
                """);
        responses.put(PREFIX + "/notes/10/award_emoji?per_page=100&page=1", "[]");
        responses.put(PREFIX + "/notes/11/award_emoji?per_page=100&page=1", "[]");

        assertThat(reader.discussion(access, repository, 7, existing()).timeline()).singleElement().satisfies(thread -> {
            assertThat(thread.event()).isEqualTo("review_thread");
            assertThat(thread.payload().resolved()).isTrue();
            assertThat(thread.payload().path()).isNull();
            assertThat(thread.payload().comments()).hasSize(2);
            assertThat(thread.payload().comments().getLast().inReplyToId()).isEqualTo(10);
        });
    }

    @Test
    void optionalJobsAndBridgesDoNotBlockOrFailThePipeline()
    {
        responses.put(PREFIX, "{\"sha\":\"" + HEAD + "\",\"head_pipeline\":{\"id\":9,\"project_id\":42,\"status\":\"success\"}}");
        responses.put("/gitlab/api/v4/projects/42/pipelines/9/jobs?per_page=100&page=1", """
                [{"id":1,"name":"optional manual","status":"manual","allow_failure":true},
                {"id":2,"name":"optional failure","status":"failed","allow_failure":true},
                {"id":3,"name":"required manual","status":"manual","allow_failure":false}]
                """);
        responses.put("/gitlab/api/v4/projects/42/pipelines/9/bridges?per_page=100&page=1", """
                [{"id":4,"name":"optional downstream","status":"failed","allow_failure":true}]
                """);

        var checks = reader.checks(access, repository, 7, HEAD, ImmutableList.of(), Optional.empty(), Optional.empty()).checkRuns();

        assertThat(checks).extracting(check -> check.conclusion()).containsExactly("skipped", "neutral", null, "neutral");
        assertThat(checks).extracting(check -> check.status()).containsExactly("completed", "completed", "queued", "completed");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fullDiffUsesMergeBaseWhileExplicitRangeKeepsItsEndpoints(boolean fullDiff)
    {
        String mergeBase = "c".repeat(40);
        String from = fullDiff ? mergeBase : BASE;
        responses.put(PREFIX, """
                {"sha":"%s","diff_refs":{"base_sha":"%s","start_sha":"%s","head_sha":"%s"}}
                """.formatted(HEAD, mergeBase, BASE, HEAD));
        responses.put("/gitlab/api/v4/projects/42/repository/compare?from=" + from + "&to=" + HEAD + "&straight=true",
                "{\"diffs\":[{\"new_path\":\"file.txt\",\"old_path\":\"file.txt\"}]}");
        responses.put("/gitlab/api/v4/projects/42/repository/files/file.txt?ref=" + from, "{\"encoding\":\"base64\",\"content\":\"b2xk\"}");
        responses.put("/gitlab/api/v4/projects/42/repository/files/file.txt?ref=" + HEAD, "{\"encoding\":\"base64\",\"content\":\"bmV3\"}");

        var content = reader.fileContent(access, repository, 7, "file.txt", BASE, HEAD, fullDiff);

        assertThat(content.oldContent()).isEqualTo("old");
        assertThat(content.newContent()).isEqualTo("new");
        assertThat(received).contains("/gitlab/api/v4/projects/42/repository/files/file.txt?ref=" + from);
    }

    @Test
    void changedDiffCannotPopulateFileContentForThePreviousRevision()
    {
        responses.put(PREFIX, """
                {"sha":"%s","diff_refs":{"base_sha":"%s","start_sha":"%s","head_sha":"%s"}}
                """.formatted(BASE, BASE, BASE, BASE));

        assertThatThrownBy(() -> reader.fileContent(access, repository, 7, "file.txt", BASE, HEAD, true))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(REVISION_CONFLICT));
        assertThat(received).containsExactly(PREFIX);
    }

    @Test
    void timedOutComparisonCannotTurnMissingRenameIntoAbsentContent()
    {
        responses.put(PREFIX, "{}");
        responses.put("/gitlab/api/v4/projects/42/repository/compare?from=" + BASE + "&to=" + HEAD + "&straight=true",
                "{\"compare_timeout\":true,\"diffs\":[]}");

        assertThatThrownBy(() -> reader.fileContent(access, repository, 7, "new.txt", BASE, HEAD, false))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(UNAVAILABLE));
        assertThat(received).noneMatch(path -> path.contains("/repository/files/"));
    }

    @Test
    void readsReactionsConcurrentlyWithAtMostFourRequestsInFlight()
            throws Exception
    {
        List<String> discussions = new ArrayList<>();
        for (int id = 1; id <= 8; id++) {
            discussions.add("""
                    {"id":"thread-%s","individual_note":true,"notes":[{"id":%s,"body":"Note",
                    "author":{"username":"reviewer"},"created_at":"2026-01-01T00:00:00Z"}]}
                    """.formatted(id, id));
            responses.put(PREFIX + "/notes/" + id + "/award_emoji?per_page=100&page=1", "[{\"name\":\"thumbsup\"}]");
        }
        discussionData("[" + String.join(",", discussions) + "]");
        CountDownLatch firstBatch = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        beforeResponse = path -> {
            if (path.contains("/notes/")) {
                firstBatch.countDown();
                try {
                    assertThat(release.await(10, SECONDS)).isTrue();
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
        };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = executor.submit(() -> reader.discussion(access, repository, 7, existing()));
            try {
                assertThat(firstBatch.await(5, SECONDS)).isTrue();
                assertThat(received.stream().filter(path -> path.contains("/notes/"))).hasSize(4);
            }
            finally {
                release.countDown();
            }
            assertThat(result.get(10, SECONDS).timeline()).hasSize(8).allSatisfy(event ->
                    assertThat(event.payload().reactions()).singleElement().satisfies(reaction -> assertThat(reaction.count()).isEqualTo(1)));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 429})
    void reactionFailuresKeepTheirAuthenticationRateLimitAndPartialDataSemantics(int status)
    {
        discussionData("""
                [{"id":"thread","individual_note":true,"notes":[{"id":10,"body":"Note",
                "author":{"username":"reviewer"},"created_at":"2026-01-01T00:00:00Z"}]}]
                """);
        statuses.put(PREFIX + "/notes/10/award_emoji?per_page=100&page=1", status);
        if (status == 403) {
            var detail = reader.discussion(access, repository, 7, existing());
            assertThat(detail.timeline()).hasSize(1);
            assertThat(detail.unavailableSections()).containsExactly("reactions");
        }
        else {
            assertThatThrownBy(() -> reader.discussion(access, repository, 7, existing())).isInstanceOfSatisfying(HostingFailure.class,
                    failure -> assertThat(failure.kind()).isEqualTo(status == 401 ? HostingFailure.Kind.AUTHENTICATION_REQUIRED : HostingFailure.Kind.RATE_LIMITED));
        }
    }

    private void discussionData(String discussions)
    {
        responses.put(PREFIX + "/discussions?per_page=100&page=1", discussions);
        responses.put(PREFIX + "/discussions?per_page=100&page=2", "[]");
        responses.put(PREFIX + "/approvals", "{\"approved_by\":[]}");
        responses.put(PREFIX + "/commits?per_page=100&page=1", "[]");
        responses.put(PREFIX + "/award_emoji?per_page=100&page=1", "[]");
    }

    private static StoredPrDetail existing()
    {
        PrRawDetail raw = new PrRawDetail("Title", "https://gitlab.example/group/repo/-/merge_requests/7", "open", "author", null, null, null, null,
                "Body", ImmutableList.of(), ImmutableMap.of(), ImmutableList.of(), null, false, null, null, 0, 0, 0, 0, ImmutableList.of(), HEAD, "feature", "main", BASE);
        return new StoredPrDetail(raw, ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), true);
    }
}

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
import com.bytequay.core.hosting.HostingServer;
import com.bytequay.core.hosting.PullRequestFetch;
import com.bytequay.core.hosting.RemoteHostingRepository;
import com.bytequay.integration.gitlab.GitLabIntegration;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static com.bytequay.core.domain.PrRawDetail.UNKNOWN_COUNT;
import static com.bytequay.core.hosting.HostingFailure.Kind.INVALID_RESPONSE;
import static com.bytequay.core.hosting.HostingProvider.GITLAB;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class TestGitLabPullRequestReader
{
    private static final String HEAD = "b".repeat(40);
    private static final String TARGET = "c".repeat(40);
    private static final String MERGE_BASE = "a".repeat(40);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AtomicReference<Reply> reply = new AtomicReference<>();
    private final AtomicReference<String> requestedPath = new AtomicReference<>();
    private final AtomicReference<String> requestToken = new AtomicReference<>();
    private final AtomicReference<String> requestValidator = new AtomicReference<>();
    private HttpServer server;
    private HttpClient http;
    private HostingAccess access;
    private HostingRepository repository;
    private GitLabPullRequestReader reader;
    private String base;

    @BeforeEach
    void setUp()
            throws IOException
    {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestedPath.set(exchange.getRequestURI().toASCIIString());
            requestToken.set(exchange.getRequestHeaders().getFirst("PRIVATE-TOKEN"));
            requestValidator.set(exchange.getRequestHeaders().getFirst("If-None-Match"));
            Reply result = reply.get();
            byte[] bytes = result.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            result.etag().ifPresent(value -> exchange.getResponseHeaders().set("ETag", value));
            exchange.getResponseHeaders().set("Retry-After", "17");
            exchange.getResponseHeaders().set("Location", "/redirected");
            exchange.sendResponseHeaders(result.status(), result.status() == 304 ? -1 : bytes.length);
            try (var output = exchange.getResponseBody()) {
                if (result.status() != 304) {
                    output.write(bytes);
                }
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/gitlab";
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        HostingInstance instance = new HostingInstance(1, GITLAB, URI.create(base), URI.create(base + "/api/v4"));
        access = new HostingAccess(instance, () -> "secret-token");
        repository = new HostingRepository(2, 1, "42", "group/subgroup/repo", URI.create(base + "/group/subgroup/repo"),
                URI.create(base + "/group/subgroup/repo.git"), Optional.empty(), Optional.of("main"));
        reader = new GitLabPullRequestReader(http, MAPPER);
        reply.set(new Reply(200, mr().toString(), Optional.of("\"fresh\"")));
    }

    @AfterEach
    void tearDown()
    {
        http.close();
        server.stop(0);
    }

    @Test
    void readsByTargetProjectIdAndIidUnderTheConfiguredApiPrefix()
    {
        PullRequestFetch result = GitLabIntegration.create(http, MAPPER).pullRequests().fetch(access, repository, 17, Optional.empty());

        assertThat(requestedPath.get()).isEqualTo("/gitlab/api/v4/projects/42/merge_requests/17");
        assertThat(requestToken.get()).isEqualTo("secret-token");
        assertThat(result.etag()).contains("\"fresh\"");
        assertThat(result.pullRequest()).hasValueSatisfying(summary -> {
            assertThat(summary.githubId()).isEqualTo(9001L);
            assertThat(summary.number()).isEqualTo(17);
            assertThat(summary.repo()).isEqualTo("group/subgroup/repo");
            assertThat(summary.state()).isEqualTo("open");
            assertThat(summary.requestedReviewers()).isEmpty();
            assertThat(summary.mergeable()).isNull();
            assertThat(summary.mergeableState()).isNull();
        });
        assertThat(result.detail()).hasValueSatisfying(detail -> {
            assertThat(detail.headRef()).isEqualTo("feature/nested");
            assertThat(detail.baseRef()).isEqualTo("main");
            assertThat(detail.headSha()).isEqualTo(HEAD);
            assertThat(detail.baseSha()).isEqualTo(TARGET).isNotEqualTo(MERGE_BASE);
            assertThat(detail.labels()).containsExactly("bug");
            assertThat(detail.assignees()).containsExactly("alice");
            assertThat(detail.additions()).isEqualTo(UNKNOWN_COUNT);
            assertThat(detail.deletions()).isEqualTo(UNKNOWN_COUNT);
            assertThat(detail.changedFiles()).isEqualTo(9);
            assertThat(detail.requestedReviewerCount()).isEqualTo(UNKNOWN_COUNT);
        });
    }

    @Test
    void preservesConditionalReadSemantics()
    {
        reply.set(new Reply(304, "", Optional.empty()));

        PullRequestFetch result = reader.fetch(access, repository, 17, Optional.of("\"old\""));

        assertThat(requestValidator.get()).isEqualTo("\"old\"");
        assertThat(result.pullRequest()).isEmpty();
        assertThat(result.detail()).isEmpty();
        assertThat(result.etag()).contains("\"old\"");
    }

    @Test
    void doesNotReuseAnOldValidatorForAChangedResponse()
    {
        reply.set(new Reply(200, mr().toString(), Optional.empty()));

        assertThat(reader.fetch(access, repository, 17, Optional.of("\"old\"")).etag()).isEmpty();
    }

    @Test
    void rejectsAnUnsolicitedNotModifiedResponse()
    {
        reply.set(new Reply(304, "", Optional.empty()));

        assertInvalidResponse();
    }

    @ParameterizedTest
    @CsvSource({"401,AUTHENTICATION_REQUIRED", "403,FORBIDDEN", "404,MISSING_OR_INACCESSIBLE", "408,UNAVAILABLE",
            "409,REVISION_CONFLICT", "429,RATE_LIMITED", "503,UNAVAILABLE", "302,INVALID_RESPONSE", "206,INVALID_RESPONSE"})
    void reportsSafeTypedFailuresWithoutForwardingRedirects(int status, HostingFailure.Kind kind)
    {
        reply.set(new Reply(status, "{\"message\":\"secret-token must not leak\"}", Optional.empty()));

        assertThatThrownBy(() -> reader.fetch(access, repository, 17, Optional.empty()))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(kind);
                    assertThat(failure.getMessage()).doesNotContain("secret-token");
                    assertThat(failure.getCause()).isNull();
                    if (status == 429) {
                        assertThat(failure.retryAfter()).contains(Duration.ofSeconds(17));
                    }
                });
        assertThat(requestedPath.get()).isEqualTo("/gitlab/api/v4/projects/42/merge_requests/17");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "[]", "{} {}", "{}"})
    void rejectsTruncatedOrInvalidPayloads(String body)
    {
        reply.set(new Reply(200, body, Optional.empty()));

        assertInvalidResponse();
    }

    @Test
    void rejectsASecondJsonValueAfterValidMetadata()
    {
        reply.set(new Reply(200, mr() + " {}", Optional.empty()));

        assertInvalidResponse();
    }

    @Test
    void boundsResponseSize()
    {
        reply.set(new Reply(200, "{\"oversized\":\"" + "x".repeat(2 * 1024 * 1024) + "\"}", Optional.empty()));

        assertInvalidResponse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "iid", "project_id", "title", "state", "draft", "labels", "assignees", "created_at", "updated_at"})
    void doesNotFabricateMissingRequiredFacts(String field)
    {
        ObjectNode body = mr();
        body.remove(field);
        reply.set(new Reply(200, body.toString(), Optional.empty()));

        assertInvalidResponse();
    }

    @ParameterizedTest
    @CsvSource({"iid,18", "project_id,99", "target_project_id,99"})
    void rejectsAResponseForAnotherRequest(String field, int value)
    {
        ObjectNode body = mr().put(field, value);
        reply.set(new Reply(200, body.toString(), Optional.empty()));

        assertInvalidResponse();
    }

    @Test
    void toleratesNewFieldsButRejectsAnUnknownLifecycleState()
    {
        ObjectNode body = mr().put("new_server_field", "new");
        ((ObjectNode) body.get("author")).put("new_user_field", 7);
        ((ObjectNode) body.get("diff_refs")).put("new_diff_field", true);
        reply.set(new Reply(200, body.toString(), Optional.empty()));
        assertThat(reader.fetch(access, repository, 17, Optional.empty()).detail()).isPresent();
        body.put("state", "future_state");
        reply.set(new Reply(200, body.toString(), Optional.empty()));

        assertInvalidResponse();
    }

    @ParameterizedTest
    @MethodSource("malformedFields")
    void rejectsScalarCoercionAndMalformedNestedValues(String field, String value)
            throws IOException
    {
        ObjectNode body = mr();
        body.set(field, MAPPER.readTree(value));
        reply.set(new Reply(200, body.toString(), Optional.empty()));

        assertInvalidResponse();
    }

    @Test
    void permitsAnEmptyDescription()
    {
        reply.set(new Reply(200, mr().put("description", "").toString(), Optional.empty()));

        assertThat(reader.fetch(access, repository, 17, Optional.empty()).detail().orElseThrow().body()).isEmpty();
    }

    @Test
    void usesTheLegacyDraftFlagOnlyWhenDraftIsAbsent()
    {
        ObjectNode body = mr().put("work_in_progress", false);
        body.remove("draft");
        reply.set(new Reply(200, body.toString(), Optional.empty()));
        assertThat(reader.fetch(access, repository, 17, Optional.empty()).detail().orElseThrow().draft()).isFalse();

        body.putNull("draft");
        reply.set(new Reply(200, body.toString(), Optional.empty()));
        assertInvalidResponse();
    }

    @Test
    void doesNotChangeTheSharedMapperCoercionConfiguration()
            throws IOException
    {
        ObjectMapper shared = new ObjectMapper();
        assertThat(shared.readValue("\"42\"", Long.class)).isEqualTo(42L);
        new GitLabHttpClient(http, shared);

        assertThat(shared.readValue("\"42\"", Long.class)).isEqualTo(42L);
        assertThat(shared.readValue("42", String.class)).isEqualTo("42");
    }

    @ParameterizedTest
    @ValueSource(strings = {"description", "author", "milestone", "diff_refs", "sha", "changes_count"})
    void rejectsEmptyArraysEvenWhenTheCallerMapperTreatsThemAsNull(String field)
            throws IOException
    {
        ObjectMapper shared = new ObjectMapper().enable(DeserializationFeature.ACCEPT_EMPTY_ARRAY_AS_NULL_OBJECT);
        assertThat(shared.readValue("[]", GitLabMergeRequestResponse.User.class)).isNull();
        ObjectNode body = mr();
        body.set(field, MAPPER.createArrayNode());
        reply.set(new Reply(200, body.toString(), Optional.empty()));
        GitLabPullRequestReader strict = new GitLabPullRequestReader(http, shared);

        assertThatThrownBy(() -> strict.fetch(access, repository, 17, Optional.empty()))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(INVALID_RESPONSE));
        assertThat(shared.isEnabled(DeserializationFeature.ACCEPT_EMPTY_ARRAY_AS_NULL_OBJECT)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"opened,open", "locked,open", "closed,closed", "merged,merged"})
    void mapsKnownLifecycleStates(String remote, String normalized)
    {
        reply.set(new Reply(200, mr().put("state", remote).toString(), Optional.empty()));

        assertThat(reader.fetch(access, repository, 17, Optional.empty()).detail().orElseThrow().state()).isEqualTo(normalized);
    }

    @ParameterizedTest
    @ValueSource(strings = {"checking", "mergeable", "not_approved", "future_status"})
    void doesNotInventGitHubMergeabilityFromGitLabStatus(String status)
    {
        reply.set(new Reply(200, mr().put("detailed_merge_status", status).toString(), Optional.empty()));

        assertThat(reader.fetch(access, repository, 17, Optional.empty()).detail()).hasValueSatisfying(detail -> {
            assertThat(detail.mergeable()).isNull();
            assertThat(detail.mergeableState()).isNull();
        });
    }

    @Test
    void reportsDefiniteMergeConflicts()
    {
        reply.set(new Reply(200, mr().put("detailed_merge_status", "conflict").toString(), Optional.empty()));

        assertThat(reader.fetch(access, repository, 17, Optional.empty()).detail().orElseThrow().mergeable()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1000+", "999999999999999999999999"})
    void keepsMissingOrTruncatedCountsUnknown(String changes)
    {
        reply.set(new Reply(200, mr().put("changes_count", changes).toString(), Optional.empty()));

        assertThat(reader.fetch(access, repository, 17, Optional.empty()).detail().orElseThrow().changedFiles()).isEqualTo(UNKNOWN_COUNT);
    }

    @Test
    void keepsMissingBranchesAndUnpreparedDiffCoordinatesUnknown()
    {
        ObjectNode body = mr();
        body.remove("source_branch");
        body.remove("target_branch");
        body.remove("sha");
        body.putNull("diff_refs");
        body.putNull("changes_count");
        reply.set(new Reply(200, body.toString(), Optional.empty()));

        assertThat(reader.fetch(access, repository, 17, Optional.empty()).detail()).hasValueSatisfying(detail -> {
            assertThat(detail.headRef()).isNull();
            assertThat(detail.baseRef()).isNull();
            assertThat(detail.headSha()).isNull();
            assertThat(detail.baseSha()).isNull();
            assertThat(detail.changedFiles()).isEqualTo(UNKNOWN_COUNT);
        });
    }

    @Test
    void doesNotCombineAnOldDiffWithTheCurrentHead()
    {
        ObjectNode body = mr().put("sha", "d".repeat(40));
        reply.set(new Reply(200, body.toString(), Optional.empty()));

        assertThat(reader.fetch(access, repository, 17, Optional.empty()).detail()).hasValueSatisfying(detail -> {
            assertThat(detail.headSha()).isEqualTo("d".repeat(40));
            assertThat(detail.baseSha()).isNull();
            assertThat(detail.changedFiles()).isEqualTo(UNKNOWN_COUNT);
        });
    }

    @Test
    void rejectsCrossInstanceRepositoryBeforeSendingCredentials()
    {
        HostingRepository other = new HostingRepository(3, 2, "42", repository.fullPath(), repository.webUrl(), repository.httpCloneUrl(),
                Optional.empty(), Optional.empty());

        assertThatThrownBy(() -> reader.fetch(access, other, 17, Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        assertThat(requestedPath.get()).isNull();
    }

    @Test
    void boundsTheWholeResponseWhenTheServerStallsAfterHeaders()
    {
        CountDownLatch headers = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        stalledBody(headers, release);
        GitLabHttpClient bounded = new GitLabHttpClient(http, MAPPER, Duration.ofMillis(500));
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                    assertThatThrownBy(() -> bounded.get(access, "stalled", Optional.empty(), GitLabAccountResponse.class))
                            .isInstanceOfSatisfying(HostingFailure.class, failure -> {
                                assertThat(failure.kind()).isEqualTo(HostingFailure.Kind.UNAVAILABLE);
                                assertThat(failure.getMessage()).contains("timed out");
                                assertThat(failure.getCause()).isNull();
                            }));
            assertThat(headers.getCount()).isZero();
        }
        finally {
            release.countDown();
        }
    }

    @Test
    void cancelsTheExchangeAndPreservesInterruption()
            throws InterruptedException
    {
        CountDownLatch headers = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        stalledBody(headers, release);
        AtomicReference<HostingFailure> observed = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread thread = Thread.ofVirtual().start(() -> {
            try {
                new GitLabHttpClient(http, MAPPER).get(access, "stalled", Optional.empty(), GitLabAccountResponse.class);
            }
            catch (HostingFailure failure) {
                observed.set(failure);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        try {
            assertThat(headers.await(5, SECONDS)).isTrue();
            thread.interrupt();
            thread.join(3000);
            assertThat(thread.isAlive()).isFalse();
            assertThat(observed.get()).isNotNull();
            assertThat(observed.get().kind()).isEqualTo(HostingFailure.Kind.UNAVAILABLE);
            assertThat(interrupted.get()).isTrue();
        }
        finally {
            release.countDown();
            thread.interrupt();
        }
    }

    @Test
    void verifiesAnAccountBeforeItsServerHasALocalIdentity()
    {
        HostingAccess unpersisted = new HostingAccess(new HostingServer(GITLAB, URI.create(base), URI.create(base + "/api/v4")), () -> "secret-token");
        reply.set(new Reply(200, "{\"id\":123,\"username\":\"alice\",\"new_account_field\":true}", Optional.empty()));

        assertThat(new GitLabConnectionClient(http, MAPPER).verify(unpersisted).login()).isEqualTo("alice");
        assertThat(requestedPath.get()).isEqualTo("/gitlab/api/v4/user");
    }

    @ParameterizedTest
    @ValueSource(strings = {"secret-token\r\nInjected: true", "secret-token\n", "secret-token\t", "secret-token ", "secret-tokené"})
    void rejectsUnsafeTokensWithoutExposingThem(String token)
    {
        HostingAccess invalid = new HostingAccess(new HostingInstance(1, GITLAB, URI.create(base), URI.create(base + "/api/v4")), () -> token);

        assertThatThrownBy(() -> reader.fetch(invalid, repository, 17, Optional.empty()))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(HostingFailure.Kind.AUTHENTICATION_REQUIRED);
                    assertThat(failure.toString()).doesNotContain("secret-token", token);
                    assertThat(failure.getCause()).isNull();
                    assertThat(failure.getSuppressed()).isEmpty();
                });
        assertThat(requestedPath.get()).isNull();
    }

    @Test
    void connectionLookupEncodesNestedPaths()
    {
        reply.set(new Reply(200, """
                {"id":42,"path_with_namespace":"group/subgroup/repo","web_url":"%s/group/subgroup/repo",
                 "http_url_to_repo":"%s/group/subgroup/repo.git","default_branch":"main","new_project_field":true}
                """.formatted(base, base), Optional.empty()));

        assertThat(new GitLabConnectionClient(http, MAPPER).findByPath(access, "group/subgroup/repo").remoteId()).isEqualTo("42");
        assertThat(requestedPath.get()).isEqualTo("/gitlab/api/v4/projects/group%2Fsubgroup%2Frepo");
    }

    @Test
    void resolvesARenamedProjectByIdUnderTheConfiguredPrefix()
    {
        reply.set(new Reply(200, """
                {"id":42,"path_with_namespace":"new/group/project","web_url":"%s/new/group/project",
                 "http_url_to_repo":"%s/new/group/project.git"}
                """.formatted(base, base), Optional.empty()));

        RemoteHostingRepository found = new GitLabConnectionClient(http, MAPPER).findById(access, "42");

        assertThat(found.remoteId()).isEqualTo("42");
        assertThat(found.fullPath()).isEqualTo("new/group/project");
        assertThat(found.webUrl()).isEqualTo(URI.create(base + "/new/group/project"));
        assertThat(requestedPath.get()).isEqualTo("/gitlab/api/v4/projects/42");
        assertThat(requestToken.get()).isEqualTo("secret-token");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0", "-1", "+42", "042", "42.0", "42/other", "42?query", "9223372036854775808"})
    void rejectsInvalidProjectIdsBeforeSendingCredentials(String remoteId)
    {
        assertThatThrownBy(() -> new GitLabConnectionClient(http, MAPPER).findById(access, remoteId)).isInstanceOf(IllegalArgumentException.class);
        assertThat(requestedPath.get()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"43,group/subgroup/repo,group/subgroup/repo,group/subgroup/repo", "42,other/project,group/subgroup/repo,group/subgroup/repo",
            "42,group/subgroup/repo,other/project,group/subgroup/repo", "42,group/subgroup/repo,group/subgroup/repo,other/project"})
    void rejectsProjectIdentityAndCoordinateMismatches(int id, String path, String webPath, String clonePath)
    {
        reply.set(new Reply(200, """
                {"id":%d,"path_with_namespace":"%s","web_url":"%s/%s","http_url_to_repo":"%s/%s.git"}
                """.formatted(id, path, base, webPath, base, clonePath), Optional.empty()));

        assertThatThrownBy(() -> new GitLabConnectionClient(http, MAPPER).findById(access, "42"))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(INVALID_RESPONSE)).hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://other.example/gitlab", "http://127.0.0.1:%d/outside"})
    void rejectsProjectUrlsOutsideTheConfiguredInstance(String otherBase)
    {
        reply.set(new Reply(200, """
                {"id":42,"path_with_namespace":"group/subgroup/repo","web_url":"%s/group/subgroup/repo",
                 "http_url_to_repo":"%s/group/subgroup/repo.git"}
                """.formatted(otherBase.formatted(server.getAddress().getPort()), base), Optional.empty()));

        assertThatThrownBy(() -> new GitLabConnectionClient(http, MAPPER).findById(access, "42"))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(INVALID_RESPONSE)).hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"id\":1.5,\"username\":\"alice\"}",
            "{\"id\":\"123\",\"username\":\"alice\"}",
            "{\"id\":123,\"username\":false}",
            "{\"id\":123,\"username\":42}",
            "{\"id\":123}",
            "{\"username\":\"alice\"}"})
    void validatesTypedAccountResponses(String body)
    {
        reply.set(new Reply(200, body, Optional.empty()));

        assertThatThrownBy(() -> new GitLabConnectionClient(http, MAPPER).verify(access))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(INVALID_RESPONSE));
    }

    @Test
    void validatesTypedRepositoryResponsesWithoutCoercingText()
    {
        reply.set(new Reply(200, """
                {"id":42,"path_with_namespace":"group/subgroup/repo","web_url":"%s/group/subgroup/repo",
                 "http_url_to_repo":"%s/group/subgroup/repo.git","default_branch":false}
                """.formatted(base, base), Optional.empty()));

        assertThatThrownBy(() -> new GitLabConnectionClient(http, MAPPER).findByPath(access, "group/subgroup/repo"))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(INVALID_RESPONSE));
    }

    private static Stream<Arguments> malformedFields()
    {
        return Stream.of(
                Arguments.of("id", "9001.5"),
                Arguments.of("id", "\"9001\""),
                Arguments.of("id", "true"),
                Arguments.of("iid", "17.25"),
                Arguments.of("project_id", "\"42\""),
                Arguments.of("title", "42"),
                Arguments.of("title", "true"),
                Arguments.of("state", "42"),
                Arguments.of("draft", "\"false\""),
                Arguments.of("draft", "0"),
                Arguments.of("draft", "null"),
                Arguments.of("created_at", "1700000000"),
                Arguments.of("description", "42"),
                Arguments.of("changes_count", "9"),
                Arguments.of("labels", "[1]"),
                Arguments.of("labels", "[null]"),
                Arguments.of("labels", "[\"\"]"),
                Arguments.of("labels", "\"bug\""),
                Arguments.of("assignees", "[null]"),
                Arguments.of("assignees", "[{}]"),
                Arguments.of("assignees", "[{\"username\":7}]"),
                Arguments.of("author", "{\"username\":true}"),
                Arguments.of("milestone", "{\"title\":42}"),
                Arguments.of("source_branch", "42"),
                Arguments.of("sha", "false"),
                Arguments.of("diff_refs", "{\"head_sha\":42}"));
    }

    private void assertInvalidResponse()
    {
        assertThatThrownBy(() -> reader.fetch(access, repository, 17, Optional.empty()))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(INVALID_RESPONSE));
    }

    private void stalledBody(CountDownLatch headers, CountDownLatch release)
    {
        server.createContext("/gitlab/api/v4/stalled", exchange -> {
            exchange.sendResponseHeaders(200, 1024);
            try (var output = exchange.getResponseBody()) {
                output.write('{');
                output.flush();
                headers.countDown();
                try {
                    release.await(5, SECONDS);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    private ObjectNode mr()
    {
        ObjectNode mr = MAPPER.createObjectNode().put("id", 9001).put("iid", 17).put("project_id", 42).put("target_project_id", 42)
                .put("source_project_id", 8).put("title", "Support nested projects").put("state", "opened").put("draft", false)
                .put("web_url", base + "/group/subgroup/repo/-/merge_requests/17").put("created_at", "2026-10-01T10:00:00Z")
                .put("updated_at", "2026-10-02T10:00:00Z").put("description", "Keep **Markdown** unchanged")
                .put("source_branch", "feature/nested").put("target_branch", "main").put("sha", HEAD)
                .put("changes_count", "9").put("detailed_merge_status", "mergeable");
        mr.putObject("author").put("username", "alice");
        mr.putArray("labels").add("bug");
        mr.putArray("assignees").addObject().put("username", "alice");
        mr.putArray("reviewers").addObject().put("username", "already-reviewed");
        mr.putObject("diff_refs").put("base_sha", MERGE_BASE).put("head_sha", HEAD).put("start_sha", TARGET);
        return mr;
    }

    private record Reply(int status, String body, Optional<String> etag) {}
}

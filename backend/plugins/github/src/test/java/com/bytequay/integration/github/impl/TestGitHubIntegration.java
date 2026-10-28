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
package com.bytequay.integration.github.impl;

import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingIntegration;
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.PullRequestFetch;
import com.bytequay.core.hosting.RemoteHostingRepository;
import com.bytequay.integration.github.GitHubIntegration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.core.domain.PrRawDetail.UNKNOWN_COUNT;
import static com.bytequay.core.hosting.HostingFailure.Kind.AUTHENTICATION_REQUIRED;
import static com.bytequay.core.hosting.HostingFailure.Kind.INVALID_RESPONSE;
import static com.bytequay.core.hosting.HostingFailure.Kind.RATE_LIMITED;
import static com.bytequay.core.hosting.HostingFailure.Kind.UNAVAILABLE;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestGitHubIntegration
{
    private static final String METADATA_PATH = "/api/v3/repos/bytequay/bytequay/pulls/7";
    private static final String REPOSITORY_RESPONSE = """
            {"id":42,"full_name":"bytequay/bytequay","html_url":"https://github.example/bytequay/bytequay",
            "clone_url":"https://github.example/bytequay/bytequay.git","ssh_url":"git@github.example:bytequay/bytequay.git","default_branch":"main"}
            """;
    private static final String PULL_REQUEST = """
            {
              "id": 1007, "number": 7, "title": "Preserve request context", "html_url": "https://github.example/bytequay/bytequay/pull/7",
              "state": "open", "user": {"login": "alice", "avatar_url": "https://avatars.example/alice", "node_id": "ignored"},
              "created_at": "2026-10-01T10:00:00Z", "updated_at": "2026-10-02T10:00:00Z", "body": "Details", "draft": true,
              "labels": [{"name": "bug", "color": "ff0000", "id": 1}], "assignees": [{"login": "bob"}],
              "milestone": {"title": "Release", "number": 1}, "additions": 12, "deletions": 3, "changed_files": 2,
              "requested_reviewers": [{"login": "charlie"}], "requested_teams": [{"slug": "maintainers", "id": 2}],
              "head": {"sha": "head-sha", "ref": "feature", "repo": {}}, "base": {"sha": "base-sha", "ref": "main", "repo": {"id": 42}},
              "mergeable": null, "mergeable_state": "unknown", "node_id": "ignored"
            }
            """;

    private final AtomicReference<Request> request = new AtomicReference<>();
    private HttpServer server;
    private HttpClient http;
    private HostingIntegration integration;
    private HostingAccess access;
    private HostingRepository repository;

    @BeforeEach
    void setUp()
            throws IOException
    {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        integration = GitHubIntegration.create(http, new ObjectMapper().findAndRegisterModules());
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        URI webBase = URI.create("https://github.example");
        access = new HostingAccess(new HostingInstance(1, HostingProvider.GITHUB, webBase, base.resolve("/api/v3")), () -> "private-token");
        repository = new HostingRepository(2, 1, "42", "bytequay/bytequay", webBase.resolve("/bytequay/bytequay"),
                webBase.resolve("/bytequay/bytequay.git"), Optional.empty(), Optional.of("main"));
    }

    @AfterEach
    void tearDown()
    {
        server.stop(0);
        http.close();
    }

    @Test
    void readsAccountAndRepositoryThroughTheConfiguredApiBase()
    {
        serve("/api/v3/user", 200, "{\"id\":123,\"login\":\"alice\"}", ImmutableMap.of());
        serve("/api/v3/repos/bytequay/bytequay", 200, REPOSITORY_RESPONSE, ImmutableMap.of());

        assertThat(integration.accounts().verify(access).remoteId()).isEqualTo("123");
        assertThat(request.get().path()).isEqualTo("/api/v3/user");
        assertThat(request.get().authorization()).isEqualTo("Bearer private-token");
        RemoteHostingRepository found = integration.repositories().findByPath(access, "bytequay/bytequay");
        assertThat(found.remoteId()).isEqualTo("42");
        assertThat(found.fullPath()).isEqualTo("bytequay/bytequay");
        assertThat(found.defaultBranch()).contains("main");
        assertThat(request.get().path()).isEqualTo("/api/v3/repos/bytequay/bytequay");
    }

    @Test
    void resolvesARenamedRepositoryByIdUnderTheConfiguredApiPrefix()
    {
        serve("/api/v3/repositories/42", 200, REPOSITORY_RESPONSE.replace("bytequay/bytequay", "new-owner/new-name"), ImmutableMap.of());

        RemoteHostingRepository found = integration.repositories().findById(access, "42");

        assertThat(found.remoteId()).isEqualTo("42");
        assertThat(found.fullPath()).isEqualTo("new-owner/new-name");
        assertThat(found.webUrl()).isEqualTo(URI.create("https://github.example/new-owner/new-name"));
        assertThat(request.get().path()).isEqualTo("/api/v3/repositories/42");
        assertThat(request.get().authorization()).isEqualTo("Bearer private-token");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0", "-1", "+42", "042", "42.0", "42/other", "42?query", "9223372036854775808"})
    void rejectsInvalidRepositoryIdsBeforeSendingCredentials(String remoteId)
    {
        assertThatThrownBy(() -> integration.repositories().findById(access, remoteId)).isInstanceOf(IllegalArgumentException.class);
        assertThat(request.get()).isNull();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "\"id\":42|\"id\":43",
            "\"full_name\":\"bytequay/bytequay\"|\"full_name\":\"other/repository\"",
            "https://github.example/bytequay/bytequay\"|https://other.example/bytequay/bytequay\"",
            "https://github.example/bytequay/bytequay.git|https://other.example/bytequay/bytequay.git",
            "https://github.example/bytequay/bytequay.git|https://github.example/other/repository.git"
    })
    void rejectsRepositoryIdentityAndCoordinateMismatches(String original, String replacement)
    {
        serve("/api/v3/repositories/42", 200, REPOSITORY_RESPONSE.replace(original, replacement), ImmutableMap.of());

        assertThatThrownBy(() -> integration.repositories().findById(access, "42"))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(INVALID_RESPONSE)).hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "null", "[]", "42", "\"sensitive-response-value\"", "[{\"id\":123,\"login\":\"alice\"}]",
            "{\"id\":123,\"login\":\"alice\"} {}", "{\"id\":\"123\",\"login\":\"alice\"}",
            "{\"id\":123.5,\"login\":\"alice\"}", "{\"id\":true,\"login\":\"alice\"}",
            "{\"id\":123,\"login\":123}", "{\"id\":123,\"login\":false}", "{\"id\":123,\"login\":[\"sensitive-response-value\"]}",
            "{\"id\":123,\"login\":{}}", "{\"id\":123,\"login\":null}", "{\"id\":123,\"login\":\"\"}", "{\"login\":\"alice\"}"
    })
    void rejectsMalformedAccountBindingWithoutLeakingPayloads(String body)
    {
        serve("/api/v3/user", 200, body, ImmutableMap.of());

        assertThatThrownBy(() -> integration.accounts().verify(access))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(INVALID_RESPONSE))
                .hasMessageNotContaining("sensitive-response-value").hasMessageNotContaining("private-token").hasNoCause();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "\"id\":42|\"id\":\"42\"", "\"id\":42|\"id\":42.5", "\"id\":42|\"id\":true", "\"id\":42|\"id\":null",
            "\"full_name\":\"bytequay/bytequay\"|\"full_name\":42", "\"html_url\":\"https://github.example/bytequay/bytequay\"|\"html_url\":false",
            "\"ssh_url\":\"git@github.example:bytequay/bytequay.git\"|\"ssh_url\":42", "\"default_branch\":\"main\"|\"default_branch\":[]"
    })
    void rejectsMalformedRepositoryScalarTypes(String original, String replacement)
    {
        serve("/api/v3/repos/bytequay/bytequay", 200, REPOSITORY_RESPONSE.replace(original, replacement), ImmutableMap.of());

        assertThatThrownBy(() -> integration.repositories().findByPath(access, "bytequay/bytequay"))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(INVALID_RESPONSE)).hasNoCause();
    }

    @Test
    void preservesUnknownFieldsAndAbsentOptionalRepositoryCoordinates()
    {
        serve("/api/v3/user", 200, "{\"id\":123,\"login\":\"alice\",\"future_metadata\":{\"enabled\":true}}", ImmutableMap.of());
        serve("/api/v3/repos/bytequay/bytequay", 200, REPOSITORY_RESPONSE
                .replace("\"ssh_url\":\"git@github.example:bytequay/bytequay.git\"", "\"ssh_url\":null")
                .replace("\"default_branch\":\"main\"", "\"default_branch\":\"\""), ImmutableMap.of());

        assertThat(integration.accounts().verify(access).login()).isEqualTo("alice");
        RemoteHostingRepository found = integration.repositories().findByPath(access, "bytequay/bytequay");
        assertThat(found.sshCloneUrl()).isEmpty();
        assertThat(found.defaultBranch()).isEmpty();
    }

    @Test
    void configuringThePluginDoesNotChangeTheSharedMapper()
            throws IOException
    {
        ObjectMapper shared = new ObjectMapper().findAndRegisterModules();
        HostingIntegration strict = GitHubIntegration.create(http, shared);
        serve("/api/v3/user", 200, "{\"id\":\"123\",\"login\":\"alice\"}", ImmutableMap.of());

        assertThatThrownBy(() -> strict.accounts().verify(access))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(INVALID_RESPONSE));
        assertThat(shared.readValue("\"123\"", Long.class)).isEqualTo(123L);
        assertThat(shared.readValue("123", String.class)).isEqualTo("123");
    }

    @Test
    void normalizesMetadataAndDetailFromOneConditionalRead()
    {
        serve(METADATA_PATH, 200, PULL_REQUEST, ImmutableMap.of("ETag", "\"v2\""));
        PullRequestFetch fetched = integration.pullRequests().fetch(access, repository, 7, Optional.of("\"v1\""));

        assertThat(request.get().validator()).contains("\"v1\"");
        assertThat(request.get().authorization()).isEqualTo("Bearer private-token");
        assertThat(fetched.etag()).contains("\"v2\"");
        assertThat(fetched.pullRequest()).hasValueSatisfying(value -> {
            assertThat(value.githubId()).isEqualTo(1007);
            assertThat(value.number()).isEqualTo(7);
            assertThat(value.repo()).isEqualTo("bytequay/bytequay");
            assertThat(value.author()).isEqualTo("alice");
            assertThat(value.createdAt()).isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
            assertThat(value.updatedAt()).isEqualTo(Instant.parse("2026-10-02T10:00:00Z"));
            assertThat(value.labels()).containsExactly("bug");
            assertThat(value.labelColors()).containsEntry("bug", "ff0000");
            assertThat(value.requestedReviewers()).containsExactly("charlie");
            assertThat(value.draft()).isTrue();
            assertThat(value.mergeable()).isNull();
        });
        assertThat(fetched.detail()).hasValueSatisfying(value -> {
            assertThat(value.title()).isEqualTo("Preserve request context");
            assertThat(value.body()).isEqualTo("Details");
            assertThat(value.authorAvatarUrl()).isEqualTo("https://avatars.example/alice");
            assertThat(value.assignees()).containsExactly("bob");
            assertThat(value.milestone()).isEqualTo("Release");
            assertThat(value.requestedReviewers()).containsExactly("charlie", "bytequay/maintainers");
            assertThat(value.requestedReviewerCount()).isEqualTo(2);
            assertThat(value.headSha()).isEqualTo("head-sha");
            assertThat(value.headRef()).isEqualTo("feature");
            assertThat(value.baseSha()).isEqualTo("base-sha");
            assertThat(value.baseRef()).isEqualTo("main");
            assertThat(value.additions()).isEqualTo(12);
            assertThat(value.deletions()).isEqualTo(3);
            assertThat(value.changedFiles()).isEqualTo(2);
        });
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "\"id\": 1007|\"id\": \"1007\"", "\"id\": 1007|\"id\": 1007.5", "\"id\": 1007|\"id\": true",
            "\"number\": 7|\"number\": 7.5", "\"id\": 42|\"id\": 42.5", "\"title\": \"Preserve request context\"|\"title\": 123",
            "\"draft\": true|\"draft\": null", "\"draft\": true|\"draft\": \"true\"", "\"draft\": true|\"draft\": 1",
            "\"created_at\": \"2026-10-01T10:00:00Z\"|\"created_at\": 1790848800",
            "\"additions\": 12|\"additions\": 12.5", "\"additions\": 12|\"additions\": \"12\"", "\"additions\": 12|\"additions\": true",
            "\"head\": {\"sha\": \"head-sha\", \"ref\": \"feature\", \"repo\": {}}|\"head\": []"
    })
    void rejectsMalformedPullRequestScalarTypes(String original, String replacement)
    {
        serve(METADATA_PATH, 200, PULL_REQUEST.replace(original, replacement), ImmutableMap.of());

        assertThatThrownBy(() -> integration.pullRequests().fetch(access, repository, 7, Optional.empty()))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(INVALID_RESPONSE)).hasNoCause();
    }

    @Test
    void rejectsTrailingPullRequestJson()
    {
        serve(METADATA_PATH, 200, PULL_REQUEST + " {}", ImmutableMap.of());

        assertThatThrownBy(() -> integration.pullRequests().fetch(access, repository, 7, Optional.empty()))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(INVALID_RESPONSE)).hasNoCause();
    }

    @Test
    void keepsAnExplicitlyEmptyDescription()
    {
        serve(METADATA_PATH, 200, PULL_REQUEST.replace("\"body\": \"Details\"", "\"body\": \"\""), ImmutableMap.of());

        assertThat(integration.pullRequests().fetch(access, repository, 7, Optional.empty()).detail())
                .hasValueSatisfying(value -> assertThat(value.body()).isEmpty());
    }

    @Test
    void keepsDeletedAuthorsAndMergedMetadata()
    {
        serve(METADATA_PATH, 200, """
                {"id":1007,"number":7,"title":"Merged","state":"closed","user":null,
                "draft":false,
                "html_url":"https://github.example/bytequay/bytequay/pull/7",
                "created_at":"2026-10-01T10:00:00Z","updated_at":"2026-10-02T10:00:00Z","base":{"repo":{"id":42}},
                "closed_at":"2026-10-02T10:00:00Z","merged_at":"2026-10-02T10:00:00Z"}
                """, ImmutableMap.of());
        PullRequestFetch fetched = integration.pullRequests().fetch(access, repository, 7, Optional.empty());
        assertThat(fetched.pullRequest()).hasValueSatisfying(value -> {
            assertThat(value.author()).isEqualTo(FetchedPullRequest.GHOST_AUTHOR);
            assertThat(value.state()).isEqualTo("merged");
            assertThat(value.closedAt()).isEqualTo(Instant.parse("2026-10-02T10:00:00Z"));
            assertThat(value.mergedAt()).isEqualTo(value.closedAt());
        });
        assertThat(fetched.detail()).hasValueSatisfying(value -> {
            assertThat(value.author()).isEqualTo(FetchedPullRequest.GHOST_AUTHOR);
            assertThat(value.state()).isEqualTo("merged");
            assertThat(value.requestedReviewers()).isEmpty();
        });
    }

    @Test
    void rejectsARepositoryPathReassignedToAnotherRemoteIdentity()
    {
        serve(METADATA_PATH, 200, PULL_REQUEST.replace("\"id\": 42", "\"id\": 43"), ImmutableMap.of());

        assertThatThrownBy(() -> integration.pullRequests().fetch(access, repository, 7, Optional.empty()))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(INVALID_RESPONSE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{\"id\":0}"})
    void rejectsMissingTargetRepositoryIdentity(String targetRepository)
    {
        serve(METADATA_PATH, 200, PULL_REQUEST.replace("{\"id\": 42}", targetRepository), ImmutableMap.of());

        assertThatThrownBy(() -> integration.pullRequests().fetch(access, repository, 7, Optional.empty()))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(INVALID_RESPONSE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "locked", "merged", ""})
    void rejectsUnknownNativeStates(String state)
    {
        serve(METADATA_PATH, 200, PULL_REQUEST.replace("\"state\": \"open\"", "\"state\": \"" + state + "\""), ImmutableMap.of());

        assertThatThrownBy(() -> integration.pullRequests().fetch(access, repository, 7, Optional.empty()))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(INVALID_RESPONSE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"title", "created_at", "updated_at", "html_url", "draft"})
    void rejectsMissingRequiredMetadata(String field)
    {
        serve(METADATA_PATH, 200, PULL_REQUEST.replace("\"" + field + "\":", "\"ignored\":"), ImmutableMap.of());

        assertThatThrownBy(() -> integration.pullRequests().fetch(access, repository, 7, Optional.empty()))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(INVALID_RESPONSE));
    }

    @Test
    void rejectsBlankTitlesAndMalformedTimestamps()
    {
        serve(METADATA_PATH, 200, PULL_REQUEST.replace("Preserve request context", " "), ImmutableMap.of());
        assertThatThrownBy(() -> integration.pullRequests().fetch(access, repository, 7, Optional.empty()))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(INVALID_RESPONSE));
        server.removeContext(METADATA_PATH);
        serve(METADATA_PATH, 200, PULL_REQUEST.replace("2026-10-01T10:00:00Z", "invalid-timestamp"), ImmutableMap.of());
        assertThatThrownBy(() -> integration.pullRequests().fetch(access, repository, 7, Optional.empty()))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(INVALID_RESPONSE));
    }

    @Test
    void rejectsPullRequestUrlsOutsideTheConfiguredWebOrigin()
    {
        serve(METADATA_PATH, 200, PULL_REQUEST.replace("https://github.example/", "https://other.example/"), ImmutableMap.of());

        assertThatThrownBy(() -> integration.pullRequests().fetch(access, repository, 7, Optional.empty()))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(INVALID_RESPONSE));
    }

    @Test
    void preservesUnknownCountsWithoutConvertingMissingDataToZero()
    {
        String payload = PULL_REQUEST.replace("\"additions\":", "\"ignored_additions\":")
                .replace("\"deletions\": 3", "\"deletions\": null")
                .replace("\"changed_files\":", "\"ignored_changed_files\":")
                .replace("\"requested_teams\":", "\"ignored_requested_teams\":");
        serve(METADATA_PATH, 200, payload, ImmutableMap.of());

        assertThat(integration.pullRequests().fetch(access, repository, 7, Optional.empty()).detail()).hasValueSatisfying(value -> {
            assertThat(value.additions()).isEqualTo(UNKNOWN_COUNT);
            assertThat(value.deletions()).isEqualTo(UNKNOWN_COUNT);
            assertThat(value.changedFiles()).isEqualTo(UNKNOWN_COUNT);
            assertThat(value.requestedReviewerCount()).isEqualTo(UNKNOWN_COUNT);
        });
    }

    @Test
    void retainsExplicitZeroCounts()
    {
        serve(METADATA_PATH, 200, PULL_REQUEST.replace("\"additions\": 12, \"deletions\": 3, \"changed_files\": 2",
                "\"additions\": 0, \"deletions\": 0, \"changed_files\": 0"), ImmutableMap.of());

        assertThat(integration.pullRequests().fetch(access, repository, 7, Optional.empty()).detail()).hasValueSatisfying(value -> {
            assertThat(value.additions()).isZero();
            assertThat(value.deletions()).isZero();
            assertThat(value.changedFiles()).isZero();
        });
    }

    @Test
    void acceptsDeletedSourceForkWhenTheTargetIdentityMatches()
    {
        serve(METADATA_PATH, 200, PULL_REQUEST.replace("\"repo\": {}", "\"repo\": null"), ImmutableMap.of());

        PullRequestFetch fetched = integration.pullRequests().fetch(access, repository, 7, Optional.empty());
        assertThat(fetched.pullRequest()).isPresent();
        assertThat(fetched.detail()).hasValueSatisfying(value -> assertThat(value.headSha()).isEqualTo("head-sha"));
    }

    @Test
    void preservesTheValidatorWhenAnUnchangedResponseOmitsIt()
    {
        serve(METADATA_PATH, 304, "", ImmutableMap.of());
        PullRequestFetch fetched = integration.pullRequests().fetch(access, repository, 7, Optional.of("\"v1\""));
        assertThat(fetched.pullRequest()).isEmpty();
        assertThat(fetched.detail()).isEmpty();
        assertThat(fetched.etag()).contains("\"v1\"");
        assertThatThrownBy(() -> integration.pullRequests().fetch(access, repository, 7, Optional.empty()))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(INVALID_RESPONSE));
    }

    @Test
    void clearsTheValidatorWhenAChangedResponseOmitsIt()
    {
        serve(METADATA_PATH, 200, PULL_REQUEST, ImmutableMap.of());

        PullRequestFetch fetched = integration.pullRequests().fetch(access, repository, 7, Optional.of("\"v1\""));

        assertThat(fetched.pullRequest()).isPresent();
        assertThat(fetched.detail()).isPresent();
        assertThat(fetched.etag()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"401,AUTHENTICATION_REQUIRED", "403,FORBIDDEN", "404,MISSING_OR_INACCESSIBLE", "409,REVISION_CONFLICT", "500,UNAVAILABLE"})
    void translatesErrorsWithoutExposingBodiesOrCredentials(int status, HostingFailure.Kind expected)
    {
        serve("/api/v3/user", status, "sensitive-remote-body", ImmutableMap.of());
        assertThatThrownBy(() -> integration.accounts().verify(access))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(expected))
                .hasMessageNotContaining("sensitive-remote-body").hasMessageNotContaining("private-token").hasNoCause();
    }

    @Test
    void reportsRateLimitsWithTheirRetryDelay()
    {
        serve(METADATA_PATH, 403, "", ImmutableMap.of("X-RateLimit-Remaining", "0", "Retry-After", "12"));
        assertThatThrownBy(() -> integration.pullRequests().fetch(access, repository, 7, Optional.empty()))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(RATE_LIMITED);
                    assertThat(failure.retryAfter()).contains(Duration.ofSeconds(12));
                });
    }

    @Test
    void rejectsRedirectingClientsAndDoesNotFollowResponses()
    {
        try (HttpClient redirecting = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()) {
            assertThatThrownBy(() -> GitHubIntegration.create(redirecting, new ObjectMapper())).isInstanceOf(IllegalArgumentException.class);
        }
        AtomicInteger redirected = new AtomicInteger();
        server.createContext("/unexpected", exchange -> {
            redirected.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        serve(METADATA_PATH, 302, "", ImmutableMap.of("Location", "/unexpected"));
        assertThatThrownBy(() -> integration.pullRequests().fetch(access, repository, 7, Optional.empty()))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(INVALID_RESPONSE));
        assertThat(redirected).hasValue(0);
    }

    @Test
    void boundsResponseBodies()
    {
        serve(METADATA_PATH, 200, " ".repeat(2 * 1024 * 1024 + 1), ImmutableMap.of());
        assertThatThrownBy(() -> integration.pullRequests().fetch(access, repository, 7, Optional.empty()))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(INVALID_RESPONSE))
                .hasMessageContaining("permitted size");
    }

    @Test
    @Timeout(5)
    void boundsTheWholeExchangeWhenTheBodyStallsAfterHeaders()
            throws InterruptedException
    {
        CountDownLatch headersSent = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        serveStalledBody(headersSent, releaseBody);
        GitHubHttpClient client = new GitHubHttpClient(http, new ObjectMapper(), Duration.ofMillis(250));

        try {
            assertThatThrownBy(() -> client.get(access, "user", GitHubAccountResponse.class))
                    .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(UNAVAILABLE))
                    .hasNoCause();
            assertThat(headersSent.await(1, SECONDS)).isTrue();
        }
        finally {
            releaseBody.countDown();
        }
    }

    @Test
    @Timeout(5)
    void cancelsAnInterruptedExchangeAndPreservesTheInterruptFlag()
            throws InterruptedException
    {
        CountDownLatch headersSent = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        serveStalledBody(headersSent, releaseBody);
        AtomicReference<HostingFailure> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread reader = Thread.ofPlatform().start(() -> {
            try {
                new GitHubHttpClient(http, new ObjectMapper()).get(access, "user", GitHubAccountResponse.class);
            }
            catch (HostingFailure e) {
                failure.set(e);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        try {
            assertThat(headersSent.await(1, SECONDS)).isTrue();
            reader.interrupt();
            reader.join(1000);
            assertThat(reader.isAlive()).isFalse();
            assertThat(failure.get()).isNotNull();
            assertThat(failure.get().kind()).isEqualTo(UNAVAILABLE);
            assertThat(interrupted).isTrue();
        }
        finally {
            releaseBody.countDown();
            reader.interrupt();
            reader.join(1000);
        }
    }

    @ParameterizedTest
    @CsvSource({"[]", "invalid-json", "{}", "{\"id\":1007}"})
    void rejectsMalformedMetadata(String body)
    {
        serve(METADATA_PATH, 200, body, ImmutableMap.of());
        assertThatThrownBy(() -> integration.pullRequests().fetch(access, repository, 7, Optional.empty()))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(INVALID_RESPONSE));
    }

    @Test
    void refusesWrongInstancesAndUnsafeCredentialsBeforeSending()
    {
        HostingAccess other = new HostingAccess(new HostingInstance(3, HostingProvider.GITHUB, access.server().webBaseUrl(), access.server().apiBaseUrl()), () -> "token");
        assertThatThrownBy(() -> integration.pullRequests().fetch(other, repository, 7, Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        HostingAccess unsafe = new HostingAccess(access.server(), () -> "secret\r\nheader");
        assertThatThrownBy(() -> integration.accounts().verify(unsafe))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(AUTHENTICATION_REQUIRED))
                .hasMessageNotContaining("secret");
        assertThat(request.get()).isNull();
    }

    private void serve(String path, int status, String body, Map<String, String> headers)
    {
        server.createContext(path, exchange -> {
            request.set(new Request(exchange.getRequestURI().getRawPath(), exchange.getRequestHeaders().getFirst("Authorization"),
                    Optional.ofNullable(exchange.getRequestHeaders().getFirst("If-None-Match"))));
            headers.forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(status, status == 304 ? -1 : bytes.length);
                if (status != 304) {
                    exchange.getResponseBody().write(bytes);
                }
            }
            finally {
                exchange.close();
            }
        });
    }

    private void serveStalledBody(CountDownLatch headersSent, CountDownLatch releaseBody)
    {
        server.createContext("/api/v3/user", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 1024);
                exchange.getResponseBody().write("{\"id\":".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                headersSent.countDown();
                releaseBody.await();
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            finally {
                exchange.close();
            }
        });
    }

    private record Request(String path, String authorization, Optional<String> validator) {}
}

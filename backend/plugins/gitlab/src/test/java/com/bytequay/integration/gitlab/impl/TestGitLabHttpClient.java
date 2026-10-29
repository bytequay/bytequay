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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.core.hosting.HostingFailure.Kind.AUTHENTICATION_REQUIRED;
import static com.bytequay.core.hosting.HostingFailure.Kind.FORBIDDEN;
import static com.bytequay.core.hosting.HostingFailure.Kind.INVALID_RESPONSE;
import static com.bytequay.core.hosting.HostingFailure.Kind.MISSING_OR_INACCESSIBLE;
import static com.bytequay.core.hosting.HostingFailure.Kind.RATE_LIMITED;
import static com.bytequay.core.hosting.HostingProvider.GITLAB;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestGitLabHttpClient
{
    private static final String TOKEN = "secret-token";
    private static final String REMOTE_DETAIL = "private-server-detail";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AtomicReference<Reply> reply = new AtomicReference<>();
    private final AtomicReference<String> requestedPath = new AtomicReference<>();
    private final AtomicReference<String> requestAuthorization = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger();
    private HttpServer server;
    private HttpClient http;
    private GitLabHttpClient client;
    private HostingAccess access;

    @BeforeEach
    void setUp()
            throws IOException
    {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            requestedPath.set(exchange.getRequestURI().toASCIIString());
            requestAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            Reply result = reply.get();
            byte[] bytes = result.body().getBytes(StandardCharsets.UTF_8);
            result.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
            exchange.sendResponseHeaders(result.status(), bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/gitlab";
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        client = new GitLabHttpClient(http, MAPPER);
        access = new HostingAccess(new HostingInstance(1, GITLAB, URI.create(base), URI.create(base + "/api/v4")), TOKEN);
        reply.set(new Reply(200, "{\"scope\":[\"api\"]}", ImmutableMap.of()));
    }

    @AfterEach
    void tearDown()
    {
        http.close();
        server.stop(0);
    }

    @Test
    void readsOAuthPermissionsAtThePrefixedWebAddressWithCredentialsOnlyInTheHeader()
    {
        TokenInfo result = client.getOAuthTokenInfo(access, TokenInfo.class).body().orElseThrow();

        assertThat(result.scope()).containsExactly("api");
        assertThat(requestedPath.get()).isEqualTo("/gitlab/oauth/token/info").doesNotContain(TOKEN);
        assertThat(requestAuthorization.get()).isEqualTo("Bearer " + TOKEN);
        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    void doesNotFollowOAuthPermissionRedirects()
    {
        reply.set(new Reply(302, REMOTE_DETAIL + TOKEN, ImmutableMap.of("Location", "/credential-target")));

        assertThatThrownBy(() -> client.getOAuthTokenInfo(access, TokenInfo.class))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(INVALID_RESPONSE);
                    assertSafeFailure(failure, "token permission inspection", 302);
                });
        assertThat(requestCount.get()).isEqualTo(1);
        assertThat(requestedPath.get()).isEqualTo("/gitlab/oauth/token/info");
    }

    @Test
    void rejectsClientsThatCouldForwardCredentialsThroughRedirects()
    {
        try (HttpClient redirecting = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()) {
            assertThatThrownBy(() -> new GitLabHttpClient(redirecting, MAPPER)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(requestCount.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"scope\":[17]}", "{\"scope\":\"api\"}", "{\"scope\":[\"api\"]} {}", "null", "<html>secret-token</html>"})
    void strictlyDecodesOAuthPermissionResponses(String body)
    {
        reply.set(new Reply(200, body, ImmutableMap.of()));

        assertThatThrownBy(() -> client.getOAuthTokenInfo(access, TokenInfo.class))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(INVALID_RESPONSE);
                    assertSafeFailure(failure, "token permission inspection", 200);
                });
    }

    @ParameterizedTest
    @CsvSource({
            "projects?search=private-server-detail,project search",
            "projects/group%2Fsubgroup%2Frepo,project access",
            "projects/42/merge_requests?scope=all,merge request list",
            "projects/42/merge_requests/17,merge request details",
            "projects/42/merge_requests/17/discussions,merge request discussions",
            "projects/42/merge_requests/17/notes,merge request comments",
            "projects/42/pipelines/99/jobs,pipeline jobs",
            "projects/42/pipelines/99/bridges,pipeline trigger jobs",
            "projects/42/jobs/88/trace,pipeline job log"})
    void identifiesTheFailedResourceWithoutClaimingWhyItIsUnavailable(String path, String operation)
    {
        reply.set(new Reply(404, "{\"message\":\"" + REMOTE_DETAIL + TOKEN + "\"}", ImmutableMap.of()));

        assertThatThrownBy(() -> client.get(access, path, Optional.empty(), TokenInfo.class))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(MISSING_OR_INACCESSIBLE);
                    assertSafeFailure(failure, operation, 404);
                    assertThat(failure.getMessage()).contains("does not distinguish a missing resource from one this account cannot access")
                            .doesNotContain("group", "subgroup", "/42", "/17", "/99", "/88");
                });
    }

    @ParameterizedTest
    @CsvSource({
            "projects/group%2Fsubgroup%2Frepo/merge_requests/17?access_token=secret-token,<api>/projects/{id}/merge_requests/{id}",
            "projects/42/pipelines/99/jobs?per_page=100,<api>/projects/{id}/pipelines/{id}/jobs",
            "projects/42/merge_requests/17/discussions/private-discussion/notes,<api>/projects/{id}/merge_requests/{id}/discussions/{id}/notes",
            "projects/42/repository/files/private-file?ref=private-branch,<api>/projects/{id}/repository/files/{id}",
            "projects/42/repository/compare?from=private-branch,<api>/projects/{id}/repository/compare",
            "personal_access_tokens/self,<api>/personal_access_tokens/self",
            "unknown-resource/private-value,<api>/{resource}/{id}"})
    void includesCopyableRequestDetailsWithOnlyStaticEndpointSegments(String path, String template)
    {
        reply.set(new Reply(404, REMOTE_DETAIL + TOKEN, ImmutableMap.of("X-Request-Id", "01SAFE-request-id")));

        assertThatThrownBy(() -> client.get(access, path, Optional.empty(), TokenInfo.class))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> {
                    assertThat(failure.getMessage()).contains("Request: GET " + template, "GitLab request ID: 01SAFE-request-id", "HTTP 404")
                            .doesNotContain("access_token=", "private-", "subgroup", TOKEN, "?", "per_page", "unknown-resource");
                    assertThat(Instant.parse(failure.getMessage().split("Time \\(UTC\\): ")[1])).isNotNull();
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"secret-token", "prefix-secret-token-suffix", "Bearer private-token", "private/detail", "<private>",
            "01234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890"})
    void omitsUntrustedOrCredentialContainingRequestIds(String requestId)
    {
        reply.set(new Reply(403, "{}", ImmutableMap.of("X-Request-Id", requestId)));

        assertThatThrownBy(() -> client.get(access, "projects/42", Optional.empty(), TokenInfo.class))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.getMessage())
                        .contains("GitLab request ID: unavailable").doesNotContain(requestId));
    }

    @Test
    void diagnosticRequestUsesTheActualHttpMethod()
    {
        reply.set(new Reply(403, "{}", ImmutableMap.of()));

        assertThatThrownBy(() -> client.post(access, "projects/42/merge_requests/17/notes", ImmutableMap.of("body", "private-comment"), TokenInfo.class))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.getMessage())
                        .contains("Request: POST <api>/projects/{id}/merge_requests/{id}/notes").doesNotContain("private-comment"));
    }

    @Test
    void identifiesAnExplicitMissingScopeWithoutExposingTheProviderDescription()
    {
        String body = "{\"error\":\"insufficient_scope\",\"error_description\":\"" + REMOTE_DETAIL + TOKEN + "\",\"scope\":\"untrusted-scope\"}";
        reply.set(new Reply(403, body, ImmutableMap.of()));

        assertThatThrownBy(() -> client.get(access, "projects?search=" + TOKEN, Optional.empty(), TokenInfo.class))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(FORBIDDEN);
                    assertSafeFailure(failure, "project search", 403);
                    assertThat(failure.getMessage()).contains("missing required API permissions", "Reconnect GitLab", "api access").doesNotContain("untrusted-scope");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer error=\"insufficient_scope\"", "Bearer realm=\"GitLab\", error=insufficient_scope"})
    void recognizesMissingScopesFromTheAuthenticationHeader(String challenge)
    {
        reply.set(new Reply(403, REMOTE_DETAIL + TOKEN, ImmutableMap.of("WWW-Authenticate", challenge + ", error_description=\"" + TOKEN + "\"")));

        assertThatThrownBy(() -> client.get(access, "projects/42/pipelines/99/jobs", Optional.empty(), TokenInfo.class))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(FORBIDDEN);
                    assertSafeFailure(failure, "pipeline jobs", 403);
                    assertThat(failure.getMessage()).contains("missing required API permissions", "Reconnect GitLab");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "<html>insufficient_scope</html>", "{\"message\":\"insufficient_scope\"}", "{\"error\":[\"insufficient_scope\"]}"})
    void doesNotClaimAForbiddenRequestHasMissingScopesWithoutExplicitEvidence(String body)
    {
        reply.set(new Reply(403, body, ImmutableMap.of()));

        assertThatThrownBy(() -> client.get(access, "projects/42", Optional.empty(), TokenInfo.class))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(FORBIDDEN);
                    assertSafeFailure(failure, "project access", 403);
                    assertThat(failure.getMessage()).contains("denied this operation").doesNotContain("missing required API permissions");
                });
    }

    @Test
    void doesNotInterpretAnErrorDescriptionAsAnAuthenticationParameter()
    {
        reply.set(new Reply(403, "{}", ImmutableMap.of("WWW-Authenticate", "Bearer error_description=\"example, error=insufficient_scope value\"")));

        assertThatThrownBy(() -> client.get(access, "projects/42", Optional.empty(), TokenInfo.class))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(FORBIDDEN);
                    assertSafeFailure(failure, "project access", 403);
                    assertThat(failure.getMessage()).doesNotContain("missing required API permissions");
                });
    }

    @Test
    void explainsWhenTheSignInIsInvalidOrExpired()
    {
        reply.set(new Reply(401, REMOTE_DETAIL + TOKEN, ImmutableMap.of()));

        assertThatThrownBy(() -> client.get(access, "projects/42", Optional.empty(), TokenInfo.class))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(AUTHENTICATION_REQUIRED);
                    assertSafeFailure(failure, "project access", 401);
                    assertThat(failure.getMessage()).contains("invalid or expired", "Reconnect GitLab");
                });
    }

    @Test
    void doesNotTreatAnUnsupportedTokenInspectionAsAnExpiredSignIn()
    {
        reply.set(new Reply(401, REMOTE_DETAIL + TOKEN, ImmutableMap.of()));

        assertThatThrownBy(() -> client.getOAuthTokenInfo(access, TokenInfo.class))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(AUTHENTICATION_REQUIRED);
                    assertSafeFailure(failure, "token permission inspection", 401);
                    assertThat(failure.getMessage()).contains("may be invalid or unsupported by this endpoint").doesNotContain("sign-in is invalid or expired");
                });
    }

    @Test
    void preservesRateLimitRetryInformation()
    {
        reply.set(new Reply(429, REMOTE_DETAIL + TOKEN, ImmutableMap.of("Retry-After", "17")));

        assertThatThrownBy(() -> client.get(access, "projects/42/merge_requests", Optional.empty(), TokenInfo.class))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(RATE_LIMITED);
                    assertSafeFailure(failure, "merge request list", 429);
                    assertThat(failure.retryAfter()).contains(Duration.ofSeconds(17));
                });
    }

    private static void assertSafeFailure(HostingFailure failure, String operation, int status)
    {
        assertThat(failure.getMessage()).contains(operation, "HTTP " + status).doesNotContain(TOKEN, REMOTE_DETAIL, "Bearer", "Authorization");
        assertThat(failure.getCause()).isNull();
        assertThat(failure.getSuppressed()).isEmpty();
    }

    private record Reply(int status, String body, Map<String, String> headers)
    {
        private Reply
        {
            headers = ImmutableMap.copyOf(headers);
        }
    }

    private record TokenInfo(String[] scope) {}
}

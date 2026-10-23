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

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.app.repository.github.GitHubRateLimitException.isRateLimit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.MockRestServiceServer.bindTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TestGitHubRateLimitInterceptor
{
    private static final Instant NOW = Instant.parse("2026-09-29T00:00:00Z");
    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    private final Clock clock = mock(Clock.class);

    private GitHubRateLimitInterceptor interceptor(int concurrency)
    {
        when(clock.instant()).thenAnswer(ignored -> now.get());
        return new GitHubRateLimitInterceptor(clock, concurrency);
    }

    @Test
    void successfulFinalRequestBlocksOnlyItsPrimaryBucketAndCredential()
            throws Exception
    {
        GitHubRateLimitInterceptor interceptor = interceptor(4);
        try (ClientHttpResponse response = call(interceptor, "/repos/owner/repo", "first", quota(HttpStatus.OK, "last result", "core", 0))) {
            assertThat(response.getBody().readAllBytes()).asString(StandardCharsets.UTF_8).isEqualTo("last result");
        }
        assertBlocked(interceptor, "/repos/other/repo", "first", "core", NOW.plusSeconds(3600));
        call(interceptor, "/search/issues", "first", response(HttpStatus.OK, "[]")).close();
        call(interceptor, "/graphql", "first", response(HttpStatus.OK, "{\"data\":{}}")).close();
        call(interceptor, "/repos/other/repo", "second", response(HttpStatus.OK, "{}")).close();
        assertThat(interceptor.snapshot("first").inFlight()).isZero();
        assertThat(interceptor.snapshot("first").deferredRequests()).isEqualTo(1);
        assertThat(interceptor.snapshot("first").quotas().getFirst().remaining()).contains(0L);
        now.set(NOW.plusSeconds(3600));
        call(interceptor, "/repos/owner/repo", "first", response(HttpStatus.OK, "{}")).close();
    }

    @Test
    void secondaryBodyWithoutRetryHeaderPausesRestSearchAndGraphql()
            throws Exception
    {
        GitHubRateLimitInterceptor interceptor = interceptor(4);
        MockClientHttpResponse limited = quota(HttpStatus.FORBIDDEN, "{\"message\":\"You have exceeded a secondary rate limit.\"}", "core", 4900);
        assertThatThrownBy(() -> call(interceptor, "/repos/owner/repo", "pat", limited))
                .isInstanceOf(GitHubRateLimitException.class)
                .hasMessageContaining(limitMessage("secondary", NOW.plusSeconds(60)));
        assertBlocked(interceptor, "/search/issues", "pat", "secondary", NOW.plusSeconds(60));
        assertBlocked(interceptor, "/graphql", "pat", "secondary", NOW.plusSeconds(60));
        assertThat(interceptor.snapshot("pat").rateLimitedResponses()).isEqualTo(1);
        assertThat(interceptor.snapshot("pat").inFlight()).isZero();
        now.set(NOW.plusSeconds(60));
        assertThatThrownBy(() -> call(interceptor, "/graphql", "pat", response(HttpStatus.OK, "{\"errors\":[{\"message\":\"You have exceeded a secondary rate limit\"}]}")))
                .isInstanceOf(GitHubRateLimitException.class).hasMessageContaining("retry after " + NOW.plusSeconds(180));
    }

    @Test
    void retryAfterWinsOverUnexhaustedPrimaryResetAndAcceptsHttpDate()
            throws Exception
    {
        GitHubRateLimitInterceptor interceptor = interceptor(4);
        MockClientHttpResponse limited = quota(HttpStatus.FORBIDDEN, "{}", "core", 100);
        limited.getHeaders().set(HttpHeaders.RETRY_AFTER, "10");
        assertThatThrownBy(() -> call(interceptor, "/repos/owner/repo", "pat", limited))
                .isInstanceOf(GitHubRateLimitException.class).hasMessageContaining("retry after " + NOW.plusSeconds(10));
        now.set(NOW.plusSeconds(10));
        call(interceptor, "/graphql", "pat", response(HttpStatus.OK, "{\"data\":{}}")).close();
        MockClientHttpResponse dated = response(HttpStatus.TOO_MANY_REQUESTS, "{}");
        dated.getHeaders().set(HttpHeaders.RETRY_AFTER, DateTimeFormatter.RFC_1123_DATE_TIME.format(NOW.plusSeconds(40).atZone(ZoneOffset.UTC)));
        assertThatThrownBy(() -> call(interceptor, "/repos/owner/repo", "pat", dated))
                .isInstanceOf(GitHubRateLimitException.class).hasMessageContaining("retry after " + NOW.plusSeconds(40));
        assertThat(interceptor.snapshot("pat").cooldowns()).containsOnlyKeys("secondary");
    }

    @Test
    void exhaustedPrimaryAndSecondaryCooldownsExpireIndependently()
            throws Exception
    {
        GitHubRateLimitInterceptor interceptor = interceptor(4);
        MockClientHttpResponse limited = quota(HttpStatus.FORBIDDEN, "{}", "core", 0);
        limited.getHeaders().set(HttpHeaders.RETRY_AFTER, "10");
        assertThatThrownBy(() -> call(interceptor, "/repos/owner/repo", "pat", limited))
                .isInstanceOf(GitHubRateLimitException.class).hasMessageContaining("retry after " + NOW.plusSeconds(3600));
        assertBlocked(interceptor, "/graphql", "pat", "secondary", NOW.plusSeconds(10));
        now.set(NOW.plusSeconds(10));
        call(interceptor, "/graphql", "pat", response(HttpStatus.OK, "{}")).close();
        assertBlocked(interceptor, "/repos/owner/repo", "pat", "core", NOW.plusSeconds(3600));
    }

    @Test
    void graphqlHttp200RateLimitUpdatesTheSharedPrimaryState()
            throws Exception
    {
        GitHubRateLimitInterceptor interceptor = interceptor(4);
        MockClientHttpResponse limited = quota(HttpStatus.OK, "{\"data\":null,\"errors\":[{\"type\":\"RATE_LIMITED\",\"message\":\"API rate limit exceeded\"}]}", "graphql", 0);
        assertThatThrownBy(() -> call(interceptor, "/graphql", "pat", limited)).isInstanceOf(GitHubRateLimitException.class);
        assertBlocked(interceptor, "/graphql", "pat", "graphql", NOW.plusSeconds(3600));
        call(interceptor, "/search/issues", "pat", response(HttpStatus.OK, "[]")).close();
        assertThat(interceptor.snapshot("pat").requests()).anySatisfy(count -> {
            assertThat(count.resource()).isEqualTo("graphql");
            assertThat(count.status()).isEqualTo(200);
            assertThat(count.count()).isEqualTo(1);
        });
        assertThat(isRateLimit(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "GraphQL rate limit"))).isTrue();
        assertThat(isRateLimit(new ResponseStatusException(HttpStatus.FORBIDDEN, "Missing permission"))).isFalse();
    }

    @Test
    void ordinaryPermissionAndGraphqlErrorsKeepTheirBodiesAndDoNotBlock()
            throws Exception
    {
        GitHubRateLimitInterceptor interceptor = interceptor(4);
        String permission = "{\"message\":\"Resource not accessible by personal access token\"}";
        try (ClientHttpResponse response = call(interceptor, "/repos/owner/repo", "pat", response(HttpStatus.FORBIDDEN, permission))) {
            assertThat(response.getBody().readAllBytes()).asString(StandardCharsets.UTF_8).isEqualTo(permission);
        }
        String partial = "{\"data\":{\"r0\":{}},\"errors\":[{\"type\":\"NOT_FOUND\",\"path\":[\"r1\"]}]}";
        try (ClientHttpResponse response = call(interceptor, "/graphql", "pat", response(HttpStatus.OK, partial))) {
            assertThat(response.getBody().readAllBytes()).asString(StandardCharsets.UTF_8).isEqualTo(partial);
        }
        assertThat(interceptor.snapshot("pat").cooldowns()).isEmpty();
        assertThat(interceptor.snapshot("pat").rateLimitedResponses()).isZero();
    }

    @Test
    void accountIdentitySharesCooldownAcrossTokensWithoutLeakingToAnotherAccount()
            throws Exception
    {
        GitHubRateLimitInterceptor interceptor = interceptor(4);
        call(interceptor, "/user", "original", response(HttpStatus.OK, "{\"id\":123}")).close();
        call(interceptor, "/search/issues", "original", quota(HttpStatus.OK, "[]", "search", 0)).close();
        call(interceptor, "/user", "replacement", response(HttpStatus.OK, "{\"id\":123}")).close();
        assertBlocked(interceptor, "/search/issues", "replacement", "search", NOW.plusSeconds(3600));
        call(interceptor, "/user", "other", response(HttpStatus.OK, "{\"id\":456}")).close();
        call(interceptor, "/search/issues", "other", response(HttpStatus.OK, "[]")).close();
        assertThat(interceptor.snapshot("replacement").requests().stream().mapToLong(GitHubRateLimitInterceptor.RequestCount::count).sum()).isEqualTo(3);
        assertThat(interceptor.snapshot("other").cooldowns()).isEmpty();
        assertThat(interceptor.snapshot("replacement").toString()).doesNotContain("original", "replacement", "Bearer", "123");
    }

    @Test
    void permitRemainsHeldUntilCloseAndIsReleasedExactlyOnce()
            throws Exception
    {
        GitHubRateLimitInterceptor interceptor = interceptor(1);
        ClientHttpResponse first = call(interceptor, "/repos/owner/repo", "pat", response(HttpStatus.OK, "{}"));
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch attempted = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var waiting = executor.submit(() -> {
                attempted.countDown();
                return interceptor.intercept(request("/graphql", "pat"), new byte[0], (request, body) -> {
                    requests.incrementAndGet();
                    return response(HttpStatus.OK, "{\"data\":{}}");
                });
            });
            try {
                assertThat(attempted.await(1, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> waiting.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                assertThat(requests).hasValue(0);
                assertThat(interceptor.snapshot("pat").inFlight()).isEqualTo(1);
                first.close();
                first.close();
                waiting.get(1, TimeUnit.SECONDS).close();
                assertThat(requests).hasValue(1);
                assertThat(interceptor.snapshot("pat").inFlight()).isZero();
            }
            finally {
                first.close();
                waiting.cancel(true);
            }
        }
    }

    @Test
    void transportFailuresReleaseCapacityAndCountersIncludeStatusAndDuration()
            throws Exception
    {
        GitHubRateLimitInterceptor interceptor = interceptor(1);
        assertThatThrownBy(() -> interceptor.intercept(request("/notifications", "pat"), new byte[0], (request, body) -> {
            throw new IOException("connection lost");
        })).isInstanceOf(IOException.class);
        call(interceptor, "/notifications", "pat", response(HttpStatus.NOT_MODIFIED, "")).close();
        call(interceptor, "/repos/owner/repo/commits/sha/check-runs", "pat", response(HttpStatus.BAD_GATEWAY, "<html>upstream unavailable</html>")).close();
        assertThat(interceptor.snapshot("pat").inFlight()).isZero();
        assertThat(interceptor.snapshot("pat").requests()).anySatisfy(count -> {
            assertThat(count.family()).isEqualTo("notifications");
            assertThat(count.status()).isZero();
            assertThat(count.count()).isEqualTo(1);
            assertThat(count.totalDurationMillis()).isNotNegative();
        }).anySatisfy(count -> {
            assertThat(count.family()).isEqualTo("notifications");
            assertThat(count.status()).isEqualTo(304);
        }).anySatisfy(count -> {
            assertThat(count.family()).isEqualTo("checks");
            assertThat(count.status()).isEqualTo(502);
        });
    }

    @Test
    void restClientClosesTheBufferedGraphqlResponseAndRecordsSummaryFamily()
    {
        GitHubRateLimitInterceptor interceptor = interceptor(1);
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com/graphql").requestInterceptor(interceptor);
        var server = bindTo(builder).build();
        server.expect(once(), requestTo("https://api.github.com/graphql")).andRespond(withSuccess("{\"data\":{\"r0\":{}}}", MediaType.APPLICATION_JSON));
        String result = GitHubApiSupport.graphql(builder.build(), "pat", "query{...Summary} fragment Summary on PullRequest { number }", ImmutableMap.of()).body(String.class);
        assertThat(result).isEqualTo("{\"data\":{\"r0\":{}}}");
        assertThat(interceptor.snapshot("pat").inFlight()).isZero();
        assertThat(interceptor.snapshot("pat").requests()).singleElement().satisfies(count -> {
            assertThat(count.family()).isEqualTo("pr-summary");
            assertThat(count.status()).isEqualTo(200);
            assertThat(count.costedRequests()).isZero();
            assertThat(count.reportedCost()).isZero();
        });
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({
            "viewer-reactions, query { nodes { reactionGroups { content viewerHasReacted } } }",
            "review-reactions, query { reviews { reactionGroups { users { totalCount } viewerHasReacted } } }",
            "review-threads, query { reviewThreads { nodes { id } } }",
            "pr-summary, query { ...Summary } fragment Summary on PullRequest { number }"})
    void recordsReportedPointCostsWithoutTreatingMissingCostsAsMeasured(String family, String query)
    {
        GitHubRateLimitInterceptor interceptor = interceptor(1);
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com/graphql").requestInterceptor(interceptor);
        var server = bindTo(builder).build();
        String response = "{\"data\":{\"rateLimit\":{\"cost\":3}}}";
        server.expect(requestTo("https://api.github.com/graphql")).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/graphql")).andRespond(withSuccess("{\"data\":{}}", MediaType.APPLICATION_JSON));
        RestClient client = builder.build();

        assertThat(GitHubApiSupport.graphql(client, "pat", query, ImmutableMap.of()).body(String.class)).isEqualTo(response);
        GitHubApiSupport.graphql(client, "pat", query, ImmutableMap.of()).body(String.class);

        assertThat(interceptor.snapshot("pat").requests()).singleElement().satisfies(count -> {
            assertThat(count.family()).isEqualTo(family);
            assertThat(count.count()).isEqualTo(2);
            assertThat(count.reportedCost()).isEqualTo(3);
            assertThat(count.costedRequests()).isEqualTo(1);
        });
        assertThat(interceptor.snapshot("pat").inFlight()).isZero();
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({
            "/repos/owner/repository/pulls/123/reviews, /repos/{o}/{r}/pulls/{n}/reviews",
            "/repos/actions/checkout/commits/0123456789abcdef0123456789abcdef01234567/pulls, /repos/{o}/{r}/commits/{sha}/pulls",
            "/repos/owner/repository/commits/feature-branch/check-runs, /repos/{o}/{r}/commits/{}/check-runs",
            "/repos/owner/repository/compare/main...topic, /repos/{o}/{r}/compare/{}",
            "/repositories/42/issues, /repositories/{n}/issues",
            "/users/octocat/events/public, /users/{}/events/public",
            "/graphql, /graphql"})
    void endpointTemplateKeepsOnlyApiWords(String path, String template)
    {
        assertThat(GitHubRateLimitInterceptor.endpoint(path)).isEqualTo(template);
    }

    @Test
    void snapshotCountsRequestsPerEndpointAndStatus()
            throws Exception
    {
        GitHubRateLimitInterceptor interceptor = interceptor(4);
        call(interceptor, "/repos/owner/repository/pulls/1", "pat", response(HttpStatus.OK, "{}")).close();
        call(interceptor, "/repos/airlift/airlift/pulls/2", "pat", response(HttpStatus.NOT_MODIFIED, "")).close();
        call(interceptor, "/repos/owner/repository/pulls/3", "pat", response(HttpStatus.NOT_MODIFIED, "")).close();
        call(interceptor, "/repos/owner/repository/pulls/3/reviews", "pat", response(HttpStatus.OK, "[]")).close();

        GitHubRateLimitInterceptor.Snapshot snapshot = interceptor.snapshot("pat");
        assertThat(snapshot.requests()).extracting(GitHubRateLimitInterceptor.RequestCount::endpoint, GitHubRateLimitInterceptor.RequestCount::status,
                GitHubRateLimitInterceptor.RequestCount::count).containsExactlyInAnyOrder(
                tuple("/repos/{o}/{r}/pulls/{n}", 200, 1L),
                tuple("/repos/{o}/{r}/pulls/{n}", 304, 2L),
                tuple("/repos/{o}/{r}/pulls/{n}/reviews", 200, 1L));
        assertThat(snapshot.toString()).doesNotContain("owner", "airlift");
    }

    private static String limitMessage(String resource, Instant retryAt)
    {
        return "GitHub " + resource + " API rate limit reached; retry after " + retryAt;
    }

    private static void assertBlocked(GitHubRateLimitInterceptor interceptor, String path, String pat, String resource, Instant retryAt)
    {
        assertThatThrownBy(() -> interceptor.intercept(request(path, pat), new byte[0], (request, body) -> {
            throw new AssertionError("A blocked request must not reach GitHub");
        })).isInstanceOf(GitHubRateLimitException.class).hasMessageContaining(limitMessage(resource, retryAt));
    }

    private static HttpRequest request(String path, String pat)
    {
        MockClientHttpRequest request = new MockClientHttpRequest(GET, URI.create("https://api.github.com" + path));
        request.getHeaders().setBearerAuth(pat);
        return request;
    }

    private static ClientHttpResponse call(GitHubRateLimitInterceptor interceptor, String path, String pat, ClientHttpResponse response)
            throws IOException
    {
        return interceptor.intercept(request(path, pat), new byte[0], (request, body) -> response);
    }

    private static MockClientHttpResponse response(HttpStatus status, String body)
    {
        return new MockClientHttpResponse(body.getBytes(StandardCharsets.UTF_8), status);
    }

    private static MockClientHttpResponse quota(HttpStatus status, String body, String resource, long remaining)
    {
        MockClientHttpResponse response = response(status, body);
        response.getHeaders().set("X-RateLimit-Resource", resource);
        response.getHeaders().set("X-RateLimit-Limit", "5000");
        response.getHeaders().set("X-RateLimit-Remaining", Long.toString(remaining));
        response.getHeaders().set("X-RateLimit-Reset", Long.toString(NOW.plusSeconds(3600).getEpochSecond()));
        return response;
    }
}

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

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestGitHubOrgAccessInterceptor
{
    private static final String DENIAL_BODY =
            "{\"message\":\"`acme` forbids access via a personal access token (classic).\",\"status\":\"403\"}";

    @Test
    void secondCallToADeniedOrgSkipsTheNetwork()
            throws IOException
    {
        GitHubOrgAccessInterceptor interceptor = new GitHubOrgAccessInterceptor(new GitHubOrgAccess());
        AtomicInteger executed = new AtomicInteger();
        ClientHttpRequestExecution execution = (request, body) -> {
            executed.incrementAndGet();
            return new MockClientHttpResponse(DENIAL_BODY.getBytes(UTF_8), HttpStatusCode.valueOf(403));
        };

        ClientHttpResponse first = interceptor.intercept(
                restRequest("https://api.github.com/repos/acme/cork/pulls/1"), new byte[0], execution);
        // The body must still be readable after the interceptor inspected it.
        assertThat(new String(first.getBody().readAllBytes(), UTF_8)).isEqualTo(DENIAL_BODY);

        ClientHttpResponse second = interceptor.intercept(
                restRequest("https://api.github.com/repos/acme/cork/pulls/2"), new byte[0], execution);

        assertThat(executed).hasValue(1);
        assertThat(second.getStatusCode().value()).isEqualTo(403);
        assertThat(new String(second.getBody().readAllBytes(), UTF_8))
                .contains("forbids access via a personal access token");
    }

    @Test
    void graphQlCallsForADeniedOrgAreBlockedToo()
            throws IOException
    {
        GitHubOrgAccessInterceptor interceptor = new GitHubOrgAccessInterceptor(new GitHubOrgAccess());
        AtomicInteger executed = new AtomicInteger();
        ClientHttpRequestExecution execution = (request, body) -> {
            executed.incrementAndGet();
            return new MockClientHttpResponse(DENIAL_BODY.getBytes(UTF_8), HttpStatusCode.valueOf(403));
        };

        interceptor.intercept(restRequest("https://api.github.com/repos/acme/cork/pulls/1"), new byte[0], execution);
        interceptor.intercept(
                restRequest("https://api.github.com/graphql"),
                "{\"query\":\"q\",\"variables\":{\"owner\":\"acme\",\"name\":\"cork\"}}".getBytes(UTF_8),
                execution);

        assertThat(executed).hasValue(1);
    }

    @Test
    void otherOrgsAndOtherTokensStillGoOut()
            throws IOException
    {
        GitHubOrgAccessInterceptor interceptor = new GitHubOrgAccessInterceptor(new GitHubOrgAccess());
        AtomicInteger executed = new AtomicInteger();
        ClientHttpRequestExecution execution = (request, body) -> {
            executed.incrementAndGet();
            return new MockClientHttpResponse(DENIAL_BODY.getBytes(UTF_8), HttpStatusCode.valueOf(403));
        };

        interceptor.intercept(restRequest("https://api.github.com/repos/acme/cork/pulls/1"), new byte[0], execution);
        interceptor.intercept(restRequest("https://api.github.com/repos/other/cork/pulls/1"), new byte[0], execution);
        // Same org, different PAT — a token swap must lift the block.
        interceptor.intercept(
                restRequest("https://api.github.com/repos/acme/cork/pulls/3", "Bearer new-token"),
                new byte[0],
                execution);

        assertThat(executed).hasValue(3);
    }

    @Test
    void plain403sDoNotBlockTheOrg()
            throws IOException
    {
        GitHubOrgAccessInterceptor interceptor = new GitHubOrgAccessInterceptor(new GitHubOrgAccess());
        AtomicInteger executed = new AtomicInteger();
        ClientHttpRequestExecution execution = (request, body) -> {
            executed.incrementAndGet();
            return new MockClientHttpResponse(
                    "{\"message\":\"API rate limit exceeded\"}".getBytes(UTF_8), HttpStatusCode.valueOf(403));
        };

        interceptor.intercept(restRequest("https://api.github.com/repos/acme/cork/pulls/1"), new byte[0], execution);
        interceptor.intercept(restRequest("https://api.github.com/repos/acme/cork/pulls/2"), new byte[0], execution);

        assertThat(executed).hasValue(2);
    }

    private static HttpRequest restRequest(String uri)
    {
        return restRequest(uri, "Bearer ghp_token");
    }

    private static HttpRequest restRequest(String uri, String authorization)
    {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, authorization);
        HttpRequest request = mock(HttpRequest.class);
        when(request.getURI()).thenReturn(URI.create(uri));
        when(request.getHeaders()).thenReturn(headers);
        return request;
    }
}

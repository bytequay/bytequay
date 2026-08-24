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
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;

import java.net.URI;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestGitHubRateLimitInterceptor
{
    @Test
    void blocksOnlyTheExhaustedResourceUntilReset()
            throws Exception
    {
        GitHubRateLimitInterceptor interceptor = new GitHubRateLimitInterceptor();
        HttpRequest coreRequest = request("https://api.github.com/repos/owner/repo");
        ClientHttpRequestExecution coreExecution = mock(ClientHttpRequestExecution.class);
        ClientHttpResponse exhaustedResponse = response("core", HttpStatus.OK);
        byte[] body = new byte[0];
        when(coreExecution.execute(coreRequest, body)).thenReturn(exhaustedResponse);

        interceptor.intercept(coreRequest, body, coreExecution);

        ClientHttpRequestExecution blockedExecution = mock(ClientHttpRequestExecution.class);
        assertThatThrownBy(() -> interceptor.intercept(coreRequest, body, blockedExecution))
                .isInstanceOf(GitHubRateLimitException.class);
        verify(blockedExecution, never()).execute(coreRequest, body);

        HttpRequest searchRequest = request("https://api.github.com/search/issues");
        ClientHttpRequestExecution searchExecution = mock(ClientHttpRequestExecution.class);
        ClientHttpResponse availableResponse = mock(ClientHttpResponse.class);
        when(availableResponse.getHeaders()).thenReturn(new HttpHeaders());
        when(availableResponse.getStatusCode()).thenReturn(org.springframework.http.HttpStatus.OK);
        when(searchExecution.execute(searchRequest, body)).thenReturn(availableResponse);
        interceptor.intercept(searchRequest, body, searchExecution);
        verify(searchExecution).execute(searchRequest, body);
    }

    @Test
    void translatesAnExhaustedGitHubResponseWithoutReturningItsBody()
            throws Exception
    {
        GitHubRateLimitInterceptor interceptor = new GitHubRateLimitInterceptor();
        HttpRequest request = request("https://api.github.com/repos/owner/repo");
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        ClientHttpResponse response = response("core", HttpStatus.FORBIDDEN);
        byte[] body = new byte[0];
        when(execution.execute(request, body)).thenReturn(response);

        assertThatThrownBy(() -> interceptor.intercept(request, body, execution))
                .isInstanceOf(GitHubRateLimitException.class);
        verify(response).close();
    }

    private static HttpRequest request(String uri)
    {
        HttpRequest request = mock(HttpRequest.class);
        when(request.getURI()).thenReturn(URI.create(uri));
        return request;
    }

    private static ClientHttpResponse response(String resource, HttpStatus status)
            throws Exception
    {
        ClientHttpResponse response = mock(ClientHttpResponse.class);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-RateLimit-Remaining", "0");
        headers.set("X-RateLimit-Reset", Long.toString(Instant.now().plusSeconds(3600).getEpochSecond()));
        headers.set("X-RateLimit-Resource", resource);
        when(response.getHeaders()).thenReturn(headers);
        when(response.getStatusCode()).thenReturn(status);
        return response;
    }
}

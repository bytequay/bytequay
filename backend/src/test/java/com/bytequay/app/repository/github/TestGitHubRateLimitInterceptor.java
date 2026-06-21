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
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestGitHubRateLimitInterceptor
{
    @Test
    void capturesRateLimitHeadersIntoTheMonitor()
            throws IOException
    {
        GitHubRateLimitMonitor monitor = new GitHubRateLimitMonitor();
        GitHubRateLimitInterceptor interceptor = new GitHubRateLimitInterceptor(monitor);

        HttpHeaders headers = new HttpHeaders();
        headers.add("X-RateLimit-Remaining", "4987");
        headers.add("X-RateLimit-Limit", "5000");
        headers.add("X-RateLimit-Reset", "1781000000");
        ClientHttpResponse response = mock(ClientHttpResponse.class);
        when(response.getHeaders()).thenReturn(headers);
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        when(execution.execute(any(), any())).thenReturn(response);

        interceptor.intercept(mock(HttpRequest.class), new byte[0], execution);

        assertThat(monitor.latest()).hasValueSatisfying(s -> {
            assertThat(s.remaining()).isEqualTo(4987);
            assertThat(s.limit()).isEqualTo(5000);
            assertThat(s.resetAt()).isEqualTo(Instant.ofEpochSecond(1781000000L));
        });
    }

    @Test
    void absentHeadersLeaveTheMonitorEmpty()
            throws IOException
    {
        GitHubRateLimitMonitor monitor = new GitHubRateLimitMonitor();
        GitHubRateLimitInterceptor interceptor = new GitHubRateLimitInterceptor(monitor);

        ClientHttpResponse response = mock(ClientHttpResponse.class);
        when(response.getHeaders()).thenReturn(new HttpHeaders());
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        when(execution.execute(any(), any())).thenReturn(response);

        interceptor.intercept(mock(HttpRequest.class), new byte[0], execution);

        assertThat(monitor.latest()).isEmpty();
    }
}

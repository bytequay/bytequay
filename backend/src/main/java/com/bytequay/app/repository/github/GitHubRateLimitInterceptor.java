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

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.time.Instant;

import static java.util.Objects.requireNonNull;

/**
 * Captures GitHub's {@code X-RateLimit-*} headers off every REST/GraphQL
 * response and feeds them to {@link GitHubRateLimitMonitor}. Header parsing
 * never fails the call — a malformed or absent header is just skipped.
 * Wired as a {@code @Bean} in {@code WebConfig}.
 */
public class GitHubRateLimitInterceptor
        implements ClientHttpRequestInterceptor
{
    private final GitHubRateLimitMonitor monitor;

    public GitHubRateLimitInterceptor(GitHubRateLimitMonitor monitor)
    {
        this.monitor = requireNonNull(monitor, "monitor is null");
    }

    @Override
    public ClientHttpResponse intercept(
            HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException
    {
        ClientHttpResponse response = execution.execute(request, body);
        Integer remaining = parseInt(response.getHeaders().getFirst("X-RateLimit-Remaining"));
        Integer limit = parseInt(response.getHeaders().getFirst("X-RateLimit-Limit"));
        Long resetEpochSec = parseLong(response.getHeaders().getFirst("X-RateLimit-Reset"));
        if (remaining != null && limit != null && resetEpochSec != null) {
            monitor.record(remaining, limit, Instant.ofEpochSecond(resetEpochSec));
        }
        return response;
    }

    private static Integer parseInt(String value)
    {
        try {
            return value == null ? null : Integer.parseInt(value.trim());
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long parseLong(String value)
    {
        try {
            return value == null ? null : Long.parseLong(value.trim());
        }
        catch (NumberFormatException e) {
            return null;
        }
    }
}

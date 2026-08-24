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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public final class GitHubRateLimitInterceptor
        implements ClientHttpRequestInterceptor
{
    private static final Logger log = LoggerFactory.getLogger(GitHubRateLimitInterceptor.class);
    private static final String RATE_LIMIT_REMAINING = "X-RateLimit-Remaining";
    private static final String RATE_LIMIT_RESET = "X-RateLimit-Reset";
    private static final String RATE_LIMIT_RESOURCE = "X-RateLimit-Resource";

    private final ConcurrentMap<String, Long> blockedUntil = new ConcurrentHashMap<>();

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException
    {
        String requestResource = resourceForPath(request.getURI().getPath());
        rejectWhileBlocked(requestResource);

        ClientHttpResponse response = execution.execute(request, body);
        String responseResource = response.getHeaders().getFirst(RATE_LIMIT_RESOURCE);
        String resource = responseResource == null || responseResource.isBlank() ? requestResource : responseResource.toLowerCase(Locale.ROOT);
        long resetAt = resetAt(response.getHeaders());
        boolean exhausted = "0".equals(response.getHeaders().getFirst(RATE_LIMIT_REMAINING));
        int status = response.getStatusCode().value();
        boolean retryRequested = (status == 403 || status == 429) && response.getHeaders().containsKey(HttpHeaders.RETRY_AFTER);
        if (exhausted || retryRequested) {
            if (resetAt <= 0) {
                resetAt = Instant.now().plusSeconds(60).getEpochSecond();
            }
            block(resource, resetAt);
            if (response.getStatusCode().isError()) {
                response.close();
                throw new GitHubRateLimitException(resource, resetAt);
            }
        }
        return response;
    }

    private void rejectWhileBlocked(String resource)
    {
        Long resetAt = blockedUntil.get(resource);
        if (resetAt == null) {
            return;
        }
        if (resetAt <= Instant.now().getEpochSecond()) {
            blockedUntil.remove(resource, resetAt);
            return;
        }
        throw new GitHubRateLimitException(resource, resetAt);
    }

    private void block(String resource, long resetAt)
    {
        AtomicBoolean changed = new AtomicBoolean();
        blockedUntil.compute(resource, (ignored, current) -> {
            if (current == null || resetAt > current) {
                changed.set(true);
                return resetAt;
            }
            return current;
        });
        if (changed.get()) {
            log.warn("GitHub {} API rate limit reached; pausing requests until {}", resource, Instant.ofEpochSecond(resetAt));
        }
    }

    private static long resetAt(HttpHeaders headers)
    {
        String reset = headers.getFirst(RATE_LIMIT_RESET);
        if (reset != null) {
            try {
                return Long.parseLong(reset);
            }
            catch (NumberFormatException ignored) {
                // Fall through to Retry-After.
            }
        }
        String retryAfter = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (retryAfter != null) {
            try {
                return Instant.now().plusSeconds(Long.parseLong(retryAfter)).getEpochSecond();
            }
            catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static String resourceForPath(String path)
    {
        return path != null && path.startsWith("/search/") ? "search" : "core";
    }
}

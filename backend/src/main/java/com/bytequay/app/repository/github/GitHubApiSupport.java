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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

/**
 * Shared GitHub client mechanics that should stay identical across REST and
 * GraphQL call groups: auth header formatting, PAT guards, and readable API
 * error translation.
 */
final class GitHubApiSupport
{
    private static final Logger log = LoggerFactory.getLogger(GitHubApiSupport.class);
    private static final ObjectMapper ERROR_MAPPER = new ObjectMapper();

    private GitHubApiSupport() {}

    static String authorization(String pat)
    {
        return "Bearer " + pat;
    }

    static void requirePat(String pat)
    {
        if (pat == null || pat.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(401), "GitHub PAT missing");
        }
    }

    static ResponseStatusException toReadableException(RestClientResponseException e)
    {
        HttpStatusCode status = HttpStatusCode.valueOf(e.getStatusCode().value());
        String fallback = switch (e.getStatusCode().value()) {
            case 401 -> "GitHub rejected the PAT. Check that the token is valid and not expired.";
            case 403 -> "GitHub denied the request. The token may be missing scopes or you may be rate limited.";
            // 422 is reused across many endpoints (search query syntax, review
            // validation, "Can not approve your own pull request", etc.), so
            // fall back to GitHub's own message rather than guessing.
            default -> "GitHub API request failed with status " + e.getStatusCode().value() + ".";
        };
        String responseBody = e.getResponseBodyAsString();
        // Log GitHub's raw response so the real reason is recoverable even
        // when GitHub only sends a generic top-level message.
        log.warn("GitHub API {} failed: {}", e.getStatusCode().value(),
                responseBody == null || responseBody.isBlank() ? "(empty body)" : responseBody);
        String githubMessage = extractGitHubErrorMessage(responseBody);
        String message = githubMessage != null ? githubMessage : fallback;
        return new ResponseStatusException(status, message, e);
    }

    /**
     * Pulls the human-readable message out of a GitHub error response. GitHub
     * returns {@code {"message": "...", "errors": [...], "documentation_url": "..."}};
     * we surface {@code message} and the first {@code errors[].message} when
     * present.
     */
    static String extractGitHubErrorMessage(String body)
    {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = ERROR_MAPPER.readTree(body);
            String top = root.path("message").asText(null);
            if (top == null || top.isBlank()) {
                return null;
            }
            JsonNode errors = root.path("errors");
            if (errors.isArray() && !errors.isEmpty()) {
                JsonNode first = errors.get(0);
                String detail = first.path("message").asText(null);
                if (detail != null && !detail.isBlank()) {
                    return top + ": " + detail;
                }
            }
            return top;
        }
        catch (IOException | RuntimeException ignored) {
            return null;
        }
    }
}

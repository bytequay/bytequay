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
import com.google.common.collect.ImmutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriBuilder;

import java.io.IOException;
import java.net.URI;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Shared GitHub client mechanics: authenticated requests, PAT guards,
 * readable API error translation, and issue-type response decoding.
 */
final class GitHubApiSupport
{
    private static final Logger log = LoggerFactory.getLogger(GitHubApiSupport.class);
    private static final ObjectMapper ERROR_MAPPER = new ObjectMapper();
    /** Max chars of an error body we log. GitHub's JSON errors are tiny; its
     *  transient 5xx "unicorn" pages are large HTML blobs (inline base64
     *  images and all) that carry nothing actionable, so we collapse those. */
    private static final int MAX_LOGGED_BODY = 1000;

    private GitHubApiSupport() {}

    static String authorization(String pat)
    {
        return "Bearer " + pat;
    }

    /** Starts an authenticated GET; the caller adds any conditional headers before {@code retrieve()}. */
    static RestClient.RequestHeadersSpec<?> get(RestClient client, String pat, String uri, Object... uriVariables)
    {
        return client.get().uri(uri, uriVariables).header("Authorization", authorization(pat));
    }

    static RestClient.RequestHeadersSpec<?> get(RestClient client, String pat, Function<UriBuilder, URI> uri)
    {
        return client.get().uri(uri).header("Authorization", authorization(pat));
    }

    static RestClient.RequestHeadersSpec<?> get(RestClient client, String pat, URI uri)
    {
        return client.get().uri(uri).header("Authorization", authorization(pat));
    }

    /** Sends an authenticated JSON write; the caller decodes the response. */
    static RestClient.ResponseSpec send(RestClient client, HttpMethod method, String pat, Object body, String uri, Object... uriVariables)
    {
        return client.method(method).uri(uri, uriVariables).header("Authorization", authorization(pat)).contentType(MediaType.APPLICATION_JSON).body(body).retrieve();
    }

    /** Posts one GraphQL document with its variables; the caller decodes the response. */
    static RestClient.ResponseSpec graphql(RestClient client, String pat, String query, Object variables)
    {
        return client.post().header("Authorization", authorization(pat)).body(ImmutableMap.of("query", query, "variables", variables)).retrieve();
    }

    /** GitHub answered with no body where one was required. */
    static <T> T requireBody(T body, String message)
    {
        if (body == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(502), message);
        }
        return body;
    }

    static void requirePat(String pat)
    {
        if (pat == null || pat.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(401), "GitHub PAT missing");
        }
    }

    static String issueTypeName(JsonNode type)
    {
        if (type == null || type.isNull()) {
            return null;
        }
        return type.isTextual() ? type.asText() : type.path("name").textValue();
    }

    /** Runs one GitHub call, translating API failures into readable exceptions. */
    static <T> T translating(Supplier<T> call)
    {
        try {
            return call.get();
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
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
        // Log GitHub's response so the real reason is recoverable even when
        // GitHub only sends a generic top-level message — but summarize HTML
        // error pages and oversized bodies so a transient 5xx doesn't dump a
        // whole markup blob into the log.
        if (e.getStatusCode().value() == 404) {
            log.debug("GitHub API 404: {}", summarizeErrorBody(responseBody));
        }
        else {
            log.warn("GitHub API {} failed: {}", e.getStatusCode().value(), summarizeErrorBody(responseBody));
        }
        String githubMessage = extractGitHubErrorMessage(responseBody);
        String message = githubMessage != null ? githubMessage : fallback;
        if (e.getStatusCode().value() == 403
                && "Resource not accessible by personal access token".equals(githubMessage)) {
            message = "The configured GitHub token cannot perform this action. "
                    + "Grant it access to this repository and the required write permissions, "
                    + "then update it in Settings → Credentials.";
        }
        return new ResponseStatusException(status, message, e);
    }

    /**
     * Render an error body for the log: GitHub's small JSON errors verbatim,
     * but HTML error pages (transient 5xx "unicorn" pages) and anything
     * oversized collapsed to a one-line summary so the log stays readable.
     */
    static String summarizeErrorBody(String body)
    {
        if (body == null || body.isBlank()) {
            return "(empty body)";
        }
        String trimmed = body.strip();
        if (trimmed.startsWith("<")) {
            return "(HTML error page, " + body.length() + " bytes)";
        }
        if (trimmed.length() > MAX_LOGGED_BODY) {
            return trimmed.substring(0, MAX_LOGGED_BODY) + "… (truncated, " + trimmed.length() + " bytes)";
        }
        return trimmed;
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

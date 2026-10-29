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
import com.bytequay.core.hosting.HostingProvider;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.bytequay.core.hosting.HostingFailure.Kind.AUTHENTICATION_REQUIRED;
import static com.bytequay.core.hosting.HostingFailure.Kind.FORBIDDEN;
import static com.bytequay.core.hosting.HostingFailure.Kind.INVALID_RESPONSE;
import static com.bytequay.core.hosting.HostingFailure.Kind.MISSING_OR_INACCESSIBLE;
import static com.bytequay.core.hosting.HostingFailure.Kind.RATE_LIMITED;
import static com.bytequay.core.hosting.HostingFailure.Kind.REVISION_CONFLICT;
import static com.bytequay.core.hosting.HostingFailure.Kind.UNAVAILABLE;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

final class GitLabHttpClient
{
    private static final Pattern AUTHENTICATION_PARAMETER = Pattern.compile(
            "(?:^Bearer\\s+|,\\s*)([a-z][a-z0-9_]*)\\s*=\\s*(?:\"((?:\\\\.|[^\"\\\\])*)\"|([^,\\s]+))", Pattern.CASE_INSENSITIVE);
    private static final Set<String> API_RESOURCES = ImmutableSet.of("projects", "user", "users", "personal_access_tokens", "merge_requests", "issues",
            "pipelines", "jobs", "bridges", "repository", "languages", "notes", "discussions", "diffs", "changes", "commits", "approvals", "approve",
            "merge", "cancel_merge_when_pipeline_succeeds", "award_emoji", "trace", "retry");

    private final HttpClient http;
    private final ObjectReader reader;
    private final ObjectWriter writer;

    GitLabHttpClient(HttpClient http, ObjectMapper mapper)
    {
        this.http = requireNonNull(http, "http is null");
        ObjectMapper decoder = requireNonNull(mapper, "mapper is null").copy()
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT, DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY,
                        DeserializationFeature.UNWRAP_SINGLE_VALUE_ARRAYS, DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT,
                        DeserializationFeature.ACCEPT_EMPTY_ARRAY_AS_NULL_OBJECT);
        decoder.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        decoder.coercionConfigFor(LogicalType.Integer)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
        decoder.coercionConfigFor(LogicalType.Boolean)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
        decoder.coercionConfigFor(LogicalType.POJO).setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
        decoder.coercionConfigFor(LogicalType.Collection).setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
        this.reader = decoder.reader();
        this.writer = mapper.writer();
        checkArgument(http.followRedirects() == HttpClient.Redirect.NEVER, "hosting clients must not forward credentials through redirects");
    }

    <T> Response<T> get(HostingAccess access, String path, Optional<String> validator, Class<T> responseType)
    {
        return request(access, "GET", path, validator, Optional.empty(), responseType);
    }

    <T> Response<T> getOAuthTokenInfo(HostingAccess access, Class<T> responseType)
    {
        requireNonNull(access, "access is null");
        return request(access, "GET", URI.create(access.server().webBaseUrl() + "/oauth/token/info"), "token permission inspection",
                "<web>/oauth/token/info", Optional.empty(), Optional.empty(), responseType);
    }

    String getText(HostingAccess access, String path)
    {
        return request(access, "GET", path, Optional.empty(), Optional.empty(), String.class).body().orElseThrow();
    }

    <T> Response<T> post(HostingAccess access, String path, Object body, Class<T> responseType)
    {
        return request(access, "POST", path, Optional.empty(), Optional.of(body), responseType);
    }

    <T> Response<T> put(HostingAccess access, String path, Object body, Class<T> responseType)
    {
        return request(access, "PUT", path, Optional.empty(), Optional.of(body), responseType);
    }

    <T> Response<T> delete(HostingAccess access, String path, Class<T> responseType)
    {
        return request(access, "DELETE", path, Optional.empty(), Optional.empty(), responseType);
    }

    private <T> Response<T> request(HostingAccess access, String method, String path, Optional<String> validator, Optional<Object> body, Class<T> responseType)
    {
        requireNonNull(access, "access is null");
        return request(access, method, URI.create(access.server().apiBaseUrl() + "/" + path), operation(method, path), endpointTemplate(path), validator, body, responseType);
    }

    private <T> Response<T> request(HostingAccess access, String method, URI endpoint, String operation, String template,
            Optional<String> validator, Optional<Object> body, Class<T> responseType)
    {
        requireNonNull(access, "access is null");
        requireNonNull(validator, "validator is null");
        requireNonNull(responseType, "responseType is null");
        checkArgument(access.server().provider() == HostingProvider.GITLAB, "wrong hosting provider");
        String token = access.token();
        Optional<String> requestId = Optional.empty();
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json");
            validator.ifPresent(value -> request.header("If-None-Match", value));
            HttpRequest.BodyPublisher payload = body.isPresent()
                    ? HttpRequest.BodyPublishers.ofByteArray(writer.writeValueAsBytes(body.orElseThrow())) : HttpRequest.BodyPublishers.noBody();
            if (body.isPresent()) {
                request.header("Content-Type", "application/json");
            }
            HttpResponse<byte[]> response = readResponse(request.method(method, payload).build(), operation);
            requestId = response.headers().firstValue("X-Request-Id")
                    .filter(value -> value.matches("[A-Za-z0-9_-]{1,128}") && !value.contains(token));
            int status = response.statusCode();
            String failure = "GitLab " + operation + " failed (HTTP " + status + "). ";
            Optional<String> etag = response.headers().firstValue("etag");
            if (status == 304 && validator.isPresent()) {
                return new Response<>(Optional.empty(), etag.or(() -> validator), false);
            }
            switch (status) {
                case 200, 201, 202, 204 -> {}
                case 401 -> throw new HostingFailure(AUTHENTICATION_REQUIRED, failure + (operation.equals("token permission inspection")
                        ? "GitLab could not inspect this token's permissions. The token may be invalid or unsupported by this endpoint."
                        : "Your GitLab sign-in is invalid or expired. Reconnect GitLab in Settings."));
                case 403 -> throw new HostingFailure(FORBIDDEN, failure + (insufficientScope(response)
                        ? "GitLab reported insufficient_scope: your sign-in is missing required API permissions. Reconnect GitLab in Settings and grant api access."
                        : "GitLab denied this operation for the connected account. Check the GitLab connection's permissions in Settings."));
                case 404 -> throw new HostingFailure(MISSING_OR_INACCESSIBLE,
                        failure + "GitLab does not distinguish a missing resource from one this account cannot access. " +
                                "Use Check permissions in GitLab Settings to verify this account's API access.");
                case 409 -> throw new HostingFailure(REVISION_CONFLICT, failure + "GitLab reported a conflicting change. Refresh and try again.");
                case 429 -> throw new HostingFailure(RATE_LIMITED, failure + "GitLab's rate limit was reached. Try again later.", retryAfter(response));
                default -> {
                    if (status == 408 || status >= 500) {
                        throw new HostingFailure(UNAVAILABLE, failure + "GitLab is temporarily unavailable. Try again later.");
                    }
                    throw new HostingFailure(INVALID_RESPONSE, failure + "GitLab returned an unexpected response. Check the configured GitLab address.");
                }
            }
            if (responseType == Void.class) {
                return new Response<>(Optional.empty(), etag, false);
            }
            T decoded;
            try {
                decoded = responseType == String.class ? responseType.cast(new String(response.body(), StandardCharsets.UTF_8))
                        : reader.forType(responseType).readValue(response.body());
            }
            catch (IOException e) {
                throw new HostingFailure(INVALID_RESPONSE, failure + "GitLab returned invalid JSON.");
            }
            if (decoded == null) {
                throw new HostingFailure(INVALID_RESPONSE, failure + "GitLab returned no valid object.");
            }
            boolean hasNext = response.headers().allValues("Link").stream().anyMatch(link -> link.contains("rel=\"next\""));
            return new Response<>(Optional.of(decoded), etag, hasNext);
        }
        catch (HostingFailure failure) {
            throw new HostingFailure(failure.kind(), failure.getMessage() + diagnostics(method, template, requestId), failure.retryAfter());
        }
        catch (IOException e) {
            throw new HostingFailure(INVALID_RESPONSE, "Could not encode the GitLab " + operation + " request." + diagnostics(method, template, requestId));
        }
    }

    private static String diagnostics(String method, String template, Optional<String> requestId)
    {
        return "\n\nRequest: " + method + " " + template + "\nGitLab request ID: " + requestId.orElse("unavailable") + "\nTime (UTC): " + Instant.now();
    }

    private static String endpointTemplate(String path)
    {
        String[] parts = path.split("\\?", 2)[0].split("/", -1);
        boolean tokenInfo = parts[0].equals("personal_access_tokens");
        boolean repository = parts.length > 2 && parts[2].equals("repository");
        for (int index = 0; index < parts.length; index++) {
            if (tokenInfo && index == 1 && parts[index].equals("self")) {
                continue;
            }
            if (repository && index == 3 && (parts[index].equals("files") || parts[index].equals("compare"))) {
                continue;
            }
            // Odd segments identify accounts, projects or resources; file paths also occupy an even segment.
            if (index % 2 == 1 || repository && index >= 4) {
                parts[index] = "{id}";
            }
            else if (!API_RESOURCES.contains(parts[index])) {
                parts[index] = "{resource}";
            }
        }
        return "<api>/" + String.join("/", parts);
    }

    private boolean insufficientScope(HttpResponse<byte[]> response)
    {
        if (response.headers().allValues("WWW-Authenticate").stream().anyMatch(GitLabHttpClient::insufficientScope)) {
            return true;
        }
        try {
            ErrorResponse error = reader.forType(ErrorResponse.class).readValue(response.body());
            return error != null && "insufficient_scope".equals(error.error());
        }
        catch (IOException e) {
            return false;
        }
    }

    private static boolean insufficientScope(String challenge)
    {
        Matcher parameters = AUTHENTICATION_PARAMETER.matcher(challenge);
        while (parameters.find()) {
            if (parameters.group(1).equalsIgnoreCase("error")
                    && ("insufficient_scope".equals(parameters.group(2)) || "insufficient_scope".equals(parameters.group(3)))) {
                return true;
            }
        }
        return false;
    }

    private static String operation(String method, String path)
    {
        String[] parts = path.split("\\?", 2)[0].split("/", -1);
        if (parts[0].equals("personal_access_tokens")) {
            return "token permission inspection";
        }
        if (parts[0].equals("user") || parts[0].equals("users")) {
            return "account lookup";
        }
        if (!parts[0].equals("projects")) {
            return "API request";
        }
        if (parts.length == 1) {
            return "project search";
        }
        if (parts.length == 2) {
            return "project access";
        }
        return switch (parts[2]) {
            case "merge_requests" -> {
                if (parts.length == 3) {
                    yield method.equals("GET") ? "merge request list" : "merge request creation";
                }
                if (parts.length == 4) {
                    yield method.equals("GET") ? "merge request details" : "merge request update";
                }
                yield switch (parts[4]) {
                    case "discussions" -> "merge request discussions";
                    case "notes" -> "merge request comments";
                    case "diffs", "changes" -> "merge request changes";
                    case "commits" -> "merge request commits";
                    case "approvals", "approve" -> "merge request approval";
                    case "merge", "cancel_merge_when_pipeline_succeeds" -> "merge request merge";
                    default -> "merge request operation";
                };
            }
            case "pipelines" -> parts.length > 4 ? switch (parts[4]) {
                case "jobs" -> "pipeline jobs";
                case "bridges" -> "pipeline trigger jobs";
                default -> "pipeline details";
            } : "pipeline details";
            case "jobs" -> parts.length > 4 && parts[4].equals("trace") ? "pipeline job log" : "pipeline job";
            case "issues" -> parts.length > 4 ? "issue comments" : "issue access";
            case "repository" -> "repository content";
            default -> "project request";
        };
    }

    private HttpResponse<byte[]> readResponse(HttpRequest request, String operation)
    {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HostingFailure(UNAVAILABLE, "GitLab " + operation + " request was interrupted.");
        }
        catch (IOException e) {
            throw new HostingFailure(UNAVAILABLE, "Could not complete GitLab " + operation + ". Check connectivity and certificate trust.");
        }
    }

    private static Optional<Duration> retryAfter(HttpResponse<?> response)
    {
        return response.headers().firstValue("retry-after").flatMap(value -> {
            try {
                long seconds = Long.parseLong(value);
                return seconds >= 0 ? Optional.of(Duration.ofSeconds(seconds)) : Optional.empty();
            }
            catch (NumberFormatException e) {
                return Optional.empty();
            }
        });
    }

    static String encode(String value)
    {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    record Response<T>(Optional<T> body, Optional<String> etag, boolean hasNext) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ErrorResponse(String error) {}
}

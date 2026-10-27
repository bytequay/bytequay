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
package com.bytequay.integration.github.impl;

import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingProvider;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.type.LogicalType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static com.bytequay.core.domain.Checks.checkText;
import static com.bytequay.core.hosting.HostingFailure.Kind.AUTHENTICATION_REQUIRED;
import static com.bytequay.core.hosting.HostingFailure.Kind.FORBIDDEN;
import static com.bytequay.core.hosting.HostingFailure.Kind.INVALID_RESPONSE;
import static com.bytequay.core.hosting.HostingFailure.Kind.MISSING_OR_INACCESSIBLE;
import static com.bytequay.core.hosting.HostingFailure.Kind.RATE_LIMITED;
import static com.bytequay.core.hosting.HostingFailure.Kind.REVISION_CONFLICT;
import static com.bytequay.core.hosting.HostingFailure.Kind.UNAVAILABLE;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

final class GitHubHttpClient
{
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    private final HttpClient http;
    private final ObjectReader reader;
    private final Duration timeout;

    GitHubHttpClient(HttpClient http, ObjectMapper mapper)
    {
        this(http, mapper, Duration.ofSeconds(30));
    }

    GitHubHttpClient(HttpClient http, ObjectMapper mapper, Duration timeout)
    {
        this.http = requireNonNull(http, "http is null");
        ObjectMapper responseMapper = requireNonNull(mapper, "mapper is null").copy();
        responseMapper.setConfig(responseMapper.getDeserializationConfig().without(MapperFeature.ALLOW_COERCION_OF_SCALARS));
        responseMapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        responseMapper.coercionConfigFor(LogicalType.Integer)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
        responseMapper.coercionConfigFor(LogicalType.Boolean)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
        responseMapper.registerModule(new SimpleModule().addDeserializer(Instant.class, new GitHubInstantDeserializer()));
        this.reader = responseMapper.reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT, DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY,
                        DeserializationFeature.UNWRAP_SINGLE_VALUE_ARRAYS, DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT,
                        DeserializationFeature.ACCEPT_EMPTY_ARRAY_AS_NULL_OBJECT);
        this.timeout = requireNonNull(timeout, "timeout is null");
        checkArgument(!timeout.isNegative() && !timeout.isZero(), "timeout must be positive");
        checkArgument(http.followRedirects() == HttpClient.Redirect.NEVER, "hosting clients must not forward credentials through redirects");
    }

    <T> T get(HostingAccess access, String path, Class<T> responseType)
    {
        return get(access, path, responseType, Optional.empty()).body()
                .orElseThrow(() -> new HostingFailure(INVALID_RESPONSE, "GitHub returned no body for an unconditional read"));
    }

    <T> Response<T> get(HostingAccess access, String path, Class<T> responseType, Optional<String> validator)
    {
        requireNonNull(access, "access is null");
        requireNonNull(validator, "validator is null");
        requireNonNull(responseType, "responseType is null");
        checkArgument(access.server().provider() == HostingProvider.GITHUB, "wrong hosting provider");
        URI endpoint = URI.create(access.server().apiBaseUrl().toASCIIString() + "/" + path);
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + access.token())
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28");
            validator.ifPresent(value -> request.header("If-None-Match", value));
            HttpResponse<byte[]> response = send(request.GET().build());
            int status = response.statusCode();
            if (status == 429 || status == 403 && response.headers().firstValue("x-ratelimit-remaining").filter("0"::equals).isPresent()) {
                throw new HostingFailure(RATE_LIMITED, "GitHub rate limit reached", retryAfter(response));
            }
            if (status >= 500) {
                throw new HostingFailure(UNAVAILABLE, "GitHub is temporarily unavailable");
            }
            Optional<String> responseValidator = response.headers().firstValue("etag").or(() -> validator);
            if (status == 304 && validator.isPresent()) {
                return new Response<>(Optional.empty(), responseValidator);
            }
            if (status != 200) {
                throw switch (status) {
                    case 401 -> new HostingFailure(AUTHENTICATION_REQUIRED, "GitHub authentication is required");
                    case 403 -> new HostingFailure(FORBIDDEN, "GitHub denied access with this account");
                    case 404 -> new HostingFailure(MISSING_OR_INACCESSIBLE, "The GitHub resource is missing or inaccessible to this account");
                    case 409 -> new HostingFailure(REVISION_CONFLICT, "GitHub reported a conflicting change");
                    default -> new HostingFailure(INVALID_RESPONSE, "GitHub returned an unexpected response; check the configured API address");
                };
            }
            T body = reader.forType(responseType).readValue(response.body());
            if (body == null) {
                throw new HostingFailure(INVALID_RESPONSE, "GitHub returned no valid object");
            }
            return new Response<>(Optional.of(body), responseValidator);
        }
        catch (IOException e) {
            throw new HostingFailure(INVALID_RESPONSE, "GitHub returned invalid JSON");
        }
    }

    private HttpResponse<byte[]> send(HttpRequest request)
    {
        CompletableFuture<HttpResponse<byte[]>> response = http.sendAsync(request, info -> boundedBody());
        try {
            // The request timeout alone does not bound a response body that stalls after headers.
            return response.get(timeout.toNanos(), NANOSECONDS);
        }
        catch (TimeoutException e) {
            response.cancel(true);
            throw new HostingFailure(UNAVAILABLE, "GitHub response timed out");
        }
        catch (InterruptedException e) {
            response.cancel(true);
            Thread.currentThread().interrupt();
            throw new HostingFailure(UNAVAILABLE, "GitHub request was interrupted");
        }
        catch (ExecutionException e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof HostingFailure failure) {
                    throw failure;
                }
            }
            throw new HostingFailure(UNAVAILABLE, "Could not read GitHub; check connectivity and certificate trust");
        }
    }

    static String repositoryPath(String fullPath)
    {
        checkText(fullPath, "fullPath");
        checkArgument(fullPath.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"), "invalid repository path");
        for (String segment : fullPath.split("/")) {
            checkArgument(!segment.equals(".") && !segment.equals(".."), "invalid repository path");
        }
        return "repos/" + fullPath;
    }

    private static HttpResponse.BodySubscriber<byte[]> boundedBody()
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        return HttpResponse.BodySubscribers.mapping(HttpResponse.BodySubscribers.ofByteArrayConsumer(chunk -> chunk.ifPresent(value -> {
            if (value.length > MAX_RESPONSE_BYTES - bytes.size()) {
                throw new HostingFailure(INVALID_RESPONSE, "GitHub response exceeded the permitted size");
            }
            bytes.writeBytes(value);
        })), ignored -> bytes.toByteArray());
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

    record Response<T>(Optional<T> body, Optional<String> validator) {}
}

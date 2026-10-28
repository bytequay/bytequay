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
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

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
    private final HttpClient http;
    private final ObjectReader reader;

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
        checkArgument(http.followRedirects() == HttpClient.Redirect.NEVER, "hosting clients must not forward credentials through redirects");
    }

    <T> Response<T> get(HostingAccess access, String path, Optional<String> validator, Class<T> responseType)
    {
        requireNonNull(access, "access is null");
        requireNonNull(validator, "validator is null");
        requireNonNull(responseType, "responseType is null");
        checkArgument(access.server().provider() == HostingProvider.GITLAB, "wrong hosting provider");
        URI endpoint = URI.create(access.server().apiBaseUrl() + "/" + path);
        String token = access.token();
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(30))
                    .header("PRIVATE-TOKEN", token)
                    .header("Accept", "application/json");
            validator.ifPresent(value -> request.header("If-None-Match", value));
            HttpResponse<byte[]> response = readResponse(request.GET().build());
            int status = response.statusCode();
            Optional<String> etag = response.headers().firstValue("etag");
            if (status == 304 && validator.isPresent()) {
                return new Response<>(Optional.empty(), etag.or(() -> validator));
            }
            switch (status) {
                case 200 -> {}
                case 401 -> throw new HostingFailure(AUTHENTICATION_REQUIRED, "GitLab authentication is required");
                case 403 -> throw new HostingFailure(FORBIDDEN, "GitLab denied access with this account");
                case 404 -> throw new HostingFailure(MISSING_OR_INACCESSIBLE, "The GitLab resource is missing or inaccessible to this account");
                case 409 -> throw new HostingFailure(REVISION_CONFLICT, "GitLab reported a conflicting change");
                case 429 -> throw new HostingFailure(RATE_LIMITED, "GitLab rate limit reached", retryAfter(response));
                default -> {
                    if (status == 408 || status >= 500) {
                        throw new HostingFailure(UNAVAILABLE, "GitLab is temporarily unavailable");
                    }
                    throw new HostingFailure(INVALID_RESPONSE, "GitLab returned an unexpected response; check the configured API address");
                }
            }
            T body = reader.forType(responseType).readValue(response.body());
            if (body == null) {
                throw new HostingFailure(INVALID_RESPONSE, "GitLab returned no valid object");
            }
            return new Response<>(Optional.of(body), etag);
        }
        catch (IOException e) {
            throw new HostingFailure(INVALID_RESPONSE, "GitLab returned invalid JSON");
        }
    }

    private HttpResponse<byte[]> readResponse(HttpRequest request)
    {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HostingFailure(UNAVAILABLE, "GitLab request was interrupted");
        }
        catch (IOException e) {
            throw new HostingFailure(UNAVAILABLE, "Could not read GitLab; check connectivity and certificate trust");
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

    record Response<T>(Optional<T> body, Optional<String> etag) {}
}

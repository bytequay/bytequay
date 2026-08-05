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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/**
 * Learns which orgs reject the configured token (403 "forbids access via a
 * personal access token") and short-circuits later calls to those orgs with the
 * same 403, without touching the network. Callers see the identical exception
 * they would have got from GitHub, so no call site changes — they just stop
 * paying rate limit for a request that cannot succeed.
 *
 * <p>Wired as a {@code @Bean} in {@code WebConfig} on both the REST and GraphQL
 * GitHub clients.
 */
public class GitHubOrgAccessInterceptor
        implements ClientHttpRequestInterceptor
{
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int FORBIDDEN = 403;

    private final GitHubOrgAccess orgAccess;

    public GitHubOrgAccessInterceptor(GitHubOrgAccess orgAccess)
    {
        this.orgAccess = requireNonNull(orgAccess, "orgAccess is null");
    }

    @Override
    public ClientHttpResponse intercept(
            HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException
    {
        String owner = owner(request, body);
        if (owner == null) {
            return execution.execute(request, body);
        }
        String tokenId = GitHubOrgAccess.tokenId(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        if (orgAccess.isDenied(owner, tokenId)) {
            return deniedResponse(owner);
        }

        ClientHttpResponse response = execution.execute(request, body);
        if (response.getStatusCode().value() != FORBIDDEN) {
            return response;
        }
        // Reading the body consumes the stream, so hand the caller a replayable
        // copy. Only error responses land here, so nothing large is buffered.
        byte[] errorBody = response.getBody().readAllBytes();
        orgAccess.recordIfClassicPatDenial(owner, tokenId, new String(errorBody, UTF_8));
        return new ReplayedResponse(response.getStatusCode(), response.getStatusText(),
                response.getHeaders(), errorBody);
    }

    /**
     * Org a request is about: the first path segment after {@code /repos} or
     * {@code /orgs} for REST, the {@code owner} GraphQL variable otherwise.
     * Null for requests that aren't org-scoped (search, /user, rate limit).
     */
    private static String owner(HttpRequest request, byte[] body)
    {
        List<String> segments = request.getURI().getPath() == null ? List.of()
                : List.of(request.getURI().getPath().split("/"));
        for (int i = 0; i < segments.size() - 1; i++) {
            if ("repos".equals(segments.get(i)) || "orgs".equals(segments.get(i))) {
                return segments.get(i + 1);
            }
        }
        return graphQlOwner(body);
    }

    private static String graphQlOwner(byte[] body)
    {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            JsonNode owner = MAPPER.readTree(body).path("variables").path("owner");
            return owner.isTextual() ? owner.asText() : null;
        }
        catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static ClientHttpResponse deniedResponse(String owner)
    {
        // Same shape GitHub sends, so GitHubApiSupport surfaces the same
        // user-facing message it would for the real 403.
        String json = "{\"message\":\"`" + owner + "` forbids access via a personal access token (classic). "
                + "Please use a GitHub App, OAuth App, or a personal access token with fine-grained permissions.\","
                + "\"status\":\"403\"}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new ReplayedResponse(HttpStatusCode.valueOf(FORBIDDEN), "Forbidden", headers, json.getBytes(UTF_8));
    }

    /** A {@link ClientHttpResponse} over bytes we already hold. */
    private static final class ReplayedResponse
            implements ClientHttpResponse
    {
        private final HttpStatusCode statusCode;
        private final String statusText;
        private final HttpHeaders headers;
        private final byte[] body;

        private ReplayedResponse(HttpStatusCode statusCode, String statusText, HttpHeaders headers, byte[] body)
        {
            this.statusCode = statusCode;
            this.statusText = statusText;
            this.headers = headers;
            this.body = body;
        }

        @Override
        public HttpStatusCode getStatusCode()
        {
            return statusCode;
        }

        @Override
        public String getStatusText()
        {
            return statusText;
        }

        @Override
        public HttpHeaders getHeaders()
        {
            return headers;
        }

        @Override
        public InputStream getBody()
        {
            return new ByteArrayInputStream(body);
        }

        @Override
        public void close() {}
    }
}

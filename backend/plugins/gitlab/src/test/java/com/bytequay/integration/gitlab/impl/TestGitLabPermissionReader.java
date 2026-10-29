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
import com.bytequay.core.hosting.HostingServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.bytequay.core.hosting.HostingPermissions.Status.MISSING;
import static com.bytequay.core.hosting.HostingPermissions.Status.UNAVAILABLE;
import static com.bytequay.core.hosting.HostingPermissions.Status.VERIFIED;
import static com.bytequay.core.hosting.HostingProvider.GITLAB;
import static org.assertj.core.api.Assertions.assertThat;

class TestGitLabPermissionReader
{
    private static final String OAUTH = "/gitlab/oauth/token/info";
    private static final String PERSONAL = "/gitlab/api/v4/personal_access_tokens/self";
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();
    private final List<String> requests = new ArrayList<>();
    private HttpServer server;
    private HttpClient http;
    private HostingAccess access;
    private GitLabConnectionClient client;

    @BeforeEach
    void setUp()
            throws IOException
    {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().toASCIIString();
            requests.add(path);
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer secret_token");
            Reply reply = replies.getOrDefault(path, new Reply(404, "{}"));
            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status(), body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/gitlab";
        access = new HostingAccess(new HostingServer(GITLAB, URI.create(base), URI.create(base + "/api/v4")), "secret_token");
        http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        client = new GitLabConnectionClient(http, new ObjectMapper());
    }

    @AfterEach
    void tearDown()
    {
        http.close();
        server.stop(0);
    }

    @Test
    void inspectsOAuthPermissionsWithoutSendingTokensInUrls()
    {
        replies.put(OAUTH, new Reply(200, "{\"scope\":[\"read_user\",\"api\",\"api\"],\"resource_owner_id\":17}"));

        var permissions = client.checkPermissions(access).orElseThrow();

        assertThat(permissions.status()).isEqualTo(VERIFIED);
        assertThat(permissions.scopes()).containsExactly("api", "read_user");
        assertThat(permissions.detail()).contains("membership").doesNotContain("secret_token");
        assertThat(requests).containsExactly(OAUTH);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "[\"read_user\"]", "[\"read_api\",\"read_repository\"]"})
    void identifiesMissingApiPermission(String scopes)
    {
        replies.put(OAUTH, new Reply(200, "{\"scope\":" + scopes + "}"));

        var permissions = client.checkPermissions(access).orElseThrow();

        assertThat(permissions.status()).isEqualTo(MISSING);
        assertThat(permissions.detail()).contains("requires api", "Reconnect").doesNotContain("secret_token");
        assertThat(requests).containsExactly(OAUTH);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404, 405})
    void inspectsPersonalTokensWhenOAuthInspectionIsNotApplicable(int status)
    {
        replies.put(OAUTH, new Reply(status, "{}"));
        replies.put(PERSONAL, new Reply(200, "{\"scopes\":[\"api\"],\"active\":true,\"revoked\":false}"));

        var permissions = client.checkPermissions(access).orElseThrow();

        assertThat(permissions.status()).isEqualTo(VERIFIED);
        assertThat(permissions.scopes()).containsExactly("api");
        assertThat(requests).containsExactly(OAUTH, PERSONAL);
    }

    @Test
    void identifiesMissingPersonalTokenPermission()
    {
        replies.put(OAUTH, new Reply(401, "{}"));
        replies.put(PERSONAL, new Reply(200, "{\"scopes\":[\"read_user\"]}"));

        var permissions = client.checkPermissions(access).orElseThrow();

        assertThat(permissions.status()).isEqualTo(MISSING);
        assertThat(permissions.scopes()).containsExactly("read_user");
    }

    @Test
    void unsupportedInspectionDoesNotClaimPermissionsWereVerified()
    {
        var permissions = client.checkPermissions(access).orElseThrow();

        assertThat(permissions.status()).isEqualTo(UNAVAILABLE);
        assertThat(permissions.scopes()).isEmpty();
        assertThat(permissions.detail()).contains("may not support", "not been verified");
        assertThat(requests).containsExactly(OAUTH, PERSONAL);
    }

    @Test
    void legacyPersonalTokenCanVerifyAndDiscoverProjectsWithoutInspection()
    {
        String userPath = "/gitlab/api/v4/user";
        String projectsPath = "/gitlab/api/v4/projects?membership=true&order_by=last_activity_at&sort=desc&per_page=10";
        String base = access.server().webBaseUrl().toString();
        replies.put(userPath, new Reply(200, "{\"id\":17,\"username\":\"alice\",\"state\":\"active\"}"));
        replies.put(OAUTH, new Reply(401, "{\"error\":\"invalid_token\"}"));
        replies.put(PERSONAL, new Reply(404, "{\"error\":\"404 Not Found\"}"));
        // GitLab 14.10.5 lacks token self-inspection and can omit default_branch without download_code access.
        replies.put(projectsPath, new Reply(200, """
                [{"id":42,"path_with_namespace":"group/project","web_url":"%s/group/project",
                  "http_url_to_repo":"%s/group/project.git","topics":["tools"]}]
                """.formatted(base, base)));

        assertThat(client.verify(access).login()).isEqualTo("alice");
        assertThat(client.checkPermissions(access).orElseThrow().status()).isEqualTo(UNAVAILABLE);
        assertThat(client.search(access, "", 10)).singleElement().satisfies(project -> {
            assertThat(project.remoteId()).isEqualTo("42");
            assertThat(project.fullPath()).isEqualTo("group/project");
            assertThat(project.defaultBranch()).isEmpty();
        });
        assertThat(requests).containsExactly(userPath, OAUTH, PERSONAL, projectsPath);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"scope\":null}", "{\"scope\":[null]}", "{\"scope\":[\"api\",\"secret_token\"]}", "{\"scope\":[\"api\\nsecret\"]}",
            "{\"scope\":[7]}", "{\"scope\":\"api\"}", "{\"scope\":[\"api\"]} {}"})
    void malformedOrSensitiveScopeResponsesAreNeverShownAsVerified(String body)
    {
        replies.put(OAUTH, new Reply(200, body));

        var permissions = client.checkPermissions(access).orElseThrow();

        assertThat(permissions.status()).isEqualTo(UNAVAILABLE);
        assertThat(permissions.scopes()).isEmpty();
        assertThat(permissions.toString()).doesNotContain("secret_token", body);
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 503})
    void transientOAuthFailuresDoNotProbeAnotherTokenType(int status)
    {
        replies.put(OAUTH, new Reply(status, "{\"message\":\"secret_token\"}"));

        var permissions = client.checkPermissions(access).orElseThrow();

        assertThat(permissions.status()).isEqualTo(UNAVAILABLE);
        assertThat(permissions.detail()).contains("try again").doesNotContain("secret_token");
        assertThat(requests).containsExactly(OAUTH);
    }

    private record Reply(int status, String body) {}
}

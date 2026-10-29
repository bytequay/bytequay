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
import com.bytequay.core.hosting.HostingServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.bytequay.core.hosting.HostingFailure.Kind.INVALID_RESPONSE;
import static com.bytequay.core.hosting.HostingFailure.Kind.MISSING_OR_INACCESSIBLE;
import static com.bytequay.core.hosting.HostingFailure.Kind.REVISION_CONFLICT;
import static com.bytequay.core.hosting.HostingFailure.Kind.UNAVAILABLE;
import static com.bytequay.core.hosting.HostingProvider.GITLAB;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestGitLabMergeRequestDiffs
{
    private static final String PATH = "projects/42/merge_requests/7";
    private static final String PREFIX = "/gitlab/api/v4/" + PATH;
    private static final String FIRST_PAGE = PREFIX + "/diffs?per_page=100&page=1";
    private static final String CHANGES = PREFIX + "/changes";
    private static final String HEAD = "a".repeat(40);
    private static final String BASE = "b".repeat(40);
    private static final String FILE = """
            {"old_path":"old.txt","new_path":"new.txt","diff":"@@ -1 +1 @@\\n-old\\n+new","renamed_file":true}
            """;
    private static final String METADATA = "{\"project_id\":42,\"iid\":7,\"sha\":\"" + HEAD + "\"}";
    private static final String LEGACY = "{\"project_id\":42,\"iid\":7,\"sha\":\"" + HEAD + "\",\"overflow\":false,\"changes\":[" + FILE + "]}";
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private HttpClient http;
    private HostingAccess access;
    private GitLabMergeRequestDiffs diffs;

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
            Reply reply = replies.getOrDefault(path, new Reply(404, "{}", false));
            exchange.getResponseHeaders().set("X-Request-Id", "request-123");
            if (reply.hasNext()) {
                exchange.getResponseHeaders().set("Link", "<http://unused.invalid>; rel=\"next\"");
            }
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
        diffs = new GitLabMergeRequestDiffs(new GitLabHttpClient(http, new ObjectMapper()));
    }

    @AfterEach
    void tearDown()
    {
        http.close();
        server.stop(0);
    }

    @Test
    void readsEveryModernPageWithoutLegacyRequests()
    {
        replies.put(FIRST_PAGE, new Reply(200, "[" + FILE + "]", true));
        replies.put(PREFIX + "/diffs?per_page=100&page=2", new Reply(200,
                "[{\"old_path\":\"large.txt\",\"new_path\":\"large.txt\",\"too_large\":true,\"collapsed\":true,\"new_file\":true}]", false));

        var files = diffs.read(access, PATH);

        assertThat(files).hasSize(2);
        assertThat(files.getFirst().oldPath()).isEqualTo("old.txt");
        assertThat(files.getFirst().newPath()).isEqualTo("new.txt");
        assertThat(files.getFirst().diff()).isEqualTo("@@ -1 +1 @@\n-old\n+new");
        assertThat(files.getFirst().renamedFile()).isTrue();
        assertThat(files.getLast().tooLarge()).isTrue();
        assertThat(files.getLast().collapsed()).isTrue();
        assertThat(files.getLast().newFile()).isTrue();
        assertThat(files.getLast().diff()).isNull();
        assertThat(requests).containsExactly(FIRST_PAGE, PREFIX + "/diffs?per_page=100&page=2");
    }

    @Test
    void emptyModernDiffDoesNotTryLegacyChanges()
    {
        replies.put(FIRST_PAGE, new Reply(200, "[]", false));

        assertThat(diffs.read(access, PATH)).isEmpty();
        assertThat(requests).containsExactly(FIRST_PAGE);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesWhitespaceFileNames(boolean legacy)
    {
        String file = "{\"old_path\":\" \",\"new_path\":\" \",\"diff\":\"\"}";
        if (legacy) {
            replies.put(PREFIX, new Reply(200, METADATA, false));
            replies.put(CHANGES, new Reply(200, LEGACY.replace(FILE, file), false));
        }
        else {
            replies.put(FIRST_PAGE, new Reply(200, "[" + file + "]", false));
        }

        assertThat(diffs.read(access, PATH)).singleElement().satisfies(diff -> {
            assertThat(diff.oldPath()).isEqualTo(" ");
            assertThat(diff.newPath()).isEqualTo(" ");
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fallsBackToCompleteLegacyChangesOnlyAfterVerifyingMergeRequest(boolean includeRefs)
    {
        String refs = includeRefs ? ",\"diff_refs\":{\"head_sha\":\"" + HEAD + "\",\"start_sha\":\"" + BASE + "\",\"base_sha\":\"" + BASE + "\"}" : "";
        replies.put(PREFIX, new Reply(200, METADATA.substring(0, METADATA.length() - 1) + refs + "}", false));
        replies.put(CHANGES, new Reply(200, LEGACY.substring(0, LEGACY.length() - 1) + refs + "}", false));

        assertThat(diffs.read(access, PATH)).singleElement().satisfies(file -> {
            assertThat(file.oldPath()).isEqualTo("old.txt");
            assertThat(file.newPath()).isEqualTo("new.txt");
            assertThat(file.renamedFile()).isTrue();
            assertThat(file.diff()).isEqualTo("@@ -1 +1 @@\n-old\n+new");
        });
        assertThat(requests).containsExactly(FIRST_PAGE, PREFIX, CHANGES);
    }

    @ParameterizedTest
    @CsvSource({"401, AUTHENTICATION_REQUIRED", "403, FORBIDDEN", "429, RATE_LIMITED", "500, UNAVAILABLE", "503, UNAVAILABLE"})
    void otherModernFailuresNeverTriggerFallback(int status, HostingFailure.Kind kind)
    {
        replies.put(FIRST_PAGE, new Reply(status, "{\"message\":\"secret_token\"}", false));

        assertFailure(kind);
        assertThat(requests).containsExactly(FIRST_PAGE);
    }

    @Test
    void laterPageNotFoundDoesNotReplaceAPartialModernDiffWithLegacyChanges()
    {
        replies.put(FIRST_PAGE, new Reply(200, "[" + FILE + "]", true));

        assertFailure(MISSING_OR_INACCESSIBLE);
        assertThat(requests).containsExactly(FIRST_PAGE, PREFIX + "/diffs?per_page=100&page=2");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[null]", "[{}]", "[{\"old_path\":\"a\",\"new_path\":\"\"}]", "[{\"old_path\":\"a\",\"new_path\":3}]"})
    void malformedModernDiffIsNotSilentlyReplacedByLegacyChanges(String body)
    {
        replies.put(FIRST_PAGE, new Reply(200, body, false));

        assertFailure(INVALID_RESPONSE);
        assertThat(requests).containsExactly(FIRST_PAGE);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404, 429, 503})
    void inaccessibleMetadataDoesNotProbeLegacyChanges(int status)
    {
        replies.put(PREFIX, new Reply(status, "{}", false));

        assertThatThrownBy(() -> diffs.read(access, PATH)).isInstanceOf(HostingFailure.class).hasMessageContaining("HTTP " + status);
        assertThat(requests).containsExactly(FIRST_PAGE, PREFIX);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"project_id\":42,\"iid\":7}", "{\"project_id\":43,\"iid\":7,\"sha\":\"head\"}",
            "{\"project_id\":42,\"iid\":8,\"sha\":\"head\"}", "{\"project_id\":42,\"iid\":7,\"sha\":\" \"}"})
    void invalidMetadataDoesNotProbeLegacyChanges(String body)
    {
        replies.put(PREFIX, new Reply(200, body, false));

        assertFailure(INVALID_RESPONSE);
        assertThat(requests).containsExactly(FIRST_PAGE, PREFIX);
    }

    @ParameterizedTest
    @CsvSource({"401, AUTHENTICATION_REQUIRED", "403, FORBIDDEN", "404, MISSING_OR_INACCESSIBLE", "429, RATE_LIMITED", "503, UNAVAILABLE"})
    void failedLegacyRequestPreservesItsKindAndSafeDiagnostics(int status, HostingFailure.Kind kind)
    {
        replies.put(PREFIX, new Reply(200, METADATA, false));
        replies.put(CHANGES, new Reply(status, "{\"message\":\"secret_token\"}", false));

        assertThatThrownBy(() -> diffs.read(access, PATH)).isInstanceOfSatisfying(HostingFailure.class, failure -> {
            assertThat(failure.kind()).isEqualTo(kind);
            assertThat(failure.getMessage()).contains("HTTP " + status, "Request: GET <api>/projects/{id}/merge_requests/{id}/changes")
                    .contains("Time (UTC):", "GitLab request ID: request-123")
                    .doesNotContain("secret_token");
        });
        assertThat(requests).containsExactly(FIRST_PAGE, PREFIX, CHANGES);
    }

    @Test
    void refusesTruncatedLegacyChanges()
    {
        replies.put(PREFIX, new Reply(200, METADATA, false));
        replies.put(CHANGES, new Reply(200, LEGACY.replace("\"overflow\":false", "\"overflow\":true"), false));

        assertFailure(UNAVAILABLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "\"overflow\":null,", "\"overflow\":\"false\",", "\"overflow\":0,"})
    void requiresExplicitBooleanFalseForLegacyCompleteness(String overflow)
    {
        replies.put(PREFIX, new Reply(200, METADATA, false));
        replies.put(CHANGES, new Reply(200, LEGACY.replace("\"overflow\":false,", overflow), false));

        assertFailure(INVALID_RESPONSE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "\"changes\":null,", "\"changes\":{},", "\"changes\":[null],", "\"changes\":[{}],"})
    void rejectsMissingOrMalformedLegacyChanges(String changes)
    {
        replies.put(PREFIX, new Reply(200, METADATA, false));
        replies.put(CHANGES, new Reply(200, "{\"project_id\":42,\"iid\":7,\"sha\":\"" + HEAD + "\"," + changes + "\"overflow\":false}", false));

        assertFailure(INVALID_RESPONSE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"project_id\":43,\"iid\":7", "\"project_id\":42,\"iid\":8", "\"project_id\":0,\"iid\":7"})
    void rejectsLegacyChangesForAnotherMergeRequest(String identity)
    {
        replies.put(PREFIX, new Reply(200, METADATA, false));
        replies.put(CHANGES, new Reply(200, LEGACY.replace("\"project_id\":42,\"iid\":7", identity), false));

        assertFailure(INVALID_RESPONSE);
    }

    @Test
    void rejectsLegacyChangesFromAnotherHead()
    {
        replies.put(PREFIX, new Reply(200, METADATA, false));
        replies.put(CHANGES, new Reply(200, LEGACY.replace(HEAD, BASE), false));

        assertFailure(REVISION_CONFLICT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"head_sha", "start_sha", "base_sha"})
    void rejectsChangedLegacyDiffRefsEvenWithTheSameHead(String field)
    {
        String refs = ",\"diff_refs\":{\"head_sha\":\"" + HEAD + "\",\"start_sha\":\"" + BASE + "\",\"base_sha\":\"" + BASE + "\"}";
        String previous = field.equals("head_sha") ? HEAD : BASE;
        String changedRefs = refs.replace("\"" + field + "\":\"" + previous + "\"", "\"" + field + "\":\"" + "c".repeat(40) + "\"");
        replies.put(PREFIX, new Reply(200, METADATA.substring(0, METADATA.length() - 1) + refs + "}", false));
        replies.put(CHANGES, new Reply(200, LEGACY.substring(0, LEGACY.length() - 1) + changedRefs + "}", false));

        assertFailure(REVISION_CONFLICT);
    }

    @Test
    void matchingStaleDiffRefsCannotValidateTheCurrentHead()
    {
        String refs = ",\"diff_refs\":{\"head_sha\":\"" + BASE + "\",\"start_sha\":\"" + BASE + "\",\"base_sha\":\"" + BASE + "\"}";
        replies.put(PREFIX, new Reply(200, METADATA.substring(0, METADATA.length() - 1) + refs + "}", false));
        replies.put(CHANGES, new Reply(200, LEGACY.substring(0, LEGACY.length() - 1) + refs + "}", false));

        assertFailure(REVISION_CONFLICT);
        assertThat(requests).containsExactly(FIRST_PAGE, PREFIX);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"head_sha\":\"%s\"}", "{\"head_sha\":\"%s\",\"start_sha\":\" \",\"base_sha\":\"base\"}"})
    void rejectsMalformedLegacyDiffRefs(String refs)
    {
        replies.put(PREFIX, new Reply(200, METADATA, false));
        replies.put(CHANGES, new Reply(200, LEGACY.substring(0, LEGACY.length() - 1) + ",\"diff_refs\":" + refs.formatted(HEAD) + "}", false));

        assertFailure(INVALID_RESPONSE);
    }

    private void assertFailure(HostingFailure.Kind kind)
    {
        assertThatThrownBy(() -> diffs.read(access, PATH)).isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(kind));
    }

    private record Reply(int status, String body, boolean hasNext) {}
}

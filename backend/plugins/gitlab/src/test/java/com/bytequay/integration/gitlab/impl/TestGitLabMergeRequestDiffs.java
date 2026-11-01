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
    private static final String CHANGES = PREFIX + "/changes?access_raw_diffs=true";
    private static final String HEAD = "a".repeat(40);
    private static final String BASE = "b".repeat(40);
    private static final String COMPARE = "/gitlab/api/v4/projects/42/repository/compare?from=" + BASE + "&to=" + HEAD + "&straight=true";
    private static final String REFS = ",\"diff_refs\":{\"head_sha\":\"" + HEAD + "\",\"start_sha\":\"" + BASE + "\",\"base_sha\":\"" + BASE + "\"}";
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
    private GitLabDiffs paged;
    private GitLabDiffs legacy;

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
        GitLabHttpClient client = new GitLabHttpClient(http, new ObjectMapper());
        paged = new GitLabPagedDiffs(client);
        legacy = new GitLabLegacyDiffs(client);
    }

    @AfterEach
    void tearDown()
    {
        http.close();
        server.stop(0);
    }

    @Test
    void currentReleasesReadEveryPage()
    {
        replies.put(FIRST_PAGE, new Reply(200, "[" + FILE + "]", true));
        replies.put(PREFIX + "/diffs?per_page=100&page=2", new Reply(200,
                "[{\"old_path\":\"large.txt\",\"new_path\":\"large.txt\",\"too_large\":true,\"collapsed\":true,\"new_file\":true}]", false));

        var files = paged.mergeRequest(access, PATH);

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
    void anEmptyDiffIsOneRead()
    {
        replies.put(FIRST_PAGE, new Reply(200, "[]", false));

        assertThat(paged.mergeRequest(access, PATH)).isEmpty();
        assertThat(requests).containsExactly(FIRST_PAGE);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesWhitespaceFileNames(boolean older)
    {
        String file = "{\"old_path\":\" \",\"new_path\":\" \",\"diff\":\"\"}";
        if (older) {
            replies.put(PREFIX, new Reply(200, METADATA, false));
            replies.put(CHANGES, new Reply(200, LEGACY.replace(FILE, file), false));
        }
        else {
            replies.put(FIRST_PAGE, new Reply(200, "[" + file + "]", false));
        }

        assertThat((older ? legacy : paged).mergeRequest(access, PATH)).singleElement().satisfies(diff -> {
            assertThat(diff.oldPath()).isEqualTo(" ");
            assertThat(diff.newPath()).isEqualTo(" ");
        });
    }

    @ParameterizedTest
    @CsvSource({"401, AUTHENTICATION_REQUIRED", "403, FORBIDDEN", "404, MISSING_OR_INACCESSIBLE", "429, RATE_LIMITED", "500, UNAVAILABLE"})
    void currentReleaseFailuresKeepTheirKind(int status, HostingFailure.Kind kind)
    {
        replies.put(FIRST_PAGE, new Reply(status, "{\"message\":\"secret_token\"}", false));

        assertFailure(paged, kind);
        assertThat(requests).containsExactly(FIRST_PAGE);
    }

    @Test
    void aLaterMissingPageFailsInsteadOfReturningPart()
    {
        replies.put(FIRST_PAGE, new Reply(200, "[" + FILE + "]", true));

        assertFailure(paged, MISSING_OR_INACCESSIBLE);
        assertThat(requests).containsExactly(FIRST_PAGE, PREFIX + "/diffs?per_page=100&page=2");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[null]", "[{}]", "[{\"old_path\":\"a\",\"new_path\":\"\"}]", "[{\"old_path\":\"a\",\"new_path\":3}]"})
    void rejectsMalformedPages(String body)
    {
        replies.put(FIRST_PAGE, new Reply(200, body, false));

        assertFailure(paged, INVALID_RESPONSE);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void olderReleasesReadRawChangesAfterVerifyingTheMergeRequest(boolean includeRefs)
    {
        String refs = includeRefs ? REFS : "";
        replies.put(PREFIX, new Reply(200, METADATA.substring(0, METADATA.length() - 1) + refs + "}", false));
        replies.put(CHANGES, new Reply(200, LEGACY.substring(0, LEGACY.length() - 1) + refs + "}", false));

        assertThat(legacy.mergeRequest(access, PATH)).singleElement().satisfies(file -> {
            assertThat(file.oldPath()).isEqualTo("old.txt");
            assertThat(file.newPath()).isEqualTo("new.txt");
            assertThat(file.renamedFile()).isTrue();
            assertThat(file.diff()).isEqualTo("@@ -1 +1 @@\n-old\n+new");
            assertThat(file.collapsed()).isFalse();
        });
        assertThat(requests).containsExactly(PREFIX, CHANGES);
    }

    @Test
    void aFileWhoseTextWasLeftOutIsNotShown()
    {
        replies.put(PREFIX, new Reply(200, METADATA, false));
        replies.put(CHANGES, new Reply(200, LEGACY.replace(FILE, "{\"old_path\":\"big.txt\",\"new_path\":\"big.txt\",\"diff\":\"\"}"), false));

        assertThat(legacy.mergeRequest(access, PATH)).singleElement().satisfies(file -> assertThat(file.collapsed()).isTrue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"changes_count\":\"1+\",\"overflow\":false", "\"overflow\":true"})
    void filesPastTheServersDiffLimitsAreListedFromTheComparisonAsNotShown(String cut)
    {
        String changes = LEGACY.replace("\"overflow\":false", cut);
        String extra = "{\"old_path\":\"extra.txt\",\"new_path\":\"extra.txt\",\"diff\":\"\",\"new_file\":true}";
        replies.put(PREFIX, new Reply(200, METADATA.substring(0, METADATA.length() - 1) + REFS + "}", false));
        replies.put(CHANGES, new Reply(200, changes.substring(0, changes.length() - 1) + REFS + "}", false));
        replies.put(COMPARE, new Reply(200, "{\"compare_timeout\":false,\"diffs\":[" + FILE + "," + extra + "]}", false));

        var files = legacy.mergeRequest(access, PATH);

        assertThat(files).extracting(GitLabFileDiff::newPath).containsExactly("new.txt", "extra.txt");
        assertThat(files.getFirst().collapsed()).isFalse();
        assertThat(files.getLast().collapsed()).isTrue();
        assertThat(files.getLast().newFile()).isTrue();
        assertThat(requests).containsExactly(PREFIX, CHANGES, COMPARE);
    }

    @Test
    void cutChangesWithoutRevisionsToCompareAreRefused()
    {
        replies.put(PREFIX, new Reply(200, METADATA, false));
        replies.put(CHANGES, new Reply(200, LEGACY.replace("\"overflow\":false", "\"overflow\":true"), false));

        assertFailure(legacy, UNAVAILABLE);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404, 429, 503})
    void inaccessibleMetadataDoesNotReadChanges(int status)
    {
        replies.put(PREFIX, new Reply(status, "{}", false));

        assertThatThrownBy(() -> legacy.mergeRequest(access, PATH)).isInstanceOf(HostingFailure.class).hasMessageContaining("HTTP " + status);
        assertThat(requests).containsExactly(PREFIX);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"project_id\":42,\"iid\":7}", "{\"project_id\":43,\"iid\":7,\"sha\":\"head\"}",
            "{\"project_id\":42,\"iid\":8,\"sha\":\"head\"}", "{\"project_id\":42,\"iid\":7,\"sha\":\" \"}"})
    void invalidMetadataDoesNotReadChanges(String body)
    {
        replies.put(PREFIX, new Reply(200, body, false));

        assertFailure(legacy, INVALID_RESPONSE);
        assertThat(requests).containsExactly(PREFIX);
    }

    @ParameterizedTest
    @CsvSource({"401, AUTHENTICATION_REQUIRED", "403, FORBIDDEN", "404, MISSING_OR_INACCESSIBLE", "429, RATE_LIMITED", "503, UNAVAILABLE"})
    void failedChangesReadKeepsItsKindAndSafeDiagnostics(int status, HostingFailure.Kind kind)
    {
        replies.put(PREFIX, new Reply(200, METADATA, false));
        replies.put(CHANGES, new Reply(status, "{\"message\":\"secret_token\"}", false));

        assertThatThrownBy(() -> legacy.mergeRequest(access, PATH)).isInstanceOfSatisfying(HostingFailure.class, failure -> {
            assertThat(failure.kind()).isEqualTo(kind);
            assertThat(failure.getMessage()).contains("HTTP " + status, "Request: GET <api>/projects/{id}/merge_requests/{id}/changes")
                    .contains("Time (UTC):", "GitLab request ID: request-123")
                    .doesNotContain("secret_token");
        });
        assertThat(requests).containsExactly(PREFIX, CHANGES);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "\"overflow\":null,", "\"overflow\":\"false\",", "\"overflow\":0,"})
    void requiresAnExplicitOverflowFlag(String overflow)
    {
        replies.put(PREFIX, new Reply(200, METADATA, false));
        replies.put(CHANGES, new Reply(200, LEGACY.replace("\"overflow\":false,", overflow), false));

        assertFailure(legacy, INVALID_RESPONSE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "\"changes\":null,", "\"changes\":{},", "\"changes\":[null],", "\"changes\":[{}],"})
    void rejectsMissingOrMalformedChanges(String changes)
    {
        replies.put(PREFIX, new Reply(200, METADATA, false));
        replies.put(CHANGES, new Reply(200, "{\"project_id\":42,\"iid\":7,\"sha\":\"" + HEAD + "\"," + changes + "\"overflow\":false}", false));

        assertFailure(legacy, INVALID_RESPONSE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"project_id\":43,\"iid\":7", "\"project_id\":42,\"iid\":8", "\"project_id\":0,\"iid\":7"})
    void rejectsChangesForAnotherMergeRequest(String identity)
    {
        replies.put(PREFIX, new Reply(200, METADATA, false));
        replies.put(CHANGES, new Reply(200, LEGACY.replace("\"project_id\":42,\"iid\":7", identity), false));

        assertFailure(legacy, INVALID_RESPONSE);
    }

    @Test
    void rejectsChangesFromAnotherHead()
    {
        replies.put(PREFIX, new Reply(200, METADATA, false));
        replies.put(CHANGES, new Reply(200, LEGACY.replace(HEAD, BASE), false));

        assertFailure(legacy, REVISION_CONFLICT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"head_sha", "start_sha", "base_sha"})
    void rejectsChangedDiffRefsEvenWithTheSameHead(String field)
    {
        String previous = field.equals("head_sha") ? HEAD : BASE;
        String changedRefs = REFS.replace("\"" + field + "\":\"" + previous + "\"", "\"" + field + "\":\"" + "c".repeat(40) + "\"");
        replies.put(PREFIX, new Reply(200, METADATA.substring(0, METADATA.length() - 1) + REFS + "}", false));
        replies.put(CHANGES, new Reply(200, LEGACY.substring(0, LEGACY.length() - 1) + changedRefs + "}", false));

        assertFailure(legacy, REVISION_CONFLICT);
    }

    @Test
    void matchingStaleDiffRefsCannotValidateTheCurrentHead()
    {
        String refs = ",\"diff_refs\":{\"head_sha\":\"" + BASE + "\",\"start_sha\":\"" + BASE + "\",\"base_sha\":\"" + BASE + "\"}";
        replies.put(PREFIX, new Reply(200, METADATA.substring(0, METADATA.length() - 1) + refs + "}", false));
        replies.put(CHANGES, new Reply(200, LEGACY.substring(0, LEGACY.length() - 1) + refs + "}", false));

        assertFailure(legacy, REVISION_CONFLICT);
        assertThat(requests).containsExactly(PREFIX);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"head_sha\":\"%s\"}", "{\"head_sha\":\"%s\",\"start_sha\":\" \",\"base_sha\":\"base\"}"})
    void rejectsMalformedDiffRefs(String refs)
    {
        replies.put(PREFIX, new Reply(200, METADATA, false));
        replies.put(CHANGES, new Reply(200, LEGACY.substring(0, LEGACY.length() - 1) + ",\"diff_refs\":" + refs.formatted(HEAD) + "}", false));

        assertFailure(legacy, INVALID_RESPONSE);
    }

    private void assertFailure(GitLabDiffs diffs, HostingFailure.Kind kind)
    {
        assertThatThrownBy(() -> diffs.mergeRequest(access, PATH)).isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(kind));
    }

    private record Reply(int status, String body, boolean hasNext) {}
}

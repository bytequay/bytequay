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

import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.store.AppSettingsStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TestGitHubNotificationClient
        extends AbstractGitHubClientTest
{
    private static final Instant START = Instant.parse("2026-09-29T00:00:00Z");
    private static final String MODIFIED = "Tue, 29 Sep 2026 00:00:00 GMT";
    private static final String ENDPOINT = "https://api.github.test/notifications?all=true&participating=false&per_page=50&page=";
    private static final String KEY = "github.notifications.me";

    private final AtomicReference<Instant> now = new AtomicReference<>(START);
    private final Map<String, String> stored = new HashMap<>();
    private final AppSettingsStore settings = mock(AppSettingsStore.class);
    private final Clock clock = mock(Clock.class);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule()).registerModule(new Jdk8Module());
    private GitHubNotificationClient client;

    @BeforeEach
    void setUp()
    {
        when(clock.instant()).thenAnswer(ignored -> now.get());
        when(settings.get(anyString())).thenAnswer(invocation -> Optional.ofNullable(stored.get(invocation.getArgument(0))));
        doAnswer(invocation -> {
            stored.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(settings).put(anyString(), anyString());
        restart();
    }

    @Test
    void catchesUpBeyondFiftyIncludingReadThreadsAndIssues()
    {
        String first = IntStream.rangeClosed(1, 50).mapToObj(number -> thread(number, number != 2, number % 2 == 0, START)).collect(joining(",", "[", "]"));
        server.expect(requestTo(ENDPOINT + "1")).andExpect(headerDoesNotExist(HttpHeaders.IF_MODIFIED_SINCE))
                .andRespond(withSuccess(first, MediaType.APPLICATION_JSON).header(HttpHeaders.LAST_MODIFIED, MODIFIED)
                        .header(HttpHeaders.LINK, "<" + ENDPOINT + "2>; rel=\"next\""));
        server.expect(requestTo(ENDPOINT + "2")).andExpect(headerDoesNotExist(HttpHeaders.IF_MODIFIED_SINCE))
                .andRespond(withSuccess("[" + thread(51, true, false, START) + "]", MediaType.APPLICATION_JSON));
        List<GitHubNotificationRef> received = new ArrayList<>();

        client.poll("pat", "Me", received::add);

        assertThat(received).hasSize(51);
        assertThat(received.get(1).pullRequest()).isFalse();
        assertThat(received.get(1).ref()).isEqualTo(PullRequestRef.of("owner", "repo", 2));
        assertThat(client.reviewRequests("ME")).containsExactly(PullRequestRef.of("owner", "repo", 1));
        assertThat(stored.get(KEY)).contains("\"completedAt\"", "\"lastModified\"").doesNotContain("pat", "Bearer");
    }

    @Test
    void mentionsNameOnlyPullRequestsThatMentionTheViewerDirectly()
    {
        String body = "[" + thread(1, true, true, START) + "," + thread(3, true, true, START).replace("subscribed", "mention") + ","
                + thread(4, false, true, START).replace("subscribed", "mention") + "," + thread(5, true, true, START).replace("subscribed", "team_mention") + "]";
        server.expect(requestTo(ENDPOINT + "1")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        client.poll("pat", "me", ignored -> {});

        assertThat(client.mentions("ME")).containsExactly(PullRequestRef.of("owner", "repo", 3));
    }

    @Test
    void reviewRequestsRemainReadableDuringNotificationIngestion()
            throws Exception
    {
        server.expect(requestTo(ENDPOINT + "1"))
                .andRespond(withSuccess("[" + thread(1, true, true, START) + "]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT + "1"))
                .andRespond(withSuccess("[" + thread(2, true, true, START.plusSeconds(60)) + "]", MediaType.APPLICATION_JSON));
        client.poll("pat", "me", ignored -> {});
        now.set(START.plusSeconds(60));
        CountDownLatch ingesting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var poll = executor.submit(() -> client.poll("pat", "me", ignored -> {
                ingesting.countDown();
                try {
                    assertThat(release.await(10, SECONDS)).isTrue();
                }
                catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
            }));
            try {
                assertThat(ingesting.await(5, SECONDS)).isTrue();
                var read = executor.submit(() -> client.reviewRequests("ME"));
                assertThat(read.get(5, SECONDS)).containsExactly(PullRequestRef.of("owner", "repo", 1));
            }
            finally {
                release.countDown();
            }
            poll.get(5, SECONDS);
        }
        assertThat(client.reviewRequests("me")).containsExactlyInAnyOrder(PullRequestRef.of("owner", "repo", 1), PullRequestRef.of("owner", "repo", 2));
    }

    @Test
    void failedIngestionPersistsSuccessfulVersionsAcrossRestartWithoutCompletingItsCheckpoint()
            throws Exception
    {
        String firstPage = "[" + thread(1, true, true, START) + "," + thread(2, false, false, START) + "]";
        String body = "[" + thread(1, true, true, START) + "," + thread(2, false, false, START) + "," + thread(3, true, false, START) + "]";
        server.expect(requestTo(ENDPOINT + "1"))
                .andRespond(withSuccess(firstPage, MediaType.APPLICATION_JSON).header(HttpHeaders.LAST_MODIFIED, MODIFIED)
                        .header(HttpHeaders.LINK, "<" + ENDPOINT + "2>; rel=\"next\""));
        server.expect(requestTo(ENDPOINT + "2"))
                .andRespond(withSuccess("[" + thread(3, true, false, START) + "]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT + "1")).andExpect(headerDoesNotExist(HttpHeaders.IF_MODIFIED_SINCE))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON).header(HttpHeaders.LAST_MODIFIED, MODIFIED));
        server.expect(requestTo(ENDPOINT + "1")).andExpect(headerDoesNotExist(HttpHeaders.IF_MODIFIED_SINCE))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON).header(HttpHeaders.LAST_MODIFIED, MODIFIED));
        List<String> received = new ArrayList<>();

        assertThatThrownBy(() -> client.poll("pat", "me", item -> {
            if (item.id().equals("2")) {
                throw new IllegalStateException("SQLite ingestion failed");
            }
            received.add(item.id());
        })).isInstanceOf(IllegalStateException.class);
        assertThat(received).containsExactly("1", "3");
        var partial = mapper.readTree(stored.get(KEY));
        assertThat(partial.path("completedAt").isNull()).isTrue();
        assertThat(partial.path("lastModified").isNull()).isTrue();
        assertThat(partial.path("threads")).hasSize(2);
        assertThat(partial.at("/threads/0/id").asText()).isEqualTo("1");
        assertThat(partial.at("/threads/1/id").asText()).isEqualTo("3");
        restart();
        now.set(START.plusSeconds(60));
        client.poll("pat", "me", item -> received.add(item.id()));
        assertThat(received).containsExactly("1", "3", "2");

        restart();
        now.set(START.plusSeconds(120));
        client.poll("pat", "me", item -> {
            throw new AssertionError("Completed notification versions must remain deduplicated after restart");
        });
        assertThat(stored).containsKey(KEY);
    }

    @Test
    void inaccessibleSourceDoesNotPauseTheInboxPermissionWindow()
    {
        String body = "[" + thread(1, true, true, START) + "," + thread(2, false, false, START) + "]";
        server.expect(requestTo(ENDPOINT + "1")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT + "1")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        List<String> received = new ArrayList<>();
        assertThatThrownBy(() -> client.poll("pat", "me", item -> {
            if (item.id().equals("1")) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Source repository inaccessible");
            }
            received.add(item.id());
        })).isInstanceOf(ResponseStatusException.class);
        assertThat(received).containsExactly("2");

        now.set(START.plusSeconds(60));
        client.poll("pat", "me", item -> received.add(item.id()));

        assertThat(received).containsExactly("2", "1");
    }

    @Test
    void paginationFailureDoesNotCommitItsFirstPageValidatorOrCheckpoint()
            throws Exception
    {
        server.expect(requestTo(ENDPOINT + "1"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON).header(HttpHeaders.LAST_MODIFIED, MODIFIED));
        server.expect(requestTo(ENDPOINT + "1")).andExpect(header(HttpHeaders.IF_MODIFIED_SINCE, MODIFIED))
                .andRespond(withSuccess("[" + thread(1, true, true, START.plusSeconds(60)) + "]", MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.LAST_MODIFIED, "Tue, 29 Sep 2026 00:01:00 GMT")
                        .header(HttpHeaders.LINK, "<" + ENDPOINT + "2>; rel=\"next\""));
        server.expect(requestTo(ENDPOINT + "2")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(requestTo(ENDPOINT + "1")).andExpect(header(HttpHeaders.IF_MODIFIED_SINCE, MODIFIED))
                .andRespond(withSuccess("[" + thread(1, true, true, START.plusSeconds(60)) + "]", MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.LAST_MODIFIED, "Tue, 29 Sep 2026 00:01:00 GMT"));
        client.poll("pat", "me", ignored -> {});
        String previous = stored.get(KEY);
        now.set(START.plusSeconds(60));

        assertThatThrownBy(() -> client.poll("pat", "me", ignored -> {})).hasMessageContaining("502");
        var partial = mapper.readTree(stored.get(KEY));
        var completed = mapper.readTree(previous);
        assertThat(partial.path("completedAt")).isEqualTo(completed.path("completedAt"));
        assertThat(partial.path("lastModified")).isEqualTo(completed.path("lastModified"));
        assertThat(partial.path("threads")).hasSize(1);
        now.set(START.plusSeconds(120));
        List<GitHubNotificationRef> replayed = new ArrayList<>();
        client.poll("pat", "me", replayed::add);

        assertThat(replayed).isEmpty();
        assertThat(stored.get(KEY)).isNotEqualTo(previous);
    }

    @Test
    void stableConditionalPollHonorsTheIntervalAfterResponseCompletion()
    {
        server.expect(requestTo(ENDPOINT + "1")).andExpect(headerDoesNotExist(HttpHeaders.IF_MODIFIED_SINCE))
                .andRespond(request -> {
                    now.set(START.plusSeconds(30));
                    return withSuccess("[]", MediaType.APPLICATION_JSON).header(HttpHeaders.LAST_MODIFIED, MODIFIED)
                            .header("X-Poll-Interval", "90").createResponse(request);
                });
        server.expect(requestTo(ENDPOINT + "1")).andExpect(header(HttpHeaders.IF_MODIFIED_SINCE, MODIFIED))
                .andRespond(withStatus(HttpStatus.NOT_MODIFIED).header("X-Poll-Interval", "120"));
        client.poll("pat", "me", ignored -> {});
        String completed = stored.get(KEY);

        now.set(START.plusSeconds(119));
        client.poll("pat", "me", ignored -> {});
        now.set(START.plusSeconds(120));
        client.poll("pat", "me", ignored -> {});
        now.set(START.plusSeconds(239));
        client.poll("pat", "me", ignored -> {});

        assertThat(stored.get(KEY)).isEqualTo(completed);
    }

    @Test
    void tokenRotationClearsConditionalStateEvenWhenItsFirstRequestFails()
    {
        server.expect(requestTo(ENDPOINT + "1"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON).header(HttpHeaders.LAST_MODIFIED, MODIFIED));
        server.expect(requestTo(ENDPOINT + "1")).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer replacement"))
                .andExpect(headerDoesNotExist(HttpHeaders.IF_MODIFIED_SINCE)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        server.expect(requestTo(ENDPOINT + "1")).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer replacement"))
                .andExpect(headerDoesNotExist(HttpHeaders.IF_MODIFIED_SINCE)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT + "1")).andExpect(headerDoesNotExist(HttpHeaders.IF_MODIFIED_SINCE))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        client.poll("old", "me", ignored -> {});

        assertThatThrownBy(() -> client.poll("replacement", "ME", ignored -> {})).hasMessageContaining("403");
        now.set(START.plusSeconds(1799));
        client.poll("replacement", "me", ignored -> {});
        now.set(START.plusSeconds(1800));
        client.poll("replacement", "me", ignored -> {});
        client.poll("different", "other", ignored -> {});

        assertThat(stored).containsKeys(KEY, "github.notifications.other");
    }

    @Test
    void unsupportedOldThreadsStillEndTheCatchupAtTheCheckpoint()
    {
        String unsupported = thread(99, true, true, START.minus(Duration.ofDays(91))).replace("PullRequest", "Discussion");
        server.expect(requestTo(ENDPOINT + "1"))
                .andRespond(withSuccess("[" + unsupported + "]", MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.LINK, "<" + ENDPOINT + "2>; rel=\"next\""));

        client.poll("pat", "me", item -> {
            throw new AssertionError("An unsupported old notification is not ingested");
        });

        assertThat(stored).containsKey(KEY);
    }

    @Test
    void readsOnlyTheLinkedCommentAndRejectsAnotherHostOrRepository()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 1);
        GitHubNotificationRef notification = new GitHubNotificationRef("1", ref, true, "Title", "comment", START,
                Optional.of("https://api.github.com/repos/owner/repo/pulls/comments/123"));
        server.expect(requestTo("https://api.github.test/repos/owner/repo/pulls/comments/123")).andRespond(withSuccess("""
                {"updated_at":"2026-09-29T00:00:00Z","user":{"login":"alice"},"body":"Latest comment","path":"src/Thing.java","line":12}
                """, MediaType.APPLICATION_JSON));

        var activity = client.activity("pat", notification).orElseThrow();

        assertThat(activity.event()).isEqualTo("commented");
        assertThat(activity.actor()).contains("alice");
        assertThat(activity.excerpt()).contains("Latest comment");
        assertThat(client.activity("pat", new GitHubNotificationRef("1", ref, true, "Title", "comment", START,
                Optional.of("https://example.test/repos/owner/repo/pulls/comments/123")))).isEmpty();
        assertThat(client.activity("pat", new GitHubNotificationRef("1", ref, true, "Title", "comment", START,
                Optional.of("https://api.github.com/repos/another/repo/pulls/comments/123")))).isEmpty();
    }

    private static String thread(int number, boolean pullRequest, boolean unread, Instant updated)
    {
        return """
                {"id":"%d","unread":%s,"reason":"%s","updated_at":"%s","repository":{"full_name":"owner/repo"},
                 "subject":{"type":"%s","title":"Item %d","url":"https://api.github.com/repos/owner/repo/%s/%d","latest_comment_url":null}}
                """.formatted(number, unread, number <= 2 ? "review_requested" : "subscribed", updated, pullRequest ? "PullRequest" : "Issue",
                number, pullRequest ? "pulls" : "issues", number);
    }

    private void restart()
    {
        client = new GitHubNotificationClient(restClient, settings, mapper, clock);
    }
}

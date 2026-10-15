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

import com.bytequay.app.repository.github.GitHubAccountClient.GitHubEventItem;
import com.bytequay.core.domain.RecentEvent;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TestGitHubAccountClientEvents
{
    @Test
    void mapsActorAvatarAndPushCommitDetail()
    {
        GitHubEventItem event = event("PushEvent", new GitHubEventItem.Payload(
                2,
                null,
                null,
                "refs/heads/main",
                null,
                null,
                null,
                ImmutableList.of(new GitHubEventItem.CommitPayload("fix: inbox ack\n\nLong body"), new GitHubEventItem.CommitPayload("test: ack behavior"))));

        RecentEvent mapped = GitHubAccountClient.toRecentEvent(event);

        assertEquals("https://avatars.githubusercontent.com/u/1?v=4", mapped.actorAvatarUrl());
        assertEquals("fix: inbox ack · +1 more", mapped.detail());
        assertEquals("refs/heads/main", mapped.ref());
    }

    @Test
    void mapsReviewStateAndPullRequestTitle()
    {
        GitHubEventItem event = event("PullRequestReviewEvent", new GitHubEventItem.Payload(
                null,
                "created",
                null,
                null,
                new GitHubEventItem.PrPayload(36, "Inline comment drafts"),
                null,
                new GitHubEventItem.ReviewPayload("approved"),
                null));

        RecentEvent mapped = GitHubAccountClient.toRecentEvent(event);

        assertEquals(36, mapped.prNumber());
        assertEquals("Inline comment drafts", mapped.prTitle());
        assertNull(mapped.detail());
        assertEquals("approved", mapped.reviewState());
    }

    @ParameterizedTest
    @ValueSource(strings = {"events", "received_events"})
    void returnsNewestEventsFirstBeforeApplyingLimit(String feed)
    {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubAccountClient client = new GitHubAccountClient(builder.build(), RestClient.create());
        server.expect(requestTo("https://api.github.com/users/octocat/" + feed + "?per_page=2"))
                .andRespond(withSuccess("""
                        [
                          {"type":"PushEvent","created_at":"2026-09-29T00:00:00Z"},
                          {"type":"PushEvent","created_at":null},
                          {"type":"PullRequestReviewEvent","created_at":"2026-10-12T09:41:00Z"},
                          {"type":"IssueCommentEvent","created_at":"2026-10-12T09:00:00Z"}
                        ]
                        """, APPLICATION_JSON));

        List<RecentEvent> events = feed.equals("events") ? client.fetchUserEvents("token", "octocat", 2) : client.fetchReceivedEvents("token", "octocat", 2);

        assertEquals(ImmutableList.of(Instant.parse("2026-10-12T09:41:00Z"), Instant.parse("2026-10-12T09:00:00Z")), events.stream().map(RecentEvent::createdAt).toList());
        server.verify();
    }

    private static GitHubEventItem event(String type, GitHubEventItem.Payload payload)
    {
        return new GitHubEventItem(
                type,
                new GitHubUser("octocat", "https://avatars.githubusercontent.com/u/1?v=4", null),
                new GitHubEventItem.Repo("chenjian2664/ByteQuay"),
                payload,
                Instant.parse("2026-07-19T00:00:00Z"));
    }
}

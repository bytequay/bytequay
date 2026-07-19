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

import com.bytequay.app.domain.RecentEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TestGitHubEventMapper
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
                List.of(
                        new GitHubEventItem.CommitPayload("fix: inbox ack\n\nLong body"),
                        new GitHubEventItem.CommitPayload("test: ack behavior"))));

        RecentEvent mapped = GitHubEventMapper.toRecentEvent(event);

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

        RecentEvent mapped = GitHubEventMapper.toRecentEvent(event);

        assertEquals(36, mapped.prNumber());
        assertEquals("Inline comment drafts", mapped.prTitle());
        assertNull(mapped.detail());
        assertEquals("approved", mapped.reviewState());
    }

    private static GitHubEventItem event(String type, GitHubEventItem.Payload payload)
    {
        return new GitHubEventItem(
                type,
                new GitHubEventItem.Actor("octocat", "https://avatars.githubusercontent.com/u/1?v=4"),
                new GitHubEventItem.Repo("chenjian2664/ByteQuay"),
                payload,
                Instant.parse("2026-07-19T00:00:00Z"));
    }
}

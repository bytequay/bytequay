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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Response shape for {@code GET /repos/:owner/:repo/issues/:n/comments}.
 * One row per top-level PR/issue comment ("Add a comment" at the bottom);
 * does not include per-line review comments.
 *
 * @param reactions reactions tally on this issue or PR comment.
 * @param authorAssociation author's relationship to the repo.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubIssueComment(
        long id,
        User user,
        String body,
        @JsonProperty("html_url") String htmlUrl,
        @JsonProperty("created_at") Instant createdAt,
        Reactions reactions,
        @JsonProperty("author_association") String authorAssociation)
{
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record User(String login, @JsonProperty("avatar_url") String avatarUrl) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Reactions(
            @JsonProperty("+1") int plusOne,
            @JsonProperty("-1") int minusOne,
            int laugh,
            int hooray,
            int confused,
            int heart,
            int rocket,
            int eyes) {}
}

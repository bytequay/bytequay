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
 * Response shape for {@code /repos/{owner}/{repo}/pulls/{n}/comments}.
 * The endpoint returns one row per per-line review comment; replies on a
 * thread reference the thread root via {@link #inReplyToId}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubReviewComment(
        long id,
        @JsonProperty("in_reply_to_id") Long inReplyToId,
        @JsonProperty("pull_request_review_id") Long pullRequestReviewId,
        User user,
        String body,
        String path,
        Integer line,
        String side,
        // First line of a multi-line comment range. Null for single-line.
        // The pair (start_line, line) is the inclusive range covered.
        @JsonProperty("start_line") Integer startLine,
        @JsonProperty("start_side") String startSide,
        @JsonProperty("diff_hunk") String diffHunk,
        @JsonProperty("commit_id") String commitId,
        @JsonProperty("created_at") Instant createdAt,
        // The position in the diff hunk. Null when the comment is
        // outdated — i.e. the line it anchors to no longer exists in
        // the current diff (typically after a force-push). The pair
        // {@code (position, original_position)} lets us tell live from
        // outdated comments without a separate API call.
        Integer position,
        Reactions reactions,
        /** GitHub's author_association enum: COLLABORATOR, CONTRIBUTOR,
         *  FIRST_TIMER, FIRST_TIME_CONTRIBUTOR, MANNEQUIN, MEMBER, NONE,
         *  OWNER. Used by the UI to render the small role pill next to
         *  the comment author. */
        @JsonProperty("author_association") String authorAssociation)
{
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record User(String login) {}

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

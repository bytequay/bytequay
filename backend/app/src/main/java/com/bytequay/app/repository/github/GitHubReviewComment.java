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

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubReviewComment(
        Long id,
        User user,
        String body,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("html_url") String htmlUrl,
        @JsonProperty("pull_request_review_id") Long reviewId,
        @JsonProperty("in_reply_to_id") Long inReplyToId,
        String path,
        @JsonProperty("diff_hunk") String diffHunk,
        Integer line,
        @JsonProperty("original_line") Integer originalLine,
        @JsonProperty("start_line") Integer startLine,
        @JsonProperty("original_start_line") Integer originalStartLine,
        String side,
        GitHubReactions reactions)
{
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record User(String login, @JsonProperty("avatar_url") String avatarUrl, String type) {}
}

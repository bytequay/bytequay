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

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

public record GitHubSearchResponse(
        @JsonProperty("total_count") int totalCount,
        @JsonProperty("items") List<Item> items,
        @JsonProperty("incomplete_results") Boolean incompleteResults)
{
    public record Item(
            long id,
            int number,
            String title,
            @JsonProperty("html_url") String htmlUrl,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("updated_at") Instant updatedAt,
            @JsonProperty("closed_at") Instant closedAt,
            String state,
            @JsonProperty("repository_url") String repositoryUrl,
            User user,
            List<Label> labels,
            boolean draft,
            @JsonProperty("pull_request") PullRequestLink pullRequest,
            String body,
            @JsonProperty("state_reason") String stateReason) {}

    /** Search reports a merged PR as {@code state=closed}; {@code merged_at}
     *  lives only in this nested object, and is null for a plain close. */
    public record PullRequestLink(@JsonProperty("merged_at") Instant mergedAt) {}

    public record User(String login) {}

    public record Label(String name, String color) {}
}

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
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubSearchResponse(
        @JsonProperty("total_count") int totalCount,
        @JsonProperty("items") List<Item> items)
{
    @JsonIgnoreProperties(ignoreUnknown = true)
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
            @JsonProperty("pull_request") PullRequestLink pullRequest) {}

    /** Search reports a merged PR as {@code state=closed}; {@code merged_at}
     *  lives only in this nested object, and is null for a plain close. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PullRequestLink(@JsonProperty("merged_at") Instant mergedAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record User(String login) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Label(String name, String color) {}
}

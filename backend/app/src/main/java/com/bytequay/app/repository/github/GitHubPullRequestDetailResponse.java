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

public record GitHubPullRequestDetailResponse(
        long id,
        int number,
        @JsonProperty("closed_at") Instant closedAt,
        String title,
        @JsonProperty("html_url") String htmlUrl,
        String state,
        GitHubUser user,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt,
        @JsonProperty("merged_at") Instant mergedAt,
        String body,
        List<Label> labels,
        List<GitHubUser> assignees,
        Milestone milestone,
        boolean draft,
        int additions,
        int deletions,
        @JsonProperty("changed_files") int changedFiles,
        @JsonProperty("requested_reviewers") List<GitHubUser> requestedReviewers,
        @JsonProperty("requested_teams") List<Team> requestedTeams,
        Head head,
        Base base,
        Boolean mergeable,
        @JsonProperty("mergeable_state") String mergeableState)
{
    public record Label(String name, String color) {}

    public record Milestone(String title) {}

    public record Team(String slug) {}

    public record Head(String sha, String ref) {}

    public record Base(String sha, String ref) {}
}

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
package com.bytequay.integration.github.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
record GitHubPullRequestResponse(
        long id,
        int number,
        @JsonProperty("closed_at") Instant closedAt,
        String title,
        @JsonProperty("html_url") String htmlUrl,
        String state,
        User user,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt,
        @JsonProperty("merged_at") Instant mergedAt,
        String body,
        List<Label> labels,
        List<User> assignees,
        Milestone milestone,
        Boolean draft,
        Integer additions,
        Integer deletions,
        @JsonProperty("changed_files") Integer changedFiles,
        @JsonProperty("requested_reviewers") List<User> requestedReviewers,
        @JsonProperty("requested_teams") List<Team> requestedTeams,
        Head head,
        Base base,
        Boolean mergeable,
        @JsonProperty("mergeable_state") String mergeableState)
{
    @JsonIgnoreProperties(ignoreUnknown = true)
    record User(String login, @JsonProperty("avatar_url") String avatarUrl) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Label(String name, String color) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Milestone(String title) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Team(String slug) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Head(String sha, String ref) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Base(String sha, String ref, Repository repo) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Repository(long id) {}
}

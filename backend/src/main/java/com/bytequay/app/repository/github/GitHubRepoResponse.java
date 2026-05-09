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

/**
 * JSON shape returned by GitHub's {@code GET /repos/{owner}/{repo}}.
 * Drives {@link com.bytequay.app.domain.RepoMeta} on the repo detail
 * page right pane.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record GitHubRepoResponse(
        @JsonProperty("full_name") String fullName,
        @JsonProperty("html_url") String htmlUrl,
        String description,
        @JsonProperty("default_branch") String defaultBranch,
        License license,
        @JsonProperty("stargazers_count") int stargazersCount,
        @JsonProperty("forks_count") int forksCount,
        @JsonProperty("subscribers_count") int subscribersCount,
        @JsonProperty("open_issues_count") int openIssuesCount,
        long size,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("pushed_at") Instant pushedAt,
        List<String> topics,
        Owner owner,
        // Only populated when this repo is a fork. Drives the
        // fork → upstream view-focus dropdown on the detail page.
        Parent parent)
{
    @JsonIgnoreProperties(ignoreUnknown = true)
    record License(
            String key,
            String name,
            @JsonProperty("spdx_id") String spdxId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Owner(
            String login,
            @JsonProperty("avatar_url") String avatarUrl) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Parent(
            String name,
            @JsonProperty("default_branch") String defaultBranch,
            Owner owner) {}
}

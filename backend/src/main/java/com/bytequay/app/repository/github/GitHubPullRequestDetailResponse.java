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

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubPullRequestDetailResponse(
        String body,
        List<Label> labels,
        boolean draft,
        int additions,
        int deletions,
        @JsonProperty("changed_files") int changedFiles,
        @JsonProperty("requested_reviewers") List<RequestedReviewer> requestedReviewers,
        Head head,
        Base base,
        Boolean mergeable,
        @JsonProperty("mergeable_state") String mergeableState)
{
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Label(String name, String color) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RequestedReviewer(String login) {}

    /**
     * The PR's source side. {@code ref} is the branch name (e.g.
     * "feat/foo"); {@code repo.fullName} is the source repo's
     * "owner/repo" — present even on same-repo PRs but most useful
     * for cross-fork PRs where it differs from the base.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Head(String sha, String ref, Repo repo) {}

    /** The PR's target side (almost always the same repo's default branch). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Base(String ref, Repo repo) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Repo(@JsonProperty("full_name") String fullName) {}
}

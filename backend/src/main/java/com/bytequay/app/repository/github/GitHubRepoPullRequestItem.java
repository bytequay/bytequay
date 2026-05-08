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
record GitHubRepoPullRequestItem(
        long id,
        int number,
        String title,
        @JsonProperty("html_url") String htmlUrl,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt,
        @JsonProperty("closed_at") Instant closedAt,
        @JsonProperty("merged_at") Instant mergedAt,
        /** GitHub PR state: "open" or "closed". Note that GitHub does NOT use
         *  "merged" here — a merged PR has state="closed" with mergedAt set,
         *  which we collapse into a synthetic "merged" downstream. */
        String state,
        User user,
        List<Label> labels,
        boolean draft,
        @JsonProperty("requested_reviewers") List<RequestedReviewer> requestedReviewers,
        Head head)
{
    @JsonIgnoreProperties(ignoreUnknown = true)
    record User(String login) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Label(String name, String color) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RequestedReviewer(String login) {}

    /** PR's source branch — `head.ref` is the bare branch name on the
     *  head's repo. Used to join PRs back to local clones. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Head(String ref) {}
}

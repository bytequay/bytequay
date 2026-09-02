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

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubReviewReactionsResponse(Data data)
{
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(Repository repository) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Repository(PullRequest pullRequest) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PullRequest(Reviews reviews) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Reviews(List<Review> nodes, GitHubReviewThreadsResponse.PageInfo pageInfo) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Review(Long databaseId, List<ReactionGroup> reactionGroups) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReactionGroup(String content, Users users, Boolean viewerHasReacted) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Users(int totalCount) {}
}

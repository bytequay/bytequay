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

import java.util.List;

public record GitHubReviewThreadsResponse(Data data)
{
    public record Data(Repository repository) {}

    public record Repository(PullRequest pullRequest) {}

    public record PullRequest(ReviewThreads reviewThreads) {}

    public record ReviewThreads(List<Thread> nodes, PageInfo pageInfo) {}

    public record Thread(
            String id,
            boolean isResolved,
            boolean isOutdated,
            String path,
            Integer line,
            Integer originalLine,
            Integer startLine,
            Integer originalStartLine,
            String diffSide,
            Comments comments) {}

    public record Comments(List<Comment> nodes) {}

    public record Comment(Long databaseId, Review pullRequestReview) {}

    public record Review(boolean authorCanPushToRepository) {}

    public record PageInfo(boolean hasNextPage, String endCursor) {}
}

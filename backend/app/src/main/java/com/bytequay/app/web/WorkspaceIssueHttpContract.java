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
package com.bytequay.app.web;

import com.bytequay.app.agent.IssueDevelopmentService;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.RepositoryIssuePage;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;

/** Adds local development state without putting it into the replaceable GitHub issue cache. */
final class WorkspaceIssueHttpContract
{
    private WorkspaceIssueHttpContract() {}

    static Issue issue(String workspaceId, RepositoryIssue issue, IssueDevelopmentService development)
    {
        IssueDevelopmentService.Metadata metadata = development.metadata(workspaceId, issue.id());
        return new Issue(issue, metadata.isEpic(), metadata.developmentTask());
    }

    static Page page(String workspaceId, RepositoryIssuePage page, IssueDevelopmentService development)
    {
        return new Page(page.repositoryId(), page.repository(), page.issues().stream().map(issue -> issue(workspaceId, issue, development)).collect(toImmutableList()),
                page.page(), page.pageSize(), page.totalIssues(), page.hasMore(), page.syncedAt());
    }

    record Issue(@JsonUnwrapped RepositoryIssue issue, boolean isEpic, Optional<IssueDevelopmentService.TaskLink> developmentTask) {}

    record Page(
            long repositoryId,
            RepoRef repository,
            List<Issue> issues,
            int page,
            int pageSize,
            long totalIssues,
            boolean hasMore,
            Optional<Instant> syncedAt) {}
}

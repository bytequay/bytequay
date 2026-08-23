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
package com.bytequay.app.workspace;

import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.RepositoryIssueDetail;
import com.bytequay.core.domain.RepositoryIssuePage;
import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Optional;

public interface WorkspaceIssueService
{
    /**
     * Reads the latest local issue projection for a workspace's monitored repository.
     *
     * @param workspaceId workspace used to resolve the stable monitored repository ID
     * @return one cached page of open issues and its freshness timestamp
     */
    RepositoryIssuePage list(String workspaceId, int page, int pageSize);

    /** Returns one issue from the local projection without contacting GitHub. */
    Optional<RepositoryIssue> find(String workspaceId, int issueNumber);

    /** Returns locally persisted activity and type data without contacting GitHub. */
    RepositoryIssueDetail detail(String workspaceId, int issueNumber);

    /**
     * Posts a user-authored comment to the selected issue.
     *
     * @param workspaceId workspace used to resolve the monitored repository
     * @param issueNumber GitHub issue number
     * @param body non-blank Markdown comment body
     * @return the created comment as a timeline event
     */
    PrTimelineEvent comment(String workspaceId, int issueNumber, String body);

    IssueTypeOptions updateIssueType(String workspaceId, int issueNumber, String type);

    IssueMetadata updateMetadata(String workspaceId, int issueNumber, List<String> assignees, List<String> labels);

    record IssueTypeOptions(String current, List<String> available)
    {
        public IssueTypeOptions
        {
            available = ImmutableList.copyOf(available);
        }
    }

    record IssueMetadata(List<String> assignees, List<String> labels)
    {
        public IssueMetadata
        {
            assignees = ImmutableList.copyOf(assignees);
            labels = ImmutableList.copyOf(labels);
        }
    }
}

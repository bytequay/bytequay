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
package com.bytequay.app.jira;

import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Cached Jira source data; workspace, task links and Epic classification are local projections. */
public record JiraIssue(
        String connectionId, String cloudId, String id, String key, String url, String title, String body,
        String type, JiraDtos.Status status, Optional<JiraDtos.Person> assignee, List<String> labels,
        Optional<JiraDtos.Choice> priority, Optional<JiraDtos.Parent> parent, boolean hasChildren, boolean isEpic,
        Instant updatedAt, Optional<String> dueDate, Optional<JiraDtos.TaskLink> task, List<JiraDtos.Comment> comments, boolean commentsTruncated,
        Optional<Instant> completedAt, Optional<JiraDtos.WorkspaceLink> workspace)
{
    public JiraIssue
    {
        labels = ImmutableList.copyOf(labels);
        comments = ImmutableList.copyOf(comments);
    }

    public JiraIssue withComments(List<JiraDtos.Comment> values, boolean truncated)
    {
        return new JiraIssue(connectionId, cloudId, id, key, url, title, body, type, status, assignee, labels,
                priority, parent, hasChildren, isEpic, updatedAt, dueDate, task, values, truncated, completedAt, workspace);
    }

    public JiraIssue withChildren(boolean children)
    {
        return new JiraIssue(connectionId, cloudId, id, key, url, title, body, type, status, assignee, labels,
                priority, parent, children, isEpic, updatedAt, dueDate, task, comments, commentsTruncated, completedAt, workspace);
    }

    public JiraIssue withMetadata(boolean epic, Optional<JiraDtos.TaskLink> link, Optional<JiraDtos.WorkspaceLink> assignedWorkspace)
    {
        return new JiraIssue(connectionId, cloudId, id, key, url, title, body, type, status, assignee, labels,
                priority, parent, hasChildren, epic, updatedAt, dueDate, link, comments, commentsTruncated, completedAt, assignedWorkspace);
    }
}

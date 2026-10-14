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
package com.bytequay.persistence.sqlite.entity;

import com.bytequay.core.domain.JiraIssueSource;
import com.bytequay.core.domain.JiraIssueTask;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;

@Entity
@Table(name = "jira_issue_task", indexes = @Index(name = "idx_jira_issue_task_source", columnList = "cloud_id,issue_id"))
public class JiraIssueTaskEntity
        extends AssignedIdEntity<String>
{
    @Id
    @Getter(onMethod_ = @Override)
    private String id;

    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;

    @Column(name = "connection_id", nullable = false)
    private String connectionId;

    @Column(name = "cloud_id", nullable = false)
    private String cloudId;

    @Column(name = "issue_id", nullable = false)
    private String issueId;

    // SQLite's Hibernate dialect omits composite unique constraints.
    @Column(name = "source_slot", nullable = false, unique = true)
    private String sourceSlot;

    @Column(name = "issue_key", nullable = false)
    private String key;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String url;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "source_updated_at", nullable = false)
    private Instant sourceUpdatedAt;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String instructions;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected JiraIssueTaskEntity() {}

    public JiraIssueTaskEntity(JiraIssueTask task)
    {
        id = task.taskId();
        workspaceId = task.workspaceId();
        JiraIssueSource source = task.source();
        connectionId = source.connectionId();
        cloudId = source.cloudId();
        issueId = source.issueId();
        sourceSlot = source.identity();
        key = source.key();
        url = source.url();
        title = source.title();
        body = source.body();
        sourceUpdatedAt = source.updatedAt();
        instructions = task.instructions();
        createdAt = task.createdAt();
    }

    /** A retired attempt adds its own task id to the slot, so the ticket's next attempt can take it. */
    public void retire()
    {
        sourceSlot = JiraIssueSource.identity(cloudId, issueId) + ":" + id;
    }

    public JiraIssueTask toDomain()
    {
        return new JiraIssueTask(id, workspaceId, new JiraIssueSource(connectionId, cloudId, issueId, key, url, title, body, sourceUpdatedAt), instructions, createdAt);
    }
}

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

import com.bytequay.core.domain.GitHubIssueSource;
import com.bytequay.core.domain.IssueTask;
import com.bytequay.persistence.sqlite.converter.InstantToTextConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;
import java.util.Optional;

@Entity
@Table(name = "issue_task", indexes = @Index(name = "idx_issue_task_source", columnList = "host,issue_id"))
public class IssueTaskEntity
        extends AssignedIdEntity<String>
{
    @Id
    @Getter(onMethod_ = @Override)
    private String id;

    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;

    @Column(nullable = false)
    private String host;

    @Column(name = "issue_id", nullable = false)
    private long issueId;

    // SQLite's Hibernate dialect omits composite unique constraints.
    @Column(name = "source_slot", nullable = false, unique = true)
    private String sourceSlot;

    @Column(nullable = false)
    private String repo;

    @Column(nullable = false)
    private int number;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String url;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "source_updated_at", nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant sourceUpdatedAt;

    // Existing SQLite rows require this column; never expose or reuse its former wrapped prompt.
    @Column(name = "initial_context", nullable = false, columnDefinition = "TEXT")
    private String initialContext;

    @Column(columnDefinition = "TEXT")
    private String instructions;

    @Column(name = "created_at", nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant createdAt;

    protected IssueTaskEntity() {}

    public IssueTaskEntity(IssueTask task)
    {
        id = task.taskId();
        workspaceId = task.workspaceId();
        GitHubIssueSource source = task.source();
        host = source.host();
        issueId = source.issueId();
        sourceSlot = slot(host, issueId);
        repo = source.repo();
        number = source.number();
        url = source.url();
        title = source.title();
        body = source.body();
        sourceUpdatedAt = source.updatedAt();
        instructions = task.instructions().orElse(null);
        initialContext = task.instructions().orElse("");
        createdAt = task.createdAt();
    }

    /** The slot the issue's current attempt holds; a retired attempt adds its own task id, so a later attempt can take the slot. */
    public static String slot(String host, long issueId)
    {
        return host + ":" + issueId;
    }

    public void retire()
    {
        sourceSlot = slot(host, issueId) + ":" + id;
    }

    public IssueTask toDomain()
    {
        GitHubIssueSource source = new GitHubIssueSource(host, issueId, repo, number, url, title, body, sourceUpdatedAt);
        return new IssueTask(id, workspaceId, source, Optional.ofNullable(instructions), createdAt);
    }
}

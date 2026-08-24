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

import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.persistence.sqlite.converter.InstantToTextConverter;
import com.bytequay.persistence.sqlite.converter.StringListConverter;
import com.google.common.collect.ImmutableList;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.List;

@Entity
@Table(
        name = "repository_issue_rows",
        indexes = @Index(name = "idx_repository_issue_updated", columnList = "repository_id,updated_at"))
public class RepositoryIssueEntity
        implements Persistable<Long>
{
    @Id
    private Long id;

    @Column(name = "repository_id", nullable = false)
    private Long repositoryId;

    @Column(nullable = false)
    private Integer number;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(nullable = false)
    private String author;

    @Column(name = "author_avatar_url", nullable = false, columnDefinition = "TEXT")
    private String authorAvatarUrl;

    @Column(name = "html_url", nullable = false, columnDefinition = "TEXT")
    private String htmlUrl;

    @Column(nullable = false, columnDefinition = "TEXT")
    @Convert(converter = StringListConverter.class)
    private List<String> labels;

    @Column(nullable = false, columnDefinition = "TEXT")
    @Convert(converter = StringListConverter.class)
    private List<String> assignees;

    @Column(name = "comment_count", nullable = false)
    private Integer commentCount;

    @Column(name = "created_at", nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant updatedAt;

    @Column(name = "sync_marker")
    private String syncMarker;

    @Transient
    private boolean newEntity = true;

    protected RepositoryIssueEntity() {}

    public RepositoryIssueEntity(long repositoryId, RepositoryIssue issue, String syncMarker)
    {
        this.id = issue.id();
        update(repositoryId, issue, syncMarker);
    }

    public void update(long repositoryId, RepositoryIssue issue, String syncMarker)
    {
        this.repositoryId = repositoryId;
        this.number = issue.number();
        this.title = issue.title();
        this.body = issue.body();
        this.author = issue.author();
        this.authorAvatarUrl = issue.authorAvatarUrl();
        this.htmlUrl = issue.htmlUrl();
        this.labels = issue.labels();
        this.assignees = issue.assignees();
        this.commentCount = issue.commentCount();
        this.createdAt = issue.createdAt();
        this.updatedAt = issue.updatedAt();
        this.syncMarker = syncMarker;
    }

    public RepositoryIssue toDomain()
    {
        return new RepositoryIssue(
                id,
                number,
                title,
                body,
                author,
                authorAvatarUrl,
                htmlUrl,
                labels,
                assignees,
                commentCount,
                createdAt,
                updatedAt);
    }

    public void updateMetadata(List<String> assignees, List<String> labels, Instant updatedAt)
    {
        this.assignees = ImmutableList.copyOf(assignees);
        this.labels = ImmutableList.copyOf(labels);
        this.updatedAt = updatedAt;
    }

    @Override
    public Long getId() { return id; }

    @Override
    public boolean isNew() { return newEntity; }

    @PostLoad
    @PostPersist
    private void markPersisted() { newEntity = false; }

    public Long getRepositoryId() { return repositoryId; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Integer getNumber() { return number; }
}

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

import com.bytequay.core.domain.IssueEpic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;

// Keep the physical names so renaming the marker preserves saved Epic selections.
@Entity
@Table(name = "issue_goal", indexes = @Index(name = "idx_issue_goal_source", columnList = "host,issue_id"))
public class IssueEpicEntity
        extends AssignedIdEntity<String>
{
    @Id
    @Getter(onMethod_ = @Override)
    private String id;

    @Column(nullable = false)
    private String host;

    @Column(name = "issue_id", nullable = false)
    private long issueId;

    @Column(nullable = false)
    private String repo;

    @Column(nullable = false)
    private int number;

    @Column(name = "is_goal", nullable = false)
    private boolean epic;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected IssueEpicEntity() {}

    public IssueEpicEntity(IssueEpic epic)
    {
        id = epic.host() + ":" + epic.issueId();
        host = epic.host();
        issueId = epic.issueId();
        update(epic);
    }

    public void update(IssueEpic epic)
    {
        repo = epic.repo();
        number = epic.number();
        this.epic = epic.isEpic();
        updatedAt = epic.updatedAt();
    }

    public IssueEpic toDomain()
    {
        return new IssueEpic(host, issueId, repo, number, epic, updatedAt);
    }
}

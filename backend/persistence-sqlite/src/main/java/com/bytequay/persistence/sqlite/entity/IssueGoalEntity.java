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

import com.bytequay.core.domain.IssueGoal;
import com.bytequay.persistence.sqlite.converter.InstantToTextConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;

@Entity
@Table(name = "issue_goal", indexes = @Index(name = "idx_issue_goal_source", columnList = "host,issue_id"))
public class IssueGoalEntity
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
    private boolean goal;

    @Column(name = "updated_at", nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant updatedAt;

    protected IssueGoalEntity() {}

    public IssueGoalEntity(IssueGoal goal)
    {
        id = goal.host() + ":" + goal.issueId();
        host = goal.host();
        issueId = goal.issueId();
        update(goal);
    }

    public void update(IssueGoal goal)
    {
        repo = goal.repo();
        number = goal.number();
        this.goal = goal.isGoal();
        updatedAt = goal.updatedAt();
    }

    public IssueGoal toDomain()
    {
        return new IssueGoal(host, issueId, repo, number, goal, updatedAt);
    }
}

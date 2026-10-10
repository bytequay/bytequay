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

import com.bytequay.core.domain.PullRequestTask;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

@Entity
@Table(name = "pull_request_task")
public class PullRequestTaskEntity
        extends AssignedIdEntity<String>
{
    @Id
    @Getter(onMethod_ = @Override)
    private String id;

    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;

    @Column(nullable = false)
    private String repo;

    @Column(nullable = false)
    private int number;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String initialContext;

    public PullRequestTaskEntity() {}

    public static PullRequestTaskEntity fromDomain(PullRequestTask assignment)
    {
        PullRequestTaskEntity entity = new PullRequestTaskEntity();
        entity.id = assignment.taskId();
        entity.workspaceId = assignment.workspaceId();
        entity.repo = assignment.repo();
        entity.number = assignment.number();
        entity.initialContext = assignment.initialContext();
        return entity;
    }

    public PullRequestTask toDomain()
    {
        return new PullRequestTask(id, workspaceId, repo, number, initialContext);
    }
}

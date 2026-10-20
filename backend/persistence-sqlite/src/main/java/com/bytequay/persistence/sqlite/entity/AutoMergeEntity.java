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

import com.bytequay.core.merge.AutoMerge;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;
import java.util.Optional;

@Entity
@Table(name = "auto_merge")
public class AutoMergeEntity
        extends AssignedIdEntity<String>
{
    @Id
    @Column(name = "task_id")
    @Getter(onMethod_ = @Override)
    private String id;

    @Column(name = "head_sha", nullable = false)
    private String headSha;

    @Column(name = "ready_since")
    private Instant readySince;

    @Column(name = "started_at")
    private Instant startedAt;

    // Plain text, not an enum column: a CHECK constraint on the values would block adding one later.
    @Column
    private String outcome;

    @Column(columnDefinition = "TEXT")
    private String message;

    protected AutoMergeEntity() {}

    public AutoMergeEntity(String taskId)
    {
        id = taskId;
    }

    public void update(AutoMerge value)
    {
        headSha = value.headSha();
        readySince = value.readySince().orElse(null);
        startedAt = value.startedAt().orElse(null);
        outcome = value.outcome().map(Enum::name).orElse(null);
        message = value.message().orElse(null);
    }

    public AutoMerge toDomain()
    {
        return new AutoMerge(id, headSha, Optional.ofNullable(readySince), Optional.ofNullable(startedAt),
                Optional.ofNullable(outcome).map(AutoMerge.Outcome::valueOf), Optional.ofNullable(message));
    }
}

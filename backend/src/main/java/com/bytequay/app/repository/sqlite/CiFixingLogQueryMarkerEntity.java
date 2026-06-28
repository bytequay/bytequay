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
package com.bytequay.app.repository.sqlite;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "ci_fixing_log_query_marker")
class CiFixingLogQueryMarkerEntity
{
    @Id
    @Column(name = "task_id")
    private String taskId;

    @Column(name = "last_queried_at")
    @Convert(converter = InstantToTextConverter.class)
    private Instant lastQueriedAt;

    @Column(name = "internal_created_at", nullable = false, updatable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant internalCreatedAt;

    @Column(name = "internal_updated_at", nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant internalUpdatedAt;

    protected CiFixingLogQueryMarkerEntity() {}

    @PrePersist
    void prePersist()
    {
        Instant now = Instant.now();
        this.internalCreatedAt = now;
        this.internalUpdatedAt = now;
    }

    @PreUpdate
    void preUpdate()
    {
        this.internalUpdatedAt = Instant.now();
    }

    String getTaskId() { return taskId; }
    void setTaskId(String taskId) { this.taskId = taskId; }

    Instant getLastQueriedAt() { return lastQueriedAt; }
    void setLastQueriedAt(Instant lastQueriedAt) { this.lastQueriedAt = lastQueriedAt; }
}

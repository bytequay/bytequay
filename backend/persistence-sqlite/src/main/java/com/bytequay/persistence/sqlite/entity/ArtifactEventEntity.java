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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

/** One agent event of an artifact conversation, kept as the same JSON a task's event log holds. */
@Entity
@Table(name = "artifact_event", uniqueConstraints = @UniqueConstraint(name = "uq_artifact_event_sequence", columnNames = {"artifact_id", "sequence"}))
public class ArtifactEventEntity
        extends AssignedIdEntity<String>
{
    @Id
    @Getter(onMethod_ = @Override)
    private String id;

    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;

    @Column(name = "artifact_id", nullable = false)
    private String artifactId;

    @Column(nullable = false)
    private long sequence;

    @Getter
    @Column(nullable = false, columnDefinition = "TEXT")
    private String json;

    protected ArtifactEventEntity() {}

    public ArtifactEventEntity(String workspaceId, String artifactId, long sequence, String json)
    {
        id = artifactId + "/" + sequence;
        this.workspaceId = workspaceId;
        this.artifactId = artifactId;
        this.sequence = sequence;
        this.json = json;
    }
}

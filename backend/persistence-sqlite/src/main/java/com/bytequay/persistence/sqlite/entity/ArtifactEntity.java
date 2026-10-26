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

import com.bytequay.core.domain.Artifact;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One artifact conversation as JSON, with its workspace, whether it is answering, and whether it missed a question beside it for queries. */
@Entity
@Table(name = "artifact_conversation", indexes = @Index(name = "idx_artifact_conversation_workspace", columnList = "workspace_id"))
public class ArtifactEntity
        extends AssignedIdEntity<String>
{
    @Id
    @Getter(onMethod_ = @Override)
    private String id;

    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;

    @Column(nullable = false)
    private boolean working;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean missed;

    @Column(nullable = false, columnDefinition = "TEXT")
    @JdbcTypeCode(SqlTypes.JSON)
    private Artifact value;

    protected ArtifactEntity() {}

    public ArtifactEntity(Artifact artifact)
    {
        id = artifact.id();
        update(artifact);
    }

    public void update(Artifact artifact)
    {
        workspaceId = artifact.workspaceId();
        working = artifact.working();
        missed = artifact.missed().isPresent();
        value = artifact;
    }

    public Artifact toDomain()
    {
        return value;
    }
}

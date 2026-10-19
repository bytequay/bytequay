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

import java.time.Instant;

@Entity
@Table(name = "artifact", indexes = @Index(name = "idx_artifact_workspace", columnList = "workspace_id"))
public class ArtifactEntity
        extends AssignedIdEntity<String>
{
    @Id
    @Getter(onMethod_ = @Override)
    private String id;

    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String html;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ArtifactEntity() {}

    public ArtifactEntity(Artifact artifact)
    {
        id = artifact.id();
        update(artifact);
    }

    public void update(Artifact artifact)
    {
        workspaceId = artifact.workspaceId();
        title = artifact.title();
        html = artifact.html();
        updatedAt = artifact.updatedAt();
    }

    public Artifact toDomain()
    {
        return new Artifact(id, workspaceId, title, html, updatedAt);
    }
}

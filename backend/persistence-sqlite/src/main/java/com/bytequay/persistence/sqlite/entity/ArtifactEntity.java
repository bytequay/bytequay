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
import com.bytequay.persistence.sqlite.converter.JsonColumnConverters.ArtifactConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;

import java.util.Optional;

/** The record as JSON, with the workspace and state beside it for queries and the page in its own column so lists never read it. */
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Artifact.State state;

    @Column(nullable = false, columnDefinition = "TEXT")
    @Convert(converter = ArtifactConverter.class)
    private Artifact value;

    @Column(columnDefinition = "TEXT")
    private String html;

    protected ArtifactEntity() {}

    public ArtifactEntity(Artifact artifact)
    {
        id = artifact.id();
        update(artifact, Optional.empty());
    }

    public void update(Artifact artifact, Optional<String> page)
    {
        workspaceId = artifact.workspaceId();
        state = artifact.state();
        value = artifact;
        page.ifPresent(text -> html = text);
    }

    public Artifact toDomain()
    {
        return value;
    }
}

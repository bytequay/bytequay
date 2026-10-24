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

import com.bytequay.core.domain.ArtifactPage;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One saved page version, with its HTML in its own column so listing versions never reads a page. */
@Entity
@Table(name = "artifact_page", indexes = @Index(name = "idx_artifact_page_artifact", columnList = "artifact_id"))
public class ArtifactPageEntity
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
    private long ordinal;

    @Column(nullable = false, columnDefinition = "TEXT")
    @JdbcTypeCode(SqlTypes.JSON)
    private ArtifactPage value;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String html;

    protected ArtifactPageEntity() {}

    public ArtifactPageEntity(String workspaceId, ArtifactPage page, String html, long ordinal)
    {
        id = page.id();
        this.workspaceId = workspaceId;
        artifactId = page.artifactId();
        this.ordinal = ordinal;
        value = page;
        this.html = html;
    }
}

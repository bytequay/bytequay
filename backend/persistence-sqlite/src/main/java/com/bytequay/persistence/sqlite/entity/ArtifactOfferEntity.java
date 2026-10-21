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

import com.bytequay.core.domain.ArtifactOffer;
import com.bytequay.persistence.sqlite.converter.JsonColumnConverters.ArtifactOfferConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

/** A page an artifact conversation offered. Offers made in one millisecond keep their order through the ordinal. */
@Entity
@Table(name = "artifact_offer", uniqueConstraints = @UniqueConstraint(name = "uq_artifact_offer_ordinal", columnNames = {"artifact_id", "ordinal"}))
public class ArtifactOfferEntity
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
    @Convert(converter = ArtifactOfferConverter.class)
    private ArtifactOffer value;

    protected ArtifactOfferEntity() {}

    public ArtifactOfferEntity(String workspaceId, ArtifactOffer offer, long ordinal)
    {
        id = offer.id();
        this.workspaceId = workspaceId;
        artifactId = offer.artifactId();
        this.ordinal = ordinal;
        value = offer;
    }

    public void update(ArtifactOffer offer)
    {
        value = offer;
    }
}

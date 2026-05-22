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
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "review_participants")
class ReviewParticipantEntity
{
    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "review_pass_id", nullable = false)
    private String reviewPassId;

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "credential_id")
    private String credentialId;

    @Column(name = "persona_label", nullable = false)
    private String personaLabel;

    @Column(name = "model")
    private String model;

    @Column(name = "color")
    private String color;

    @Column(name = "created_at_ms", nullable = false)
    private long createdAtMs;

    String getId() { return id; }
    void setId(String id) { this.id = id; }

    String getReviewPassId() { return reviewPassId; }
    void setReviewPassId(String reviewPassId) { this.reviewPassId = reviewPassId; }

    String getKind() { return kind; }
    void setKind(String kind) { this.kind = kind; }

    String getCredentialId() { return credentialId; }
    void setCredentialId(String credentialId) { this.credentialId = credentialId; }

    String getPersonaLabel() { return personaLabel; }
    void setPersonaLabel(String personaLabel) { this.personaLabel = personaLabel; }

    String getModel() { return model; }
    void setModel(String model) { this.model = model; }

    String getColor() { return color; }
    void setColor(String color) { this.color = color; }

    long getCreatedAtMs() { return createdAtMs; }
    void setCreatedAtMs(long createdAtMs) { this.createdAtMs = createdAtMs; }
}

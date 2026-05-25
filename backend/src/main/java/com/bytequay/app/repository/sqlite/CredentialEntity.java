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

import com.bytequay.app.domain.CredentialType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "credentials")
class CredentialEntity
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CredentialType type;

    @Column(nullable = false)
    private String name;

    @Column(name = "instance_name", nullable = false)
    private String instanceName;

    @Column
    private String label;

    @Column(nullable = false)
    private String ciphertext;

    @Column(nullable = false)
    private String preview;

    @Column
    private String notes;

    /** Default flag for (type, name) resolution. SQLite has no native
     *  boolean — stored as INTEGER 0/1 to match V84's column type. */
    @Column(name = "is_default", nullable = false)
    private int isDefault;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant updatedAt;

    @Column(name = "last_used_at")
    @Convert(converter = InstantToTextConverter.class)
    private Instant lastUsedAt;

    protected CredentialEntity() {}

    @PrePersist
    void prePersist()
    {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate()
    {
        this.updatedAt = Instant.now();
    }

    Long getId() { return id; }

    CredentialType getType() { return type; }
    void setType(CredentialType type) { this.type = type; }

    String getName() { return name; }
    void setName(String name) { this.name = name; }

    String getInstanceName() { return instanceName; }
    void setInstanceName(String instanceName) { this.instanceName = instanceName; }

    String getLabel() { return label; }
    void setLabel(String label) { this.label = label; }

    String getCiphertext() { return ciphertext; }
    void setCiphertext(String ciphertext) { this.ciphertext = ciphertext; }

    String getPreview() { return preview; }
    void setPreview(String preview) { this.preview = preview; }

    String getNotes() { return notes; }
    void setNotes(String notes) { this.notes = notes; }

    boolean isDefault() { return isDefault != 0; }
    void setDefault(boolean value) { this.isDefault = value ? 1 : 0; }

    Instant getCreatedAt() { return createdAt; }

    Instant getUpdatedAt() { return updatedAt; }

    Instant getLastUsedAt() { return lastUsedAt; }
    void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }
}

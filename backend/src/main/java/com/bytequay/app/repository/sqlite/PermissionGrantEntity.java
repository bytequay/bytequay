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
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "permission_grant")
class PermissionGrantEntity
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scope_kind", nullable = false)
    private String scopeKind;

    @Column(name = "scope_id")
    private String scopeId;

    @Column(nullable = false)
    private String capability;

    @Column(nullable = false)
    private String mode;

    @Column(name = "params_json", columnDefinition = "TEXT")
    private String paramsJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant updatedAt;

    protected PermissionGrantEntity() {}

    @PrePersist
    void prePersist()
    {
        Instant now = Instant.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate()
    {
        this.updatedAt = Instant.now();
    }

    Long getId() { return id; }

    String getScopeKind() { return scopeKind; }
    void setScopeKind(String scopeKind) { this.scopeKind = scopeKind; }

    String getScopeId() { return scopeId; }
    void setScopeId(String scopeId) { this.scopeId = scopeId; }

    String getCapability() { return capability; }
    void setCapability(String capability) { this.capability = capability; }

    String getMode() { return mode; }
    void setMode(String mode) { this.mode = mode; }

    String getParamsJson() { return paramsJson; }
    void setParamsJson(String paramsJson) { this.paramsJson = paramsJson; }

    Instant getCreatedAt() { return createdAt; }
    Instant getUpdatedAt() { return updatedAt; }
}

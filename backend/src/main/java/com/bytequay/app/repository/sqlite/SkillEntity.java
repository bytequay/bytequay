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
@Table(name = "skill")
class SkillEntity
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String scope;

    @Column
    private String repo;

    @Column(name = "thread_id")
    private String threadId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(nullable = false)
    private String kind;

    @Column(name = "usage", nullable = false)
    private String usage = "build";

    @Column(name = "role_tag")
    private String roleTag;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(nullable = false)
    private String source = "authored";

    @Column
    private String provenance;

    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant updatedAt;

    protected SkillEntity() {}

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

    String getScope() { return scope; }
    void setScope(String scope) { this.scope = scope; }

    String getRepo() { return repo; }
    void setRepo(String repo) { this.repo = repo; }

    String getThreadId() { return threadId; }
    void setThreadId(String threadId) { this.threadId = threadId; }

    String getName() { return name; }
    void setName(String name) { this.name = name; }

    String getDescription() { return description; }
    void setDescription(String description) { this.description = description; }

    String getBody() { return body; }
    void setBody(String body) { this.body = body; }

    String getKind() { return kind; }
    void setKind(String kind) { this.kind = kind; }

    String getUsage() { return usage; }
    void setUsage(String usage) { this.usage = usage; }

    String getRoleTag() { return roleTag; }
    void setRoleTag(String roleTag) { this.roleTag = roleTag; }

    boolean isEnabled() { return enabled; }
    void setEnabled(boolean value) { this.enabled = value; }

    boolean isDefault() { return isDefault; }
    void setDefault(boolean value) { this.isDefault = value; }

    String getSource() { return source; }
    void setSource(String source) { this.source = source; }

    String getProvenance() { return provenance; }
    void setProvenance(String provenance) { this.provenance = provenance; }

    String getContentHash() { return contentHash; }
    void setContentHash(String contentHash) { this.contentHash = contentHash; }

    Instant getCreatedAt() { return createdAt; }
    Instant getUpdatedAt() { return updatedAt; }
}

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
@Table(name = "review_skill")
class ReviewSkillEntity
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "skill_name", nullable = false, unique = true)
    private String skillName;

    @Column(nullable = false, unique = true)
    private String repo;

    @Column(name = "llm_provider")
    private String llmProvider;

    @Column
    private String description;

    @Column(columnDefinition = "TEXT")
    private String context;

    /** Persisted enable toggle. SQLite stores boolean as INTEGER 0/1
     *  — V86 added the column with default 1 so existing rows stay
     *  active and the review path's behaviour doesn't shift. */
    @Column(name = "enabled", nullable = false)
    private int enabled = 1;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant updatedAt;

    protected ReviewSkillEntity() {}

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

    String getSkillName() { return skillName; }
    void setSkillName(String skillName) { this.skillName = skillName; }

    String getRepo() { return repo; }
    void setRepo(String repo) { this.repo = repo; }

    String getLlmProvider() { return llmProvider; }
    void setLlmProvider(String llmProvider) { this.llmProvider = llmProvider; }

    String getDescription() { return description; }
    void setDescription(String description) { this.description = description; }

    String getContext() { return context; }
    void setContext(String context) { this.context = context; }

    boolean isEnabled() { return enabled != 0; }
    void setEnabled(boolean value) { this.enabled = value ? 1 : 0; }

    Instant getCreatedAt() { return createdAt; }

    Instant getUpdatedAt() { return updatedAt; }
}

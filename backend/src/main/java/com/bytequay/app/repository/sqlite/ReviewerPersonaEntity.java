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
@Table(name = "reviewer_personas")
class ReviewerPersonaEntity
{
    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "system_prompt", nullable = false)
    private String systemPrompt;

    /** Stored as the enum's literal name (LEAD / REVIEWER) to keep
     *  the column human-readable in a sqlite shell. The mapping back
     *  to the domain enum lives in the store layer. */
    @Column(name = "role", nullable = false)
    private String role;

    /** Soft-delete flag: 1 = active, 0 = soft-deleted. SQLite has no
     *  bool type so we store as integer and gate the check constraint
     *  in the migration. */
    @Column(name = "is_active", nullable = false)
    private int isActive;

    @Column(name = "created_at_ms", nullable = false)
    private long createdAtMs;

    @Column(name = "updated_at_ms", nullable = false)
    private long updatedAtMs;

    String getId() { return id; }
    void setId(String id) { this.id = id; }

    String getName() { return name; }
    void setName(String name) { this.name = name; }

    String getSystemPrompt() { return systemPrompt; }
    void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

    String getRole() { return role; }
    void setRole(String role) { this.role = role; }

    int getIsActive() { return isActive; }
    void setIsActive(int isActive) { this.isActive = isActive; }

    long getCreatedAtMs() { return createdAtMs; }
    void setCreatedAtMs(long createdAtMs) { this.createdAtMs = createdAtMs; }

    long getUpdatedAtMs() { return updatedAtMs; }
    void setUpdatedAtMs(long updatedAtMs) { this.updatedAtMs = updatedAtMs; }
}

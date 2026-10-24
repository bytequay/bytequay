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

import com.bytequay.core.memory.Memory;
import com.bytequay.core.memory.MemoryReport;
import com.bytequay.core.memory.MemorySource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Entity
@Table(name = "repository_memory", indexes = @Index(name = "idx_repository_memory_workspace", columnList = "workspace_id"))
public class MemoryEntity
        extends AssignedIdEntity<String>
{
    @Id
    @Getter(onMethod_ = @Override)
    private String id;

    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;

    // Plain text rather than an enum column: SQLite keeps an enum's CHECK from the day the table was created.
    @Column(nullable = false)
    private String kind;

    // Nullable because the schema update adds it to tables that already hold memories; SQLite cannot add a NOT NULL column without a default.
    @Column
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String text;

    @Column(name = "scope_json", nullable = false, columnDefinition = "TEXT")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> scope;

    @Column(name = "sources_json", nullable = false, columnDefinition = "TEXT")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<MemorySource> sources;

    @Column(name = "origin_user", nullable = false)
    private boolean originUser;

    @Column(name = "origin_description", nullable = false)
    private String originDescription;

    @Column(name = "origin_task_id")
    private String originTaskId;

    @Column(nullable = false)
    private long version;

    @Column(name = "previous_json", columnDefinition = "TEXT")
    @JdbcTypeCode(SqlTypes.JSON)
    private Memory.Previous previous;

    @Column(nullable = false)
    private int delivered;

    @Column(nullable = false)
    private int cited;

    @Column(name = "reports_json", nullable = false, columnDefinition = "TEXT")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<MemoryReport> reports;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MemoryEntity() {}

    public MemoryEntity(Memory memory)
    {
        id = memory.id();
        workspaceId = memory.workspaceId();
        createdAt = memory.createdAt();
        update(memory);
    }

    public void update(Memory memory)
    {
        kind = memory.kind().name();
        title = memory.title();
        text = memory.text();
        scope = memory.scope();
        sources = memory.sources();
        originUser = memory.origin().user();
        originDescription = memory.origin().description();
        originTaskId = memory.origin().taskId().orElse(null);
        version = memory.version();
        previous = memory.previous().orElse(null);
        delivered = memory.delivered();
        cited = memory.cited();
        reports = memory.reports();
        updatedAt = memory.updatedAt();
    }

    public Memory toDomain()
    {
        // A memory saved before titles existed is titled by its text's opening words until it is edited.
        return new Memory(id, workspaceId, Memory.Kind.valueOf(kind), Optional.ofNullable(title).orElseGet(() -> Memory.titleFrom(text)), text, scope, sources,
                new Memory.Origin(originUser, originDescription, Optional.ofNullable(originTaskId)), version, Optional.ofNullable(previous),
                delivered, cited, reports, createdAt, updatedAt);
    }
}

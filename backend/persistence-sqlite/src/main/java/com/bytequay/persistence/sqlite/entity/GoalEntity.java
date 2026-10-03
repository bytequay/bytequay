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

import com.bytequay.core.domain.Goal;
import com.bytequay.core.domain.GoalCourse;
import com.bytequay.core.domain.GoalDefinition;
import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.GoalSource;
import com.bytequay.persistence.sqlite.converter.GoalCourseConverter;
import com.bytequay.persistence.sqlite.converter.GoalDefinitionConverter;
import com.bytequay.persistence.sqlite.converter.InstantToTextConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;
import java.util.Optional;

@Entity
@Table(name = "goal", indexes = {
        @Index(name = "idx_goal_workspace", columnList = "workspace_id"),
        @Index(name = "idx_goal_source", columnList = "source_kind,source_key")})
public class GoalEntity
        extends AssignedIdEntity<String>
{
    @Id
    @Getter(onMethod_ = @Override)
    private String id;

    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private boolean pinned;

    // Plain text rather than an enum column: SQLite keeps an enum's CHECK from the day the table was created.
    @Column(name = "source_kind")
    private String sourceKind;

    @Column(name = "source_key")
    private String sourceKey;

    @Column(name = "unmarked_at")
    @Convert(converter = InstantToTextConverter.class)
    private Instant unmarkedAt;

    @Column(nullable = false)
    private boolean archived;

    // Nullable columns: SQLite cannot add a NOT NULL column without a default to a table that already has rows.
    @Column(columnDefinition = "TEXT")
    @Convert(converter = GoalDefinitionConverter.class)
    private GoalDefinition definition;

    @Column
    private Long revision;

    @Column(columnDefinition = "TEXT")
    @Convert(converter = GoalCourseConverter.class)
    private GoalCourse course;

    @Column(name = "created_at", nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant updatedAt;

    protected GoalEntity() {}

    public GoalEntity(Goal goal)
    {
        id = goal.id();
        workspaceId = goal.workspaceId();
        createdAt = goal.createdAt();
        update(goal);
    }

    public void update(Goal goal)
    {
        title = goal.title();
        description = goal.description();
        pinned = goal.pinned();
        sourceKind = goal.source().map(source -> source.kind().name()).orElse(null);
        sourceKey = goal.source().map(GoalSource::key).orElse(null);
        unmarkedAt = goal.unmarkedAt().orElse(null);
        archived = goal.archived();
        definition = goal.definition().orElse(null);
        revision = goal.revision();
        course = goal.course().orElse(null);
        updatedAt = goal.updatedAt();
    }

    public Goal toDomain()
    {
        Optional<GoalSource> source = sourceKind == null ? Optional.empty() : Optional.of(new GoalSource(GoalItemKind.valueOf(sourceKind), sourceKey));
        return new Goal(id, workspaceId, title, description, pinned, archived, source, Optional.ofNullable(unmarkedAt), Optional.ofNullable(definition),
                revision == null ? 0 : revision, Optional.ofNullable(course), createdAt, updatedAt);
    }
}

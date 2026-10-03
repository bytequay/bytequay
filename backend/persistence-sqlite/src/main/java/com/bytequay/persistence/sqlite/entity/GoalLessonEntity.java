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

import com.bytequay.core.domain.GoalLesson;
import com.bytequay.persistence.sqlite.converter.GoalLessonConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;

@Entity
@Table(name = "goal_lesson", indexes = @Index(name = "idx_goal_lesson_state", columnList = "state"))
public class GoalLessonEntity
        extends AssignedIdEntity<String>
{
    @Id
    @Getter(onMethod_ = @Override)
    private String id;

    @Column(name = "goal_id", nullable = false)
    private String goalId;

    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;

    // Plain text rather than an enum column: SQLite keeps an enum's CHECK from the day the table was created.
    @Column(nullable = false)
    private String state;

    @Column(nullable = false, columnDefinition = "TEXT")
    @Convert(converter = GoalLessonConverter.class)
    private GoalLesson value;

    protected GoalLessonEntity() {}

    public GoalLessonEntity(GoalLesson lesson)
    {
        id = lesson.id();
        goalId = lesson.goalId();
        workspaceId = lesson.workspaceId();
        update(lesson);
    }

    public void update(GoalLesson lesson)
    {
        state = lesson.state().name();
        value = lesson;
    }

    public GoalLesson toDomain()
    {
        return value;
    }
}

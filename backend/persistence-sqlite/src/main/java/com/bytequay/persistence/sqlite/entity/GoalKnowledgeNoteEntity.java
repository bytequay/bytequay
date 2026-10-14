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

import com.bytequay.core.domain.GoalKnowledgeNote;
import com.bytequay.persistence.sqlite.converter.GoalKnowledgeNoteConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;

@Entity
@Table(name = "goal_knowledge_note", indexes = @Index(name = "idx_goal_knowledge_note_goal", columnList = "goal_id"))
public class GoalKnowledgeNoteEntity
        extends AssignedIdEntity<String>
{
    @Id
    @Getter(onMethod_ = @Override)
    private String id;

    @Column(name = "goal_id", nullable = false)
    private String goalId;

    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;

    @Column(name = "saved_at", nullable = false)
    private Instant savedAt;

    @Column(nullable = false, columnDefinition = "TEXT")
    @Convert(converter = GoalKnowledgeNoteConverter.class)
    private GoalKnowledgeNote value;

    protected GoalKnowledgeNoteEntity() {}

    public GoalKnowledgeNoteEntity(GoalKnowledgeNote saved)
    {
        id = saved.id();
        goalId = saved.goalId();
        workspaceId = saved.workspaceId();
        savedAt = saved.savedAt();
        value = saved;
    }

    public void update(GoalKnowledgeNote saved)
    {
        value = saved;
    }

    public GoalKnowledgeNote toDomain()
    {
        return value;
    }
}

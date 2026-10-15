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

import com.bytequay.core.domain.GoalTopic;
import com.bytequay.persistence.sqlite.converter.JsonColumnConverters.GoalTopicConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;

@Entity
@Table(name = "goal_topic", indexes = @Index(name = "idx_goal_topic_goal", columnList = "goal_id"))
public class GoalTopicEntity
        extends AssignedIdEntity<String>
{
    @Id
    @Getter(onMethod_ = @Override)
    private String id;

    @Column(name = "goal_id", nullable = false)
    private String goalId;

    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;

    @Column(nullable = false, columnDefinition = "TEXT")
    @Convert(converter = GoalTopicConverter.class)
    private GoalTopic value;

    protected GoalTopicEntity() {}

    public GoalTopicEntity(GoalTopic topic)
    {
        id = topic.id();
        goalId = topic.goalId();
        workspaceId = topic.workspaceId();
        value = topic;
    }

    public void update(GoalTopic topic)
    {
        value = topic;
    }

    public GoalTopic toDomain()
    {
        return value;
    }
}

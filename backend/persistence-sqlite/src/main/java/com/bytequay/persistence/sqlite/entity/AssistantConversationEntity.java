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

import com.bytequay.core.domain.AssistantConversation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** One assistant conversation; its update time is a column so the history and retention can order and filter by it. */
@Entity
@Table(name = "assistant_conversation", indexes = @Index(name = "ix_assistant_conversation_updated", columnList = "updated_at"))
public class AssistantConversationEntity
        extends AssignedIdEntity<String>
{
    @Id
    @Getter(onMethod_ = @Override)
    private String id;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(nullable = false, columnDefinition = "TEXT")
    @JdbcTypeCode(SqlTypes.JSON)
    private AssistantConversation value;

    protected AssistantConversationEntity() {}

    public AssistantConversationEntity(AssistantConversation conversation)
    {
        id = conversation.id();
        update(conversation);
    }

    public void update(AssistantConversation conversation)
    {
        updatedAt = conversation.updatedAt();
        value = conversation;
    }

    public AssistantConversation toDomain()
    {
        return value;
    }
}

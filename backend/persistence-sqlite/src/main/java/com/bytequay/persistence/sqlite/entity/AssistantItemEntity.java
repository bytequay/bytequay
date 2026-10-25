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

import com.bytequay.core.domain.AssistantItem;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One entry of an assistant conversation. A message and the failure right after it can share a millisecond, so the ordinal orders them. */
@Entity
@Table(name = "assistant_conversation_item",
        uniqueConstraints = @UniqueConstraint(name = "uq_assistant_conversation_item_ordinal", columnNames = {"conversation_id", "ordinal"}))
public class AssistantItemEntity
        extends AssignedIdEntity<String>
{
    @Id
    @Getter(onMethod_ = @Override)
    private String id;

    @Column(name = "conversation_id", nullable = false)
    private String conversationId;

    @Column(nullable = false)
    private long ordinal;

    @Column(nullable = false, columnDefinition = "TEXT")
    @JdbcTypeCode(SqlTypes.JSON)
    private AssistantItem value;

    protected AssistantItemEntity() {}

    public AssistantItemEntity(AssistantItem item, long ordinal)
    {
        id = item.id();
        conversationId = item.conversationId();
        this.ordinal = ordinal;
        value = item;
    }

    public AssistantItem toDomain()
    {
        return value;
    }
}

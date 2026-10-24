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

/** One entry of a day's assistant conversation. A message and the failure right after it can share a millisecond, so the ordinal orders them. */
@Entity
@Table(name = "assistant_item", uniqueConstraints = @UniqueConstraint(name = "uq_assistant_item_day_ordinal", columnNames = {"day", "ordinal"}))
public class AssistantItemEntity
        extends AssignedIdEntity<String>
{
    @Id
    @Getter(onMethod_ = @Override)
    private String id;

    @Column(nullable = false)
    private String day;

    @Column(nullable = false)
    private long ordinal;

    @Column(nullable = false, columnDefinition = "TEXT")
    @JdbcTypeCode(SqlTypes.JSON)
    private AssistantItem value;

    protected AssistantItemEntity() {}

    public AssistantItemEntity(AssistantItem item, long ordinal)
    {
        id = item.id();
        day = item.day().toString();
        this.ordinal = ordinal;
        value = item;
    }

    public AssistantItem toDomain()
    {
        return value;
    }
}

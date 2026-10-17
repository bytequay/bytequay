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

import com.bytequay.core.domain.AssistantDay;
import com.bytequay.persistence.sqlite.converter.JsonColumnConverters.AssistantDayConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

/** One day's assistant conversation, keyed by its ISO date so text order is day order. */
@Entity
@Table(name = "assistant_day")
public class AssistantDayEntity
        extends AssignedIdEntity<String>
{
    @Id
    @Getter(onMethod_ = @Override)
    private String id;

    @Column(nullable = false, columnDefinition = "TEXT")
    @Convert(converter = AssistantDayConverter.class)
    private AssistantDay value;

    protected AssistantDayEntity() {}

    public AssistantDayEntity(AssistantDay day)
    {
        id = day.day().toString();
        update(day);
    }

    public void update(AssistantDay day)
    {
        value = day;
    }

    public AssistantDay toDomain()
    {
        return value;
    }
}

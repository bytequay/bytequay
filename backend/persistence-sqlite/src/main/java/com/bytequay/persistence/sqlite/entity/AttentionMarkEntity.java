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

import com.bytequay.core.domain.AttentionMark;
import com.bytequay.persistence.sqlite.converter.JsonColumnConverters.AttentionMarkConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

/** One Needs attention mark, keyed by the item's key in the list. */
@Entity
@Table(name = "attention_mark")
public class AttentionMarkEntity
        extends AssignedIdEntity<String>
{
    @Id
    @Getter(onMethod_ = @Override)
    private String id;

    @Column(nullable = false, columnDefinition = "TEXT")
    @Convert(converter = AttentionMarkConverter.class)
    private AttentionMark value;

    protected AttentionMarkEntity() {}

    public AttentionMarkEntity(AttentionMark mark)
    {
        id = mark.key();
        update(mark);
    }

    public void update(AttentionMark mark)
    {
        value = mark;
    }

    public AttentionMark toDomain()
    {
        return value;
    }
}

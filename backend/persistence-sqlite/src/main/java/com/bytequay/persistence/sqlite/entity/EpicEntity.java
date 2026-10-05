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

import com.bytequay.core.domain.Epic;
import com.bytequay.persistence.sqlite.converter.EpicConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One row per Epic. Each save builds a fresh entity, so saving merges: it reads the row, then inserts or updates it. */
@Entity
@Table(name = "epic")
public class EpicEntity
{
    @Id
    private String id;

    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;

    @Column(nullable = false, columnDefinition = "TEXT")
    @Convert(converter = EpicConverter.class)
    private Epic value;

    protected EpicEntity() {}

    public EpicEntity(Epic epic)
    {
        id = epic.id();
        workspaceId = epic.workspaceId();
        value = epic;
    }

    public Epic toDomain()
    {
        return value;
    }
}

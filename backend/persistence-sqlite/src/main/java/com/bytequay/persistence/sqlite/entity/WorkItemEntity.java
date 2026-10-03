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

import com.bytequay.core.domain.WorkItem;
import com.bytequay.persistence.sqlite.converter.WorkItemConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;

@Entity
@Table(name = "work_item", indexes = @Index(name = "idx_work_item_workspace", columnList = "workspace_id"))
public class WorkItemEntity
        extends AssignedIdEntity<String>
{
    @Id
    @Getter(onMethod_ = @Override)
    private String id;

    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;

    @Column(nullable = false)
    private long revision;

    @Column(nullable = false, columnDefinition = "TEXT")
    @Convert(converter = WorkItemConverter.class)
    private WorkItem value;

    protected WorkItemEntity() {}

    public WorkItemEntity(WorkItem item)
    {
        id = item.id();
        workspaceId = item.workspaceId();
        revision = item.revision();
        value = item;
    }

    public WorkItem toDomain()
    {
        return value;
    }
}

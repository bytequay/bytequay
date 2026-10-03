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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

/** Database uniqueness protects source and task ownership, including cross-workspace starts. */
@Entity
@Table(name = "work_item_identity")
public class WorkItemIdentityEntity
        extends AssignedIdEntity<String>
{
    @Id
    @Getter(onMethod_ = @Override)
    private String id;

    @Getter
    @Column(name = "work_item_id", nullable = false)
    private String workItemId;

    protected WorkItemIdentityEntity() {}

    public WorkItemIdentityEntity(String identity, String workItemId)
    {
        id = identity;
        this.workItemId = workItemId;
    }
}

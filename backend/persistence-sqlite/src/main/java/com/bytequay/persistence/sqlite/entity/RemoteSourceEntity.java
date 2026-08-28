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

import com.bytequay.core.domain.RemoteSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Setter;

@Entity
@Table(name = "workspace_remote_sources")
public class RemoteSourceEntity
{
    @Id
    private String id;

    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    @Setter
    private String url;

    public RemoteSourceEntity() {}

    public static String idOf(String workspaceId, String name)
    {
        return workspaceId + ":" + name;
    }

    public static RemoteSourceEntity fromDomain(RemoteSource source)
    {
        RemoteSourceEntity entity = new RemoteSourceEntity();
        entity.id = idOf(source.workspaceId(), source.name());
        entity.workspaceId = source.workspaceId();
        entity.name = source.name();
        entity.url = source.url();
        return entity;
    }

    public RemoteSource toDomain()
    {
        return new RemoteSource(workspaceId, name, url);
    }
}

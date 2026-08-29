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
import com.bytequay.core.domain.RepoRef;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Optional;

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
    private String url;

    @Column(name = "provider_repository_id")
    private Long repositoryId;

    @Column(name = "provider_owner")
    private String repositoryOwner;

    @Column(name = "provider_repo")
    private String repositoryName;

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
        entity.update(source);
        return entity;
    }

    public void update(RemoteSource source)
    {
        url = source.url();
        repositoryId = source.repositoryId().orElse(null);
        repositoryOwner = source.repository().map(value -> value.owner()).orElse(null);
        repositoryName = source.repository().map(value -> value.repo()).orElse(null);
    }

    public RemoteSource toDomain()
    {
        Optional<Long> domainRepositoryId = Optional.ofNullable(repositoryId);
        Optional<RepoRef> domainRepository =
                repositoryOwner == null || repositoryName == null
                        ? Optional.empty()
                        : Optional.of(RepoRef.of(repositoryOwner, repositoryName));
        if (domainRepositoryId.isEmpty() || domainRepository.isEmpty()) {
            domainRepositoryId = Optional.empty();
            domainRepository = Optional.empty();
        }
        return new RemoteSource(workspaceId, name, url, domainRepositoryId, domainRepository);
    }
}

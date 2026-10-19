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
package com.bytequay.persistence.sqlite.repository;

import com.bytequay.core.domain.Artifact;
import com.bytequay.persistence.sqlite.entity.ArtifactEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ArtifactJpaRepository
        extends JpaRepository<ArtifactEntity, String>
{
    @Query("select a.value from ArtifactEntity a where a.workspaceId = :workspaceId")
    List<Artifact> records(String workspaceId);

    @Query("select a.value from ArtifactEntity a where a.state = :state")
    List<Artifact> inState(Artifact.State state);

    @Query("select a.html from ArtifactEntity a where a.id = :id and a.workspaceId = :workspaceId and a.html is not null")
    Optional<String> page(String id, String workspaceId);

    @Modifying
    @Query("delete from ArtifactEntity a where a.workspaceId = :workspaceId")
    void deleteWorkspace(String workspaceId);
}

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

import com.bytequay.core.domain.ArtifactPage;
import com.bytequay.persistence.sqlite.entity.ArtifactPageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ArtifactPageJpaRepository
        extends JpaRepository<ArtifactPageEntity, String>
{
    @Query("select p.value from ArtifactPageEntity p where p.workspaceId = :workspaceId and p.artifactId = :artifactId order by p.ordinal")
    List<ArtifactPage> pages(String workspaceId, String artifactId);

    @Query("select p.html from ArtifactPageEntity p where p.workspaceId = :workspaceId and p.artifactId = :artifactId and p.id = :id")
    Optional<String> html(String workspaceId, String artifactId, String id);

    long countByArtifactId(String artifactId);

    @Modifying
    @Query("delete from ArtifactPageEntity p where p.workspaceId = :workspaceId")
    void deleteWorkspace(String workspaceId);
}

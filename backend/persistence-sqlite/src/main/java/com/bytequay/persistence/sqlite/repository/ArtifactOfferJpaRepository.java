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

import com.bytequay.core.domain.ArtifactOffer;
import com.bytequay.persistence.sqlite.entity.ArtifactOfferEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ArtifactOfferJpaRepository
        extends JpaRepository<ArtifactOfferEntity, String>
{
    @Query("select o.value from ArtifactOfferEntity o where o.workspaceId = :workspaceId and o.artifactId = :artifactId order by o.ordinal")
    List<ArtifactOffer> offers(String workspaceId, String artifactId);

    long countByArtifactId(String artifactId);

    @Modifying
    @Query("delete from ArtifactOfferEntity o where o.workspaceId = :workspaceId")
    void deleteWorkspace(String workspaceId);
}

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

import com.bytequay.core.domain.WorkItem;
import com.bytequay.persistence.sqlite.entity.WorkItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface WorkItemJpaRepository
        extends JpaRepository<WorkItemEntity, String>
{
    List<WorkItemEntity> findByWorkspaceId(String workspaceId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE WorkItemEntity w SET w.value = :value, w.revision = :next WHERE w.id = :id AND w.revision = :expected")
    int replace(@Param("id") String id, @Param("value") WorkItem value, @Param("next") long next, @Param("expected") long expected);
}

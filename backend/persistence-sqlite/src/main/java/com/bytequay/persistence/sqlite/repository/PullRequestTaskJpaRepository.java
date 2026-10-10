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

import com.bytequay.persistence.sqlite.entity.PullRequestTaskEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PullRequestTaskJpaRepository
        extends JpaRepository<PullRequestTaskEntity, String>
{
    @Query("""
            SELECT assignment FROM PullRequestTaskEntity assignment
            JOIN AgentTaskEntity task ON task.id = assignment.id
            WHERE assignment.workspaceId = :workspaceId AND assignment.repo = :repo AND assignment.number = :number
            ORDER BY task.createdAt DESC, task.id DESC
            """)
    List<PullRequestTaskEntity> findLatest(@Param("workspaceId") String workspaceId, @Param("repo") String repo, @Param("number") int number, Pageable pageable);
}

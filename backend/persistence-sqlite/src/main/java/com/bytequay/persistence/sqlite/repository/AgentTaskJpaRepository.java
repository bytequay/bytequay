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

import com.bytequay.agent.core.CleanupState;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.persistence.sqlite.entity.AgentTaskEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface AgentTaskJpaRepository
        extends JpaRepository<AgentTaskEntity, String>
{
    List<AgentTaskEntity> findAllByOrderByUpdatedAtDesc(Pageable pageable);

    List<AgentTaskEntity> findByWorkspaceIdAndInteractiveTrueOrderByUpdatedAtDesc(String workspaceId, Pageable pageable);

    boolean existsByWorkspaceIdAndGeneratedBranchIsNotNull(String workspaceId);

    List<AgentTaskEntity> findByStateNotInOrderByCreatedAtAsc(Collection<TaskState> states);

    @Query("""
            FROM AgentTaskEntity task
            WHERE task.state IN :terminalStates
              AND task.permissionPolicy = :permissionPolicy
              AND task.generatedBranch IS NOT NULL
              AND task.worktreeRemovedAt IS NULL
              AND task.cleanupState <> :deleted
              AND task.updatedAt <= :retryBefore
            ORDER BY task.updatedAt ASC
            """)
    List<AgentTaskEntity> findPendingWorktreeCleanup(
            @Param("terminalStates") Collection<TaskState> terminalStates,
            @Param("permissionPolicy") AgentPermissionPolicy permissionPolicy,
            @Param("deleted") CleanupState deleted,
            @Param("retryBefore") Instant retryBefore);

    @Query("""
            FROM AgentTaskEntity task
            WHERE task.state IN :terminalStates
              AND task.cleanupState <> :deleted
              AND task.deleteAfter <= :now
              AND (task.cleanupState <> :retryPending OR task.updatedAt <= :retryBefore)
              AND (
                    task.interactive = true
                    OR task.generatedBranch IS NULL
                    OR task.worktreeRemovedAt IS NOT NULL
                    OR task.permissionPolicy NOT IN :workflowPolicies
              )
            ORDER BY task.updatedAt ASC
            """)
    List<AgentTaskEntity> findCleanupDue(
            @Param("terminalStates") Collection<TaskState> terminalStates,
            @Param("deleted") CleanupState deleted,
            @Param("retryPending") CleanupState retryPending,
            @Param("workflowPolicies") Collection<AgentPermissionPolicy> workflowPolicies,
            @Param("now") Instant now,
            @Param("retryBefore") Instant retryBefore,
            Pageable pageable);
}

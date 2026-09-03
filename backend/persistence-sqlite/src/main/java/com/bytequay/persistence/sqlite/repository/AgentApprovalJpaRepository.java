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

import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.ApprovalDecision;
import com.bytequay.persistence.sqlite.entity.AgentApprovalEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface AgentApprovalJpaRepository
        extends JpaRepository<AgentApprovalEntity, String>
{
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE AgentApprovalEntity approval
            SET approval.decision = :decision,
                approval.scopedPermission = :scopedPermission,
                approval.forTask = :forTask,
                approval.resolvedAt = :resolvedAt,
                approval.timedOut = :timedOut,
                approval.outcome = :outcome
            WHERE approval.id = :id
                AND approval.decision IS NULL
            """)
    int resolvePending(
            String id,
            ApprovalDecision.Decision decision,
            String scopedPermission,
            boolean forTask,
            Instant resolvedAt,
            boolean timedOut,
            AgentEventPayload.ApprovalOutcome outcome);

    List<AgentApprovalEntity> findByTurnIdAndDecisionIsNullOrderByRequestedAtAscIdAsc(String turnId);

    List<AgentApprovalEntity> findByDecisionIsNullOrderByRequestedAtAscIdAsc();
}

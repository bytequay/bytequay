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

import com.bytequay.agent.core.AgentApproval;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.ApprovalDecision;
import com.bytequay.agent.core.ApprovalRequest;
import com.bytequay.persistence.sqlite.converter.InstantToTextConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.Optional;

@Entity
@Table(name = "agent_approval")
public class AgentApprovalEntity
        implements Persistable<String>
{
    @Id
    private String id;

    @Column(name = "task_id", nullable = false)
    private String taskId;

    @Column(name = "turn_id", nullable = false)
    private String turnId;

    @Column(name = "request_json", nullable = false, columnDefinition = "TEXT")
    private String requestJson;

    @Column(name = "requested_at", nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant requestedAt;

    @Enumerated(EnumType.STRING)
    private ApprovalDecision.Decision decision;

    private String scopedPermission;

    private Boolean forTask;

    @Convert(converter = InstantToTextConverter.class)
    private Instant resolvedAt;

    private Boolean timedOut;

    @Enumerated(EnumType.STRING)
    private AgentEventPayload.ApprovalOutcome outcome;

    @Transient
    private boolean newEntity = true;

    public AgentApprovalEntity() {}

    public static AgentApprovalEntity fromDomain(AgentApproval approval)
    {
        ApprovalRequest request = approval.request();
        AgentApprovalEntity entity = new AgentApprovalEntity();
        entity.id = request.id().toString();
        entity.taskId = request.taskId().toString();
        entity.turnId = request.turnId().toString();
        entity.requestJson = AgentApprovalJson.encode(request);
        entity.requestedAt = request.requestedAt();
        approval.decision().ifPresent(value -> {
            entity.decision = value.decision();
            entity.scopedPermission = value.scopedPermission().orElse(null);
            entity.forTask = value.forTask();
            entity.resolvedAt = value.resolvedAt();
            entity.timedOut = value.timedOut();
        });
        entity.outcome = approval.outcome().orElse(null);
        return entity;
    }

    public AgentApproval toDomain()
    {
        ApprovalRequest request = AgentApprovalJson.decode(requestJson);
        Optional<ApprovalDecision> approvalDecision = Optional.ofNullable(decision)
                .map(value -> new ApprovalDecision(
                        request.id(),
                        value,
                        Optional.ofNullable(scopedPermission),
                        Boolean.TRUE.equals(forTask),
                        resolvedAt,
                        timedOut));
        return new AgentApproval(request, approvalDecision, Optional.ofNullable(outcome));
    }

    @Override
    public String getId()
    {
        return id;
    }

    @Override
    public boolean isNew()
    {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    private void markPersisted()
    {
        newEntity = false;
    }
}

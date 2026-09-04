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

import com.bytequay.agent.core.AgentInputDelivery;
import com.bytequay.agent.core.InputDeliveryId;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
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
import jakarta.persistence.Version;
import lombok.Getter;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.Optional;

@Entity
@Table(name = "agent_input_delivery")
public class AgentInputDeliveryEntity
        implements Persistable<String>
{
    @Id
    @Getter(onMethod_ = @Override)
    private String id;

    @Column(name = "task_id", nullable = false)
    private String taskId;

    @Column(name = "turn_id", nullable = false)
    private String turnId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(nullable = false, unique = true)
    private String idempotencySlot;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private AgentInputDelivery.State state;

    @Version
    @Getter
    private long version;

    @Column(nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant createdAt;

    @Column(nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant updatedAt;

    private String failure;

    @Transient
    private boolean newEntity = true;

    public AgentInputDeliveryEntity() {}

    public static AgentInputDeliveryEntity fromDomain(AgentInputDelivery delivery)
    {
        AgentInputDeliveryEntity entity = new AgentInputDeliveryEntity();
        entity.id = delivery.id().toString();
        entity.version = delivery.version();
        entity.updateFrom(delivery);
        return entity;
    }

    public void updateFrom(AgentInputDelivery delivery)
    {
        taskId = delivery.taskId().toString();
        turnId = delivery.turnId().toString();
        idempotencyKey = delivery.idempotencyKey();
        idempotencySlot = delivery.taskId() + ":" + delivery.idempotencyKey();
        state = delivery.state();
        createdAt = delivery.createdAt();
        updatedAt = delivery.updatedAt();
        failure = delivery.failure().orElse(null);
    }

    public AgentInputDelivery toDomain()
    {
        return new AgentInputDelivery(
                InputDeliveryId.valueOf(id),
                TaskId.valueOf(taskId),
                TurnId.valueOf(turnId),
                idempotencyKey,
                state,
                createdAt,
                updatedAt,
                Optional.ofNullable(failure),
                version);
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

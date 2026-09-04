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

import com.bytequay.agent.core.AgentSandboxPolicy;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.BlobRef;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.QueuedAgentInput;
import com.bytequay.agent.core.QueuedInputId;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
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

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Entity
@Table(name = "agent_queued_input")
public class AgentQueuedInputEntity
        implements Persistable<String>
{
    @Id
    @Getter(onMethod_ = @Override)
    private String id;

    @Column(name = "task_id", nullable = false)
    private String taskId;

    @Column(nullable = false)
    private long position;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private QueuedAgentInput.State state;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(nullable = false, unique = true)
    private String idempotencySlot;

    @Column(nullable = false)
    private String textBlobPath;

    @Column(nullable = false)
    private String textBlobMediaType;

    @Column(nullable = false)
    private long textBlobSize;

    private String requestedModel;

    private String effort;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private AgentPermissionPolicy permissionPolicy;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private AgentSandboxPolicy sandboxPolicy;

    @Column(nullable = false)
    private long maximumRuntimeMillis;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String attachmentsJson;

    private String messageEventId;

    private String turnId;

    private String pauseReason;

    @Version
    @Getter
    private long version;

    @Column(nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant createdAt;

    @Column(nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant updatedAt;

    @Transient
    private boolean newEntity = true;

    public AgentQueuedInputEntity() {}

    public static AgentQueuedInputEntity fromDomain(QueuedAgentInput input)
    {
        AgentQueuedInputEntity entity = new AgentQueuedInputEntity();
        entity.id = input.id().toString();
        entity.version = input.version();
        entity.updateFrom(input);
        return entity;
    }

    public void updateFrom(QueuedAgentInput input)
    {
        taskId = input.taskId().toString();
        position = input.position();
        state = input.state();
        idempotencyKey = input.idempotencyKey();
        idempotencySlot = input.taskId() + ":" + input.idempotencyKey();
        textBlobPath = input.textBlob().relativePath();
        textBlobMediaType = input.textBlob().mediaType();
        textBlobSize = input.textBlob().size();
        requestedModel = input.settings().model().orElse(null);
        effort = input.settings().effort().orElse(null);
        permissionPolicy = input.settings().permissionPolicy();
        sandboxPolicy = input.settings().sandboxPolicy();
        maximumRuntimeMillis = input.settings().maximumRuntime().toMillis();
        attachmentsJson = AgentAttachmentReferenceJson.encode(input.settings().attachments());
        messageEventId = input.messageEventId().map(EventId::toString).orElse(null);
        turnId = input.turnId().map(TurnId::toString).orElse(null);
        pauseReason = input.pauseReason().orElse(null);
        createdAt = input.createdAt();
        updatedAt = input.updatedAt();
    }

    public QueuedAgentInput toDomain()
    {
        return new QueuedAgentInput(
                QueuedInputId.valueOf(id),
                TaskId.valueOf(taskId),
                position,
                state,
                idempotencyKey,
                new BlobRef(textBlobPath, textBlobMediaType, textBlobSize),
                "Queued input",
                new AgentTurnSettings(
                        Optional.ofNullable(requestedModel),
                        Optional.ofNullable(effort),
                        permissionPolicy,
                        sandboxPolicy,
                        Duration.ofMillis(maximumRuntimeMillis),
                        AgentAttachmentReferenceJson.decode(attachmentsJson)),
                Optional.ofNullable(messageEventId).map(EventId::valueOf),
                Optional.ofNullable(turnId).map(TurnId::valueOf),
                Optional.ofNullable(pauseReason),
                createdAt,
                updatedAt,
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

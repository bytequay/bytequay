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

import com.bytequay.agent.core.BlobRef;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.QueuedAgentInput;
import com.bytequay.agent.core.QueuedInputId;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;

import java.time.Instant;
import java.util.Optional;

@Entity
@Table(name = "agent_queued_input", uniqueConstraints = @UniqueConstraint(columnNames = {"task_id", "idempotency_key"}))
public class AgentQueuedInputEntity
        extends AssignedIdEntity<String>
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

    @Column(nullable = false)
    private String textBlobPath;

    @Column(nullable = false)
    private String textBlobMediaType;

    @Column(nullable = false)
    private long textBlobSize;

    @Embedded
    @AttributeOverride(name = "permissionPolicy", column = @Column(name = "permission_policy", nullable = false))
    @AttributeOverride(name = "maximumRuntimeMillis", column = @Column(name = "maximum_runtime_millis", nullable = false))
    @AttributeOverride(name = "attachmentsJson", column = @Column(name = "attachments_json", nullable = false, columnDefinition = "TEXT"))
    private AgentTurnSettingsColumns settings;

    private String messageEventId;

    private String turnId;

    private String pauseReason;

    @Version
    @Getter
    private long version;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

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
        textBlobPath = input.textBlob().relativePath();
        textBlobMediaType = input.textBlob().mediaType();
        textBlobSize = input.textBlob().size();
        settings = new AgentTurnSettingsColumns(input.settings());
        messageEventId = input.messageEventId().map(EventId::toString).orElse(null);
        turnId = input.turnId().map(TurnId::toString).orElse(null);
        pauseReason = input.pauseReason().orElse(null);
        createdAt = input.createdAt();
        updatedAt = input.updatedAt();
    }

    public QueuedAgentInput toDomain()
    {
        return new QueuedAgentInput(
                QueuedInputId.valueOf(id), TaskId.valueOf(taskId), position, state, idempotencyKey, new BlobRef(textBlobPath, textBlobMediaType, textBlobSize), "Queued input",
                settings.toDomain(), Optional.ofNullable(messageEventId).map(EventId::valueOf), Optional.ofNullable(turnId).map(TurnId::valueOf), Optional.ofNullable(pauseReason),
                createdAt, updatedAt, version);
    }
}

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
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.ToolCounters;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnMetrics;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentTurnFailureKind;
import com.bytequay.agent.spi.CostProvenance;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.persistence.sqlite.converter.InstantToTextConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import lombok.Getter;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Entity
@Table(name = "agent_turn")
public class AgentTurnEntity
        implements Persistable<String>
{
    @Id
    @Getter(onMethod_ = @Override)
    private String id;

    @Column(name = "task_id", nullable = false)
    private String taskId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "task_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "agent_turn_task_fk"))
    private AgentTaskEntity task;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(nullable = false, unique = true)
    private String idempotencySlot;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private TurnState state;

    @Column(unique = true)
    private String activeTaskId;

    @Version
    @Getter
    private long version;

    @Column(nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant createdAt;

    @Convert(converter = InstantToTextConverter.class)
    private Instant startedAt;

    @Convert(converter = InstantToTextConverter.class)
    private Instant endedAt;

    @Convert(converter = InstantToTextConverter.class)
    private Instant cancellationRequestedAt;

    private String providerTurnReference;

    private Boolean knownUnsent;

    private String model;

    @Column(name = "requested_model")
    private String requestedModel;

    private String effort;

    private String serviceTier;

    @Enumerated(EnumType.STRING)
    private AgentPermissionPolicy permissionPolicy;

    @Enumerated(EnumType.STRING)
    private AgentSandboxPolicy sandboxPolicy;

    private Long maximumRuntimeMillis;

    @Column(columnDefinition = "TEXT")
    private String attachmentsJson;

    private String terminalReason;

    @Enumerated(EnumType.STRING)
    private AgentTurnFailureKind failureKind;

    @Column(nullable = false)
    private long inputTokens;

    @Column(nullable = false)
    private long cachedInputTokens;

    @Column(nullable = false)
    private long outputTokens;

    private BigDecimal cost;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private CostProvenance costProvenance;

    @Column(nullable = false)
    private long elapsedMillis;

    // Nullable so a database written before these counters keeps opening; null reads as zero.
    @Column(name = "tool_calls")
    private Long toolCalls;

    @Column(name = "explore_actions")
    private Long exploreActions;

    @Column(name = "codegraph_calls")
    private Long codegraphCalls;

    @Column(name = "codegraph_millis")
    private Long codegraphMillis;

    @Column(name = "codegraph_failures")
    private Long codegraphFailures;

    @Transient
    private boolean newEntity = true;

    public AgentTurnEntity() {}

    public static AgentTurnEntity fromDomain(AgentTurn turn)
    {
        AgentTurnEntity entity = new AgentTurnEntity();
        entity.id = turn.id().toString();
        entity.taskId = turn.taskId().toString();
        entity.version = turn.version();
        entity.updateFrom(turn);
        return entity;
    }

    public void updateFrom(AgentTurn turn)
    {
        idempotencyKey = turn.idempotencyKey();
        idempotencySlot = turn.taskId() + ":" + turn.idempotencyKey();
        state = turn.state();
        activeTaskId = turn.state().active() ? turn.taskId().toString() : null;
        createdAt = turn.createdAt();
        startedAt = turn.startedAt().orElse(null);
        endedAt = turn.endedAt().orElse(null);
        cancellationRequestedAt = turn.cancellationRequestedAt().orElse(null);
        providerTurnReference = turn.providerTurnReference().map(ProviderTurnRef::value).orElse(null);
        knownUnsent = turn.knownUnsent();
        model = turn.model().orElse(null);
        requestedModel = turn.settings().flatMap(AgentTurnSettings::model).orElse(null);
        effort = turn.settings().flatMap(AgentTurnSettings::effort).orElse(null);
        serviceTier = turn.settings().flatMap(AgentTurnSettings::serviceTier).orElse(null);
        permissionPolicy = turn.settings().map(AgentTurnSettings::permissionPolicy).orElse(null);
        sandboxPolicy = turn.settings().map(AgentTurnSettings::sandboxPolicy).orElse(null);
        maximumRuntimeMillis = turn.settings().map(settings -> settings.maximumRuntime().toMillis()).orElse(null);
        attachmentsJson = turn.settings()
                .map(AgentTurnSettings::attachments)
                .map(AgentAttachmentReferenceJson::encode)
                .orElse(null);
        terminalReason = turn.terminalReason().orElse(null);
        failureKind = turn.failureKind().orElse(null);
        inputTokens = turn.metrics().inputTokens();
        cachedInputTokens = turn.metrics().cachedInputTokens();
        outputTokens = turn.metrics().outputTokens();
        cost = turn.metrics().cost().orElse(null);
        costProvenance = turn.metrics().costProvenance();
        elapsedMillis = turn.metrics().elapsed().toMillis();
        toolCalls = turn.metrics().counters().toolCalls();
        exploreActions = turn.metrics().counters().exploreActions();
        codegraphCalls = turn.metrics().counters().codegraphCalls();
        codegraphMillis = turn.metrics().counters().codegraphMillis();
        codegraphFailures = turn.metrics().counters().codegraphFailures();
    }

    public AgentTurn toDomain()
    {
        Optional<AgentTurnSettings> settings = Optional.ofNullable(maximumRuntimeMillis)
                .map(runtime -> new AgentTurnSettings(
                        Optional.ofNullable(requestedModel),
                        Optional.ofNullable(effort),
                        permissionPolicy,
                        sandboxPolicy,
                        Duration.ofMillis(runtime),
                        AgentAttachmentReferenceJson.decode(attachmentsJson),
                        Optional.ofNullable(serviceTier)));
        return new AgentTurn(
                TurnId.valueOf(id),
                TaskId.valueOf(taskId),
                idempotencyKey,
                state,
                version,
                createdAt,
                Optional.ofNullable(startedAt),
                Optional.ofNullable(endedAt),
                Optional.ofNullable(cancellationRequestedAt),
                Optional.ofNullable(providerTurnReference).map(ProviderTurnRef::new),
                Boolean.TRUE.equals(knownUnsent),
                Optional.ofNullable(model),
                settings,
                Optional.ofNullable(terminalReason),
                Optional.ofNullable(failureKind),
                new TurnMetrics(
                        inputTokens,
                        cachedInputTokens,
                        outputTokens,
                        Optional.ofNullable(cost),
                        costProvenance,
                        Duration.ofMillis(elapsedMillis),
                        counters()));
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

    private ToolCounters counters()
    {
        return new ToolCounters(zeroIfNull(toolCalls), zeroIfNull(exploreActions), zeroIfNull(codegraphCalls), zeroIfNull(codegraphMillis), zeroIfNull(codegraphFailures));
    }

    private static long zeroIfNull(Long value)
    {
        return value == null ? 0 : value;
    }
}

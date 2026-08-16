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
package com.bytequay.persistence.sqlite;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.CleanupState;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskMetrics;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentSessionRef;
import com.bytequay.agent.spi.CostProvenance;
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
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Entity
@Table(name = "agent_task")
class AgentTaskEntity
        implements Persistable<String>
{
    @Id
    private String id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String laneId;

    @Column(nullable = false)
    private String repositoryOwner;

    @Column(nullable = false)
    private String repositoryName;

    @Column(nullable = false)
    private String worktree;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private AgentPermissionPolicy permissionPolicy;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private TaskState state;

    @Version
    private long version;

    @Column(nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant createdAt;

    @Column(nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant updatedAt;

    @Convert(converter = InstantToTextConverter.class)
    private Instant terminalAt;

    private String terminalReason;

    private String sessionLaneId;

    private Integer sessionContractVersion;

    private String sessionProviderValue;

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

    @Column(columnDefinition = "TEXT")
    private String summaryJson;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private CleanupState cleanupState;

    @Convert(converter = InstantToTextConverter.class)
    private Instant deleteAfter;

    private String cleanupFailure;

    @Transient
    private boolean newEntity = true;

    protected AgentTaskEntity() {}

    static AgentTaskEntity fromDomain(AgentTask task)
    {
        AgentTaskEntity entity = new AgentTaskEntity();
        entity.id = task.id().toString();
        entity.version = task.version();
        entity.updateFrom(task);
        return entity;
    }

    void updateFrom(AgentTask task)
    {
        title = task.title();
        laneId = task.laneId().toString();
        repositoryOwner = task.repository().owner();
        repositoryName = task.repository().name();
        worktree = task.worktree().toString();
        permissionPolicy = task.permissionPolicy();
        state = task.state();
        createdAt = task.createdAt();
        updatedAt = task.updatedAt();
        terminalAt = task.terminalAt().orElse(null);
        terminalReason = task.terminalReason().orElse(null);
        sessionLaneId = task.sessionReference().map(reference -> reference.laneId().toString()).orElse(null);
        sessionContractVersion = task.sessionReference().map(AgentSessionRef::contractVersion).orElse(null);
        sessionProviderValue = task.sessionReference().map(AgentSessionRef::providerValue).orElse(null);
        inputTokens = task.metrics().inputTokens();
        cachedInputTokens = task.metrics().cachedInputTokens();
        outputTokens = task.metrics().outputTokens();
        cost = task.metrics().cost().orElse(null);
        costProvenance = task.metrics().costProvenance();
        elapsedMillis = task.metrics().elapsed().toMillis();
        summaryJson = task.summary().map(AgentTaskSummaryJson::encode).orElse(null);
        cleanupState = task.cleanupState();
        deleteAfter = task.deleteAfter().orElse(null);
        cleanupFailure = task.cleanupFailure().orElse(null);
    }

    AgentTask toDomain()
    {
        Optional<AgentSessionRef> sessionReference = Optional.ofNullable(sessionLaneId)
                .map(ignored -> new AgentSessionRef(
                        new AgentLaneId(sessionLaneId),
                        sessionContractVersion,
                        sessionProviderValue));
        return new AgentTask(
                TaskId.valueOf(id),
                title,
                new AgentLaneId(laneId),
                new RepositoryIdentity(repositoryOwner, repositoryName),
                Path.of(worktree),
                permissionPolicy,
                state,
                version,
                createdAt,
                updatedAt,
                Optional.ofNullable(terminalAt),
                Optional.ofNullable(terminalReason),
                sessionReference,
                new TaskMetrics(
                        inputTokens,
                        cachedInputTokens,
                        outputTokens,
                        Optional.ofNullable(cost),
                        costProvenance,
                        Duration.ofMillis(elapsedMillis)),
                Optional.ofNullable(summaryJson).map(AgentTaskSummaryJson::decode),
                cleanupState,
                Optional.ofNullable(deleteAfter),
                Optional.ofNullable(cleanupFailure));
    }

    long getVersion()
    {
        return version;
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

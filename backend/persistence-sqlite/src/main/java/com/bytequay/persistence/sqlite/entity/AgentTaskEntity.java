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

import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.CleanupState;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskMetrics;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentSessionRef;
import com.bytequay.agent.spi.CostProvenance;
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

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Entity
@Table(name = "agent_task")
public class AgentTaskEntity
        implements Persistable<String>
{
    @Id
    @Getter(onMethod_ = @Override)
    private String id;

    private String workspaceId;

    // Nullable so rows written before workspace-scoped tasks remain readable as internal.
    private Boolean interactive;

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

    private String generatedBranch;

    private String startCommit;

    private String defaultModel;

    private String defaultEffort;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private AgentPermissionPolicy permissionPolicy;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private TaskState state;

    @Version
    @Getter
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

    public AgentTaskEntity() {}

    public static AgentTaskEntity fromDomain(AgentTask task)
    {
        AgentTaskEntity entity = new AgentTaskEntity();
        entity.id = task.id().toString();
        entity.version = task.version();
        entity.updateFrom(task);
        return entity;
    }

    public void updateFrom(AgentTask task)
    {
        workspaceId = task.workspaceId().orElse(null);
        interactive = task.interactive();
        title = task.title();
        laneId = task.laneId().toString();
        repositoryOwner = task.repository().owner();
        repositoryName = task.repository().name();
        worktree = task.worktree().toString();
        generatedBranch = task.taskWorktree().map(TaskWorktree::generatedBranch).orElse(null);
        startCommit = task.taskWorktree().map(TaskWorktree::startCommit).orElse(null);
        defaultModel = task.defaults().model().orElse(null);
        defaultEffort = task.defaults().effort().orElse(null);
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

    public AgentTask toDomain()
    {
        Optional<AgentSessionRef> sessionReference = Optional.ofNullable(sessionLaneId)
                .map(ignored -> new AgentSessionRef(
                        new AgentLaneId(sessionLaneId),
                        sessionContractVersion,
                        sessionProviderValue));
        return new AgentTask(
                TaskId.valueOf(id),
                Optional.ofNullable(workspaceId),
                Boolean.TRUE.equals(interactive),
                title,
                new AgentLaneId(laneId),
                new RepositoryIdentity(repositoryOwner, repositoryName),
                Path.of(worktree),
                taskWorktree(),
                new AgentTaskDefaults(
                        Optional.ofNullable(defaultModel),
                        Optional.ofNullable(defaultEffort),
                        permissionPolicy),
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

    private Optional<TaskWorktree> taskWorktree()
    {
        if (generatedBranch == null && startCommit == null) {
            return Optional.empty();
        }
        if (workspaceId == null || generatedBranch == null || startCommit == null) {
            throw new AgentStorageException("Stored task worktree facts are incomplete: " + id);
        }
        return Optional.of(new TaskWorktree(
                TaskId.valueOf(id),
                workspaceId,
                new RepositoryIdentity(repositoryOwner, repositoryName),
                Path.of(worktree),
                generatedBranch,
                startCommit));
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

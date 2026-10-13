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

import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.CleanupState;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.ToolCounters;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentSessionRef;
import com.bytequay.persistence.sqlite.converter.DevelopmentAutomationConverter;
import com.bytequay.persistence.sqlite.converter.InstantToTextConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Entity
@Table(name = "agent_task")
public class AgentTaskEntity
        extends AssignedIdEntity<String>
{
    @Id
    @Getter(onMethod_ = @Override)
    private String id;

    private String workspaceId;

    // Nullable so rows written before workspace-scoped tasks remain readable as internal.
    private Boolean interactive;

    private String creationFingerprint;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean developmentContinuationUnfinished;

    @Column(nullable = false)
    private String title;

    // Nullable so rows written before titles had a source read as fixed, never as proposable.
    @Enumerated(EnumType.STRING)
    private TitleSource titleSource;

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

    private String defaultLaneId;

    private String defaultEffort;

    private String defaultServiceTier;

    @Column(columnDefinition = "TEXT")
    @Convert(converter = DevelopmentAutomationConverter.class)
    private DevelopmentAutomation developmentAutomation;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private AgentPermissionPolicy permissionPolicy;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private TaskState state;

    @Enumerated(EnumType.STRING)
    private WorktreeMutationClaim.Kind mutationClaimKind;

    private String mutationClaimOwnerId;

    @Convert(converter = InstantToTextConverter.class)
    private Instant mutationClaimAcquiredAt;

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
    private long elapsedMillis;

    // Nullable so a database written before these counters keeps opening; null reads as zero.
    @Column(name = "tool_calls")
    private Long toolCalls;

    @Column(name = "explore_actions")
    private Long exploreActions;

    @Column(name = "context_compactions")
    private Long contextCompactions;

    @Column(columnDefinition = "TEXT")
    private String summaryJson;

    @Convert(converter = InstantToTextConverter.class)
    private Instant worktreeRemovedAt;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private CleanupState cleanupState;

    @Convert(converter = InstantToTextConverter.class)
    private Instant deleteAfter;

    private String cleanupFailure;

    @Convert(converter = InstantToTextConverter.class)
    private Instant archivedAt;

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
        creationFingerprint = task.creationFingerprint().orElse(null);
        developmentContinuationUnfinished = task.developmentContinuationUnfinished();
        title = task.title();
        titleSource = task.titleSource();
        laneId = task.laneId().toString();
        repositoryOwner = task.repository().owner();
        repositoryName = task.repository().name();
        worktree = task.worktree().toString();
        generatedBranch = task.taskWorktree().map(TaskWorktree::generatedBranch).orElse(null);
        startCommit = task.taskWorktree().map(TaskWorktree::startCommit).orElse(null);
        defaultModel = task.defaults().model().orElse(null);
        defaultLaneId = task.defaults().laneId().map(AgentLaneId::value).orElse(null);
        defaultEffort = task.defaults().effort().orElse(null);
        defaultServiceTier = task.defaults().serviceTier().orElse(null);
        developmentAutomation = task.defaults().developmentAutomation().orElse(null);
        permissionPolicy = task.permissionPolicy();
        state = task.state();
        mutationClaimKind = task.worktreeMutationClaim().map(WorktreeMutationClaim::kind).orElse(null);
        mutationClaimOwnerId = task.worktreeMutationClaim().map(WorktreeMutationClaim::ownerId).orElse(null);
        mutationClaimAcquiredAt = task.worktreeMutationClaim().map(WorktreeMutationClaim::acquiredAt).orElse(null);
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
        elapsedMillis = task.metrics().elapsed().toMillis();
        toolCalls = task.metrics().counters().toolCalls();
        exploreActions = task.metrics().counters().exploreActions();
        contextCompactions = task.metrics().counters().compactions();
        summaryJson = task.summary().map(AgentTaskSummaryJson::encode).orElse(null);
        worktreeRemovedAt = task.worktreeRemovedAt().orElse(null);
        cleanupState = task.cleanupState();
        deleteAfter = task.deleteAfter().orElse(null);
        cleanupFailure = task.cleanupFailure().orElse(null);
        archivedAt = task.archivedAt().orElse(null);
    }

    public AgentTask toDomain()
    {
        Optional<TaskWorktree> storedWorktree = taskWorktree();
        Optional<AgentSessionRef> sessionReference = Optional.ofNullable(sessionLaneId)
                .map(ignored -> new AgentSessionRef(new AgentLaneId(sessionLaneId), sessionContractVersion, sessionProviderValue));
        return new AgentTask(
                TaskId.valueOf(id),
                Optional.ofNullable(workspaceId),
                Boolean.TRUE.equals(interactive),
                Optional.ofNullable(creationFingerprint),
                title,
                titleSource == null ? TitleSource.USER : titleSource,
                new AgentLaneId(laneId),
                new RepositoryIdentity(repositoryOwner, repositoryName),
                Path.of(worktree),
                storedWorktree,
                new AgentTaskDefaults(
                        Optional.ofNullable(defaultModel),
                        Optional.ofNullable(defaultEffort),
                        permissionPolicy,
                        Optional.ofNullable(defaultServiceTier),
                        Optional.ofNullable(developmentAutomation),
                        Optional.ofNullable(defaultLaneId).map(AgentLaneId::new)),
                state,
                worktreeMutationClaim(),
                version,
                createdAt,
                updatedAt,
                Optional.ofNullable(terminalAt),
                Optional.ofNullable(terminalReason),
                sessionReference,
                new AgentMetrics(inputTokens, cachedInputTokens, outputTokens, Optional.ofNullable(cost), Duration.ofMillis(elapsedMillis), counters()),
                Optional.ofNullable(summaryJson).map(json -> AgentTaskSummaryJson.decode(json, storedWorktree)),
                Optional.ofNullable(worktreeRemovedAt),
                cleanupState,
                Optional.ofNullable(deleteAfter),
                Optional.ofNullable(cleanupFailure),
                Optional.ofNullable(archivedAt),
                developmentContinuationUnfinished);
    }

    private Optional<WorktreeMutationClaim> worktreeMutationClaim()
    {
        if (mutationClaimKind == null && mutationClaimOwnerId == null && mutationClaimAcquiredAt == null) {
            return Optional.empty();
        }
        if (mutationClaimKind == null || mutationClaimOwnerId == null || mutationClaimAcquiredAt == null) {
            throw new AgentStorageException("Stored worktree mutation claim is incomplete: " + id);
        }
        return Optional.of(new WorktreeMutationClaim(mutationClaimKind, mutationClaimOwnerId, mutationClaimAcquiredAt));
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

    private ToolCounters counters()
    {
        return new ToolCounters(zeroIfNull(toolCalls), zeroIfNull(exploreActions), zeroIfNull(contextCompactions));
    }

    private static long zeroIfNull(Long value)
    {
        return value == null ? 0 : value;
    }
}

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

import com.bytequay.core.cherrypick.CherryPickExecution;
import com.bytequay.core.cherrypick.CherryPickExecution.FinishReason;
import com.bytequay.core.cherrypick.CherryPickExecution.PauseReason;
import com.bytequay.core.cherrypick.CherryPickExecution.Range;
import com.bytequay.core.cherrypick.CherryPickExecution.Status;
import com.bytequay.core.cherrypick.CherryPickExecution.Step;
import com.bytequay.core.cherrypick.CherryPickExecution.Workspace;
import com.bytequay.core.cherrypick.CherryPickExecutionId;
import com.bytequay.core.domain.RepoRef;
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
import org.springframework.data.domain.Persistable;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;

@Entity
@Table(name = "cherry_pick_execution")
public class CherryPickExecutionEntity
        implements Persistable<String>
{
    @Id
    private String id;

    @Column(nullable = false, unique = true)
    private String startIdempotencyKey;

    @Column(nullable = false)
    private String repositoryOwner;

    @Column(nullable = false)
    private String repositoryName;

    @Column(nullable = false)
    private String sourceRemote;

    @Column(nullable = false)
    private String targetRemote;

    @Column(nullable = false)
    private String targetBranch;

    @Column(nullable = false)
    private String localTargetBranch;

    @Column(nullable = false)
    private String workBranch;

    @Column(nullable = false)
    private String worktree;

    @Column(nullable = false)
    private String laneId;

    @Column(nullable = false)
    private String sourceBaseSha;

    @Column(nullable = false)
    private String sourceTipSha;

    @Column(nullable = false)
    private String initialTargetSha;

    @Column(nullable = false)
    private String currentTargetSha;

    @Column(unique = true)
    private String agentTaskId;

    private String currentTurnId;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status;

    @Enumerated(EnumType.STRING)
    private Step currentStep;

    @Enumerated(EnumType.STRING)
    private PauseReason pauseReason;

    @Enumerated(EnumType.STRING)
    private FinishReason finishReason;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(columnDefinition = "TEXT")
    private String journalJson;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String itemsJson;

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

    @Transient
    private boolean newEntity = true;

    public CherryPickExecutionEntity() {}

    public static CherryPickExecutionEntity fromDomain(CherryPickExecution execution)
    {
        CherryPickExecutionEntity entity = new CherryPickExecutionEntity();
        entity.id = execution.id().toString();
        entity.version = execution.version();
        entity.updateFrom(execution);
        return entity;
    }

    public void updateFrom(CherryPickExecution execution)
    {
        checkArgument(id.equals(execution.id().toString()), "execution identity changed");
        Workspace workspace = execution.workspace();
        Range range = execution.range();
        startIdempotencyKey = execution.startIdempotencyKey();
        repositoryOwner = workspace.repository().owner();
        repositoryName = workspace.repository().repo();
        sourceRemote = workspace.sourceRemote();
        targetRemote = workspace.targetRemote();
        targetBranch = workspace.targetBranch();
        localTargetBranch = workspace.localTargetBranch();
        workBranch = workspace.workBranch();
        worktree = workspace.worktree().toString();
        laneId = workspace.laneId();
        sourceBaseSha = range.sourceBaseSha();
        sourceTipSha = range.sourceTipSha();
        initialTargetSha = range.initialTargetSha();
        currentTargetSha = execution.currentTargetSha();
        agentTaskId = execution.agentTaskId().orElse(null);
        currentTurnId = execution.currentTurnId().orElse(null);
        status = execution.status();
        currentStep = execution.currentStep().orElse(null);
        pauseReason = execution.pauseReason().orElse(null);
        finishReason = execution.finishReason().orElse(null);
        summary = execution.summary().orElse(null);
        journalJson = CherryPickJson.encodeJournal(execution.journal());
        itemsJson = CherryPickJson.encodeItems(execution.items());
        createdAt = execution.createdAt();
        updatedAt = execution.updatedAt();
        terminalAt = execution.terminalAt().orElse(null);
    }

    public CherryPickExecution toExecution()
    {
        return new CherryPickExecution(
                CherryPickExecutionId.valueOf(id),
                startIdempotencyKey,
                new Workspace(
                        RepoRef.of(repositoryOwner, repositoryName),
                        sourceRemote,
                        targetRemote,
                        targetBranch,
                        localTargetBranch,
                        workBranch,
                        Path.of(worktree),
                        laneId),
                new Range(sourceBaseSha, sourceTipSha, initialTargetSha),
                currentTargetSha,
                Optional.ofNullable(agentTaskId),
                Optional.ofNullable(currentTurnId),
                status,
                Optional.ofNullable(currentStep),
                Optional.ofNullable(pauseReason),
                Optional.ofNullable(finishReason),
                Optional.ofNullable(summary),
                CherryPickJson.decodeJournal(journalJson),
                CherryPickJson.decodeItems(itemsJson),
                version,
                createdAt,
                updatedAt,
                Optional.ofNullable(terminalAt));
    }

    public long getVersion()
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

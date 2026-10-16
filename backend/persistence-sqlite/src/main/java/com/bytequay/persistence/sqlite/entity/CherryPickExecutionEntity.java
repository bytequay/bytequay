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
import com.bytequay.core.cherrypick.CherryPickExecution.ActionJournal;
import com.bytequay.core.cherrypick.CherryPickExecution.Delivery;
import com.bytequay.core.cherrypick.CherryPickExecution.FinishReason;
import com.bytequay.core.cherrypick.CherryPickExecution.PauseReason;
import com.bytequay.core.cherrypick.CherryPickExecution.PullRequestDraft;
import com.bytequay.core.cherrypick.CherryPickExecution.Range;
import com.bytequay.core.cherrypick.CherryPickExecution.Status;
import com.bytequay.core.cherrypick.CherryPickExecution.Step;
import com.bytequay.core.cherrypick.CherryPickExecution.Workspace;
import com.bytequay.core.cherrypick.CherryPickExecutionId;
import com.bytequay.core.cherrypick.CherryPickItem;
import com.bytequay.core.domain.RepoRef;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableList;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.bytequay.persistence.sqlite.entity.JsonGuard.CHERRY_PICK;
import static com.google.common.base.Preconditions.checkArgument;

@Entity
@Table(name = "cherry_pick_execution")
public class CherryPickExecutionEntity
        extends AssignedIdEntity<String>
{
    private static final TypeReference<List<CherryPickItem>> ITEMS = new TypeReference<>() {};

    @Id
    @Getter(onMethod_ = @Override)
    private String id;

    @Column(nullable = false, unique = true)
    private String startIdempotencyKey;

    @Column(nullable = false)
    private String workspaceId;

    @Column(nullable = false)
    private String repositoryOwner;

    @Column(nullable = false)
    private String repositoryName;

    @Column(nullable = false)
    private String physicalRepositoryOwner;

    @Column(nullable = false)
    private String physicalRepositoryName;

    @Column(nullable = false)
    private String sourceRemote;

    private String sourceRepositoryOwner;

    private String sourceRepositoryName;

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

    private String model;

    private String effort;

    private String pullRequestTitle;

    @Column(columnDefinition = "TEXT")
    private String pullRequestBody;

    private String pushedRemote;

    private String pushedHead;

    private String pushedSha;

    private Long providerPullRequestId;

    private Integer providerPullRequestNumber;

    private String providerPullRequestUrl;

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
    @Getter
    private long version;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private Instant terminalAt;

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
        workspaceId = workspace.workspaceId();
        repositoryOwner = workspace.repository().owner();
        repositoryName = workspace.repository().repo();
        physicalRepositoryOwner = workspace.physicalRepository().owner();
        physicalRepositoryName = workspace.physicalRepository().repo();
        sourceRemote = workspace.sourceRemote();
        sourceRepositoryOwner = workspace.sourceRepository().map(RepoRef::owner).orElse(null);
        sourceRepositoryName = workspace.sourceRepository().map(RepoRef::repo).orElse(null);
        targetRemote = workspace.targetRemote();
        targetBranch = workspace.targetBranch();
        localTargetBranch = workspace.localTargetBranch();
        workBranch = workspace.workBranch();
        worktree = workspace.worktree().toString();
        laneId = workspace.laneId();
        model = workspace.model().orElse(null);
        effort = workspace.effort().orElse(null);
        pullRequestTitle = execution.pullRequestDraft().map(PullRequestDraft::title).orElse(null);
        pullRequestBody = execution.pullRequestDraft().map(PullRequestDraft::body).orElse(null);
        pushedRemote = execution.delivery().map(Delivery::pushRemote).orElse(null);
        pushedHead = execution.delivery().map(Delivery::head).orElse(null);
        pushedSha = execution.delivery().map(Delivery::sha).orElse(null);
        providerPullRequestId = execution.delivery().flatMap(Delivery::pullRequestId).orElse(null);
        providerPullRequestNumber = execution.delivery().flatMap(Delivery::pullRequestNumber).orElse(null);
        providerPullRequestUrl = execution.delivery().flatMap(Delivery::pullRequestUrl).orElse(null);
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
        journalJson = execution.journal().map(entry -> CHERRY_PICK.write(entry, "cherry-pick action journal")).orElse(null);
        itemsJson = CHERRY_PICK.write(execution.items(), "cherry-pick items");
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
                        workspaceId,
                        RepoRef.of(repositoryOwner, repositoryName),
                        RepoRef.of(physicalRepositoryOwner, physicalRepositoryName),
                        sourceRemote,
                        targetRemote,
                        targetBranch,
                        localTargetBranch,
                        workBranch,
                        Path.of(worktree),
                        laneId,
                        Optional.ofNullable(sourceRepositoryOwner).map(owner -> RepoRef.of(owner, sourceRepositoryName)),
                        Optional.ofNullable(model),
                        Optional.ofNullable(effort)),
                Optional.ofNullable(pullRequestTitle)
                        .map(title -> new PullRequestDraft(title, Optional.ofNullable(pullRequestBody).orElse(""))),
                Optional.ofNullable(pushedRemote).map(remote -> new Delivery(
                        remote,
                        pushedHead,
                        pushedSha,
                        Optional.ofNullable(providerPullRequestId),
                        Optional.ofNullable(providerPullRequestNumber),
                        Optional.ofNullable(providerPullRequestUrl))),
                new Range(sourceBaseSha, sourceTipSha, initialTargetSha),
                currentTargetSha,
                Optional.ofNullable(agentTaskId),
                Optional.ofNullable(currentTurnId),
                status,
                Optional.ofNullable(currentStep),
                Optional.ofNullable(pauseReason),
                Optional.ofNullable(finishReason),
                Optional.ofNullable(summary),
                decodeJournal(journalJson),
                decodeItems(itemsJson),
                version,
                createdAt,
                updatedAt,
                Optional.ofNullable(terminalAt));
    }

    private static List<CherryPickItem> decodeItems(String json)
    {
        return json == null || json.isBlank() ? ImmutableList.of() : ImmutableList.copyOf(CHERRY_PICK.read(json, ITEMS, "cherry-pick items"));
    }

    private static Optional<ActionJournal> decodeJournal(String json)
    {
        return json == null || json.isBlank() ? Optional.empty() : Optional.of(CHERRY_PICK.read(json, ActionJournal.class, "cherry-pick action journal"));
    }
}

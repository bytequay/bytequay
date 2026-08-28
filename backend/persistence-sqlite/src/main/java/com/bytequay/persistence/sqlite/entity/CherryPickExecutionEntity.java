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
import com.bytequay.core.cherrypick.CherryPickExecution.Delivery;
import com.bytequay.core.cherrypick.CherryPickExecution.FinishReason;
import com.bytequay.core.cherrypick.CherryPickExecution.PauseReason;
import com.bytequay.core.cherrypick.CherryPickExecution.PullRequestDraft;
import com.bytequay.core.cherrypick.CherryPickExecution.Range;
import com.bytequay.core.cherrypick.CherryPickExecution.RangeReview;
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
import lombok.Getter;
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
    @Getter(onMethod_ = @Override)
    private String id;

    @Column(nullable = false, unique = true)
    private String startIdempotencyKey;

    private String workspaceId;

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

    @Column(columnDefinition = "TEXT")
    private String rangeReview;

    private Boolean rangeReviewTruncated;

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
        workspaceId = workspace.workspaceId().orElse(null);
        repositoryOwner = workspace.repository().owner();
        repositoryName = workspace.repository().repo();
        sourceRemote = workspace.sourceRemote();
        targetRemote = workspace.targetRemote();
        targetBranch = workspace.targetBranch();
        localTargetBranch = workspace.localTargetBranch();
        workBranch = workspace.workBranch();
        worktree = workspace.worktree().toString();
        laneId = workspace.laneId();
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
        journalJson = CherryPickJson.encodeJournal(execution.journal());
        itemsJson = CherryPickJson.encodeItems(execution.items());
        rangeReview = execution.rangeReview().map(RangeReview::output).orElse(null);
        rangeReviewTruncated = execution.rangeReview().map(RangeReview::truncated).orElse(null);
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
                        Optional.ofNullable(workspaceId),
                        RepoRef.of(repositoryOwner, repositoryName),
                        sourceRemote,
                        targetRemote,
                        targetBranch,
                        localTargetBranch,
                        workBranch,
                        Path.of(worktree),
                        laneId),
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
                CherryPickJson.decodeJournal(journalJson),
                Optional.ofNullable(rangeReview)
                        .map(output -> new RangeReview(output, Boolean.TRUE.equals(rangeReviewTruncated))),
                CherryPickJson.decodeItems(itemsJson),
                version,
                createdAt,
                updatedAt,
                Optional.ofNullable(terminalAt));
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

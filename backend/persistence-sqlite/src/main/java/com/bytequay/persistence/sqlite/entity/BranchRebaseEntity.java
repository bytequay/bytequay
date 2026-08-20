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

import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.rebase.BranchRebase;
import com.bytequay.core.rebase.BranchRebase.ActionJournal;
import com.bytequay.core.rebase.BranchRebase.ActionType;
import com.bytequay.core.rebase.BranchRebase.FinishReason;
import com.bytequay.core.rebase.BranchRebase.PauseReason;
import com.bytequay.core.rebase.BranchRebase.Status;
import com.bytequay.core.rebase.BranchRebase.Step;
import com.bytequay.core.rebase.BranchRebaseId;
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
@Table(name = "branch_rebase")
public class BranchRebaseEntity
        implements Persistable<String>
{
    @Id
    private String id;

    @Column(nullable = false, unique = true)
    private String startIdempotencyKey;

    @Column(nullable = false)
    private String agentTaskId;

    @Column(nullable = false)
    private String repositoryOwner;

    @Column(nullable = false)
    private String repositoryName;

    @Column(nullable = false)
    private String worktree;

    @Column(nullable = false)
    private String workBranch;

    @Column(nullable = false)
    private String baseBranch;

    @Column(nullable = false)
    private String originalWorkTip;

    @Column(nullable = false)
    private String baseSha;

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

    private String conflictCommitSha;

    @Column(nullable = false)
    private int attemptCount;

    private String currentTurnId;

    @Enumerated(EnumType.STRING)
    private ActionType actionType;

    private String actionStoppedCommitSha;

    private String actionExpectedHead;

    private Integer actionAttempt;

    private String actionIdempotencyKey;

    @Convert(converter = InstantToTextConverter.class)
    private Instant actionStartedAt;

    private String resultWorkTip;

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

    public BranchRebaseEntity() {}

    public static BranchRebaseEntity fromDomain(BranchRebase rebase)
    {
        BranchRebaseEntity entity = new BranchRebaseEntity();
        entity.id = rebase.id().toString();
        entity.version = rebase.version();
        entity.updateFrom(rebase);
        return entity;
    }

    public void updateFrom(BranchRebase rebase)
    {
        checkArgument(id.equals(rebase.id().toString()), "rebase identity changed");
        startIdempotencyKey = rebase.startIdempotencyKey();
        agentTaskId = rebase.agentTaskId();
        repositoryOwner = rebase.repository().owner();
        repositoryName = rebase.repository().repo();
        worktree = rebase.worktree().toString();
        workBranch = rebase.workBranch();
        baseBranch = rebase.baseBranch();
        originalWorkTip = rebase.originalWorkTip();
        baseSha = rebase.baseSha();
        status = rebase.status();
        currentStep = rebase.currentStep().orElse(null);
        pauseReason = rebase.pauseReason().orElse(null);
        finishReason = rebase.finishReason().orElse(null);
        summary = rebase.summary().orElse(null);
        conflictCommitSha = rebase.conflictCommitSha().orElse(null);
        attemptCount = rebase.attemptCount();
        currentTurnId = rebase.currentTurnId().orElse(null);
        writeJournal(rebase.journal());
        resultWorkTip = rebase.resultWorkTip().orElse(null);
        createdAt = rebase.createdAt();
        updatedAt = rebase.updatedAt();
        terminalAt = rebase.terminalAt().orElse(null);
    }

    public BranchRebase toRebase()
    {
        return new BranchRebase(
                BranchRebaseId.valueOf(id),
                startIdempotencyKey,
                agentTaskId,
                RepoRef.of(repositoryOwner, repositoryName),
                Path.of(worktree),
                workBranch,
                baseBranch,
                originalWorkTip,
                baseSha,
                status,
                Optional.ofNullable(currentStep),
                Optional.ofNullable(pauseReason),
                Optional.ofNullable(finishReason),
                Optional.ofNullable(summary),
                Optional.ofNullable(conflictCommitSha),
                attemptCount,
                Optional.ofNullable(currentTurnId),
                readJournal(),
                Optional.ofNullable(resultWorkTip),
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

    private void writeJournal(Optional<ActionJournal> journal)
    {
        actionType = journal.map(ActionJournal::type).orElse(null);
        actionStoppedCommitSha = journal.flatMap(ActionJournal::stoppedCommitSha).orElse(null);
        actionExpectedHead = journal.map(ActionJournal::expectedHead).orElse(null);
        actionAttempt = journal.map(ActionJournal::attempt).orElse(null);
        actionIdempotencyKey = journal.map(ActionJournal::idempotencyKey).orElse(null);
        actionStartedAt = journal.map(ActionJournal::startedAt).orElse(null);
    }

    private Optional<ActionJournal> readJournal()
    {
        if (actionType == null) {
            return Optional.empty();
        }
        return Optional.of(new ActionJournal(
                actionType,
                Optional.ofNullable(actionStoppedCommitSha),
                actionExpectedHead,
                actionAttempt,
                actionIdempotencyKey,
                actionStartedAt));
    }

    @PostLoad
    @PostPersist
    private void markPersisted()
    {
        newEntity = false;
    }
}

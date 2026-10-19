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
import com.bytequay.core.ci.CiAutofixAttempt;
import com.bytequay.core.ci.CiAutofixAttemptId;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixRun.Authorization;
import com.bytequay.core.ci.CiAutofixRun.CheckResult;
import com.bytequay.core.ci.CiAutofixRun.FinalFixupCheck;
import com.bytequay.core.ci.CiAutofixRun.FinishReason;
import com.bytequay.core.ci.CiAutofixRun.PauseReason;
import com.bytequay.core.ci.CiAutofixRun.PushJournal;
import com.bytequay.core.ci.CiAutofixRun.RerunCounter;
import com.bytequay.core.ci.CiAutofixRun.RerunJournal;
import com.bytequay.core.ci.CiAutofixRun.Scope;
import com.bytequay.core.ci.CiAutofixRun.Status;
import com.bytequay.core.ci.CiAutofixRun.Warning;
import com.bytequay.core.ci.CiAutofixRunId;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableList;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.bytequay.persistence.sqlite.entity.JsonGuard.CI_AUTOFIX;
import static com.google.common.base.Preconditions.checkArgument;

@Entity
@Table(name = "ci_autofix_run", indexes = @Index(name = "idx_ci_autofix_parent_task", columnList = "parent_task_id"))
public class CiAutofixRunEntity
        extends AssignedIdEntity<String>
{
    private static final TypeReference<List<Warning>> WARNINGS = new TypeReference<>() {};

    @Id
    @Getter(onMethod_ = @Override)
    private String id;

    @Column(nullable = false, unique = true)
    private String startIdempotencyKey;

    @Column(nullable = false)
    private String repositoryOwner;

    @Column(nullable = false)
    private String repositoryName;

    // Same-repository heads use the PR repository; only forks need a separate identity.
    private String forkRepositoryOwner;

    private String forkRepositoryName;

    @Column(nullable = false)
    private int pullRequestNumber;

    @Column(nullable = false)
    private String author;

    @Column(nullable = false)
    private String headRef;

    @Column(nullable = false)
    private String baseRef;

    @Column(nullable = false)
    private String workspaceId;

    @Column(nullable = false)
    private String physicalRepositoryOwner;

    @Column(nullable = false)
    private String physicalRepositoryName;

    @Column(nullable = false)
    private String laneId;

    @Column(unique = true)
    private String agentTaskId;

    private String parentTaskId;

    @Column(nullable = false)
    private String taskStartCommit;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(unique = true)
    private String activeBranchSlot;

    @Enumerated(EnumType.STRING)
    private PauseReason pauseReason;

    @Enumerated(EnumType.STRING)
    private FinishReason finishReason;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(nullable = false)
    private String expectedRemoteHead;

    @Column(nullable = false)
    private String latestObservedBaseSha;

    @Column(nullable = false)
    private Instant authorizedAt;

    @Column(nullable = false)
    private int attemptsUsed;

    private String rerunHeadSha;

    @Column(nullable = false)
    private int rerunsUsed;

    private String finalCheckHeadSha;

    @Enumerated(EnumType.STRING)
    private CheckResult finalCheckResult;

    @Column(columnDefinition = "TEXT")
    private String finalCheckDiagnostics;

    private Instant finalCheckAt;

    private String currentTurnId;

    private String currentRebaseId;

    private String pushAttemptId;

    private String pushExpectedRemoteSha;

    private String pushLocalHeadSha;

    private String pushLocalFingerprint;

    private Instant pushStartedAt;

    private Integer pushRetries;

    private String rerunAttemptId;

    private Long rerunWorkflowRunId;

    private String rerunJournalHeadSha;

    private Integer rerunPreviousAttempt;

    private Instant rerunStartedAt;

    private Integer rerunObservedAttempt;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String warningsJson;

    @Version
    @Getter
    private long version;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private Instant terminalAt;

    public CiAutofixRunEntity() {}

    public static CiAutofixRunEntity fromDomain(CiAutofixRun run)
    {
        CiAutofixRunEntity entity = new CiAutofixRunEntity();
        entity.id = run.id().toString();
        entity.version = run.version();
        entity.updateFrom(run);
        return entity;
    }

    public void updateFrom(CiAutofixRun run)
    {
        checkArgument(id.equals(run.id().toString()), "run identity changed");
        Scope scope = run.scope();
        startIdempotencyKey = run.startIdempotencyKey();
        repositoryOwner = scope.pullRequest().owner();
        repositoryName = scope.pullRequest().repo();
        boolean sameRepository = scope.headRepository().fullName().equalsIgnoreCase(scope.pullRequest().repoFullName());
        forkRepositoryOwner = sameRepository ? null : scope.headRepository().owner();
        forkRepositoryName = sameRepository ? null : scope.headRepository().repo();
        pullRequestNumber = scope.pullRequest().number();
        workspaceId = scope.workspaceId();
        physicalRepositoryOwner = scope.physicalRepository().owner();
        physicalRepositoryName = scope.physicalRepository().repo();
        author = scope.author();
        headRef = scope.headRef();
        baseRef = scope.baseRef();
        laneId = scope.laneId();
        if (!isNew()) {
            checkArgument(Optional.ofNullable(parentTaskId).equals(scope.parentTaskId()), "CI parent task identity changed");
        }
        parentTaskId = scope.parentTaskId().orElse(null);
        agentTaskId = run.agentTaskId().orElse(null);
        status = run.status();
        activeBranchSlot = status == Status.FINISHED ? null : scope.activeBranchSlot();
        pauseReason = run.pauseReason().orElse(null);
        finishReason = run.finishReason().orElse(null);
        summary = run.summary().orElse(null);
        taskStartCommit = run.taskStartCommit();
        expectedRemoteHead = run.expectedRemoteHead();
        latestObservedBaseSha = run.latestObservedBaseSha();
        Authorization authorization = run.authorization();
        authorizedAt = authorization.authorizedAt();
        attemptsUsed = run.attemptsUsed();
        rerunHeadSha = run.rerunCounter().headSha().orElse(null);
        rerunsUsed = run.rerunCounter().used();
        updateFinalCheck(run.finalFixupCheck());
        currentTurnId = run.currentTurnId().orElse(null);
        currentRebaseId = run.currentRebaseId().orElse(null);
        updatePushJournal(run.pushJournal());
        updateRerunJournal(run.rerunJournal());
        warningsJson = CI_AUTOFIX.write(run.warnings(), "CI Autofix warnings");
        createdAt = run.createdAt();
        updatedAt = run.updatedAt();
        terminalAt = run.terminalAt().orElse(null);
    }

    public CiAutofixRun toDomain(List<CiAutofixAttempt> attempts)
    {
        Scope scope = new Scope(
                PullRequestRef.of(repositoryOwner, repositoryName, pullRequestNumber), headRepository(), workspaceId, RepoRef.of(physicalRepositoryOwner, physicalRepositoryName),
                author, headRef, baseRef, laneId, Optional.ofNullable(parentTaskId));
        Authorization authorization = new Authorization(authorizedAt);
        return new CiAutofixRun(
                CiAutofixRunId.valueOf(id), startIdempotencyKey, scope, Optional.ofNullable(agentTaskId), status, Optional.ofNullable(pauseReason),
                Optional.ofNullable(finishReason), Optional.ofNullable(summary), taskStartCommit, expectedRemoteHead, latestObservedBaseSha, authorization, attemptsUsed,
                new RerunCounter(Optional.ofNullable(rerunHeadSha), rerunsUsed), finalCheck(), Optional.ofNullable(currentTurnId), Optional.ofNullable(currentRebaseId),
                pushJournal(), rerunJournal(), warningsJson == null || warningsJson.isBlank() ? ImmutableList.of() : CI_AUTOFIX.read(warningsJson, WARNINGS, "CI Autofix warnings"),
                attempts, version, createdAt, updatedAt, Optional.ofNullable(terminalAt));
    }

    private RepoRef headRepository()
    {
        if (forkRepositoryOwner == null && forkRepositoryName == null) {
            return RepoRef.of(repositoryOwner, repositoryName);
        }
        if (forkRepositoryOwner == null || forkRepositoryName == null) {
            throw new AgentStorageException("Stored CI Autofix head repository is incomplete: " + id);
        }
        return RepoRef.of(forkRepositoryOwner, forkRepositoryName);
    }

    private void updateFinalCheck(Optional<FinalFixupCheck> check)
    {
        finalCheckHeadSha = check.map(FinalFixupCheck::headSha).orElse(null);
        finalCheckResult = check.map(FinalFixupCheck::result).orElse(null);
        finalCheckDiagnostics = check.map(FinalFixupCheck::diagnostics).orElse(null);
        finalCheckAt = check.map(FinalFixupCheck::checkedAt).orElse(null);
    }

    private Optional<FinalFixupCheck> finalCheck()
    {
        return Optional.ofNullable(finalCheckHeadSha)
                .map(head -> new FinalFixupCheck(head, finalCheckResult, finalCheckDiagnostics, finalCheckAt));
    }

    private void updatePushJournal(Optional<PushJournal> journal)
    {
        pushAttemptId = journal.map(value -> value.attemptId().toString()).orElse(null);
        pushExpectedRemoteSha = journal.map(PushJournal::expectedRemoteSha).orElse(null);
        pushLocalHeadSha = journal.map(PushJournal::localHeadSha).orElse(null);
        pushLocalFingerprint = journal.map(PushJournal::localFingerprint).orElse(null);
        pushStartedAt = journal.map(PushJournal::startedAt).orElse(null);
        pushRetries = journal.map(PushJournal::retries).orElse(null);
    }

    private Optional<PushJournal> pushJournal()
    {
        return Optional.ofNullable(pushAttemptId)
                .map(attempt -> new PushJournal(CiAutofixAttemptId.valueOf(attempt), pushExpectedRemoteSha, pushLocalHeadSha, pushLocalFingerprint, pushStartedAt, pushRetries));
    }

    private void updateRerunJournal(Optional<RerunJournal> journal)
    {
        rerunAttemptId = journal.map(value -> value.attemptId().toString()).orElse(null);
        rerunWorkflowRunId = journal.map(RerunJournal::workflowRunId).orElse(null);
        rerunJournalHeadSha = journal.map(RerunJournal::headSha).orElse(null);
        rerunPreviousAttempt = journal.map(RerunJournal::previousAttempt).orElse(null);
        rerunStartedAt = journal.map(RerunJournal::startedAt).orElse(null);
        rerunObservedAttempt = journal.flatMap(RerunJournal::observedAttempt).orElse(null);
    }

    private Optional<RerunJournal> rerunJournal()
    {
        return Optional.ofNullable(rerunAttemptId)
                .map(attempt -> new RerunJournal(
                        CiAutofixAttemptId.valueOf(attempt), rerunWorkflowRunId, rerunJournalHeadSha, rerunPreviousAttempt, rerunStartedAt,
                        Optional.ofNullable(rerunObservedAttempt)));
    }
}

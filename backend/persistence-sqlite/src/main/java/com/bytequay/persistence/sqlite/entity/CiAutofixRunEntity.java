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
import com.bytequay.core.ci.CiAutofixRunId;
import com.bytequay.core.domain.PullRequestRef;
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
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;

@Entity
@Table(name = "ci_autofix_run")
public class CiAutofixRunEntity
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
    private int pullRequestNumber;

    @Column(nullable = false)
    private String author;

    @Column(nullable = false)
    private String headRef;

    @Column(nullable = false)
    private String baseRef;

    @Column(nullable = false)
    private String worktree;

    @Column(nullable = false)
    private String laneId;

    @Column(unique = true)
    private String agentTaskId;

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
    private boolean allowFailedJobReruns;

    @Column(nullable = false)
    private boolean allowHistoryRewrites;

    @Column(nullable = false)
    private boolean allowExactLeasePush;

    @Column(nullable = false)
    private int maxFixAttempts;

    @Column(nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant authorizedAt;

    @Column(nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant authorizationExpiresAt;

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

    @Convert(converter = InstantToTextConverter.class)
    private Instant finalCheckAt;

    private String currentTurnId;

    private String pushAttemptId;

    private String pushExpectedRemoteSha;

    private String pushLocalHeadSha;

    private String pushLocalFingerprint;

    @Convert(converter = InstantToTextConverter.class)
    private Instant pushStartedAt;

    private Integer pushRetries;

    private String rerunAttemptId;

    private Long rerunWorkflowRunId;

    private String rerunJournalHeadSha;

    private Integer rerunPreviousAttempt;

    @Convert(converter = InstantToTextConverter.class)
    private Instant rerunStartedAt;

    private Integer rerunObservedAttempt;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String warningsJson;

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
        pullRequestNumber = scope.pullRequest().number();
        author = scope.author();
        headRef = scope.headRef();
        baseRef = scope.baseRef();
        worktree = scope.worktree().toString();
        laneId = scope.laneId();
        agentTaskId = run.agentTaskId().orElse(null);
        status = run.status();
        activeBranchSlot = status == Status.FINISHED ? null : scope.activeBranchSlot();
        pauseReason = run.pauseReason().orElse(null);
        finishReason = run.finishReason().orElse(null);
        summary = run.summary().orElse(null);
        expectedRemoteHead = run.expectedRemoteHead();
        latestObservedBaseSha = run.latestObservedBaseSha();
        Authorization authorization = run.authorization();
        allowFailedJobReruns = authorization.allowFailedJobReruns();
        allowHistoryRewrites = authorization.allowHistoryRewrites();
        allowExactLeasePush = authorization.allowExactLeasePush();
        maxFixAttempts = authorization.maxFixAttempts();
        authorizedAt = authorization.authorizedAt();
        authorizationExpiresAt = authorization.expiresAt();
        attemptsUsed = run.attemptsUsed();
        rerunHeadSha = run.rerunCounter().headSha().orElse(null);
        rerunsUsed = run.rerunCounter().used();
        updateFinalCheck(run.finalFixupCheck());
        currentTurnId = run.currentTurnId().orElse(null);
        updatePushJournal(run.pushJournal());
        updateRerunJournal(run.rerunJournal());
        warningsJson = CiAutofixJson.encodeWarnings(run.warnings());
        createdAt = run.createdAt();
        updatedAt = run.updatedAt();
        terminalAt = run.terminalAt().orElse(null);
    }

    public CiAutofixRun toDomain(List<CiAutofixAttempt> attempts)
    {
        Scope scope = new Scope(
                PullRequestRef.of(repositoryOwner, repositoryName, pullRequestNumber),
                author,
                headRef,
                baseRef,
                Path.of(worktree),
                laneId);
        Authorization authorization = new Authorization(
                allowFailedJobReruns,
                allowHistoryRewrites,
                allowExactLeasePush,
                maxFixAttempts,
                authorizedAt,
                authorizationExpiresAt);
        return new CiAutofixRun(
                CiAutofixRunId.valueOf(id),
                startIdempotencyKey,
                scope,
                Optional.ofNullable(agentTaskId),
                status,
                Optional.ofNullable(pauseReason),
                Optional.ofNullable(finishReason),
                Optional.ofNullable(summary),
                expectedRemoteHead,
                latestObservedBaseSha,
                authorization,
                attemptsUsed,
                new RerunCounter(Optional.ofNullable(rerunHeadSha), rerunsUsed),
                finalCheck(),
                Optional.ofNullable(currentTurnId),
                pushJournal(),
                rerunJournal(),
                CiAutofixJson.decodeWarnings(warningsJson),
                attempts,
                version,
                createdAt,
                updatedAt,
                Optional.ofNullable(terminalAt));
    }

    public long getVersion()
    {
        return version;
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
                .map(attempt -> new PushJournal(
                        CiAutofixAttemptId.valueOf(attempt),
                        pushExpectedRemoteSha,
                        pushLocalHeadSha,
                        pushLocalFingerprint,
                        pushStartedAt,
                        pushRetries));
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
                        CiAutofixAttemptId.valueOf(attempt),
                        rerunWorkflowRunId,
                        rerunJournalHeadSha,
                        rerunPreviousAttempt,
                        rerunStartedAt,
                        Optional.ofNullable(rerunObservedAttempt)));
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

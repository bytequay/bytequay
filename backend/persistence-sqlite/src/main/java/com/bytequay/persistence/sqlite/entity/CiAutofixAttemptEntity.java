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
import com.bytequay.core.ci.CiAutofixAttempt.CiResult;
import com.bytequay.core.ci.CiAutofixAttempt.GitResult;
import com.bytequay.core.ci.CiAutofixAttemptId;
import com.bytequay.core.ci.CiAutofixRunId;
import com.bytequay.persistence.sqlite.converter.InstantToTextConverter;
import com.bytequay.persistence.sqlite.converter.StringListConverter;
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
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;

@Entity
@Table(name = "ci_autofix_attempt")
public class CiAutofixAttemptEntity
        implements Persistable<String>
{
    @Id
    private String id;

    @Column(name = "run_id", nullable = false)
    private String runId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "run_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "ci_autofix_attempt_run_fk"))
    private CiAutofixRunEntity run;

    @Column(nullable = false)
    private int attemptNumber;

    @Column(nullable = false, unique = true)
    private String runAttemptSlot;

    @Column(nullable = false)
    private String inputSha;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String failuresJson;

    private String agentTurnId;

    @Column(columnDefinition = "TEXT")
    private String untrustedAgentNote;

    private String inspectedLocalHead;

    private String localFingerprint;

    @Column(nullable = false, columnDefinition = "TEXT")
    @Convert(converter = StringListConverter.class)
    private List<String> changedPaths;

    @Column(columnDefinition = "TEXT")
    @Convert(converter = StringListConverter.class)
    private List<String> commitSubjects;

    private String expectedRemoteSha;

    private String pushedSha;

    @Convert(converter = InstantToTextConverter.class)
    private Instant pushedAt;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private CiResult observedCiResult;

    private String timelineEventKey;

    @Column(nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant createdAt;

    @Column(nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant updatedAt;

    @Transient
    private boolean newEntity = true;

    public CiAutofixAttemptEntity() {}

    public static CiAutofixAttemptEntity fromDomain(CiAutofixAttempt attempt)
    {
        CiAutofixAttemptEntity entity = new CiAutofixAttemptEntity();
        entity.id = attempt.id().toString();
        entity.runId = attempt.runId().toString();
        entity.attemptNumber = attempt.number();
        entity.runAttemptSlot = attempt.runId() + ":" + attempt.number();
        entity.updateFrom(attempt);
        return entity;
    }

    public void updateFrom(CiAutofixAttempt attempt)
    {
        checkArgument(id.equals(attempt.id().toString())
                        && runId.equals(attempt.runId().toString())
                        && attemptNumber == attempt.number(),
                "attempt identity changed");
        inputSha = attempt.inputSha();
        failuresJson = CiAutofixJson.encodeFailures(attempt.failures());
        agentTurnId = attempt.agentTurnId().orElse(null);
        untrustedAgentNote = attempt.untrustedAgentNote().orElse(null);
        inspectedLocalHead = attempt.gitResult().map(GitResult::localHead).orElse(null);
        localFingerprint = attempt.gitResult().map(GitResult::fingerprint).orElse(null);
        changedPaths = attempt.gitResult().map(GitResult::changedPaths).orElse(List.of());
        commitSubjects = attempt.gitResult().map(GitResult::commitSubjects).orElse(List.of());
        expectedRemoteSha = attempt.expectedRemoteSha().orElse(null);
        pushedSha = attempt.pushedSha().orElse(null);
        pushedAt = attempt.pushedAt().orElse(null);
        observedCiResult = attempt.observedCiResult();
        timelineEventKey = attempt.timelineEventKey().orElse(null);
        createdAt = attempt.createdAt();
        updatedAt = attempt.updatedAt();
    }

    public CiAutofixAttempt toDomain()
    {
        Optional<GitResult> gitResult = Optional.ofNullable(inspectedLocalHead)
                .map(head -> new GitResult(
                        head,
                        localFingerprint,
                        changedPaths,
                        commitSubjects == null ? List.of() : commitSubjects));
        return new CiAutofixAttempt(
                CiAutofixAttemptId.valueOf(id),
                CiAutofixRunId.valueOf(runId),
                attemptNumber,
                inputSha,
                CiAutofixJson.decodeFailures(failuresJson),
                Optional.ofNullable(agentTurnId),
                Optional.ofNullable(untrustedAgentNote),
                gitResult,
                Optional.ofNullable(expectedRemoteSha),
                Optional.ofNullable(pushedSha),
                Optional.ofNullable(pushedAt),
                observedCiResult,
                Optional.ofNullable(timelineEventKey),
                createdAt,
                updatedAt);
    }

    public String getRunId()
    {
        return runId;
    }

    public int getAttemptNumber()
    {
        return attemptNumber;
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

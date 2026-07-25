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
package com.bytequay.app.repository.sqlite;

import com.bytequay.app.domain.ValidationClaim;
import com.bytequay.app.repository.ValidationPassStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Component
class SqliteValidationPassStore
        implements ValidationPassStore
{
    private final ValidationPassJpaRepository rows;

    SqliteValidationPassStore(ValidationPassJpaRepository rows)
    {
        this.rows = requireNonNull(rows, "rows is null");
    }

    @Override
    @Transactional
    public long startPass(String taskId, Instant startedAt)
    {
        ValidationPassEntity e = new ValidationPassEntity();
        e.setTaskId(taskId);
        e.setStartedAtMs(startedAt.toEpochMilli());
        return rows.save(e).getId();
    }

    @Override
    @Transactional
    public void finishPass(long id, Instant endedAt, boolean passed, int fixRounds, String failuresJson)
    {
        rows.findById(id).ifPresent(e -> {
            e.setEndedAtMs(endedAt.toEpochMilli());
            e.setPassed(passed);
            e.setFixRounds(fixRounds);
            e.setFailuresJson(failuresJson);
            rows.save(e);
        });
    }

    @Override
    @Transactional
    public Optional<Long> insertClaim(
            String claimKey, String taskId, String context, String roundId,
            String codeFingerprint, Long throughSequence, String rootSetDigest, Instant startedAt)
    {
        // Same-key inserts are serialized by the task command stripe (the
        // key embeds the task id), so select-then-insert cannot race; the
        // unique index stays as the invariant's backstop.
        if (rows.findByClaimKey(claimKey).isPresent()) {
            return Optional.empty();
        }
        ValidationPassEntity e = new ValidationPassEntity();
        e.setTaskId(taskId);
        e.setStartedAtMs(startedAt.toEpochMilli());
        e.setClaimKey(claimKey);
        e.setContext(context);
        e.setRoundId(roundId);
        e.setCodeFingerprint(codeFingerprint);
        e.setThroughSequence(throughSequence);
        e.setRootSetDigest(rootSetDigest);
        return Optional.of(rows.saveAndFlush(e).getId());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ValidationClaim> findByClaimKey(String claimKey)
    {
        return rows.findByClaimKey(claimKey).map(SqliteValidationPassStore::toDomain);
    }

    @Override
    @Transactional
    public boolean acquireOwner(
            String claimKey, String ownerId, String executorIdentity, Instant leaseUntil, Instant now)
    {
        return rows.acquireOwner(
                claimKey, ownerId, executorIdentity, leaseUntil.toEpochMilli(), now.toEpochMilli()) == 1;
    }

    @Override
    @Transactional
    public boolean renewLease(String claimKey, String ownerId, Instant leaseUntil, Instant heartbeatAt)
    {
        return rows.renewLease(
                claimKey, ownerId, leaseUntil.toEpochMilli(), heartbeatAt.toEpochMilli()) == 1;
    }

    @Override
    @Transactional
    public boolean completeOwned(
            String claimKey, String ownerId, String codeFingerprint,
            Instant endedAt, boolean passed, String failuresJson)
    {
        return rows.completeOwned(
                claimKey, ownerId, codeFingerprint, endedAt.toEpochMilli(), passed, failuresJson) == 1;
    }

    @Override
    @Transactional
    public boolean requestCancel(String claimKey, Instant requestedAt, Instant deadline)
    {
        return rows.requestCancel(claimKey, requestedAt.toEpochMilli(), deadline.toEpochMilli()) == 1;
    }

    @Override
    @Transactional
    public boolean markSuperseded(String claimKey, Instant at)
    {
        return rows.markSuperseded(claimKey, at.toEpochMilli()) == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ValidationClaim> findResumableStarted(Instant now)
    {
        return rows.findResumableStarted(now.toEpochMilli()).stream()
                .map(SqliteValidationPassStore::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ValidationClaim> findOpenByTask(String taskId)
    {
        return rows.findOpenByTask(taskId).stream()
                .map(SqliteValidationPassStore::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PendingValidationCancel> findCancelPending()
    {
        return rows.findCancelPending().stream()
                .map(e -> new PendingValidationCancel(
                        e.getClaimKey(),
                        e.getTaskId(),
                        e.getExecutorIdentity(),
                        e.getLeaseUntilMs() == null ? null : Instant.ofEpochMilli(e.getLeaseUntilMs()),
                        e.getCancelDeadlineAtMs() == null
                                ? null
                                : Instant.ofEpochMilli(e.getCancelDeadlineAtMs())))
                .toList();
    }

    @Override
    @Transactional
    public void incrementCancelAttempts(String claimKey)
    {
        rows.incrementCancelAttempts(claimKey);
    }

    private static ValidationClaim toDomain(ValidationPassEntity e)
    {
        return new ValidationClaim(
                e.getId(),
                e.getClaimKey(),
                e.getTaskId(),
                e.getContext(),
                e.getRoundId(),
                e.getCodeFingerprint(),
                e.getThroughSequence(),
                e.getRootSetDigest(),
                Instant.ofEpochMilli(e.getStartedAtMs()),
                fromEpoch(e.getEndedAtMs()),
                e.getPassed(),
                e.getFailuresJson(),
                fromEpoch(e.getCancelRequestedAtMs()),
                fromEpoch(e.getSupersededAtMs()),
                e.getOwnerId(),
                fromEpoch(e.getLeaseUntilMs()));
    }

    private static Instant fromEpoch(Long ms)
    {
        return ms == null ? null : Instant.ofEpochMilli(ms);
    }
}

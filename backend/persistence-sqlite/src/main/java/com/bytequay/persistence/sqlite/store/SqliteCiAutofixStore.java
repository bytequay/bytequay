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
package com.bytequay.persistence.sqlite.store;

import com.bytequay.core.ci.CiAutofixAttempt;
import com.bytequay.core.ci.CiAutofixConflictException;
import com.bytequay.core.ci.CiAutofixNotFoundException;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixRun.Status;
import com.bytequay.core.ci.CiAutofixRunId;
import com.bytequay.core.ci.CiAutofixStorageException;
import com.bytequay.core.ci.CiAutofixStore;
import com.bytequay.persistence.sqlite.entity.CiAutofixAttemptEntity;
import com.bytequay.persistence.sqlite.entity.CiAutofixRunEntity;
import com.bytequay.persistence.sqlite.repository.CiAutofixAttemptJpaRepository;
import com.bytequay.persistence.sqlite.repository.CiAutofixRunJpaRepository;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteCiAutofixStore
        implements CiAutofixStore
{
    private static final List<Status> NON_FINISHED = ImmutableList.of(Status.ACTIVE, Status.PAUSED);

    private final CiAutofixRunJpaRepository runRepository;
    private final CiAutofixAttemptJpaRepository attemptRepository;

    public SqliteCiAutofixStore(
            CiAutofixRunJpaRepository runRepository,
            CiAutofixAttemptJpaRepository attemptRepository)
    {
        this.runRepository = requireNonNull(runRepository, "runRepository is null");
        this.attemptRepository = requireNonNull(attemptRepository, "attemptRepository is null");
    }

    @Override
    @Transactional
    public void insert(CiAutofixRun run)
    {
        requireNonNull(run, "run is null");
        if (run.version() != 0) {
            throw new IllegalArgumentException("new run version must be zero");
        }
        if (runRepository.existsById(run.id().toString())) {
            throw new CiAutofixConflictException("CI Autofix run already exists: " + run.id());
        }
        try {
            runRepository.saveAndFlush(CiAutofixRunEntity.fromDomain(run));
            attemptRepository.saveAllAndFlush(run.attempts().stream()
                    .map(CiAutofixAttemptEntity::fromDomain)
                    .collect(toImmutableList()));
        }
        catch (DataAccessException e) {
            throw mapWriteFailure("CI Autofix run conflicts with durable state: " + run.id(), e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CiAutofixRun> find(CiAutofixRunId runId)
    {
        requireNonNull(runId, "runId is null");
        try {
            return runRepository.findById(runId.toString()).map(this::toProjection);
        }
        catch (DataAccessException e) {
            throw new CiAutofixStorageException("Cannot read CI Autofix run " + runId, e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CiAutofixRun> findByIdempotencyKey(String key)
    {
        requireNonNull(key, "key is null");
        if (key.isBlank() || key.length() > 200) {
            throw new IllegalArgumentException("key must contain between 1 and 200 characters");
        }
        try {
            return runRepository.findByStartIdempotencyKey(key).map(this::toProjection);
        }
        catch (DataAccessException e) {
            throw new CiAutofixStorageException("Cannot read CI Autofix idempotency key", e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<CiAutofixRun> listRecent(int limit)
    {
        checkLimit(limit);
        try {
            return runRepository.findAllByOrderByUpdatedAtDesc(PageRequest.of(0, limit)).stream()
                    .map(this::toProjection)
                    .collect(toImmutableList());
        }
        catch (DataAccessException e) {
            throw new CiAutofixStorageException("Cannot list recent CI Autofix runs", e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<CiAutofixRun> listNonFinished(int limit)
    {
        checkLimit(limit);
        try {
            return runRepository.findByStatusInOrderByUpdatedAtAsc(
                            NON_FINISHED,
                            PageRequest.of(0, limit)).stream()
                    .map(this::toProjection)
                    .collect(toImmutableList());
        }
        catch (DataAccessException e) {
            throw new CiAutofixStorageException("Cannot list non-finished CI Autofix runs", e);
        }
    }

    @Override
    @Transactional
    public CiAutofixRun update(CiAutofixRun run)
    {
        requireNonNull(run, "run is null");
        CiAutofixRunEntity entity = runRepository.findById(run.id().toString())
                .orElseThrow(() -> new CiAutofixNotFoundException(run.id()));
        if (entity.getVersion() != run.version()) {
            throw new CiAutofixConflictException("CI Autofix run version changed: " + run.id());
        }

        List<CiAutofixAttemptEntity> existing = attemptRepository.findByRunIdOrderByAttemptNumberAsc(
                run.id().toString());
        Set<String> requestedIds = run.attempts().stream()
                .map(attempt -> attempt.id().toString())
                .collect(toImmutableSet());
        if (existing.stream().anyMatch(attempt -> !requestedIds.contains(attempt.getId()))) {
            throw new CiAutofixConflictException("Existing CI Autofix attempts cannot be removed");
        }
        Map<String, CiAutofixAttemptEntity> existingById = existing.stream()
                .collect(ImmutableMap.toImmutableMap(CiAutofixAttemptEntity::getId, value -> value));
        List<CiAutofixAttemptEntity> updatedAttempts = run.attempts().stream()
                .map(attempt -> updateAttempt(existingById, attempt))
                .collect(toImmutableList());

        entity.updateFrom(run);
        try {
            runRepository.saveAndFlush(entity);
            attemptRepository.saveAllAndFlush(updatedAttempts);
            return toProjection(entity);
        }
        catch (OptimisticLockingFailureException e) {
            throw new CiAutofixConflictException("CI Autofix run version changed: " + run.id(), e);
        }
        catch (DataAccessException e) {
            throw mapWriteFailure("CI Autofix run conflicts with durable state: " + run.id(), e);
        }
    }

    private CiAutofixAttemptEntity updateAttempt(
            Map<String, CiAutofixAttemptEntity> existingById,
            CiAutofixAttempt attempt)
    {
        CiAutofixAttemptEntity entity = existingById.get(attempt.id().toString());
        if (entity == null) {
            return CiAutofixAttemptEntity.fromDomain(attempt);
        }
        if (!entity.getRunId().equals(attempt.runId().toString())
                || entity.getAttemptNumber() != attempt.number()) {
            throw new CiAutofixConflictException("CI Autofix attempt identity changed: " + attempt.id());
        }
        entity.updateFrom(attempt);
        return entity;
    }

    private CiAutofixRun toProjection(CiAutofixRunEntity entity)
    {
        try {
            List<CiAutofixAttempt> attempts = attemptRepository.findByRunIdOrderByAttemptNumberAsc(entity.getId())
                    .stream()
                    .map(CiAutofixAttemptEntity::toDomain)
                    .collect(toImmutableList());
            return entity.toDomain(attempts);
        }
        catch (CiAutofixStorageException e) {
            throw e;
        }
        catch (RuntimeException e) {
            throw new CiAutofixStorageException("Cannot reconstruct CI Autofix run " + entity.getId(), e);
        }
    }

    private static RuntimeException mapWriteFailure(String message, DataAccessException failure)
    {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains("SQLITE_CONSTRAINT")) {
                return new CiAutofixConflictException(message, failure);
            }
        }
        return new CiAutofixStorageException(message, failure);
    }

    private static void checkLimit(int limit)
    {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }
}

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

import com.bytequay.core.cherrypick.CherryPickConflictException;
import com.bytequay.core.cherrypick.CherryPickExecution;
import com.bytequay.core.cherrypick.CherryPickExecution.Status;
import com.bytequay.core.cherrypick.CherryPickExecutionId;
import com.bytequay.core.cherrypick.CherryPickExecutionStore;
import com.bytequay.core.cherrypick.CherryPickNotFoundException;
import com.bytequay.core.cherrypick.CherryPickStorageException;
import com.bytequay.persistence.sqlite.entity.CherryPickExecutionEntity;
import com.bytequay.persistence.sqlite.repository.CherryPickExecutionJpaRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteCherryPickExecutionStore
        implements CherryPickExecutionStore
{
    private final CherryPickExecutionJpaRepository repository;

    public SqliteCherryPickExecutionStore(CherryPickExecutionJpaRepository repository)
    {
        this.repository = requireNonNull(repository, "repository is null");
    }

    @Override
    @Transactional
    public CherryPickExecution createOrGet(CherryPickExecution execution)
    {
        requireNonNull(execution, "execution is null");
        checkArgument(execution.version() == 0, "new execution version must be zero");
        Optional<CherryPickExecutionEntity> existing =
                repository.findByStartIdempotencyKey(execution.startIdempotencyKey());
        if (existing.isPresent()) {
            return existing.orElseThrow().toExecution();
        }
        if (repository.existsById(execution.id().toString())) {
            throw new CherryPickConflictException("Cherry-pick execution already exists: " + execution.id());
        }
        try {
            return repository.saveAndFlush(CherryPickExecutionEntity.fromDomain(execution)).toExecution();
        }
        catch (DataAccessException e) {
            // A concurrent caller may have inserted the same key between the read and this write.
            return repository.findByStartIdempotencyKey(execution.startIdempotencyKey())
                    .map(CherryPickExecutionEntity::toExecution)
                    .orElseThrow(() -> new CherryPickConflictException(
                            "Cherry-pick execution conflicts with durable state: " + execution.id(), e));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CherryPickExecution> findByIdempotencyKey(String idempotencyKey)
    {
        requireNonNull(idempotencyKey, "idempotencyKey is null");
        try {
            return repository.findByStartIdempotencyKey(idempotencyKey)
                    .map(CherryPickExecutionEntity::toExecution);
        }
        catch (DataAccessException e) {
            throw new CherryPickStorageException("Cannot read cherry-pick idempotency key", e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CherryPickExecution> find(CherryPickExecutionId executionId)
    {
        requireNonNull(executionId, "executionId is null");
        try {
            return repository.findById(executionId.toString()).map(CherryPickExecutionEntity::toExecution);
        }
        catch (DataAccessException e) {
            throw new CherryPickStorageException("Cannot read cherry-pick execution " + executionId, e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<CherryPickExecution> listRecent(int limit)
    {
        checkLimit(limit);
        try {
            return repository.findAllByOrderByUpdatedAtDesc(PageRequest.of(0, limit)).stream()
                    .map(CherryPickExecutionEntity::toExecution)
                    .collect(toImmutableList());
        }
        catch (DataAccessException e) {
            throw new CherryPickStorageException("Cannot list recent cherry-pick executions", e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<CherryPickExecution> listRunning(int limit)
    {
        checkLimit(limit);
        try {
            return repository.findByStatusOrderByUpdatedAtAsc(Status.RUNNING, PageRequest.of(0, limit)).stream()
                    .map(CherryPickExecutionEntity::toExecution)
                    .collect(toImmutableList());
        }
        catch (DataAccessException e) {
            throw new CherryPickStorageException("Cannot list running cherry-pick executions", e);
        }
    }

    @Override
    @Transactional
    public CherryPickExecution save(CherryPickExecution execution)
    {
        requireNonNull(execution, "execution is null");
        CherryPickExecutionEntity entity = repository.findById(execution.id().toString())
                .orElseThrow(() -> new CherryPickNotFoundException(execution.id()));
        if (entity.getVersion() != execution.version()) {
            throw new CherryPickConflictException("Cherry-pick execution version changed: " + execution.id());
        }
        entity.updateFrom(execution);
        try {
            return repository.saveAndFlush(entity).toExecution();
        }
        catch (OptimisticLockingFailureException e) {
            throw new CherryPickConflictException("Cherry-pick execution version changed: " + execution.id(), e);
        }
        catch (DataAccessException e) {
            throw new CherryPickStorageException("Cannot save cherry-pick execution " + execution.id(), e);
        }
    }

    private static void checkLimit(int limit)
    {
        checkArgument(limit >= 1 && limit <= MAX_LIST_LIMIT, "limit must be between 1 and " + MAX_LIST_LIMIT);
    }
}

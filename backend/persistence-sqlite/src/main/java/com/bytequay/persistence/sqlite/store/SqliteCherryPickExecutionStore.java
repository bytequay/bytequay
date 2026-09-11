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

import com.bytequay.core.cherrypick.CherryPickExecution;
import com.bytequay.core.cherrypick.CherryPickExecution.Status;
import com.bytequay.core.cherrypick.CherryPickExecutionId;
import com.bytequay.core.cherrypick.CherryPickExecutionStore;
import com.bytequay.core.cherrypick.CherryPickNotFoundException;
import com.bytequay.core.cherrypick.CherryPickStorageException;
import com.bytequay.persistence.sqlite.entity.CherryPickExecutionEntity;
import com.bytequay.persistence.sqlite.repository.CherryPickExecutionJpaRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static com.bytequay.persistence.sqlite.store.SqliteStoreExceptions.read;
import static com.bytequay.persistence.sqlite.store.StoreWrites.CHERRY_PICK;
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
        return StoreWrites.createOrGet(
                repository,
                execution.id().toString(),
                "Cherry-pick execution",
                () -> repository.findByStartIdempotencyKey(execution.startIdempotencyKey()),
                () -> CherryPickExecutionEntity.fromDomain(execution),
                CHERRY_PICK)
                .toExecution();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CherryPickExecution> findByIdempotencyKey(String idempotencyKey)
    {
        requireNonNull(idempotencyKey, "idempotencyKey is null");
        return read("Cannot read cherry-pick idempotency key",
                () -> repository.findByStartIdempotencyKey(idempotencyKey).map(CherryPickExecutionEntity::toExecution),
                CherryPickStorageException::new);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CherryPickExecution> find(CherryPickExecutionId executionId)
    {
        requireNonNull(executionId, "executionId is null");
        return read("Cannot read cherry-pick execution " + executionId,
                () -> repository.findById(executionId.toString()).map(CherryPickExecutionEntity::toExecution),
                CherryPickStorageException::new);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CherryPickExecution> findByAgentTaskId(String taskId)
    {
        requireNonNull(taskId, "taskId is null");
        return read("Cannot read cherry-pick execution for agent task " + taskId,
                () -> repository.findByAgentTaskId(taskId).map(CherryPickExecutionEntity::toExecution),
                CherryPickStorageException::new);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CherryPickExecution> listRecent(int limit)
    {
        checkLimit(limit);
        return read("Cannot list recent cherry-pick executions",
                () -> repository.findAllByOrderByUpdatedAtDesc(PageRequest.of(0, limit)).stream()
                        .map(CherryPickExecutionEntity::toExecution)
                        .collect(toImmutableList()),
                CherryPickStorageException::new);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CherryPickExecution> listRunning(int limit)
    {
        checkLimit(limit);
        return read("Cannot list running cherry-pick executions",
                () -> repository.findByStatusOrderByUpdatedAtAsc(Status.RUNNING, PageRequest.of(0, limit)).stream()
                        .map(CherryPickExecutionEntity::toExecution)
                        .collect(toImmutableList()),
                CherryPickStorageException::new);
    }

    @Override
    @Transactional
    public CherryPickExecution save(CherryPickExecution execution)
    {
        requireNonNull(execution, "execution is null");
        return StoreWrites.update(
                repository, execution.id().toString(), "Cherry-pick execution", execution.version(), CherryPickExecutionEntity::getVersion, entity -> entity.updateFrom(execution),
                () -> new CherryPickNotFoundException(execution.id()), CHERRY_PICK)
                .toExecution();
    }

    private static void checkLimit(int limit)
    {
        checkArgument(limit >= 1 && limit <= MAX_LIST_LIMIT, "limit must be between 1 and " + MAX_LIST_LIMIT);
    }
}

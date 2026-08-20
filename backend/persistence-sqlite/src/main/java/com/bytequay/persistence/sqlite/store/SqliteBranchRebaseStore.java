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

import com.bytequay.core.rebase.BranchRebase;
import com.bytequay.core.rebase.BranchRebase.Status;
import com.bytequay.core.rebase.BranchRebaseException;
import com.bytequay.core.rebase.BranchRebaseId;
import com.bytequay.core.rebase.BranchRebaseNotFoundException;
import com.bytequay.core.rebase.BranchRebaseStorageException;
import com.bytequay.core.rebase.BranchRebaseStore;
import com.bytequay.persistence.sqlite.entity.BranchRebaseEntity;
import com.bytequay.persistence.sqlite.repository.BranchRebaseJpaRepository;
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
public class SqliteBranchRebaseStore
        implements BranchRebaseStore
{
    private final BranchRebaseJpaRepository repository;

    public SqliteBranchRebaseStore(BranchRebaseJpaRepository repository)
    {
        this.repository = requireNonNull(repository, "repository is null");
    }

    @Override
    @Transactional
    public BranchRebase createOrGet(BranchRebase rebase)
    {
        requireNonNull(rebase, "rebase is null");
        checkArgument(rebase.version() == 0, "new rebase version must be zero");
        Optional<BranchRebaseEntity> existing =
                repository.findByStartIdempotencyKey(rebase.startIdempotencyKey());
        if (existing.isPresent()) {
            return existing.orElseThrow().toRebase();
        }
        if (repository.existsById(rebase.id().toString())) {
            throw new BranchRebaseException("Branch rebase already exists: " + rebase.id());
        }
        try {
            return repository.saveAndFlush(BranchRebaseEntity.fromDomain(rebase)).toRebase();
        }
        catch (DataAccessException e) {
            return repository.findByStartIdempotencyKey(rebase.startIdempotencyKey())
                    .map(BranchRebaseEntity::toRebase)
                    .orElseThrow(() -> new BranchRebaseException(
                            "Branch rebase conflicts with durable state: " + rebase.id(), e));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BranchRebase> findByIdempotencyKey(String idempotencyKey)
    {
        requireNonNull(idempotencyKey, "idempotencyKey is null");
        try {
            return repository.findByStartIdempotencyKey(idempotencyKey)
                    .map(BranchRebaseEntity::toRebase);
        }
        catch (DataAccessException e) {
            throw new BranchRebaseStorageException("Cannot read branch rebase idempotency key", e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BranchRebase> find(BranchRebaseId rebaseId)
    {
        requireNonNull(rebaseId, "rebaseId is null");
        try {
            return repository.findById(rebaseId.toString()).map(BranchRebaseEntity::toRebase);
        }
        catch (DataAccessException e) {
            throw new BranchRebaseStorageException("Cannot read branch rebase " + rebaseId, e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<BranchRebase> listRunning(int limit)
    {
        checkArgument(limit >= 1 && limit <= MAX_LIST_LIMIT,
                "limit must be between 1 and " + MAX_LIST_LIMIT);
        try {
            return repository.findByStatusOrderByUpdatedAtAsc(
                            Status.RUNNING, PageRequest.of(0, limit)).stream()
                    .map(BranchRebaseEntity::toRebase)
                    .collect(toImmutableList());
        }
        catch (DataAccessException e) {
            throw new BranchRebaseStorageException("Cannot list running branch rebases", e);
        }
    }

    @Override
    @Transactional
    public BranchRebase save(BranchRebase rebase)
    {
        requireNonNull(rebase, "rebase is null");
        BranchRebaseEntity entity = repository.findById(rebase.id().toString())
                .orElseThrow(() -> new BranchRebaseNotFoundException(rebase.id()));
        if (entity.getVersion() != rebase.version()) {
            throw new BranchRebaseException("Branch rebase version changed: " + rebase.id());
        }
        entity.updateFrom(rebase);
        try {
            return repository.saveAndFlush(entity).toRebase();
        }
        catch (OptimisticLockingFailureException e) {
            throw new BranchRebaseException("Branch rebase version changed: " + rebase.id(), e);
        }
        catch (DataAccessException e) {
            throw new BranchRebaseStorageException("Cannot save branch rebase " + rebase.id(), e);
        }
    }
}

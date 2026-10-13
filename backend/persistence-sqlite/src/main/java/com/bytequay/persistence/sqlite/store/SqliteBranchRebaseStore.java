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
import com.bytequay.core.rebase.BranchRebaseId;
import com.bytequay.core.rebase.BranchRebaseNotFoundException;
import com.bytequay.core.rebase.BranchRebaseStore;
import com.bytequay.core.store.StorageException;
import com.bytequay.persistence.sqlite.entity.BranchRebaseEntity;
import com.bytequay.persistence.sqlite.repository.BranchRebaseJpaRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static com.bytequay.persistence.sqlite.store.SqliteStoreExceptions.read;
import static com.bytequay.persistence.sqlite.store.StoreWrites.BRANCH_REBASE;
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
        return StoreWrites.createOrGet(
                repository,
                rebase.id().toString(),
                "Branch rebase",
                () -> repository.findByStartIdempotencyKey(rebase.startIdempotencyKey()),
                () -> BranchRebaseEntity.fromDomain(rebase),
                BRANCH_REBASE)
                .toRebase();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BranchRebase> findByIdempotencyKey(String idempotencyKey)
    {
        requireNonNull(idempotencyKey, "idempotencyKey is null");
        return read("Cannot read branch rebase idempotency key",
                () -> repository.findByStartIdempotencyKey(idempotencyKey).map(BranchRebaseEntity::toRebase),
                StorageException::new);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BranchRebase> find(BranchRebaseId rebaseId)
    {
        requireNonNull(rebaseId, "rebaseId is null");
        return read("Cannot read branch rebase " + rebaseId, () -> repository.findById(rebaseId.toString()).map(BranchRebaseEntity::toRebase), StorageException::new);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BranchRebase> listRunning(int limit)
    {
        checkArgument(limit >= 1 && limit <= MAX_LIST_LIMIT, "limit must be between 1 and " + MAX_LIST_LIMIT);
        return read("Cannot list running branch rebases",
                () -> repository.findByStatusOrderByUpdatedAtAsc(Status.RUNNING, PageRequest.of(0, limit)).stream()
                        .map(BranchRebaseEntity::toRebase)
                        .collect(toImmutableList()),
                StorageException::new);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BranchRebase> listIndependentlyRunning(int limit)
    {
        checkArgument(limit >= 1 && limit <= MAX_LIST_LIMIT, "limit must be between 1 and " + MAX_LIST_LIMIT);
        return read("Cannot list independently scheduled branch rebases",
                () -> repository.findIndependentlyRunning(Status.RUNNING, PageRequest.of(0, limit)).stream()
                        .map(BranchRebaseEntity::toRebase)
                        .collect(toImmutableList()),
                StorageException::new);
    }

    @Override
    @Transactional
    public BranchRebase save(BranchRebase rebase)
    {
        requireNonNull(rebase, "rebase is null");
        return StoreWrites.update(
                repository, rebase.id().toString(), "Branch rebase", rebase.version(), BranchRebaseEntity::getVersion, entity -> entity.updateFrom(rebase),
                () -> new BranchRebaseNotFoundException(rebase.id()), BRANCH_REBASE)
                .toRebase();
    }
}

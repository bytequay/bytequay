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

import com.bytequay.core.backport.BackportChain;
import com.bytequay.core.backport.BackportChainStore;
import com.bytequay.core.store.StorageException;
import com.bytequay.persistence.sqlite.entity.BackportChainEntity;
import com.bytequay.persistence.sqlite.repository.BackportChainJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteBackportChainStore
        implements BackportChainStore
{
    private static final StoreWrites.Failures FAILURES = new StoreWrites.Failures(
            IllegalStateException::new, IllegalStateException::new, StorageException::new, StorageException::new);
    private final BackportChainJpaRepository repository;

    public SqliteBackportChainStore(BackportChainJpaRepository repository)
    {
        this.repository = requireNonNull(repository, "repository is null");
    }

    @Override
    @Transactional
    public BackportChain createOrGet(BackportChain chain)
    {
        checkArgument(chain.version() == 0, "New chain version must be zero");
        return StoreWrites.createOrGet(repository, chain.id(), "Backport chain", () -> repository.findById(chain.id()),
                () -> BackportChainEntity.fromDomain(chain), FAILURES).toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BackportChain> find(String id)
    {
        return repository.findById(id).map(BackportChainEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BackportChain> list()
    {
        // ponytail: local chain history is small; add indexed workspace/state queries if it grows.
        return repository.findAll().stream().map(BackportChainEntity::toDomain).collect(toImmutableList());
    }

    @Override
    @Transactional
    public BackportChain save(BackportChain chain)
    {
        return StoreWrites.update(repository, chain.id(), "Backport chain", chain.version(), BackportChainEntity::getVersion,
                entity -> entity.updateFrom(chain), () -> new IllegalArgumentException("Backport chain is unavailable"), FAILURES).toDomain();
    }
}

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

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.core.cherrypick.CherryPickConflictException;
import com.bytequay.core.rebase.BranchRebaseException;
import com.bytequay.core.review.ReviewConflictException;
import com.bytequay.core.store.StorageException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Locale;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

import static java.util.Objects.requireNonNull;

/**
 * The insert and versioned-update steps every store repeats, reported through the owning module's
 * exception family so callers keep catching their own conflict and storage types.
 */
final class StoreWrites
{
    static final Failures AGENT = new Failures(AgentConflictException::new, AgentConflictException::new, SqliteStoreExceptions::mapConstraint, AgentStorageException::new);
    static final Failures REVIEW = new Failures(ReviewConflictException::new, ReviewConflictException::new, ReviewConflictException::new, ReviewConflictException::new);
    static final Failures BRANCH_REBASE = new Failures(BranchRebaseException::new, BranchRebaseException::new, BranchRebaseException::new, StorageException::new);
    static final Failures CHERRY_PICK = new Failures(CherryPickConflictException::new, CherryPickConflictException::new, CherryPickConflictException::new, StorageException::new);

    private StoreWrites() {}

    /** Inserts a row whose id the caller assigned, refusing to overwrite an existing one. */
    static <E> E insert(JpaRepository<E, String> repository, String id, String label, Supplier<E> entity, Failures failures)
    {
        if (repository.existsById(id)) {
            throw failures.conflict().apply(label + " already exists: " + id);
        }
        try {
            return repository.saveAndFlush(entity.get());
        }
        catch (DataAccessException e) {
            throw failures.rejectedInsert().apply(label + " already exists: " + id, e);
        }
    }

    /** Inserts the row unless one already carries the same idempotency key, in which case that row wins. */
    static <E> E createOrGet(JpaRepository<E, String> repository, String id, String label, Supplier<Optional<E>> existing, Supplier<E> entity, Failures failures)
    {
        Optional<E> found = existing.get();
        if (found.isPresent()) {
            return found.orElseThrow();
        }
        if (repository.existsById(id)) {
            throw failures.conflict().apply(label + " already exists: " + id);
        }
        try {
            return repository.saveAndFlush(entity.get());
        }
        catch (DataAccessException e) {
            // A concurrent caller may have inserted the same key between the read and this write.
            return existing.get().orElseThrow(() -> failures.causedConflict().apply(label + " conflicts with durable state: " + id, e));
        }
    }

    /** Applies an update only when the caller saw the stored version, so a concurrent writer gets a conflict rather than a lost update. */
    static <E> E update(
            JpaRepository<E, String> repository,
            String id,
            String label,
            long expectedVersion,
            ToLongFunction<E> storedVersion,
            Consumer<E> apply,
            Supplier<RuntimeException> absent,
            Failures failures)
    {
        E entity = repository.findById(id).orElseThrow(absent);
        if (storedVersion.applyAsLong(entity) != expectedVersion) {
            throw failures.conflict().apply(label + " version changed: " + id);
        }
        apply.accept(entity);
        try {
            return repository.saveAndFlush(entity);
        }
        catch (OptimisticLockingFailureException e) {
            throw failures.causedConflict().apply(label + " version changed: " + id, e);
        }
        catch (DataAccessException e) {
            throw failures.rejectedUpdate().apply("Cannot update " + label.toLowerCase(Locale.ROOT) + " " + id, e);
        }
    }

    /** How one module reports a duplicate row, a stale version, and a write the database rejected. */
    record Failures(
            Function<String, RuntimeException> conflict,
            BiFunction<String, Throwable, RuntimeException> causedConflict,
            BiFunction<String, DataAccessException, RuntimeException> rejectedInsert,
            BiFunction<String, DataAccessException, RuntimeException> rejectedUpdate)
    {
        Failures
        {
            requireNonNull(conflict, "conflict is null");
            requireNonNull(causedConflict, "causedConflict is null");
            requireNonNull(rejectedInsert, "rejectedInsert is null");
            requireNonNull(rejectedUpdate, "rejectedUpdate is null");
        }
    }
}

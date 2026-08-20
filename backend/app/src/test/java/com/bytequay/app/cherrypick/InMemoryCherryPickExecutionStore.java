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
package com.bytequay.app.cherrypick;

import com.bytequay.core.cherrypick.CherryPickConflictException;
import com.bytequay.core.cherrypick.CherryPickExecution;
import com.bytequay.core.cherrypick.CherryPickExecution.Status;
import com.bytequay.core.cherrypick.CherryPickExecutionId;
import com.bytequay.core.cherrypick.CherryPickExecutionStore;
import com.bytequay.core.cherrypick.CherryPickNotFoundException;
import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;

/** In-memory store so service tests exercise transitions, not SQLite. */
final class InMemoryCherryPickExecutionStore
        implements CherryPickExecutionStore
{
    private final Map<String, CherryPickExecution> executions = new LinkedHashMap<>();
    private final List<String> saves = new ArrayList<>();

    @Override
    public CherryPickExecution createOrGet(CherryPickExecution execution)
    {
        Optional<CherryPickExecution> existing = findByIdempotencyKey(execution.startIdempotencyKey());
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }
        executions.put(execution.id().toString(), execution);
        return execution;
    }

    @Override
    public Optional<CherryPickExecution> findByIdempotencyKey(String idempotencyKey)
    {
        return executions.values().stream()
                .filter(execution -> execution.startIdempotencyKey().equals(idempotencyKey))
                .findFirst();
    }

    @Override
    public Optional<CherryPickExecution> find(CherryPickExecutionId executionId)
    {
        return Optional.ofNullable(executions.get(executionId.toString()));
    }

    @Override
    public List<CherryPickExecution> listRecent(int limit)
    {
        return executions.values().stream()
                .sorted(Comparator.comparing(CherryPickExecution::updatedAt).reversed())
                .limit(limit)
                .collect(toImmutableList());
    }

    @Override
    public List<CherryPickExecution> listRunning(int limit)
    {
        return executions.values().stream()
                .filter(execution -> execution.status() == Status.RUNNING)
                .limit(limit)
                .collect(toImmutableList());
    }

    @Override
    public CherryPickExecution save(CherryPickExecution execution)
    {
        CherryPickExecution current = executions.get(execution.id().toString());
        if (current == null) {
            throw new CherryPickNotFoundException(execution.id());
        }
        if (current.version() != execution.version()) {
            throw new CherryPickConflictException("Cherry-pick execution version changed: " + execution.id());
        }
        CherryPickExecution stored = execution.withVersion(execution.version() + 1);
        executions.put(stored.id().toString(), stored);
        saves.add(stored.id().toString());
        return stored;
    }

    /** Every save this store has seen, so a test can count optimistic writes. */
    List<String> saves()
    {
        return ImmutableList.copyOf(saves);
    }
}

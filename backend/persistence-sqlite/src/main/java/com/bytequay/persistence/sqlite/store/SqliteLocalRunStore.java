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

import com.bytequay.core.review.LocalRun;
import com.bytequay.core.review.LocalRunStore;
import com.bytequay.core.review.ReviewNotFoundException;
import com.bytequay.persistence.sqlite.entity.LocalRunEntity;
import com.bytequay.persistence.sqlite.entity.LocalRunOutputEntity;
import com.bytequay.persistence.sqlite.repository.LocalRunJpaRepository;
import com.bytequay.persistence.sqlite.repository.LocalRunOutputJpaRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static com.bytequay.persistence.sqlite.store.StoreWrites.REVIEW;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteLocalRunStore
        implements LocalRunStore
{
    private final LocalRunJpaRepository repository;
    private final LocalRunOutputJpaRepository outputs;

    public SqliteLocalRunStore(LocalRunJpaRepository repository, LocalRunOutputJpaRepository outputs)
    {
        this.repository = requireNonNull(repository, "repository is null");
        this.outputs = requireNonNull(outputs, "outputs is null");
    }

    @Override
    @Transactional
    public LocalRun insert(LocalRun run)
    {
        requireNonNull(run, "run is null");
        return StoreWrites.insert(repository, run.id(), "Local run", () -> LocalRunEntity.fromDomain(run), REVIEW).toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LocalRun> find(String runId)
    {
        return repository.findById(runId).map(LocalRunEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LocalRun> listByTask(String taskId, int limit)
    {
        checkArgument(limit > 0, "limit is not positive");
        return repository.findByTaskIdOrderByOrdinalDesc(taskId, PageRequest.of(0, limit)).stream().map(LocalRunEntity::toDomain).collect(toImmutableList());
    }

    @Override
    @Transactional
    public LocalRun update(LocalRun run)
    {
        requireNonNull(run, "run is null");
        return StoreWrites.update(repository, run.id(), "Local run", run.version(), LocalRunEntity::getVersion, entity -> entity.updateFrom(run),
                () -> new ReviewNotFoundException("Local run not found: " + run.id()), REVIEW)
                .toDomain();
    }

    @Override
    @Transactional
    public LocalRun update(LocalRun run, String output)
    {
        requireNonNull(output, "output is null");
        LocalRun updated = update(run);
        LocalRunOutputEntity stored = outputs.findById(run.id()).orElseGet(() -> new LocalRunOutputEntity(run.id(), output));
        stored.setOutput(output);
        outputs.saveAndFlush(stored);
        return updated;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> findOutput(String runId)
    {
        return outputs.findById(runId).map(LocalRunOutputEntity::getOutput);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LocalRun> listUnsettled()
    {
        return repository.findByStatusOrTreeUnverifiedTrue(LocalRun.Status.RUNNING).stream().map(LocalRunEntity::toDomain).collect(toImmutableList());
    }
}

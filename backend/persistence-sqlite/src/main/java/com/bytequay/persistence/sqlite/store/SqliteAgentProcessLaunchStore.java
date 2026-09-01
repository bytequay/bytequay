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
import com.bytequay.agent.core.AgentProcessLaunch;
import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.agent.core.LaunchIntentState;
import com.bytequay.agent.core.store.AgentProcessLaunchStore;
import com.bytequay.persistence.sqlite.entity.AgentProcessLaunchEntity;
import com.bytequay.persistence.sqlite.repository.AgentProcessLaunchJpaRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteAgentProcessLaunchStore
        implements AgentProcessLaunchStore
{
    private final AgentProcessLaunchJpaRepository repository;

    public SqliteAgentProcessLaunchStore(AgentProcessLaunchJpaRepository repository)
    {
        this.repository = requireNonNull(repository, "repository is null");
    }

    @Override
    @Transactional
    public void insert(AgentProcessLaunch launch)
    {
        requireNonNull(launch, "launch is null");
        if (repository.existsById(launch.launchId())) {
            throw new AgentConflictException("Process launch already exists: " + launch.launchId());
        }
        try {
            repository.saveAndFlush(AgentProcessLaunchEntity.fromDomain(launch));
        }
        catch (DataAccessException e) {
            throw new AgentStorageException("Unable to store the process launch", e);
        }
    }

    @Override
    @Transactional
    public void updateState(String launchId, LaunchIntentState state, Instant at)
    {
        requireNonNull(state, "state is null");
        requireNonNull(at, "at is null");
        AgentProcessLaunchEntity entity = require(launchId);
        if (entity.state() == LaunchIntentState.TERMINAL) {
            throw new AgentConflictException("Process launch is already terminal: " + launchId);
        }
        entity.advance(state, at);
        repository.saveAndFlush(entity);
    }

    @Override
    @Transactional
    public void recordTerminal(String launchId, boolean verifiedStopped, Instant at)
    {
        requireNonNull(at, "at is null");
        AgentProcessLaunchEntity entity = require(launchId);
        entity.terminal(verifiedStopped, at);
        repository.saveAndFlush(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentProcessLaunch> find(String launchId)
    {
        requireNonNull(launchId, "launchId is null");
        return repository.findById(launchId).map(AgentProcessLaunchEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentProcessLaunch> list()
    {
        return repository.findAllByOrderByIdAsc().stream()
                .map(AgentProcessLaunchEntity::toDomain)
                .collect(toImmutableList());
    }

    @Override
    @Transactional
    public void delete(String launchId)
    {
        requireNonNull(launchId, "launchId is null");
        repository.deleteById(launchId);
    }

    private AgentProcessLaunchEntity require(String launchId)
    {
        requireNonNull(launchId, "launchId is null");
        return repository.findById(launchId)
                .orElseThrow(() -> new AgentConflictException("Unknown process launch: " + launchId));
    }
}

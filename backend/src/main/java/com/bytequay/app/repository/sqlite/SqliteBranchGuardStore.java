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
package com.bytequay.app.repository.sqlite;

import com.bytequay.app.domain.BranchGuard;
import com.bytequay.app.domain.BranchGuard.Health;
import com.bytequay.app.repository.BranchGuardStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Component
class SqliteBranchGuardStore
        implements BranchGuardStore
{
    private static final Logger log = LoggerFactory.getLogger(SqliteBranchGuardStore.class);

    private final BranchGuardJpaRepository guards;
    private final ObjectMapper mapper;

    SqliteBranchGuardStore(BranchGuardJpaRepository guards, ObjectMapper mapper)
    {
        this.guards = requireNonNull(guards, "guards is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    @Override
    @Transactional
    public BranchGuard save(BranchGuard guard)
    {
        BranchGuardEntity e = new BranchGuardEntity();
        e.setTaskId(guard.taskId());
        e.setEnabled(guard.enabled());
        e.setSchedule(guard.schedule());
        e.setState(guard.state());
        e.setHealthJson(toJson(guard.health()));
        e.setLastRunId(guard.lastRunId());
        e.setLastCheckedAtMs(epochOrNull(guard.lastCheckedAt()));
        return toDomain(guards.save(e));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BranchGuard> findByTask(String taskId)
    {
        return guards.findById(taskId).map(this::toDomain);
    }

    private BranchGuard toDomain(BranchGuardEntity e)
    {
        return new BranchGuard(
                e.getTaskId(),
                e.isEnabled(),
                e.getSchedule(),
                e.getState(),
                fromJson(e.getHealthJson()),
                e.getLastRunId(),
                instantOrNull(e.getLastCheckedAtMs()));
    }

    private static Long epochOrNull(Instant instant)
    {
        return instant == null ? null : instant.toEpochMilli();
    }

    private static Instant instantOrNull(Long epochMs)
    {
        return epochMs == null ? null : Instant.ofEpochMilli(epochMs);
    }

    private String toJson(Health health)
    {
        if (health == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(health);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("branch guard health JSON serialise failed", e);
        }
    }

    private Health fromJson(String json)
    {
        if (json == null || json.isBlank()) {
            return Health.UNKNOWN;
        }
        try {
            return mapper.readValue(json, Health.class);
        }
        catch (JsonProcessingException e) {
            log.warn("unparseable branch_guard health json: {}", e.getMessage());
            return Health.UNKNOWN;
        }
    }
}

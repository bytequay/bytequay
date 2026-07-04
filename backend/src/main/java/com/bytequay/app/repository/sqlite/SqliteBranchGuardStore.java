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
import com.bytequay.app.repository.BranchGuardStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Component
class SqliteBranchGuardStore
        implements BranchGuardStore
{
    private final BranchGuardJpaRepository guards;

    SqliteBranchGuardStore(BranchGuardJpaRepository guards)
    {
        this.guards = requireNonNull(guards, "guards is null");
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
        e.setLastRunId(guard.lastRunId());
        e.setLastCheckedAtMs(epochOrNull(guard.lastCheckedAt()));
        return toDomain(guards.save(e));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BranchGuard> findByTask(String taskId)
    {
        return guards.findById(taskId).map(SqliteBranchGuardStore::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BranchGuard> findEnabled()
    {
        return guards.findByEnabledTrue().stream()
                .map(SqliteBranchGuardStore::toDomain)
                .toList();
    }

    private static BranchGuard toDomain(BranchGuardEntity e)
    {
        return new BranchGuard(
                e.getTaskId(),
                e.isEnabled(),
                e.getSchedule(),
                e.getState(),
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
}

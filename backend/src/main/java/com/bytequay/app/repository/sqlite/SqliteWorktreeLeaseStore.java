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

import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.WorktreeLease;
import com.bytequay.app.repository.WorktreeLeaseStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Component
class SqliteWorktreeLeaseStore
        implements WorktreeLeaseStore
{
    private final WorktreeLeaseJpaRepository leases;

    SqliteWorktreeLeaseStore(WorktreeLeaseJpaRepository leases)
    {
        this.leases = requireNonNull(leases, "leases is null");
    }

    @Override
    @Transactional
    public void save(WorktreeLease lease)
    {
        WorktreeLeaseEntity entity = leases.findById(lease.worktreePath())
                .orElseGet(WorktreeLeaseEntity::new);
        entity.setWorktreePath(lease.worktreePath());
        entity.setTaskId(lease.taskId());
        entity.setAgentKind(lease.agentKind().name());
        entity.setHolderPid(lease.holderPid());
        entity.setAcquiredAtMs(lease.acquiredAt().toEpochMilli());
        entity.setExpiresAtMs(lease.expiresAt() == null ? null : lease.expiresAt().toEpochMilli());
        leases.save(entity);
    }

    @Override
    public Optional<WorktreeLease> findByWorktreePath(String worktreePath)
    {
        return leases.findById(worktreePath).map(SqliteWorktreeLeaseStore::toDomain);
    }

    @Override
    public List<WorktreeLease> listForTask(String taskId)
    {
        return leases.findByTaskId(taskId).stream()
                .map(SqliteWorktreeLeaseStore::toDomain)
                .toList();
    }

    @Override
    public List<WorktreeLease> listAll()
    {
        return leases.findAll().stream()
                .map(SqliteWorktreeLeaseStore::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public void releaseByWorktreePath(String worktreePath)
    {
        if (leases.existsById(worktreePath)) {
            leases.deleteById(worktreePath);
        }
    }

    private static WorktreeLease toDomain(WorktreeLeaseEntity e)
    {
        return new WorktreeLease(
                e.getWorktreePath(),
                e.getTaskId(),
                ThreadKind.valueOf(e.getAgentKind()),
                e.getHolderPid(),
                Instant.ofEpochMilli(e.getAcquiredAtMs()),
                e.getExpiresAtMs() == null ? null : Instant.ofEpochMilli(e.getExpiresAtMs()));
    }
}

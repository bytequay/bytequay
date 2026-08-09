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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Component
public class SqliteWorktreeLeaseStore
{
    private static final String LEGACY = "LEGACY";

    private final WorktreeLeaseJpaRepository leases;

    SqliteWorktreeLeaseStore(WorktreeLeaseJpaRepository leases)
    {
        this.leases = requireNonNull(leases, "leases is null");
    }

    @Transactional
    public void save(WorktreeLease lease)
    {
        WorktreeLeaseEntity entity = leases.findById(lease.worktreePath())
                .orElseGet(WorktreeLeaseEntity::new);
        if (!LEGACY.equals(entity.getWorkflowVersion())) {
            throw new DataIntegrityViolationException(
                    "V2 worktree lease is owned by the V2 writer boundary");
        }
        entity.setWorktreePath(lease.worktreePath());
        entity.setTaskId(lease.taskId());
        entity.setAgentKind(lease.agentKind().name());
        entity.setHolderPid(lease.holderPid());
        entity.setAcquiredAtMs(lease.acquiredAt().toEpochMilli());
        entity.setExpiresAtMs(Timestamps.epochMilli(lease.expiresAt()));
        entity.setWorkflowVersion(LEGACY);
        leases.save(entity);
    }

    public Optional<WorktreeLease> findByWorktreePath(String worktreePath)
    {
        return leases.findById(worktreePath).map(SqliteWorktreeLeaseStore::toDomain);
    }

    public List<WorktreeLease> listForTask(String taskId)
    {
        return leases.findByTaskIdAndWorkflowVersion(taskId, LEGACY).stream()
                .map(SqliteWorktreeLeaseStore::toDomain)
                .toList();
    }

    public List<WorktreeLease> listAll()
    {
        return leases.findByWorkflowVersion(LEGACY).stream()
                .map(SqliteWorktreeLeaseStore::toDomain)
                .toList();
    }

    @Transactional
    public void releaseByWorktreePath(String worktreePath)
    {
        leases.deleteByWorktreePathAndWorkflowVersion(worktreePath, LEGACY);
    }

    private static WorktreeLease toDomain(WorktreeLeaseEntity e)
    {
        return new WorktreeLease(
                e.getWorktreePath(),
                e.getTaskId(),
                ThreadKind.valueOf(e.getAgentKind()),
                e.getHolderPid(),
                Instant.ofEpochMilli(e.getAcquiredAtMs()),
                Timestamps.instant(e.getExpiresAtMs()));
    }
}

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
package com.bytequay.app.repository;

import com.bytequay.app.domain.WorktreeLease;

import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary for {@code worktree_leases}. Acquire/release
 * is intended to be wrapped by a service that races against the row
 * uniqueness constraint on {@code worktree_path}; this interface is
 * just the CRUD shape.
 */
public interface WorktreeLeaseStore
{
    /** Insert or update a lease keyed by worktree_path. Inserts will
     *  conflict on the PK when the worktree is already leased — the
     *  caller decides whether to fail or wait. */
    void save(WorktreeLease lease);

    Optional<WorktreeLease> findByWorktreePath(String worktreePath);

    /** Active leases for a task (typically 0 or 1; the data model
     *  allows multiple in case of bugs the reaper hasn't cleaned up). */
    List<WorktreeLease> listForTask(String taskId);

    /** All currently-held leases. The reaper walks this and releases
     *  any whose holder process is gone. */
    List<WorktreeLease> listAll();

    /** Release the lease on a worktree. No-op when nothing is held. */
    void releaseByWorktreePath(String worktreePath);
}

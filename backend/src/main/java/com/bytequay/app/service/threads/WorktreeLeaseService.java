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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.WorktreeLease;
import com.bytequay.app.repository.WorktreeLeaseStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Acquire/release/inspect for the per-worktree lease. The lease is
 * the actual lock in the new model — not the thread — so two agents
 * never write the same worktree simultaneously. See "Automation and
 * system-initiated tasks" in the model doc for the rationale.
 *
 * <p>This is the bounded service surface; the CLI agent's spawn path
 * and the eventual automation coordinator both consume it through
 * {@link #tryAcquire} / {@link #release}. Stale-lease reaping (when
 * a holder process is gone but the row is still there) lives in a
 * follow-up next to the runtime that needs it.
 */
@Service
public class WorktreeLeaseService
{
    private static final Logger log = LoggerFactory.getLogger(WorktreeLeaseService.class);

    private final WorktreeLeaseStore store;

    public WorktreeLeaseService(WorktreeLeaseStore store)
    {
        this.store = requireNonNull(store, "store is null");
    }

    /**
     * Atomically acquire the lease on {@code worktreePath} for the
     * given task + agent. Returns the new lease on success or empty
     * when someone else already holds it — the PK constraint on
     * {@code worktree_path} is what makes "atomic" cheap here.
     *
     * @param expiresAt soft expiry; {@code null} means "release
     *                  explicitly", which is the default for the
     *                  CLI-agent spawn path.
     */
    public Optional<WorktreeLease> tryAcquire(
            String worktreePath,
            String taskId,
            ThreadKind agentKind,
            Integer holderPid,
            Instant expiresAt)
    {
        requireNonNull(worktreePath, "worktreePath is null");
        requireNonNull(taskId, "taskId is null");
        requireNonNull(agentKind, "agentKind is null");
        if (store.findByWorktreePath(worktreePath).isPresent()) {
            return Optional.empty();
        }
        WorktreeLease lease = new WorktreeLease(
                worktreePath, taskId, agentKind, holderPid, Instant.now(), expiresAt);
        try {
            store.save(lease);
        }
        catch (DataIntegrityViolationException e) {
            // Race: another caller acquired between our find and our
            // save. Treat as a clean acquisition failure.
            log.debug("Lease race on {} — another holder acquired first", worktreePath);
            return Optional.empty();
        }
        return Optional.of(lease);
    }

    /**
     * Convenience for the common case of "acquire with no expiry,
     * known caller pid". Used by the CLI-agent spawn path.
     */
    public Optional<WorktreeLease> tryAcquire(
            String worktreePath, String taskId, ThreadKind agentKind, Integer holderPid)
    {
        return tryAcquire(worktreePath, taskId, agentKind, holderPid, /* expiresAt */ null);
    }

    /**
     * Atomically acquire a lease, reclaiming any prior row whose
     * holder pid no longer maps to a live process. Used by the
     * registry-owned session attachment path so a crashed-backend
     * restart picks up its old worktrees immediately rather than
     * waiting up to a minute for the reaper sweep.
     *
     * <p>Reclamation only fires when the existing row has a non-null
     * holder pid AND that pid is gone. A pid-less LOGIC_LOOP-held
     * lease or a live-pid lease is treated as a genuine conflict and
     * returns empty so the caller can surface a 409.
     */
    public Optional<WorktreeLease> tryAcquireOrReclaim(
            String worktreePath, String taskId, ThreadKind agentKind, Integer holderPid)
    {
        requireNonNull(worktreePath, "worktreePath is null");
        Optional<WorktreeLease> existing = store.findByWorktreePath(worktreePath);
        if (existing.isPresent() && isHolderDead(existing.get())) {
            log.info("Reclaiming stale lease on {} (prior taskId {}, pid {})",
                    worktreePath, existing.get().taskId(), existing.get().holderPid());
            store.releaseByWorktreePath(worktreePath);
        }
        return tryAcquire(worktreePath, taskId, agentKind, holderPid, /* expiresAt */ null);
    }

    /** True when the lease names a holder pid that no longer maps to
     *  a live OS process. Pid-less leases (LOGIC_LOOP) are NOT
     *  considered dead — those are alive-by-default and the age rule
     *  is the only way to reap them. */
    static boolean isHolderDead(WorktreeLease lease)
    {
        Integer pid = lease.holderPid();
        if (pid == null) {
            return false;
        }
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        return handle.isEmpty() || !handle.get().isAlive();
    }

    /** Release the lease — typically in a finally block on agent exit.
     *  No-op when the worktree wasn't held, so the call site doesn't
     *  need to know whether acquire actually succeeded. */
    public void release(String worktreePath)
    {
        if (worktreePath == null) {
            return;
        }
        store.releaseByWorktreePath(worktreePath);
    }

    public Optional<WorktreeLease> find(String worktreePath)
    {
        return store.findByWorktreePath(worktreePath);
    }

    /** Convenience predicate — same as {@link #find} but bool-shaped. */
    public boolean isHeld(String worktreePath)
    {
        return find(worktreePath).isPresent();
    }

    /** All currently-recorded leases. The automation coordinator's
     *  reaper sweeps this list and releases any whose holder process
     *  no longer exists. */
    public List<WorktreeLease> listAll()
    {
        return store.listAll();
    }
}

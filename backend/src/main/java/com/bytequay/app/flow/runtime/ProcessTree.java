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
package com.bytequay.app.flow.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * Kills a subprocess and everything it started, and proves they are gone.
 *
 * <p>This exists because {@code Process.destroyForcibly()} is not enough to hand
 * a worktree to a successor writer. It signals the direct child — which for a CLI
 * agent is a shell — and orphans every grandchild under it. A leftover child that
 * still holds the worktree while the next agent starts writing corrupts the
 * branch silently, and that is the one failure in this flow with no recovery.
 *
 * <p>The runtime's writer invariant is that no successor may be admitted until
 * the previous agent's thread ended and its capability was revoked. Making that
 * true for an out-of-process body means the owning thread must not return until
 * it has buried the tree, so this produces a receipt rather than a best effort.
 *
 * <p><b>PID reuse is the trap.</b> A recorded pid can be recycled by the OS onto
 * an unrelated process, and a naive liveness check would then report the dead
 * agent as alive forever. So each pid is recorded with its start time, and a pid
 * that is alive with a <em>different</em> start time counts as gone: it is not
 * the process we recorded.
 *
 * <p><b>Known hole, deliberately not closed.</b> The tree is captured, not
 * frozen — a process that reparents itself between capture and signal escapes.
 * Closing that needs real process-group semantics, and Java exposes no way to
 * put a child in a new process group ({@code ProcessBuilder} cannot, and
 * {@code setsid} is absent on macOS), so it would take a native
 * {@code setsid()} call through the FFM API. See
 * {@code docs/cherry/cherry-pick-flow.md}. Until then this is the honest bound:
 * every process the tree held at capture is proven dead.
 */
final class ProcessTree
{
    /** How long the tree gets to exit on its own before it is killed. */
    private static final Duration DEFAULT_GRACE = Duration.ofSeconds(5);
    /** How long a killed tree gets to actually disappear before we give up. */
    private static final Duration REAP_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration POLL = Duration.ofMillis(50);

    private ProcessTree() {}

    /**
     * One process the tree held, pinned to the incarnation we saw.
     *
     * @param startedAt null when the OS would not report it; such a pid can only
     *         be judged by liveness, which is weaker, and is recorded as such
     *         rather than silently treated as exact.
     */
    record Member(long pid, Instant startedAt)
    {
        boolean isTheSameProcessAs(ProcessHandle handle)
        {
            if (handle.pid() != pid) {
                return false;
            }
            Instant now = handle.info().startInstant().orElse(null);
            // Without a start time on either side there is nothing to compare,
            // so the pid alone has to stand — the weaker reading, and the one
            // that errs towards "still alive" rather than towards a false
            // receipt.
            return startedAt == null || now == null || startedAt.equals(now);
        }
    }

    /** The tree as it stood when it was captured. */
    record Snapshot(long rootPid, List<Member> members)
    {
        Snapshot
        {
            members = List.copyOf(requireNonNull(members, "members is null"));
        }

        int size()
        {
            return members.size();
        }
    }

    /**
     * Everything {@code process} holds right now, deepest first.
     *
     * <p>Order matters for signalling: killing a parent before its children can
     * leave the children reparented to init and running, and some parents respawn
     * a child on losing one. Leaves first avoids both.
     */
    static Snapshot capture(Process process)
    {
        requireNonNull(process, "process is null");
        return capture(process.toHandle());
    }

    static Snapshot capture(ProcessHandle root)
    {
        requireNonNull(root, "root is null");
        List<Member> deepestFirst = new ArrayList<>();
        // descendants() is documented as a best-effort snapshot; taking it once
        // and recording what it held is the point — the receipt is about these
        // processes, not about whatever the tree becomes later.
        List<ProcessHandle> descendants = root.descendants()
                .collect(Collectors.toList());
        for (int index = descendants.size() - 1; index >= 0; index--) {
            deepestFirst.add(member(descendants.get(index)));
        }
        deepestFirst.add(member(root));
        return new Snapshot(root.pid(), List.copyOf(deepestFirst));
    }

    /**
     * Asks the tree to exit, then kills what is left, then proves it is gone.
     *
     * @return the receipt: empty when every recorded process is gone, or the
     *         first member still alive when it is not. A non-empty result must
     *         never be treated as success — it means a successor writer cannot
     *         be admitted.
     */
    static Optional<Member> burySync(Snapshot snapshot)
    {
        return burySync(snapshot, DEFAULT_GRACE, REAP_TIMEOUT);
    }

    static Optional<Member> burySync(
            Snapshot snapshot, Duration grace, Duration reapTimeout)
    {
        requireNonNull(snapshot, "snapshot is null");
        signal(snapshot, false);
        if (waitForDeath(snapshot, grace).isEmpty()) {
            return Optional.empty();
        }
        // Still there after asking politely. From here it is not a shutdown, it
        // is a kill, and the receipt is what decides whether we may continue.
        signal(snapshot, true);
        return waitForDeath(snapshot, reapTimeout);
    }

    /** Whether every recorded process is gone. The proof, on its own. */
    static Optional<Member> firstAlive(Snapshot snapshot)
    {
        requireNonNull(snapshot, "snapshot is null");
        for (Member member : snapshot.members()) {
            ProcessHandle handle = ProcessHandle.of(member.pid()).orElse(null);
            if (handle == null || !handle.isAlive()) {
                continue;
            }
            // Alive, but is it ours? A recycled pid is somebody else's process
            // and says nothing about the one we recorded.
            if (member.isTheSameProcessAs(handle)) {
                return Optional.of(member);
            }
        }
        return Optional.empty();
    }

    /**
     * A one-line proof for the attempt's {@code stop_proof_ref}, so the reason a
     * successor was admitted is recoverable later rather than implied.
     */
    static String proof(Snapshot snapshot, Optional<Member> alive)
    {
        return alive.map(member -> "process-tree:root=" + snapshot.rootPid()
                        + ";members=" + snapshot.size()
                        + ";ALIVE=" + member.pid())
                .orElseGet(() -> "process-tree:root=" + snapshot.rootPid()
                        + ";members=" + snapshot.size() + ";all-dead");
    }

    private static void signal(Snapshot snapshot, boolean force)
    {
        // Leaves first, and re-derived children too: a process that forked after
        // capture is still this tree's problem even though the receipt cannot
        // speak for it.
        Set<Long> signalled = ConcurrentHashMap.newKeySet();
        for (Member member : snapshot.members()) {
            ProcessHandle.of(member.pid())
                    .filter(handle -> member.isTheSameProcessAs(handle))
                    .ifPresent(handle -> {
                        handle.descendants().forEach(
                                child -> signalOne(child, force, signalled));
                        signalOne(handle, force, signalled);
                    });
        }
    }

    private static void signalOne(
            ProcessHandle handle, boolean force, Set<Long> signalled)
    {
        if (!signalled.add(handle.pid())) {
            return;
        }
        if (force) {
            handle.destroyForcibly();
        }
        else {
            handle.destroy();
        }
    }

    private static Optional<Member> waitForDeath(
            Snapshot snapshot, Duration timeout)
    {
        long deadline = System.nanoTime() + timeout.toNanos();
        Optional<Member> alive = firstAlive(snapshot);
        while (alive.isPresent() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(POLL);
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                // An interrupt is not a death proof. Report what is still alive
                // so the caller refuses the successor rather than assuming.
                return alive;
            }
            alive = firstAlive(snapshot);
        }
        return alive;
    }

    private static Member member(ProcessHandle handle)
    {
        return new Member(
                handle.pid(), handle.info().startInstant().orElse(null));
    }
}

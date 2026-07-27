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

import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnStatus;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary for scheduler turns.
 */
public interface ThreadTurnStore
{
    /** Insert or update a scheduler turn by primary key. The liveness
     *  flag and kick key are entity-managed — set only by
     *  {@link #insertTurn} at creation and never rewritten here. */
    void saveTurn(ThreadTurn turn);

    /** Insert a freshly enqueued turn with its explicit liveness flag
     *  and optional idempotency kick key. A duplicate kick returns the
     *  already-durable turn id, so callers never publish or point at the
     *  losing candidate. */
    default InsertResult insertTurn(ThreadTurn turn, boolean affectsTaskLiveness, String kickKey)
    {
        if (kickKey != null) {
            Optional<String> existing = findTurnIdByKickKey(kickKey);
            if (existing.isPresent()) {
                return new InsertResult(existing.orElseThrow(), false);
            }
        }
        saveTurn(turn);
        return new InsertResult(turn.id(), true);
    }

    /** Guarded lifecycle write. Implementations must update only the
     *  lifecycle columns and only while the persisted status still equals
     *  {@code expected}. */
    default boolean updateStatusIf(
            String turnId,
            ThreadTurnStatus expected,
            ThreadTurnStatus to,
            Instant updatedAt,
            Instant startedAt,
            Instant finishedAt,
            String errorMessage)
    {
        Optional<ThreadTurn> found = findTurnById(turnId);
        if (found.isEmpty() || found.orElseThrow().status() != expected) {
            return false;
        }
        ThreadTurn turn = found.orElseThrow();
        saveTurn(new ThreadTurn(
                turn.id(), turn.threadId(), turn.taskId(), turn.lane(), to,
                turn.input(), turn.createdAt(), updatedAt, startedAt, finishedAt,
                errorMessage, turn.initiator(), turn.stageId(), turn.scope(),
                turn.agentRunId()));
        return true;
    }

    /** The turn already holding {@code kickKey}, if any — the
     *  claim-once check for keyed enqueues. */
    default Optional<String> findTurnIdByKickKey(String kickKey)
    {
        return Optional.empty();
    }

    /** True when the turn was enqueued as task-runtime work
     *  ({@code affects_task_liveness}). */
    default boolean turnAffectsTaskLiveness(String turnId)
    {
        return false;
    }

    /** This task's liveness-affecting turns, oldest-first — the runtime
     *  projection's evidence set. */
    default List<ThreadTurn> listLivenessTurns(String taskId, int limit)
    {
        return List.of();
    }

    /** Single-row lookup by id. Empty when no such turn exists. */
    Optional<ThreadTurn> findTurnById(String id);

    /** Turns in one status, oldest-first by creation time. */
    List<ThreadTurn> listTurnsByStatus(ThreadTurnStatus status, int limit);

    /** Turns in one status after a stable creation/id cursor, oldest-first. */
    List<ThreadTurn> listTurnsByStatusAfter(ThreadTurnStatus status, Instant createdAfter, String idAfter, int limit);

    /** Turns in any of the supplied statuses, oldest-first by creation time. */
    List<ThreadTurn> listTurnsByStatuses(Collection<ThreadTurnStatus> statuses, int limit);

    /**
     * Turns for one thread in one status, newest-first by creation time.
     * The limit is a caller-selected page size.
     */
    List<ThreadTurn> listTurnsByTaskIdAndStatus(String threadId, ThreadTurnStatus status, int limit);

    /** Turns for one thread, newest-first by creation time. */
    List<ThreadTurn> listTurnsByTaskId(String threadId, int limit);

    /** Turns correlated to one public Session, newest-first. */
    default List<ThreadTurn> listTurnsByAgentRunId(String agentRunId, int limit)
    {
        return List.of();
    }

    /** True when another queued or running turn is bound to the same Session. */
    boolean hasOtherActiveTurn(String agentRunId, String excludingTurnId);

    /** Turns for one exact task in one status, newest-first. Unlike the
     *  legacy {@code listTurnsByTaskId*} names above, this filters the
     *  durable row's {@code task_id}, not its owning thread id. */
    default List<ThreadTurn> listTurnsByExactTaskIdAndStatus(
            String taskId, ThreadTurnStatus status, int limit)
    {
        return List.of();
    }

    record InsertResult(String turnId, boolean inserted) {}
}

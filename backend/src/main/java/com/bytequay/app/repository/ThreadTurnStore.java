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
    /** Insert or update a scheduler turn by primary key. */
    void saveTurn(ThreadTurn turn);

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
}

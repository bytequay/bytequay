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

import com.bytequay.app.domain.TaskTurn;
import com.bytequay.app.domain.TaskTurnStatus;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary for scheduler turns.
 */
public interface TaskTurnStore
{
    /** Insert or update a scheduler turn by primary key. */
    void saveTurn(TaskTurn turn);

    /** Single-row lookup by id. Empty when no such turn exists. */
    Optional<TaskTurn> findTurnById(String id);

    /** Turns in one status, oldest-first by creation time. */
    List<TaskTurn> listTurnsByStatus(TaskTurnStatus status, int limit);

    /** Turns in any of the supplied statuses, oldest-first by creation time. */
    List<TaskTurn> listTurnsByStatuses(Collection<TaskTurnStatus> statuses, int limit);

    /** Turns for one task, newest-first by creation time. */
    List<TaskTurn> listTurnsByTaskId(String taskId, int limit);
}

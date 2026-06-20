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

import com.bytequay.app.domain.TaskStageIteration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence boundary for monitor-stage loop iterations. The service
 * layer talks only to this interface.
 */
public interface IterationStore
{
    /** Insert or update an iteration by id. */
    void save(TaskStageIteration iteration);

    Optional<TaskStageIteration> findById(UUID id);

    /** The iteration tracking a given monitor turn, if any. */
    Optional<TaskStageIteration> findByTurnId(String turnId);

    /** The iteration whose summary is being solicited by a follow-up turn. */
    Optional<TaskStageIteration> findBySummaryRequestTurnId(String turnId);

    /** Next 1-based iteration number for a stage. */
    int nextIterationNumber(UUID stageId);

    /** All iterations of a stage, oldest-first — drives the stage-detail
     *  iteration bands. */
    List<TaskStageIteration> findByStage(UUID stageId);

    /** Most-recent summarised iterations for a task, newest-first — the
     *  cross-agent context hook a later milestone reads. */
    List<TaskStageIteration> findRecentSummaries(String taskId, int limit);
}

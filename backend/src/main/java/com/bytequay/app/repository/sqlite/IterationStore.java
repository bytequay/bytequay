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

import com.bytequay.app.domain.TaskStageIteration;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.bytequay.app.repository.sqlite.SqlitePageRequests.firstPage;
import static java.util.Objects.requireNonNull;

@Component
public class IterationStore
{
    private final TaskStageIterationJpaRepository iterations;

    IterationStore(TaskStageIterationJpaRepository iterations)
    {
        this.iterations = requireNonNull(iterations, "iterations is null");
    }

    @Transactional
    /** Insert or update an iteration by id. */
    public void save(TaskStageIteration iteration)
    {
        TaskStageIterationEntity entity = iterations.findById(iteration.id().toString())
                .orElseGet(TaskStageIterationEntity::new);
        entity.setId(iteration.id().toString());
        entity.setStageId(iteration.stageId().toString());
        entity.setTaskId(iteration.taskId());
        entity.setTurnId(iteration.turnId());
        entity.setIterationNumber(iteration.iterationNumber());
        entity.setTrigger(iteration.trigger());
        entity.setStartedAtMs(iteration.startedAt().toEpochMilli());
        entity.setEndedAtMs(Timestamps.epochMilli(iteration.endedAt()));
        entity.setEndedReason(iteration.endedReason());
        entity.setSummaryText(iteration.summaryText());
        entity.setSummarizedAtMs(Timestamps.epochMilli(iteration.summarizedAt()));
        entity.setSummaryRequestTurnId(iteration.summaryRequestTurnId());
        iterations.save(entity);
    }

    public Optional<TaskStageIteration> findById(UUID id)
    {
        return iterations.findById(id.toString()).map(IterationStore::toIteration);
    }

    /** The iteration tracking a given monitor turn, if any. */
    public Optional<TaskStageIteration> findByTurnId(String turnId)
    {
        return iterations.findFirstByTurnId(turnId).map(IterationStore::toIteration);
    }

    /** The iteration whose summary is being solicited by a follow-up turn. */
    public Optional<TaskStageIteration> findBySummaryRequestTurnId(String turnId)
    {
        return iterations.findFirstBySummaryRequestTurnId(turnId).map(IterationStore::toIteration);
    }

    /** Next 1-based iteration number for a stage. */
    public int nextIterationNumber(UUID stageId)
    {
        return iterations.findFirstByStageIdOrderByIterationNumberDesc(stageId.toString())
                .map(e -> e.getIterationNumber() + 1)
                .orElse(1);
    }

    /** All iterations of a stage, oldest-first — drives the stage-detail
    *  iteration bands. */
    public List<TaskStageIteration> findByStage(UUID stageId)
    {
        return iterations.findByStageIdOrderByIterationNumberAsc(stageId.toString()).stream()
                .map(IterationStore::toIteration)
                .toList();
    }

    /** Most-recent summarised iterations for a task, newest-first — the
    *  cross-agent context hook a later milestone reads. */
    public List<TaskStageIteration> findRecentSummaries(String taskId, int limit)
    {
        return iterations
                .findBySummaryTextIsNotNullAndTaskIdOrderByStartedAtMsDesc(taskId, firstPage(limit))
                .stream()
                .map(IterationStore::toIteration)
                .toList();
    }

    private static TaskStageIteration toIteration(TaskStageIterationEntity e)
    {
        return new TaskStageIteration(
                UUID.fromString(e.getId()),
                UUID.fromString(e.getStageId()),
                e.getTaskId(),
                e.getTurnId(),
                e.getIterationNumber(),
                e.getTrigger(),
                Instant.ofEpochMilli(e.getStartedAtMs()),
                Timestamps.instant(e.getEndedAtMs()),
                e.getEndedReason(),
                e.getSummaryText(),
                Timestamps.instant(e.getSummarizedAtMs()),
                e.getSummaryRequestTurnId());
    }
}

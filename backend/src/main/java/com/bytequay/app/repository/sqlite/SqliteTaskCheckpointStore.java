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

import com.bytequay.app.domain.TaskCheckpoint;
import com.bytequay.app.repository.TaskCheckpointStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Component
class SqliteTaskCheckpointStore
        implements TaskCheckpointStore
{
    private static final Logger log = LoggerFactory.getLogger(SqliteTaskCheckpointStore.class);

    /** Per-segment seq numbering starts at 1; seq=0 is reserved for
     *  Overall and never allocated by {@link #nextSegmentSeq}. */
    private static final long FIRST_SEGMENT_SEQ = 1L;
    private static final long OVERALL_SEQ = 0L;
    private static final TypeReference<List<String>> BULLET_LIST_TYPE = new TypeReference<>() {};

    private final TaskCheckpointJpaRepository repo;
    private final ObjectMapper mapper;

    SqliteTaskCheckpointStore(TaskCheckpointJpaRepository repo, ObjectMapper mapper)
    {
        this.repo = requireNonNull(repo, "repo is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    @Override
    @Transactional
    public void saveSegment(TaskCheckpoint segment)
    {
        requireNonNull(segment, "segment is null");
        if (segment.isOverall()) {
            throw new IllegalArgumentException(
                    "saveSegment refuses Overall rows — use replaceOverall");
        }
        if (segment.seq() < FIRST_SEGMENT_SEQ) {
            throw new IllegalArgumentException(
                    "per-segment seq must be >= " + FIRST_SEGMENT_SEQ + ", got " + segment.seq());
        }
        repo.save(toEntity(segment));
    }

    @Override
    @Transactional
    public void replaceOverall(String taskId, TaskCheckpoint next)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(next, "next is null");
        if (!next.isOverall()) {
            throw new IllegalArgumentException(
                    "replaceOverall requires an Overall row (isOverall=true)");
        }
        if (next.seq() != OVERALL_SEQ) {
            throw new IllegalArgumentException(
                    "Overall row seq must be " + OVERALL_SEQ + ", got " + next.seq());
        }
        // Stamp any active Overall as superseded before inserting the
        // new one. Same transaction means a concurrent reader either
        // sees the old row (still active) or the new row, never both
        // and never neither. The unique(task_id, seq) constraint also
        // forces us to bump the old row's seq off seq=0; we do that
        // by setting seq=-(generated_at_ms) which is guaranteed
        // unique per task (Instant.now() granularity is ms) and
        // outside the valid range so a future scan can recognise
        // archived Overalls if we ever surface a history view.
        Optional<TaskCheckpointEntity> currentOpt = repo.findActiveOverall(taskId);
        if (currentOpt.isPresent()) {
            TaskCheckpointEntity current = currentOpt.get();
            current.setSupersededAtMs(next.generatedAt().toEpochMilli());
            current.setSeq(-current.getGeneratedAtMs());
            // saveAndFlush rather than save so the UPDATE hits the
            // database before the new INSERT below — otherwise
            // Hibernate batches both writes and SQLite trips on the
            // unique(task_id, seq) constraint for seq=0.
            repo.saveAndFlush(current);
        }
        repo.save(toEntity(next));
    }

    @Override
    public List<TaskCheckpoint> listActive(String taskId)
    {
        requireNonNull(taskId, "taskId is null");
        ImmutableList.Builder<TaskCheckpoint> out = ImmutableList.builder();
        for (TaskCheckpointEntity e : repo.findActiveForTask(taskId)) {
            out.add(toCheckpoint(e));
        }
        return out.build();
    }

    @Override
    public Optional<TaskCheckpoint> findById(String id)
    {
        requireNonNull(id, "id is null");
        return repo.findById(id).map(this::toCheckpoint);
    }

    @Override
    public Optional<TaskCheckpoint> findActiveOverall(String taskId)
    {
        requireNonNull(taskId, "taskId is null");
        return repo.findActiveOverall(taskId).map(this::toCheckpoint);
    }

    @Override
    public Optional<TaskCheckpoint> findLastSegment(String taskId)
    {
        requireNonNull(taskId, "taskId is null");
        List<TaskCheckpointEntity> rows = repo.findLastSegment(taskId, PageRequest.of(0, 1));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toCheckpoint(rows.get(0)));
    }

    @Override
    public long nextSegmentSeq(String taskId)
    {
        requireNonNull(taskId, "taskId is null");
        Long max = repo.maxSegmentSeq(taskId);
        return max == null ? FIRST_SEGMENT_SEQ : max + 1;
    }

    @Override
    @Transactional
    public void deleteSegment(String id)
    {
        requireNonNull(id, "id is null");
        Optional<TaskCheckpointEntity> row = repo.findById(id);
        if (row.isEmpty()) {
            return;
        }
        if (row.get().getIsOverall() == 1) {
            throw new IllegalArgumentException(
                    "deleteSegment refuses Overall rows — they're scheduler-owned");
        }
        repo.deleteById(id);
    }

    private TaskCheckpointEntity toEntity(TaskCheckpoint c)
    {
        TaskCheckpointEntity e = new TaskCheckpointEntity();
        e.setId(c.id());
        e.setTaskId(c.taskId());
        e.setSeq(c.seq());
        e.setIsOverall(c.isOverall() ? 1 : 0);
        e.setFirstMsgSeq(c.firstMsgSeq());
        e.setLastMsgSeq(c.lastMsgSeq());
        e.setTokensCovered(c.tokensCovered());
        e.setSummaryMd(c.summaryMd());
        e.setBulletTitles(writeBullets(c.bulletTitles()));
        e.setModelUsed(c.modelUsed());
        e.setPromptTokens(c.promptTokens());
        e.setCompletionTokens(c.completionTokens());
        e.setCostUsdMilli(c.costUsdMilli());
        e.setGeneratedAtMs(c.generatedAt().toEpochMilli());
        e.setSupersededAtMs(c.supersededAt() == null ? null : c.supersededAt().toEpochMilli());
        return e;
    }

    private TaskCheckpoint toCheckpoint(TaskCheckpointEntity e)
    {
        return new TaskCheckpoint(
                e.getId(),
                e.getTaskId(),
                e.getSeq(),
                e.getIsOverall() == 1,
                e.getFirstMsgSeq(),
                e.getLastMsgSeq(),
                e.getTokensCovered(),
                e.getSummaryMd(),
                readBullets(e.getBulletTitles()),
                e.getModelUsed(),
                e.getPromptTokens(),
                e.getCompletionTokens(),
                e.getCostUsdMilli(),
                Instant.ofEpochMilli(e.getGeneratedAtMs()),
                e.getSupersededAtMs() == null ? null : Instant.ofEpochMilli(e.getSupersededAtMs()));
    }

    private String writeBullets(List<String> bullets)
    {
        if (bullets == null || bullets.isEmpty()) {
            return "[]";
        }
        try {
            return mapper.writeValueAsString(bullets);
        }
        catch (Exception ex) {
            // We don't want a bullet-serialisation hiccup to fail the
            // whole checkpoint write — the summary text itself is what
            // matters. Fall back to an empty array and surface the
            // failure in the log so it's debuggable.
            log.warn("TaskCheckpointStore: failed to serialise bullet titles, "
                    + "storing []: {}", ex.getMessage());
            return "[]";
        }
    }

    private List<String> readBullets(String raw)
    {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            List<String> parsed = mapper.readValue(raw, BULLET_LIST_TYPE);
            return parsed == null ? List.of() : List.copyOf(parsed);
        }
        catch (Exception ex) {
            log.warn("TaskCheckpointStore: failed to parse bullet titles, returning empty: {}",
                    ex.getMessage());
            return List.of();
        }
    }
}

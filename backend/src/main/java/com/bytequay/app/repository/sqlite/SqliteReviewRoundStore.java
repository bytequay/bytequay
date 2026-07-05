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

import com.bytequay.app.domain.ReviewRound;
import com.bytequay.app.domain.ReviewRound.ReviewRoundStats;
import com.bytequay.app.repository.ReviewRoundStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Component
class SqliteReviewRoundStore
        implements ReviewRoundStore
{
    private static final Logger log = LoggerFactory.getLogger(SqliteReviewRoundStore.class);
    private static final List<String> LIVE_STATUSES =
            List.of(ReviewRound.STATUS_TRIAGING, ReviewRound.STATUS_ADDRESSING, ReviewRound.STATUS_AWAITING_GATE);

    private final ReviewRoundJpaRepository rounds;
    private final ObjectMapper mapper;

    SqliteReviewRoundStore(ReviewRoundJpaRepository rounds, ObjectMapper mapper)
    {
        this.rounds = requireNonNull(rounds, "rounds is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    @Override
    @Transactional
    public ReviewRound save(ReviewRound round)
    {
        ReviewRoundEntity e = new ReviewRoundEntity();
        e.setId(round.id());
        e.setTaskId(round.taskId());
        e.setIdx(round.idx());
        e.setReviewersJson(toJson(round.reviewers()));
        e.setStatus(round.status());
        e.setStatsJson(toJson(round.stats()));
        e.setRunId(round.runId());
        e.setOpenedAtMs(round.openedAt().toEpochMilli());
        e.setGatedAtMs(epochOrNull(round.gatedAt()));
        e.setPostedAtMs(epochOrNull(round.postedAt()));
        e.setOrigin(round.origin());
        e.setBrainVerdict(round.brainVerdict());
        e.setIteration(round.iteration());
        e.setBudget(round.budget());
        return toDomain(rounds.save(e));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReviewRound> findById(String id)
    {
        return rounds.findById(id).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewRound> findByTask(String taskId)
    {
        return rounds.findByTaskIdOrderByOpenedAtMsDesc(taskId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReviewRound> findLiveByTask(String taskId)
    {
        return rounds.findByTaskIdOrderByOpenedAtMsDesc(taskId).stream()
                .map(this::toDomain)
                .filter(r -> LIVE_STATUSES.contains(r.status()))
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public int nextIndex(String taskId)
    {
        return rounds.findByTaskIdOrderByOpenedAtMsDesc(taskId).stream()
                .mapToInt(ReviewRoundEntity::getIdx)
                .max()
                .orElse(0) + 1;
    }

    private ReviewRound toDomain(ReviewRoundEntity e)
    {
        return new ReviewRound(
                e.getId(),
                e.getTaskId(),
                e.getIdx(),
                fromJsonReviewers(e.getReviewersJson()),
                e.getStatus(),
                fromJsonStats(e.getStatsJson()),
                e.getRunId(),
                Instant.ofEpochMilli(e.getOpenedAtMs()),
                instantOrNull(e.getGatedAtMs()),
                instantOrNull(e.getPostedAtMs()),
                e.getOrigin(),
                e.getBrainVerdict(),
                e.getIteration(),
                e.getBudget());
    }

    private List<String> fromJsonReviewers(String json)
    {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<List<String>>() {});
        }
        catch (JsonProcessingException e) {
            log.warn("unparseable reviewers_json: {}", e.getMessage());
            return List.of();
        }
    }

    private ReviewRoundStats fromJsonStats(String json)
    {
        if (json == null || json.isBlank()) {
            return ReviewRoundStats.empty();
        }
        try {
            return mapper.readValue(json, ReviewRoundStats.class);
        }
        catch (JsonProcessingException e) {
            log.warn("unparseable stats_json: {}", e.getMessage());
            return ReviewRoundStats.empty();
        }
    }

    private String toJson(Object value)
    {
        try {
            return mapper.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("review round JSON serialise failed", e);
        }
    }

    private static Long epochOrNull(Instant instant)
    {
        return instant == null ? null : instant.toEpochMilli();
    }

    private static Instant instantOrNull(Long epochMs)
    {
        return epochMs == null ? null : Instant.ofEpochMilli(epochMs);
    }
}

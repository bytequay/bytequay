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
import com.bytequay.app.domain.ReviewRoundState;
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
public class SqliteReviewRoundStore
{
    public record AttemptFence(
            int iteration,
            int gateRevision,
            int kickAttempt,
            String turnId,
            String kickKey) {}

    private static final Logger log = LoggerFactory.getLogger(SqliteReviewRoundStore.class);
    private final ReviewRoundJpaRepository rounds;
    private final ObjectMapper mapper;

    SqliteReviewRoundStore(ReviewRoundJpaRepository rounds, ObjectMapper mapper)
    {
        this.rounds = requireNonNull(rounds, "rounds is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    @Transactional
    public ReviewRound insert(ReviewRound round)
    {
        if (rounds.existsById(round.id())) {
            throw new IllegalStateException("review round already exists: " + round.id());
        }
        ReviewRoundEntity e = new ReviewRoundEntity();
        e.setId(round.id());
        e.setTaskId(round.taskId());
        e.setIdx(round.idx());
        e.setReviewersJson(toJson(round.reviewers()));
        e.setStatus(round.status().dbValue());
        e.setStatsJson(toJson(round.stats()));
        e.setRunId(round.runId());
        e.setOpenedAtMs(round.openedAt().toEpochMilli());
        e.setGatedAtMs(epochOrNull(round.gatedAt()));
        e.setPostedAtMs(epochOrNull(round.postedAt()));
        e.setOrigin(round.origin());
        e.setBrainVerdict(round.brainVerdict());
        e.setIteration(round.iteration());
        e.setBudget(round.budget());
        e.setPausedFrom(dbValueOrNull(round.pausedFrom()));
        e.setCodeFingerprint(round.codeFingerprint());
        e.setEnqueueFailures(round.enqueueFailures());
        e.setKickAttempt(round.kickAttempt());
        e.setGateRevision(round.gateRevision());
        e.setActiveGateToken(round.activeGateToken());
        e.setClosedAtMs(epochOrNull(round.closedAt()));
        return toDomain(rounds.saveAndFlush(e));
    }

    @Transactional
    public boolean parkIf(String id, ReviewRoundState expected)
    {
        return rounds.park(id, expected.dbValue()) == 1;
    }

    @Transactional
    public boolean resumeIf(String id, ReviewRoundState pausedFrom)
    {
        return rounds.resume(id, pausedFrom.dbValue()) == 1;
    }

    @Transactional
    public boolean sealIf(String id, ReviewRoundState expected, Instant closedAt)
    {
        return rounds.seal(id, expected.dbValue(), closedAt.toEpochMilli()) == 1;
    }

    @Transactional
    public boolean concludeIf(
            String id,
            ReviewRoundState expected,
            ReviewRoundState to,
            AttemptFence attempt,
            ReviewRound.ReviewRoundStats stats,
            String verdict,
            Instant gatedAt,
            Instant closedAt)
    {
        return rounds.conclude(
                id, expected.dbValue(), to.dbValue(),
                attempt.iteration(), attempt.gateRevision(), attempt.kickAttempt(),
                attempt.turnId(), attempt.kickKey(), toJson(stats), verdict,
                epochOrNull(gatedAt), epochOrNull(closedAt)) == 1;
    }

    @Transactional
    public boolean finishAddressingIf(
            String id,
            AttemptFence attempt,
            String validationClaimKey,
            String codeFingerprint)
    {
        return rounds.finishAddressing(
                id, attempt.iteration(), attempt.gateRevision(), attempt.kickAttempt(),
                attempt.turnId(), attempt.kickKey(), validationClaimKey, codeFingerprint) == 1;
    }

    @Transactional
    public boolean authorizeGateIf(
            String id,
            int expectedGateRevision,
            String codeFingerprint,
            String activeGateToken)
    {
        return rounds.authorizeGate(
                id, expectedGateRevision, codeFingerprint, activeGateToken) == 1;
    }

    @Transactional
    public boolean postIf(String id, String activeGateToken, Instant postedAt)
    {
        return rounds.postAuthorized(
                id, activeGateToken, postedAt.toEpochMilli()) == 1;
    }

    @Transactional
    public boolean requestGateChangesIf(String id, int additionalBudget)
    {
        return rounds.requestGateChanges(
                id, additionalBudget, toJson(ReviewRoundStats.empty())) == 1;
    }

    @Transactional
    public boolean invalidateGateFingerprintIf(
            String id, String activeToken)
    {
        return rounds.invalidateGateFingerprint(
                id, activeToken, toJson(ReviewRoundStats.empty())) == 1;
    }

    @Transactional
    public boolean acceptGateValidationIf(
            String id, int expectedKickAttempt, String codeFingerprint)
    {
        return rounds.acceptGateValidation(
                id, expectedKickAttempt, codeFingerprint) == 1;
    }

    @Transactional
    public boolean updateBrainVerdictIf(
            String id, ReviewRoundState expected, String verdict)
    {
        return rounds.updateBrainVerdictIf(
                id, expected.dbValue(), verdict) == 1;
    }

    @Transactional
    public boolean recordDeliveryFailureIf(
            String id, ReviewRoundState expected, int expectedKickAttempt)
    {
        return rounds.recordDeliveryFailure(
                id, expected.dbValue(), expectedKickAttempt) == 1;
    }

    @Transactional
    public boolean clearEnqueueFailuresIf(
            String id, ReviewRoundState expected, int expectedKickAttempt)
    {
        return rounds.clearEnqueueFailures(
                id, expected.dbValue(), expectedKickAttempt) == 1;
    }

    @Transactional
    public void updateStats(String id, ReviewRound.ReviewRoundStats stats)
    {
        rounds.updateStatsJson(id, toJson(stats));
    }

    @Transactional
    public void updateRunId(String id, String runId)
    {
        rounds.updateRunId(id, runId);
    }

    @Transactional
    public void updateGateTimes(String id, Instant gatedAt, Instant postedAt)
    {
        rounds.updateGateTimes(id, epochOrNull(gatedAt), epochOrNull(postedAt));
    }

    @Transactional(readOnly = true)
    public Optional<ReviewRound> findById(String id)
    {
        return rounds.findById(id).map(this::toDomain);
    }

    @Transactional(readOnly = true)
    public List<ReviewRound> findByTask(String taskId)
    {
        return rounds.findByTaskIdOrderByOpenedAtMsDesc(taskId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<ReviewRound> findLiveByTask(String taskId)
    {
        return rounds.findByTaskIdOrderByOpenedAtMsDesc(taskId).stream()
                .map(this::toDomain)
                .filter(ReviewRound::isLive)
                .findFirst();
    }

    @Transactional(readOnly = true)
    public List<ReviewRound> findAllLive()
    {
        return rounds.findAll().stream()
                .map(this::toDomain)
                .filter(ReviewRound::isLive)
                .toList();
    }

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
                ReviewRoundState.fromDbValue(e.getStatus()),
                fromJsonStats(e.getStatsJson()),
                e.getRunId(),
                Instant.ofEpochMilli(e.getOpenedAtMs()),
                instantOrNull(e.getGatedAtMs()),
                instantOrNull(e.getPostedAtMs()),
                e.getOrigin(),
                e.getBrainVerdict(),
                e.getIteration(),
                e.getBudget(),
                stateOrNull(e.getPausedFrom()),
                e.getCodeFingerprint(),
                e.getEnqueueFailures(),
                e.getKickAttempt(),
                e.getGateRevision(),
                e.getActiveGateToken(),
                instantOrNull(e.getClosedAtMs()));
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

    private static String dbValueOrNull(ReviewRoundState state)
    {
        return state == null ? null : state.dbValue();
    }

    private static ReviewRoundState stateOrNull(String value)
    {
        return value == null ? null : ReviewRoundState.fromDbValue(value);
    }
}

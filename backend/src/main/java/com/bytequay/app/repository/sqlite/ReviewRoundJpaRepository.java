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

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

interface ReviewRoundJpaRepository
        extends JpaRepository<ReviewRoundEntity, String>
{
    List<ReviewRoundEntity> findByTaskIdOrderByOpenedAtMsDesc(String taskId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ReviewRoundEntity r SET r.status = 'paused', r.pausedFrom = :expected "
            + "WHERE r.id = :id AND r.status = :expected AND r.pausedFrom IS NULL")
    int park(@Param("id") String id, @Param("expected") String expected);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ReviewRoundEntity r SET r.status = :pausedFrom, r.pausedFrom = NULL, "
            + "r.kickAttempt = r.kickAttempt + 1, r.enqueueFailures = 0, "
            + "r.brainVerdict = CASE WHEN :pausedFrom = 'triaging' "
            + "THEN NULL ELSE r.brainVerdict END "
            + "WHERE r.id = :id AND r.status = 'paused' AND r.pausedFrom = :pausedFrom")
    int resume(@Param("id") String id, @Param("pausedFrom") String pausedFrom);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ReviewRoundEntity r SET r.status = 'closed', r.pausedFrom = NULL, "
            + "r.activeGateToken = NULL, r.closedAtMs = :closedAtMs "
            + "WHERE r.id = :id AND r.status = :expected")
    int seal(
            @Param("id") String id,
            @Param("expected") String expected,
            @Param("closedAtMs") long closedAtMs);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ReviewRoundEntity r
            SET r.status = :to,
                r.statsJson = :statsJson,
                r.brainVerdict = :verdict,
                r.gatedAtMs = :gatedAtMs,
                r.closedAtMs = :closedAtMs,
                r.enqueueFailures = 0,
                r.kickAttempt = 0
            WHERE r.id = :id
              AND r.status = :expected
              AND r.iteration = :iteration
              AND r.gateRevision = :gateRevision
              AND r.kickAttempt = :kickAttempt
              AND EXISTS (
                  SELECT turn.id
                  FROM ThreadTurnEntity turn
                  WHERE turn.id = :turnId
                    AND turn.kickKey = :kickKey
                    AND turn.status = 'COMPLETED'
                    AND turn.taskId = r.taskId
                    AND turn.agentRunId = r.runId
              )
            """)
    int conclude(
            @Param("id") String id,
            @Param("expected") String expected,
            @Param("to") String to,
            @Param("iteration") int iteration,
            @Param("gateRevision") int gateRevision,
            @Param("kickAttempt") int kickAttempt,
            @Param("turnId") String turnId,
            @Param("kickKey") String kickKey,
            @Param("statsJson") String statsJson,
            @Param("verdict") String verdict,
            @Param("gatedAtMs") Long gatedAtMs,
            @Param("closedAtMs") Long closedAtMs);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ReviewRoundEntity r
            SET r.status = 'triaging',
                r.codeFingerprint = :fingerprint,
                r.iteration = r.iteration + 1,
                r.brainVerdict = NULL,
                r.enqueueFailures = 0,
                r.kickAttempt = 0
            WHERE r.id = :id
              AND r.status = 'addressing'
              AND r.iteration = :iteration
              AND r.gateRevision = :gateRevision
              AND r.kickAttempt = :kickAttempt
              AND EXISTS (
                  SELECT turn.id
                  FROM ThreadTurnEntity turn
                  WHERE turn.id = :turnId
                    AND turn.kickKey = :kickKey
                    AND turn.status = 'COMPLETED'
                    AND turn.taskId = r.taskId
                    AND turn.agentRunId = r.runId
              )
              AND EXISTS (
                  SELECT claim.id
                  FROM ValidationPassEntity claim
                  WHERE claim.claimKey = :claimKey
                    AND claim.taskId = r.taskId
                    AND claim.context = 'review-round'
                    AND claim.roundId = r.id
                    AND claim.codeFingerprint = :fingerprint
                    AND claim.endedAtMs IS NOT NULL
                    AND claim.passed = true
                    AND claim.cancelRequestedAtMs IS NULL
                    AND claim.supersededAtMs IS NULL
              )
            """)
    int finishAddressing(
            @Param("id") String id,
            @Param("iteration") int iteration,
            @Param("gateRevision") int gateRevision,
            @Param("kickAttempt") int kickAttempt,
            @Param("turnId") String turnId,
            @Param("kickKey") String kickKey,
            @Param("claimKey") String claimKey,
            @Param("fingerprint") String fingerprint);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ReviewRoundEntity r SET r.activeGateToken = :token "
            + "WHERE r.id = :id AND r.status = 'awaiting_gate' "
            + "AND r.gateRevision = :gateRevision "
            + "AND r.codeFingerprint = :fingerprint AND r.activeGateToken IS NULL")
    int authorizeGate(
            @Param("id") String id,
            @Param("gateRevision") int gateRevision,
            @Param("fingerprint") String fingerprint,
            @Param("token") String token);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ReviewRoundEntity r SET r.status = 'posted', r.postedAtMs = :postedAtMs "
            + "WHERE r.id = :id AND r.status = 'awaiting_gate' "
            + "AND r.activeGateToken = :token")
    int postAuthorized(
            @Param("id") String id,
            @Param("token") String token,
            @Param("postedAtMs") long postedAtMs);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ReviewRoundEntity r SET r.status = 'addressing', "
            + "r.gateRevision = r.gateRevision + 1, r.activeGateToken = NULL, "
            + "r.gatedAtMs = NULL, r.postedAtMs = NULL, r.brainVerdict = NULL, "
            + "r.statsJson = :emptyStats, r.budget = r.budget + :additionalBudget, "
            + "r.enqueueFailures = 0, r.kickAttempt = 0 "
            + "WHERE r.id = :id AND r.status = 'awaiting_gate'")
    int requestGateChanges(
            @Param("id") String id,
            @Param("additionalBudget") int additionalBudget,
            @Param("emptyStats") String emptyStats);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ReviewRoundEntity r SET r.status = 'triaging', "
            + "r.activeGateToken = NULL, "
            + "r.gatedAtMs = NULL, r.postedAtMs = NULL, r.brainVerdict = NULL, "
            + "r.statsJson = :emptyStats, r.kickAttempt = r.kickAttempt + 1, "
            + "r.enqueueFailures = 0 "
            + "WHERE r.id = :id AND r.status = 'awaiting_gate' "
            + "AND r.activeGateToken = :activeToken")
    int invalidateGateFingerprint(
            @Param("id") String id,
            @Param("activeToken") String activeToken,
            @Param("emptyStats") String emptyStats);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ReviewRoundEntity r SET r.codeFingerprint = :fingerprint, "
            + "r.enqueueFailures = 0 "
            + "WHERE r.id = :id AND r.status = 'triaging' "
            + "AND r.kickAttempt = :expectedKickAttempt "
            + "AND r.codeFingerprint <> :fingerprint")
    int acceptGateValidation(
            @Param("id") String id,
            @Param("expectedKickAttempt") int expectedKickAttempt,
            @Param("fingerprint") String fingerprint);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ReviewRoundEntity r SET r.brainVerdict = :verdict "
            + "WHERE r.id = :id AND r.status = :expected")
    int updateBrainVerdictIf(
            @Param("id") String id,
            @Param("expected") String expected,
            @Param("verdict") String verdict);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ReviewRoundEntity r SET r.enqueueFailures = r.enqueueFailures + 1, "
            + "r.kickAttempt = r.kickAttempt + 1 "
            + "WHERE r.id = :id AND r.status = :expected "
            + "AND r.kickAttempt = :expectedKickAttempt")
    int recordDeliveryFailure(
            @Param("id") String id,
            @Param("expected") String expected,
            @Param("expectedKickAttempt") int expectedKickAttempt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ReviewRoundEntity r SET r.enqueueFailures = 0 "
            + "WHERE r.id = :id AND r.status = :expected "
            + "AND r.kickAttempt = :expectedKickAttempt")
    int clearEnqueueFailures(
            @Param("id") String id,
            @Param("expected") String expected,
            @Param("expectedKickAttempt") int expectedKickAttempt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ReviewRoundEntity r SET r.statsJson = :statsJson WHERE r.id = :id")
    int updateStatsJson(@Param("id") String id, @Param("statsJson") String statsJson);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ReviewRoundEntity r SET r.runId = :runId WHERE r.id = :id")
    int updateRunId(@Param("id") String id, @Param("runId") String runId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ReviewRoundEntity r SET r.gatedAtMs = :gatedAtMs, r.postedAtMs = :postedAtMs "
            + "WHERE r.id = :id")
    int updateGateTimes(
            @Param("id") String id,
            @Param("gatedAtMs") Long gatedAtMs,
            @Param("postedAtMs") Long postedAtMs);
}

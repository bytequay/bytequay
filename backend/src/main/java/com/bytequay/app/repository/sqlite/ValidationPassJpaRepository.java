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
import java.util.Optional;

interface ValidationPassJpaRepository
        extends JpaRepository<ValidationPassEntity, Long>
{
    Optional<ValidationPassEntity> findByClaimKey(String claimKey);

    Optional<ValidationPassEntity>
            findFirstByTaskIdAndContextAndEndedAtMsIsNotNullAndPassedTrueAndSupersededAtMsIsNullOrderByEndedAtMsDescIdDesc(
                    String taskId, String context);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ValidationPassEntity v SET v.roundId = :roundId "
            + "WHERE v.claimKey = :claimKey AND v.roundId IS NULL "
            + "AND v.endedAtMs IS NOT NULL AND v.passed = true "
            + "AND v.cancelRequestedAtMs IS NULL AND v.supersededAtMs IS NULL")
    int bindRoundIfUnbound(
            @Param("claimKey") String claimKey,
            @Param("roundId") String roundId);

    Optional<ValidationPassEntity> findFirstByRoundIdAndContextOrderByIdDesc(
            String roundId, String context);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ValidationPassEntity v SET v.ownerId = :ownerId, "
            + "v.executorIdentity = :executorIdentity, v.leaseUntilMs = :leaseUntilMs, "
            + "v.heartbeatAtMs = :now "
            + "WHERE v.claimKey = :claimKey AND v.endedAtMs IS NULL "
            + "AND v.cancelRequestedAtMs IS NULL AND v.supersededAtMs IS NULL "
            + "AND (v.ownerId IS NULL OR v.leaseUntilMs < :now)")
    int acquireOwner(
            @Param("claimKey") String claimKey,
            @Param("ownerId") String ownerId,
            @Param("executorIdentity") String executorIdentity,
            @Param("leaseUntilMs") long leaseUntilMs,
            @Param("now") long now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ValidationPassEntity v SET v.leaseUntilMs = :leaseUntilMs, "
            + "v.heartbeatAtMs = :heartbeatAtMs "
            + "WHERE v.claimKey = :claimKey AND v.ownerId = :ownerId AND v.endedAtMs IS NULL "
            + "AND v.cancelRequestedAtMs IS NULL AND v.supersededAtMs IS NULL")
    int renewLease(
            @Param("claimKey") String claimKey,
            @Param("ownerId") String ownerId,
            @Param("leaseUntilMs") long leaseUntilMs,
            @Param("heartbeatAtMs") long heartbeatAtMs);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ValidationPassEntity v SET v.endedAtMs = :endedAtMs, v.passed = :passed, "
            + "v.failuresJson = :failuresJson "
            + "WHERE v.claimKey = :claimKey AND v.endedAtMs IS NULL "
            + "AND v.ownerId = :ownerId AND v.codeFingerprint = :codeFingerprint "
            + "AND v.cancelRequestedAtMs IS NULL AND v.supersededAtMs IS NULL")
    int completeOwned(
            @Param("claimKey") String claimKey,
            @Param("ownerId") String ownerId,
            @Param("codeFingerprint") String codeFingerprint,
            @Param("endedAtMs") long endedAtMs,
            @Param("passed") boolean passed,
            @Param("failuresJson") String failuresJson);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ValidationPassEntity v SET v.cancelRequestedAtMs = :requestedAtMs, "
            + "v.cancelDeadlineAtMs = :deadlineMs "
            + "WHERE v.claimKey = :claimKey AND v.endedAtMs IS NULL "
            + "AND v.cancelRequestedAtMs IS NULL")
    int requestCancel(
            @Param("claimKey") String claimKey,
            @Param("requestedAtMs") long requestedAtMs,
            @Param("deadlineMs") long deadlineMs);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ValidationPassEntity v SET v.supersededAtMs = :atMs "
            + "WHERE v.claimKey = :claimKey AND v.endedAtMs IS NULL "
            + "AND v.supersededAtMs IS NULL")
    int markSuperseded(@Param("claimKey") String claimKey, @Param("atMs") long atMs);

    @Query("SELECT v FROM ValidationPassEntity v WHERE v.claimKey IS NOT NULL "
            + "AND v.endedAtMs IS NULL AND v.cancelRequestedAtMs IS NULL "
            + "AND v.supersededAtMs IS NULL "
            + "AND (v.leaseUntilMs IS NULL OR v.leaseUntilMs < :now)")
    List<ValidationPassEntity> findResumableStarted(@Param("now") long now);

    @Query("SELECT v FROM ValidationPassEntity v WHERE v.taskId = :taskId "
            + "AND v.claimKey IS NOT NULL AND v.endedAtMs IS NULL "
            + "AND v.supersededAtMs IS NULL")
    List<ValidationPassEntity> findOpenByTask(@Param("taskId") String taskId);

    @Query("SELECT v FROM ValidationPassEntity v WHERE v.claimKey IS NOT NULL "
            + "AND v.endedAtMs IS NULL AND v.supersededAtMs IS NULL "
            + "AND v.cancelRequestedAtMs IS NOT NULL")
    List<ValidationPassEntity> findCancelPending();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ValidationPassEntity v SET v.cancelAttempts = v.cancelAttempts + 1 "
            + "WHERE v.claimKey = :claimKey")
    int incrementCancelAttempts(@Param("claimKey") String claimKey);
}

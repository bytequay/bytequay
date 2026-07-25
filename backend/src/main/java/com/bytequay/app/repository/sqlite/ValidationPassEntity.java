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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** JPA row for the {@code validation_pass} audit log. */
@Entity
@Table(name = "validation_pass")
class ValidationPassEntity
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "task_id", nullable = false)
    private String taskId;

    @Column(name = "started_at_ms", nullable = false)
    private long startedAtMs;

    @Column(name = "ended_at_ms")
    private Long endedAtMs;

    @Column(name = "passed")
    private Boolean passed;

    @Column(name = "fix_rounds", nullable = false)
    private int fixRounds;

    @Column(name = "failures_json")
    private String failuresJson;

    @Column(name = "claim_key")
    private String claimKey;

    @Column(name = "context")
    private String context;

    @Column(name = "round_id")
    private String roundId;

    @Column(name = "code_fingerprint")
    private String codeFingerprint;

    @Column(name = "through_sequence")
    private Long throughSequence;

    @Column(name = "root_set_digest")
    private String rootSetDigest;

    @Column(name = "cancel_requested_at_ms")
    private Long cancelRequestedAtMs;

    @Column(name = "cancel_deadline_at_ms")
    private Long cancelDeadlineAtMs;

    @Column(name = "cancel_attempts", nullable = false)
    private int cancelAttempts;

    @Column(name = "superseded_at_ms")
    private Long supersededAtMs;

    @Column(name = "owner_id")
    private String ownerId;

    @Column(name = "executor_identity")
    private String executorIdentity;

    @Column(name = "lease_until_ms")
    private Long leaseUntilMs;

    @Column(name = "heartbeat_at_ms")
    private Long heartbeatAtMs;

    Long getId() { return id; }
    void setId(Long id) { this.id = id; }

    String getTaskId() { return taskId; }
    void setTaskId(String taskId) { this.taskId = taskId; }

    long getStartedAtMs() { return startedAtMs; }
    void setStartedAtMs(long startedAtMs) { this.startedAtMs = startedAtMs; }

    Long getEndedAtMs() { return endedAtMs; }
    void setEndedAtMs(Long endedAtMs) { this.endedAtMs = endedAtMs; }

    Boolean getPassed() { return passed; }
    void setPassed(Boolean passed) { this.passed = passed; }

    int getFixRounds() { return fixRounds; }
    void setFixRounds(int fixRounds) { this.fixRounds = fixRounds; }

    String getFailuresJson() { return failuresJson; }
    void setFailuresJson(String failuresJson) { this.failuresJson = failuresJson; }

    String getClaimKey() { return claimKey; }
    void setClaimKey(String claimKey) { this.claimKey = claimKey; }

    String getContext() { return context; }
    void setContext(String context) { this.context = context; }

    String getRoundId() { return roundId; }
    void setRoundId(String roundId) { this.roundId = roundId; }

    String getCodeFingerprint() { return codeFingerprint; }
    void setCodeFingerprint(String codeFingerprint) { this.codeFingerprint = codeFingerprint; }

    Long getThroughSequence() { return throughSequence; }
    void setThroughSequence(Long throughSequence) { this.throughSequence = throughSequence; }

    String getRootSetDigest() { return rootSetDigest; }
    void setRootSetDigest(String rootSetDigest) { this.rootSetDigest = rootSetDigest; }

    Long getCancelRequestedAtMs() { return cancelRequestedAtMs; }
    void setCancelRequestedAtMs(Long cancelRequestedAtMs) { this.cancelRequestedAtMs = cancelRequestedAtMs; }

    Long getCancelDeadlineAtMs() { return cancelDeadlineAtMs; }
    void setCancelDeadlineAtMs(Long cancelDeadlineAtMs) { this.cancelDeadlineAtMs = cancelDeadlineAtMs; }

    int getCancelAttempts() { return cancelAttempts; }
    void setCancelAttempts(int cancelAttempts) { this.cancelAttempts = cancelAttempts; }

    Long getSupersededAtMs() { return supersededAtMs; }
    void setSupersededAtMs(Long supersededAtMs) { this.supersededAtMs = supersededAtMs; }

    String getOwnerId() { return ownerId; }
    void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    String getExecutorIdentity() { return executorIdentity; }
    void setExecutorIdentity(String executorIdentity) { this.executorIdentity = executorIdentity; }

    Long getLeaseUntilMs() { return leaseUntilMs; }
    void setLeaseUntilMs(Long leaseUntilMs) { this.leaseUntilMs = leaseUntilMs; }

    Long getHeartbeatAtMs() { return heartbeatAtMs; }
    void setHeartbeatAtMs(Long heartbeatAtMs) { this.heartbeatAtMs = heartbeatAtMs; }
}

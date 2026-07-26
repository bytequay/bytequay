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
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "round_gate_authorization")
class RoundGateAuthorizationEntity
{
    @Id
    @Column(name = "token", nullable = false)
    private String token;

    @Column(name = "task_id", nullable = false)
    private String taskId;

    @Column(name = "round_id", nullable = false)
    private String roundId;

    @Column(name = "gate_revision", nullable = false)
    private int gateRevision;

    @Column(name = "attempt", nullable = false)
    private int attempt;

    @Column(name = "actor", nullable = false)
    private String actor;

    @Column(name = "code_fingerprint", nullable = false)
    private String codeFingerprint;

    @Column(name = "payload_json", nullable = false)
    private String payloadJson;

    @Column(name = "payload_digest", nullable = false)
    private String payloadDigest;

    @Column(name = "effect_keys_json", nullable = false)
    private String effectKeysJson;

    @Column(name = "approved_at_ms", nullable = false)
    private long approvedAtMs;

    @Column(name = "revoked_at_ms")
    private Long revokedAtMs;

    @Column(name = "consumed_at_ms")
    private Long consumedAtMs;

    @Column(name = "outcome")
    private String outcome;

    String getToken() { return token; }
    void setToken(String token) { this.token = token; }
    String getTaskId() { return taskId; }
    void setTaskId(String taskId) { this.taskId = taskId; }
    String getRoundId() { return roundId; }
    void setRoundId(String roundId) { this.roundId = roundId; }
    int getGateRevision() { return gateRevision; }
    void setGateRevision(int gateRevision) { this.gateRevision = gateRevision; }
    int getAttempt() { return attempt; }
    void setAttempt(int attempt) { this.attempt = attempt; }
    String getActor() { return actor; }
    void setActor(String actor) { this.actor = actor; }
    String getCodeFingerprint() { return codeFingerprint; }
    void setCodeFingerprint(String codeFingerprint) { this.codeFingerprint = codeFingerprint; }
    String getPayloadJson() { return payloadJson; }
    void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    String getPayloadDigest() { return payloadDigest; }
    void setPayloadDigest(String payloadDigest) { this.payloadDigest = payloadDigest; }
    String getEffectKeysJson() { return effectKeysJson; }
    void setEffectKeysJson(String effectKeysJson) { this.effectKeysJson = effectKeysJson; }
    long getApprovedAtMs() { return approvedAtMs; }
    void setApprovedAtMs(long approvedAtMs) { this.approvedAtMs = approvedAtMs; }
    Long getRevokedAtMs() { return revokedAtMs; }
    void setRevokedAtMs(Long revokedAtMs) { this.revokedAtMs = revokedAtMs; }
    Long getConsumedAtMs() { return consumedAtMs; }
    void setConsumedAtMs(Long consumedAtMs) { this.consumedAtMs = consumedAtMs; }
    String getOutcome() { return outcome; }
    void setOutcome(String outcome) { this.outcome = outcome; }
}

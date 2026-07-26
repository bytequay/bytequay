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

@Entity
@Table(name = "task_push_effect")
class TaskPushEffectEntity
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token", nullable = false)
    private String token;

    @Column(name = "effect_key", nullable = false)
    private String effectKey;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "attempt_limit", nullable = false)
    private int attemptLimit;

    @Column(name = "first_claimed_at_ms")
    private Long firstClaimedAtMs;

    @Column(name = "last_claimed_at_ms")
    private Long lastClaimedAtMs;

    @Column(name = "claim_owner")
    private String claimOwner;

    @Column(name = "lease_until_ms")
    private Long leaseUntilMs;

    @Column(name = "last_error_class")
    private String lastErrorClass;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "next_attempt_at_ms")
    private Long nextAttemptAtMs;

    @Column(name = "evidence_json")
    private String evidenceJson;

    @Column(name = "completed_at_ms")
    private Long completedAtMs;

    Long getId() { return id; }
    String getToken() { return token; }
    void setToken(String token) { this.token = token; }
    String getEffectKey() { return effectKey; }
    void setEffectKey(String effectKey) { this.effectKey = effectKey; }
    String getStatus() { return status; }
    void setStatus(String status) { this.status = status; }
    int getAttempts() { return attempts; }
    void setAttempts(int attempts) { this.attempts = attempts; }
    int getAttemptLimit() { return attemptLimit; }
    void setAttemptLimit(int attemptLimit) { this.attemptLimit = attemptLimit; }
    Long getFirstClaimedAtMs() { return firstClaimedAtMs; }
    Long getLastClaimedAtMs() { return lastClaimedAtMs; }
    String getClaimOwner() { return claimOwner; }
    Long getLeaseUntilMs() { return leaseUntilMs; }
    String getLastErrorClass() { return lastErrorClass; }
    String getLastError() { return lastError; }
    Long getNextAttemptAtMs() { return nextAttemptAtMs; }
    String getEvidenceJson() { return evidenceJson; }
    Long getCompletedAtMs() { return completedAtMs; }
}

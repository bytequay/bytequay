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
@Table(name = "review_messages")
class ReviewMessageEntity
{
    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "review_pass_id", nullable = false)
    private String reviewPassId;

    @Column(name = "participant_id", nullable = false)
    private String participantId;

    @Column(name = "phase", nullable = false)
    private String phase;

    @Column(name = "round", nullable = false)
    private int round;

    @Column(name = "body", nullable = false)
    private String body;

    @Column(name = "mentions")
    private String mentionsJson;

    @Column(name = "refs")
    private String refsJson;

    @Column(name = "payload_kind")
    private String payloadKind;

    @Column(name = "payload_json")
    private String payloadJson;

    @Column(name = "cost_usd_milli", nullable = false)
    private long costUsdMilli;

    @Column(name = "created_at_ms", nullable = false)
    private long createdAtMs;

    String getId() { return id; }
    void setId(String id) { this.id = id; }

    String getReviewPassId() { return reviewPassId; }
    void setReviewPassId(String reviewPassId) { this.reviewPassId = reviewPassId; }

    String getParticipantId() { return participantId; }
    void setParticipantId(String participantId) { this.participantId = participantId; }

    String getPhase() { return phase; }
    void setPhase(String phase) { this.phase = phase; }

    int getRound() { return round; }
    void setRound(int round) { this.round = round; }

    String getBody() { return body; }
    void setBody(String body) { this.body = body; }

    String getMentionsJson() { return mentionsJson; }
    void setMentionsJson(String mentionsJson) { this.mentionsJson = mentionsJson; }

    String getRefsJson() { return refsJson; }
    void setRefsJson(String refsJson) { this.refsJson = refsJson; }

    String getPayloadKind() { return payloadKind; }
    void setPayloadKind(String payloadKind) { this.payloadKind = payloadKind; }

    String getPayloadJson() { return payloadJson; }
    void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }

    long getCostUsdMilli() { return costUsdMilli; }
    void setCostUsdMilli(long costUsdMilli) { this.costUsdMilli = costUsdMilli; }

    long getCreatedAtMs() { return createdAtMs; }
    void setCreatedAtMs(long createdAtMs) { this.createdAtMs = createdAtMs; }
}

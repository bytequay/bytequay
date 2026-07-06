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

/** JPA row for a {@code pr_timeline_event}. */
@Entity
@Table(name = "pr_timeline_event")
class PrTimelineEventEntity
{
    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "pr_id", nullable = false)
    private String prId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "actor", nullable = false)
    private String actor;

    @Column(name = "is_local_only", nullable = false)
    private boolean localOnly;

    @Column(name = "stripped_on_push_at_ms")
    private Long strippedOnPushAtMs;

    @Column(name = "created_at_ms", nullable = false)
    private long createdAtMs;

    @Column(name = "payload_json")
    private String payloadJson;

    @Column(name = "remote_event_id")
    private Long remoteEventId;

    String getId() { return id; }
    void setId(String id) { this.id = id; }

    String getPrId() { return prId; }
    void setPrId(String prId) { this.prId = prId; }

    String getEventType() { return eventType; }
    void setEventType(String eventType) { this.eventType = eventType; }

    String getActor() { return actor; }
    void setActor(String actor) { this.actor = actor; }

    boolean isLocalOnly() { return localOnly; }
    void setLocalOnly(boolean localOnly) { this.localOnly = localOnly; }

    Long getStrippedOnPushAtMs() { return strippedOnPushAtMs; }
    void setStrippedOnPushAtMs(Long strippedOnPushAtMs) { this.strippedOnPushAtMs = strippedOnPushAtMs; }

    long getCreatedAtMs() { return createdAtMs; }
    void setCreatedAtMs(long createdAtMs) { this.createdAtMs = createdAtMs; }

    String getPayloadJson() { return payloadJson; }
    void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }

    Long getRemoteEventId() { return remoteEventId; }
    void setRemoteEventId(Long remoteEventId) { this.remoteEventId = remoteEventId; }
}

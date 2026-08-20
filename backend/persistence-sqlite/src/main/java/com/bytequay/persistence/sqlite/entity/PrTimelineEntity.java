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
package com.bytequay.persistence.sqlite.entity;

import com.bytequay.core.domain.PrTimelinePayload;
import com.bytequay.persistence.sqlite.converter.InstantToTextConverter;
import com.bytequay.persistence.sqlite.converter.PrTimelinePayloadConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "pr_timeline")
public class PrTimelineEntity
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pr_id", nullable = false)
    private Long prId;

    private String event;

    private String actor;

    @Convert(converter = InstantToTextConverter.class)
    private Instant timestamp;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(name = "github_id")
    private Long githubId;

    @Column(name = "local_event_key", unique = true)
    private String localEventKey;

    @Column(columnDefinition = "TEXT")
    @Convert(converter = PrTimelinePayloadConverter.class)
    private PrTimelinePayload payload;

    public PrTimelineEntity() {}

    public Long getId() { return id; }

    public Long getPrId() { return prId; }
    public void setPrId(Long prId) { this.prId = prId; }

    public String getEvent() { return event; }
    public void setEvent(String event) { this.event = event; }

    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public Long getGithubId() { return githubId; }
    public void setGithubId(Long githubId) { this.githubId = githubId; }

    public String getLocalEventKey() { return localEventKey; }
    public void setLocalEventKey(String localEventKey) { this.localEventKey = localEventKey; }

    public PrTimelinePayload getPayload() { return payload; }
    public void setPayload(PrTimelinePayload payload) { this.payload = payload; }
}

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
package com.bytequay.persistence.sqlite;

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
class PrTimelineEntity
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long prId;

    private String event;

    private String actor;

    @Convert(converter = InstantToTextConverter.class)
    private Instant timestamp;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(name = "github_id")
    private Long githubId;

    protected PrTimelineEntity() {}

    Long getId() { return id; }

    Long getPrId() { return prId; }
    void setPrId(Long prId) { this.prId = prId; }

    String getEvent() { return event; }
    void setEvent(String event) { this.event = event; }

    String getActor() { return actor; }
    void setActor(String actor) { this.actor = actor; }

    Instant getTimestamp() { return timestamp; }
    void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    String getBody() { return body; }
    void setBody(String body) { this.body = body; }

    Long getGithubId() { return githubId; }
    void setGithubId(Long githubId) { this.githubId = githubId; }
}

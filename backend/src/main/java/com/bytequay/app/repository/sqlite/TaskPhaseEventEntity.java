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

/** JPA row for the {@code task_phase_event} phase-transition audit log. */
@Entity
@Table(name = "task_phase_event")
class TaskPhaseEventEntity
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "task_id", nullable = false)
    private String taskId;

    @Column(name = "from_phase")
    private String fromPhase;

    @Column(name = "to_phase", nullable = false)
    private String toPhase;

    @Column(name = "transitioned_at_ms", nullable = false)
    private long transitionedAtMs;

    @Column(name = "reason")
    private String reason;

    @Column(name = "actor")
    private String actor;

    Long getId() { return id; }
    void setId(Long id) { this.id = id; }

    String getTaskId() { return taskId; }
    void setTaskId(String taskId) { this.taskId = taskId; }

    String getFromPhase() { return fromPhase; }
    void setFromPhase(String fromPhase) { this.fromPhase = fromPhase; }

    String getToPhase() { return toPhase; }
    void setToPhase(String toPhase) { this.toPhase = toPhase; }

    long getTransitionedAtMs() { return transitionedAtMs; }
    void setTransitionedAtMs(long transitionedAtMs) { this.transitionedAtMs = transitionedAtMs; }

    String getReason() { return reason; }
    void setReason(String reason) { this.reason = reason; }

    String getActor() { return actor; }
    void setActor(String actor) { this.actor = actor; }
}

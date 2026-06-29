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

/**
 * A row in {@code stage_messages} — one work stage's transcript, keyed by a
 * per-stage {@code seq} so concurrent per-stage agents never collide on a
 * thread-global sequence. Mirrors {@link ThreadMessageEntity}'s columns minus
 * {@code scope} (every row here is STAGE-scoped by construction).
 */
@Entity
@Table(name = "stage_messages")
class StageMessageEntity
{
    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "stage_id", nullable = false)
    private String stageId;

    @Column(name = "task_id", nullable = false)
    private String taskId;

    @Column(name = "thread_id", nullable = false)
    private String threadId;

    @Column(name = "seq", nullable = false)
    private long seq;

    @Column(name = "role", nullable = false)
    private String role;

    @Column(name = "type", nullable = false)
    private String type;

    @Column(name = "content_json", nullable = false)
    private String contentJson;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "tokens_in")
    private Long tokensIn;

    @Column(name = "tokens_out")
    private Long tokensOut;

    @Column(name = "cost_usd_milli")
    private Long costUsdMilli;

    @Column(name = "ts_ms", nullable = false)
    private long tsMs;

    String getId() { return id; }
    void setId(String id) { this.id = id; }

    String getStageId() { return stageId; }
    void setStageId(String stageId) { this.stageId = stageId; }

    String getTaskId() { return taskId; }
    void setTaskId(String taskId) { this.taskId = taskId; }

    String getThreadId() { return threadId; }
    void setThreadId(String threadId) { this.threadId = threadId; }

    long getSeq() { return seq; }
    void setSeq(long seq) { this.seq = seq; }

    String getRole() { return role; }
    void setRole(String role) { this.role = role; }

    String getType() { return type; }
    void setType(String type) { this.type = type; }

    String getContentJson() { return contentJson; }
    void setContentJson(String contentJson) { this.contentJson = contentJson; }

    Long getDurationMs() { return durationMs; }
    void setDurationMs(Long durationMs) { this.durationMs = durationMs; }

    Long getTokensIn() { return tokensIn; }
    void setTokensIn(Long tokensIn) { this.tokensIn = tokensIn; }

    Long getTokensOut() { return tokensOut; }
    void setTokensOut(Long tokensOut) { this.tokensOut = tokensOut; }

    Long getCostUsdMilli() { return costUsdMilli; }
    void setCostUsdMilli(Long costUsdMilli) { this.costUsdMilli = costUsdMilli; }

    long getTsMs() { return tsMs; }
    void setTsMs(long tsMs) { this.tsMs = tsMs; }
}

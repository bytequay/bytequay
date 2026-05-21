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
@Table(name = "thread_checkpoints")
class ThreadCheckpointEntity
{
    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "thread_id", nullable = false)
    private String threadId;

    @Column(name = "seq", nullable = false)
    private long seq;

    /** 0 = false, 1 = true. SQLite has no native boolean and we keep
     *  the column INTEGER so the migration DDL is a 1:1 match with the
     *  doc. The store layer translates to/from {@code boolean}. */
    @Column(name = "is_overall", nullable = false)
    private int isOverall;

    @Column(name = "first_msg_seq", nullable = false)
    private long firstMsgSeq;

    @Column(name = "last_msg_seq", nullable = false)
    private long lastMsgSeq;

    @Column(name = "tokens_covered", nullable = false)
    private long tokensCovered;

    @Column(name = "summary_md", nullable = false)
    private String summaryMd;

    /** JSON array of 1-3 bullet strings — stored raw, parsed by the
     *  store. Default '[]' makes legacy rows safe to read. */
    @Column(name = "bullet_titles", nullable = false)
    private String bulletTitles;

    @Column(name = "model_used", nullable = false)
    private String modelUsed;

    @Column(name = "prompt_tokens", nullable = false)
    private long promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private long completionTokens;

    @Column(name = "cost_usd_milli", nullable = false)
    private long costUsdMilli;

    @Column(name = "generated_at_ms", nullable = false)
    private long generatedAtMs;

    /** Nullable — set when a newer Overall supersedes this one. */
    @Column(name = "superseded_at_ms")
    private Long supersededAtMs;

    String getId() { return id; }
    void setId(String id) { this.id = id; }

    String getTaskId() { return threadId; }
    void setTaskId(String threadId) { this.threadId = threadId; }

    long getSeq() { return seq; }
    void setSeq(long seq) { this.seq = seq; }

    int getIsOverall() { return isOverall; }
    void setIsOverall(int isOverall) { this.isOverall = isOverall; }

    long getFirstMsgSeq() { return firstMsgSeq; }
    void setFirstMsgSeq(long firstMsgSeq) { this.firstMsgSeq = firstMsgSeq; }

    long getLastMsgSeq() { return lastMsgSeq; }
    void setLastMsgSeq(long lastMsgSeq) { this.lastMsgSeq = lastMsgSeq; }

    long getTokensCovered() { return tokensCovered; }
    void setTokensCovered(long tokensCovered) { this.tokensCovered = tokensCovered; }

    String getSummaryMd() { return summaryMd; }
    void setSummaryMd(String summaryMd) { this.summaryMd = summaryMd; }

    String getBulletTitles() { return bulletTitles; }
    void setBulletTitles(String bulletTitles) { this.bulletTitles = bulletTitles; }

    String getModelUsed() { return modelUsed; }
    void setModelUsed(String modelUsed) { this.modelUsed = modelUsed; }

    long getPromptTokens() { return promptTokens; }
    void setPromptTokens(long promptTokens) { this.promptTokens = promptTokens; }

    long getCompletionTokens() { return completionTokens; }
    void setCompletionTokens(long completionTokens) { this.completionTokens = completionTokens; }

    long getCostUsdMilli() { return costUsdMilli; }
    void setCostUsdMilli(long costUsdMilli) { this.costUsdMilli = costUsdMilli; }

    long getGeneratedAtMs() { return generatedAtMs; }
    void setGeneratedAtMs(long generatedAtMs) { this.generatedAtMs = generatedAtMs; }

    Long getSupersededAtMs() { return supersededAtMs; }
    void setSupersededAtMs(Long supersededAtMs) { this.supersededAtMs = supersededAtMs; }
}

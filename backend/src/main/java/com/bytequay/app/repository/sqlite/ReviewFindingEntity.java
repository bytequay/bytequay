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
@Table(name = "review_findings")
class ReviewFindingEntity
{
    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "review_pass_id", nullable = false)
    private String reviewPassId;

    @Column(name = "path")
    private String path;

    @Column(name = "line")
    private Integer line;

    @Column(name = "severity", nullable = false)
    private String severity;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "body", nullable = false)
    private String body;

    @Column(name = "resolution")
    private String resolution;

    @Column(name = "posted_comment_id")
    private String postedCommentId;

    @Column(name = "created_at_ms", nullable = false)
    private long createdAtMs;

    @Column(name = "debate_status")
    private String debateStatus;

    @Column(name = "debate_rounds", nullable = false)
    private int debateRounds;

    String getId() { return id; }
    void setId(String id) { this.id = id; }

    String getReviewPassId() { return reviewPassId; }
    void setReviewPassId(String reviewPassId) { this.reviewPassId = reviewPassId; }

    String getPath() { return path; }
    void setPath(String path) { this.path = path; }

    Integer getLine() { return line; }
    void setLine(Integer line) { this.line = line; }

    String getSeverity() { return severity; }
    void setSeverity(String severity) { this.severity = severity; }

    String getStatus() { return status; }
    void setStatus(String status) { this.status = status; }

    String getBody() { return body; }
    void setBody(String body) { this.body = body; }

    String getResolution() { return resolution; }
    void setResolution(String resolution) { this.resolution = resolution; }

    String getPostedCommentId() { return postedCommentId; }
    void setPostedCommentId(String postedCommentId) { this.postedCommentId = postedCommentId; }

    long getCreatedAtMs() { return createdAtMs; }
    void setCreatedAtMs(long createdAtMs) { this.createdAtMs = createdAtMs; }

    String getDebateStatus() { return debateStatus; }
    void setDebateStatus(String debateStatus) { this.debateStatus = debateStatus; }

    int getDebateRounds() { return debateRounds; }
    void setDebateRounds(int debateRounds) { this.debateRounds = debateRounds; }
}

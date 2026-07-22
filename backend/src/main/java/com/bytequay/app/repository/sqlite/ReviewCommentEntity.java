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

/** JPA row for a unified {@code review_comment}. The check constraint that
 *  ties {@code remote_link} to a {@code REMOTE_REVIEWER} source lives in
 *  the schema, not here. */
@Entity
@Table(name = "review_comment")
class ReviewCommentEntity
{
    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "task_id", nullable = false)
    private String taskId;

    @Column(name = "file", nullable = false)
    private String file;

    @Column(name = "line", nullable = false)
    private int line;

    @Column(name = "side", nullable = false)
    private String side = "RIGHT";

    @Column(name = "start_line")
    private Integer startLine;

    @Column(name = "start_side")
    private String startSide;

    @Column(name = "body", nullable = false)
    private String body;

    @Column(name = "created_at_ms", nullable = false)
    private long createdAtMs;

    @Column(name = "source", nullable = false)
    private String source;

    @Column(name = "remote_link")
    private String remoteLink;

    @Column(name = "resolved", nullable = false)
    private boolean resolved;

    @Column(name = "remote_comment_id")
    private Long remoteCommentId;

    @Column(name = "round_id")
    private String roundId;

    @Column(name = "draft_reply_body")
    private String draftReplyBody;

    @Column(name = "draft_reply_created_at_ms")
    private Long draftReplyCreatedAtMs;

    @Column(name = "draft_reply_posted_at_ms")
    private Long draftReplyPostedAtMs;

    @Column(name = "remote_thread_resolved_at_ms")
    private Long remoteThreadResolvedAtMs;

    String getId() { return id; }
    void setId(String id) { this.id = id; }

    String getTaskId() { return taskId; }
    void setTaskId(String taskId) { this.taskId = taskId; }

    String getFile() { return file; }
    void setFile(String file) { this.file = file; }

    int getLine() { return line; }
    void setLine(int line) { this.line = line; }

    String getSide() { return side; }
    void setSide(String side) { this.side = side; }

    Integer getStartLine() { return startLine; }
    void setStartLine(Integer startLine) { this.startLine = startLine; }

    String getStartSide() { return startSide; }
    void setStartSide(String startSide) { this.startSide = startSide; }

    String getBody() { return body; }
    void setBody(String body) { this.body = body; }

    long getCreatedAtMs() { return createdAtMs; }
    void setCreatedAtMs(long createdAtMs) { this.createdAtMs = createdAtMs; }

    String getSource() { return source; }
    void setSource(String source) { this.source = source; }

    String getRemoteLink() { return remoteLink; }
    void setRemoteLink(String remoteLink) { this.remoteLink = remoteLink; }

    boolean isResolved() { return resolved; }
    void setResolved(boolean resolved) { this.resolved = resolved; }

    Long getRemoteCommentId() { return remoteCommentId; }
    void setRemoteCommentId(Long remoteCommentId) { this.remoteCommentId = remoteCommentId; }

    String getRoundId() { return roundId; }
    void setRoundId(String roundId) { this.roundId = roundId; }

    String getDraftReplyBody() { return draftReplyBody; }
    void setDraftReplyBody(String draftReplyBody) { this.draftReplyBody = draftReplyBody; }

    Long getDraftReplyCreatedAtMs() { return draftReplyCreatedAtMs; }
    void setDraftReplyCreatedAtMs(Long draftReplyCreatedAtMs) { this.draftReplyCreatedAtMs = draftReplyCreatedAtMs; }

    Long getDraftReplyPostedAtMs() { return draftReplyPostedAtMs; }
    void setDraftReplyPostedAtMs(Long draftReplyPostedAtMs) { this.draftReplyPostedAtMs = draftReplyPostedAtMs; }

    Long getRemoteThreadResolvedAtMs() { return remoteThreadResolvedAtMs; }
    void setRemoteThreadResolvedAtMs(Long remoteThreadResolvedAtMs) { this.remoteThreadResolvedAtMs = remoteThreadResolvedAtMs; }
}

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

/** JPA row for a {@code pr_comment}. */
@Entity
@Table(name = "pr_comment")
class PrCommentEntity
{
    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "pr_id", nullable = false)
    private String prId;

    @Column(name = "origin", nullable = false)
    private String origin;

    @Column(name = "scope", nullable = false)
    private String scope;

    @Column(name = "file_path")
    private String filePath;

    @Column(name = "line_number")
    private Integer lineNumber;

    @Column(name = "side", nullable = false)
    private String side = "RIGHT";

    @Column(name = "start_line")
    private Integer startLine;

    @Column(name = "start_side")
    private String startSide;

    @Column(name = "author", nullable = false)
    private String author;

    @Column(name = "body", nullable = false)
    private String body;

    @Column(name = "created_at_ms", nullable = false)
    private long createdAtMs;

    @Column(name = "resolved_at_ms")
    private Long resolvedAtMs;

    @Column(name = "dismissed_at_ms")
    private Long dismissedAtMs;

    @Column(name = "stripped_on_push_at_ms")
    private Long strippedOnPushAtMs;

    @Column(name = "parent_comment_id")
    private String parentCommentId;

    @Column(name = "published_at_ms")
    private Long publishedAtMs;

    @Column(name = "finding_id")
    private String findingId;

    String getId() { return id; }
    void setId(String id) { this.id = id; }

    String getPrId() { return prId; }
    void setPrId(String prId) { this.prId = prId; }

    String getOrigin() { return origin; }
    void setOrigin(String origin) { this.origin = origin; }

    String getScope() { return scope; }
    void setScope(String scope) { this.scope = scope; }

    String getFilePath() { return filePath; }
    void setFilePath(String filePath) { this.filePath = filePath; }

    Integer getLineNumber() { return lineNumber; }
    void setLineNumber(Integer lineNumber) { this.lineNumber = lineNumber; }

    String getSide() { return side; }
    void setSide(String side) { this.side = side; }

    Integer getStartLine() { return startLine; }
    void setStartLine(Integer startLine) { this.startLine = startLine; }

    String getStartSide() { return startSide; }
    void setStartSide(String startSide) { this.startSide = startSide; }

    String getAuthor() { return author; }
    void setAuthor(String author) { this.author = author; }

    String getBody() { return body; }
    void setBody(String body) { this.body = body; }

    long getCreatedAtMs() { return createdAtMs; }
    void setCreatedAtMs(long createdAtMs) { this.createdAtMs = createdAtMs; }

    Long getResolvedAtMs() { return resolvedAtMs; }
    void setResolvedAtMs(Long resolvedAtMs) { this.resolvedAtMs = resolvedAtMs; }

    Long getDismissedAtMs() { return dismissedAtMs; }
    void setDismissedAtMs(Long dismissedAtMs) { this.dismissedAtMs = dismissedAtMs; }

    Long getStrippedOnPushAtMs() { return strippedOnPushAtMs; }
    void setStrippedOnPushAtMs(Long strippedOnPushAtMs) { this.strippedOnPushAtMs = strippedOnPushAtMs; }

    String getParentCommentId() { return parentCommentId; }
    void setParentCommentId(String parentCommentId) { this.parentCommentId = parentCommentId; }

    Long getPublishedAtMs() { return publishedAtMs; }
    void setPublishedAtMs(Long publishedAtMs) { this.publishedAtMs = publishedAtMs; }

    String getFindingId() { return findingId; }
    void setFindingId(String findingId) { this.findingId = findingId; }
}

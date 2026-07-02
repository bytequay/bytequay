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

/** JPA row for a {@code local_pr_comment}. */
@Entity
@Table(name = "local_pr_comment")
class LocalPrCommentEntity
{
    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "local_pr_id", nullable = false)
    private String localPrId;

    @Column(name = "origin", nullable = false)
    private String origin;

    @Column(name = "scope", nullable = false)
    private String scope;

    @Column(name = "file_path")
    private String filePath;

    @Column(name = "line_number")
    private Integer lineNumber;

    @Column(name = "author", nullable = false)
    private String author;

    @Column(name = "body", nullable = false)
    private String body;

    @Column(name = "created_at_ms", nullable = false)
    private long createdAtMs;

    @Column(name = "resolved_at_ms")
    private Long resolvedAtMs;

    @Column(name = "stripped_on_push_at_ms")
    private Long strippedOnPushAtMs;

    @Column(name = "parent_comment_id")
    private String parentCommentId;

    String getId() { return id; }
    void setId(String id) { this.id = id; }

    String getLocalPrId() { return localPrId; }
    void setLocalPrId(String localPrId) { this.localPrId = localPrId; }

    String getOrigin() { return origin; }
    void setOrigin(String origin) { this.origin = origin; }

    String getScope() { return scope; }
    void setScope(String scope) { this.scope = scope; }

    String getFilePath() { return filePath; }
    void setFilePath(String filePath) { this.filePath = filePath; }

    Integer getLineNumber() { return lineNumber; }
    void setLineNumber(Integer lineNumber) { this.lineNumber = lineNumber; }

    String getAuthor() { return author; }
    void setAuthor(String author) { this.author = author; }

    String getBody() { return body; }
    void setBody(String body) { this.body = body; }

    long getCreatedAtMs() { return createdAtMs; }
    void setCreatedAtMs(long createdAtMs) { this.createdAtMs = createdAtMs; }

    Long getResolvedAtMs() { return resolvedAtMs; }
    void setResolvedAtMs(Long resolvedAtMs) { this.resolvedAtMs = resolvedAtMs; }

    Long getStrippedOnPushAtMs() { return strippedOnPushAtMs; }
    void setStrippedOnPushAtMs(Long strippedOnPushAtMs) { this.strippedOnPushAtMs = strippedOnPushAtMs; }

    String getParentCommentId() { return parentCommentId; }
    void setParentCommentId(String parentCommentId) { this.parentCommentId = parentCommentId; }
}

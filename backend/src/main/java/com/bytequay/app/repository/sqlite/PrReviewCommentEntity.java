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
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "pr_review_comment")
class PrReviewCommentEntity
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "draft_id", nullable = false)
    private long draftId;

    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    @Column(nullable = false)
    private String body;

    /** When non-null, replaces {@link #body} on publish. The original is
     *  preserved as the "before" reference per the V2 mockup. */
    @Column(name = "edited_body")
    private String editedBody;

    @Column(nullable = false)
    private String severity;

    /** Soft-delete: dismissed comments are kept on the row (so the user
     *  can restore them) but excluded from the publish payload and dimmed
     *  in the UI. */
    @Column(nullable = false)
    private boolean dismissed;

    /** Origin of the comment — {@code AI} for AI-drafted, {@code HUMAN} for
     *  user-authored inline comments staged into the unified review draft.
     *  Defaults to AI for backwards compat with rows written before V28. */
    @Column(nullable = false)
    private String source = "AI";

    /** Diff side — {@code LEFT} (deleted) or {@code RIGHT} (added). AI
     *  comments always target RIGHT; human comments may target either. */
    @Column(nullable = false)
    private String side = "RIGHT";

    /** First line of a multi-line range comment. Null for single-line
     *  comments. */
    @Column(name = "start_line")
    private Integer startLine;

    /** Diff side of {@link #startLine}. Null for single-line comments. */
    @Column(name = "start_side")
    private String startSide;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant createdAt;

    protected PrReviewCommentEntity() {}

    @PrePersist
    void prePersist()
    {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }

    Long getId() { return id; }

    long getDraftId() { return draftId; }
    void setDraftId(long draftId) { this.draftId = draftId; }

    String getFilePath() { return filePath; }
    void setFilePath(String filePath) { this.filePath = filePath; }

    int getLineNumber() { return lineNumber; }
    void setLineNumber(int lineNumber) { this.lineNumber = lineNumber; }

    String getBody() { return body; }
    void setBody(String body) { this.body = body; }

    String getEditedBody() { return editedBody; }
    void setEditedBody(String editedBody) { this.editedBody = editedBody; }

    String getSeverity() { return severity; }
    void setSeverity(String severity) { this.severity = severity; }

    boolean isDismissed() { return dismissed; }
    void setDismissed(boolean dismissed) { this.dismissed = dismissed; }

    String getSource() { return source; }
    void setSource(String source) { this.source = source; }

    String getSide() { return side; }
    void setSide(String side) { this.side = side; }

    Integer getStartLine() { return startLine; }
    void setStartLine(Integer startLine) { this.startLine = startLine; }

    String getStartSide() { return startSide; }
    void setStartSide(String startSide) { this.startSide = startSide; }

    Instant getCreatedAt() { return createdAt; }
}

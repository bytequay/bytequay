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
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "pr_review_thread_message")
class PrReviewThreadMessageEntity
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pr_id", nullable = false)
    private long prId;

    @Column(name = "github_id", nullable = false)
    private long githubId;

    @Column(name = "in_reply_to")
    private Long inReplyTo;

    @Column(name = "review_id")
    private Long reviewId;

    @Column
    private String author;

    @Column
    private String body;

    @Column(name = "file_path")
    private String filePath;

    @Column(name = "line_number")
    private Integer lineNumber;

    @Column
    private String side;

    /** First line of a multi-line comment (V27). Null for single-line. */
    @Column(name = "start_line")
    private Integer startLine;

    /** Side of {@link #startLine} ("LEFT"/"RIGHT"); usually matches side. */
    @Column(name = "start_side")
    private String startSide;

    /** Original line numbers — the file-side coordinates that match
     *  {@link #diffHunk}. After the file is edited post-comment, line /
     *  startLine shift while these stay anchored to whatever GitHub
     *  recorded when the comment landed. The frontend uses these to
     *  slice the hunk to the commented range (V38). */
    @Column(name = "original_line")
    private Integer originalLine;

    @Column(name = "original_start_line")
    private Integer originalStartLine;

    @Column(name = "diff_hunk")
    private String diffHunk;

    @Column(name = "commit_id")
    private String commitId;

    @Column(name = "created_at")
    @Convert(converter = InstantToTextConverter.class)
    private Instant createdAt;

    @Column(name = "reactions_plus_one", nullable = false)
    private int reactionsPlusOne;

    @Column(name = "reactions_minus_one", nullable = false)
    private int reactionsMinusOne;

    @Column(name = "reactions_laugh", nullable = false)
    private int reactionsLaugh;

    @Column(name = "reactions_hooray", nullable = false)
    private int reactionsHooray;

    @Column(name = "reactions_confused", nullable = false)
    private int reactionsConfused;

    @Column(name = "reactions_heart", nullable = false)
    private int reactionsHeart;

    @Column(name = "reactions_rocket", nullable = false)
    private int reactionsRocket;

    @Column(name = "reactions_eyes", nullable = false)
    private int reactionsEyes;

    @Column(nullable = false)
    private boolean outdated;

    /** GitHub author_association — see V30 migration. Null for legacy
     *  rows persisted before the column existed. */
    @Column(name = "author_association")
    private String authorAssociation;

    /** GraphQL node id for the parent thread (V31). Stored on the
     *  thread root only; required by the resolve / unresolve
     *  mutations. */
    @Column(name = "graphql_node_id")
    private String graphqlNodeId;

    /** True iff the parent thread is resolved on GitHub (V31). Null
     *  for legacy rows or threads we haven't GraphQL-fetched yet. */
    @Column(name = "resolved")
    private Boolean resolved;

    /** Login of whoever resolved the parent thread on GitHub. Null when
     *  unresolved or only a REST pass has populated the row. */
    @Column(name = "resolved_by")
    private String resolvedBy;

    protected PrReviewThreadMessageEntity() {}

    Long getId() { return id; }

    long getPrId() { return prId; }
    void setPrId(long prId) { this.prId = prId; }

    long getGithubId() { return githubId; }
    void setGithubId(long githubId) { this.githubId = githubId; }

    Long getInReplyTo() { return inReplyTo; }
    void setInReplyTo(Long inReplyTo) { this.inReplyTo = inReplyTo; }

    Long getReviewId() { return reviewId; }
    void setReviewId(Long reviewId) { this.reviewId = reviewId; }

    String getAuthor() { return author; }
    void setAuthor(String author) { this.author = author; }

    String getBody() { return body; }
    void setBody(String body) { this.body = body; }

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

    Integer getOriginalLine() { return originalLine; }
    void setOriginalLine(Integer originalLine) { this.originalLine = originalLine; }

    Integer getOriginalStartLine() { return originalStartLine; }
    void setOriginalStartLine(Integer originalStartLine) { this.originalStartLine = originalStartLine; }

    String getDiffHunk() { return diffHunk; }
    void setDiffHunk(String diffHunk) { this.diffHunk = diffHunk; }

    String getCommitId() { return commitId; }
    void setCommitId(String commitId) { this.commitId = commitId; }

    Instant getCreatedAt() { return createdAt; }
    void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    int getReactionsPlusOne() { return reactionsPlusOne; }
    void setReactionsPlusOne(int reactionsPlusOne) { this.reactionsPlusOne = reactionsPlusOne; }

    int getReactionsMinusOne() { return reactionsMinusOne; }
    void setReactionsMinusOne(int reactionsMinusOne) { this.reactionsMinusOne = reactionsMinusOne; }

    int getReactionsLaugh() { return reactionsLaugh; }
    void setReactionsLaugh(int reactionsLaugh) { this.reactionsLaugh = reactionsLaugh; }

    int getReactionsHooray() { return reactionsHooray; }
    void setReactionsHooray(int reactionsHooray) { this.reactionsHooray = reactionsHooray; }

    int getReactionsConfused() { return reactionsConfused; }
    void setReactionsConfused(int reactionsConfused) { this.reactionsConfused = reactionsConfused; }

    int getReactionsHeart() { return reactionsHeart; }
    void setReactionsHeart(int reactionsHeart) { this.reactionsHeart = reactionsHeart; }

    int getReactionsRocket() { return reactionsRocket; }
    void setReactionsRocket(int reactionsRocket) { this.reactionsRocket = reactionsRocket; }

    int getReactionsEyes() { return reactionsEyes; }
    void setReactionsEyes(int reactionsEyes) { this.reactionsEyes = reactionsEyes; }

    boolean isOutdated() { return outdated; }
    void setOutdated(boolean outdated) { this.outdated = outdated; }

    String getAuthorAssociation() { return authorAssociation; }
    void setAuthorAssociation(String authorAssociation) { this.authorAssociation = authorAssociation; }

    String getGraphqlNodeId() { return graphqlNodeId; }
    void setGraphqlNodeId(String graphqlNodeId) { this.graphqlNodeId = graphqlNodeId; }

    Boolean getResolved() { return resolved; }
    void setResolved(Boolean resolved) { this.resolved = resolved; }

    String getResolvedBy() { return resolvedBy; }
    void setResolvedBy(String resolvedBy) { this.resolvedBy = resolvedBy; }
}

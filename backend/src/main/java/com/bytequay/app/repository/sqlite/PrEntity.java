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
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.List;
import java.util.Map;

/** JPA row for a {@code pr}. */
@Entity
@Table(name = "pr")
class PrEntity
{
    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "task_id")
    private String taskId;

    @Column(name = "branch_name", nullable = false)
    private String branchName;

    @Column(name = "base_branch", nullable = false)
    private String baseBranch;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "created_at_ms", nullable = false)
    private long createdAtMs;

    @Column(name = "pushed_at_ms")
    private Long pushedAtMs;

    @Column(name = "remote_pr_number")
    private Integer remotePrNumber;

    @Column(name = "remote_pr_url")
    private String remotePrUrl;

    @Column(name = "merged_at_ms")
    private Long mergedAtMs;

    @Column(name = "closed_at_ms")
    private Long closedAtMs;

    @Column(name = "local_addressed_through_ms")
    private Long localAddressedThroughMs;

    @Column(name = "origin", nullable = false)
    private String origin;

    @Column(name = "repo")
    private String repo;

    @Column(name = "author")
    private String author;

    @Column(name = "synced_at_ms")
    private Long syncedAtMs;

    @Column(name = "watch_reason")
    private String watchReason;

    @Column(name = "gh_updated_at_ms")
    private Long ghUpdatedAtMs;

    @Column(name = "labels", nullable = false)
    @Convert(converter = StringListConverter.class)
    private List<String> labels = List.of();

    @Column(name = "label_colors")
    @Convert(converter = StringMapConverter.class)
    private Map<String, String> labelColors;

    @Column(name = "draft", nullable = false)
    private boolean draft;

    @Column(name = "ci_status")
    private String ciStatus;

    @Column(name = "additions", nullable = false)
    private int additions;

    @Column(name = "deletions", nullable = false)
    private int deletions;

    @Column(name = "comment_count", nullable = false)
    private int commentCount;

    @Column(name = "attention_reason")
    private String attentionReason;

    @Column(name = "mergeable")
    private Boolean mergeable;

    @Column(name = "mergeable_state")
    private String mergeableState;

    @Column(name = "head_pushed_at_ms")
    private Long headPushedAtMs;

    @Column(name = "reviewer_verdicts")
    @Convert(converter = StringMapConverter.class)
    private Map<String, String> reviewerVerdicts;

    @Column(name = "requested_reviewers", nullable = false)
    @Convert(converter = StringListConverter.class)
    private List<String> requestedReviewers = List.of();

    @Column(name = "merge_queue_enabled", nullable = false)
    private boolean mergeQueueEnabled;

    @Column(name = "merge_queue_state")
    private String mergeQueueState;

    @Column(name = "branch_deleted_at_ms")
    private Long branchDeletedAtMs;

    String getId() { return id; }
    void setId(String id) { this.id = id; }

    String getTaskId() { return taskId; }
    void setTaskId(String taskId) { this.taskId = taskId; }

    String getBranchName() { return branchName; }
    void setBranchName(String branchName) { this.branchName = branchName; }

    String getBaseBranch() { return baseBranch; }
    void setBaseBranch(String baseBranch) { this.baseBranch = baseBranch; }

    String getTitle() { return title; }
    void setTitle(String title) { this.title = title; }

    String getDescription() { return description; }
    void setDescription(String description) { this.description = description; }

    String getStatus() { return status; }
    void setStatus(String status) { this.status = status; }

    long getCreatedAtMs() { return createdAtMs; }
    void setCreatedAtMs(long createdAtMs) { this.createdAtMs = createdAtMs; }

    Long getPushedAtMs() { return pushedAtMs; }
    void setPushedAtMs(Long pushedAtMs) { this.pushedAtMs = pushedAtMs; }

    Integer getRemotePrNumber() { return remotePrNumber; }
    void setRemotePrNumber(Integer remotePrNumber) { this.remotePrNumber = remotePrNumber; }

    String getRemotePrUrl() { return remotePrUrl; }
    void setRemotePrUrl(String remotePrUrl) { this.remotePrUrl = remotePrUrl; }

    Long getMergedAtMs() { return mergedAtMs; }
    void setMergedAtMs(Long mergedAtMs) { this.mergedAtMs = mergedAtMs; }

    Long getClosedAtMs() { return closedAtMs; }
    void setClosedAtMs(Long closedAtMs) { this.closedAtMs = closedAtMs; }

    Long getLocalAddressedThroughMs() { return localAddressedThroughMs; }
    void setLocalAddressedThroughMs(Long localAddressedThroughMs) { this.localAddressedThroughMs = localAddressedThroughMs; }

    String getOrigin() { return origin; }
    void setOrigin(String origin) { this.origin = origin; }

    String getRepo() { return repo; }
    void setRepo(String repo) { this.repo = repo; }

    String getAuthor() { return author; }
    void setAuthor(String author) { this.author = author; }

    Long getSyncedAtMs() { return syncedAtMs; }
    void setSyncedAtMs(Long syncedAtMs) { this.syncedAtMs = syncedAtMs; }

    String getWatchReason() { return watchReason; }
    void setWatchReason(String watchReason) { this.watchReason = watchReason; }

    Long getGhUpdatedAtMs() { return ghUpdatedAtMs; }
    void setGhUpdatedAtMs(Long ghUpdatedAtMs) { this.ghUpdatedAtMs = ghUpdatedAtMs; }

    List<String> getLabels() { return labels; }
    void setLabels(List<String> labels) { this.labels = labels; }

    Map<String, String> getLabelColors() { return labelColors; }
    void setLabelColors(Map<String, String> labelColors) { this.labelColors = labelColors; }

    boolean isDraft() { return draft; }
    void setDraft(boolean draft) { this.draft = draft; }

    String getCiStatus() { return ciStatus; }
    void setCiStatus(String ciStatus) { this.ciStatus = ciStatus; }

    int getAdditions() { return additions; }
    void setAdditions(int additions) { this.additions = additions; }

    int getDeletions() { return deletions; }
    void setDeletions(int deletions) { this.deletions = deletions; }

    int getCommentCount() { return commentCount; }
    void setCommentCount(int commentCount) { this.commentCount = commentCount; }

    String getAttentionReason() { return attentionReason; }
    void setAttentionReason(String attentionReason) { this.attentionReason = attentionReason; }

    Boolean getMergeable() { return mergeable; }
    void setMergeable(Boolean mergeable) { this.mergeable = mergeable; }

    String getMergeableState() { return mergeableState; }
    void setMergeableState(String mergeableState) { this.mergeableState = mergeableState; }

    Long getHeadPushedAtMs() { return headPushedAtMs; }
    void setHeadPushedAtMs(Long headPushedAtMs) { this.headPushedAtMs = headPushedAtMs; }

    Map<String, String> getReviewerVerdicts() { return reviewerVerdicts; }
    void setReviewerVerdicts(Map<String, String> reviewerVerdicts) { this.reviewerVerdicts = reviewerVerdicts; }

    List<String> getRequestedReviewers() { return requestedReviewers; }
    void setRequestedReviewers(List<String> requestedReviewers) { this.requestedReviewers = requestedReviewers; }

    boolean isMergeQueueEnabled() { return mergeQueueEnabled; }
    void setMergeQueueEnabled(boolean mergeQueueEnabled) { this.mergeQueueEnabled = mergeQueueEnabled; }

    String getMergeQueueState() { return mergeQueueState; }
    void setMergeQueueState(String mergeQueueState) { this.mergeQueueState = mergeQueueState; }

    Long getBranchDeletedAtMs() { return branchDeletedAtMs; }
    void setBranchDeletedAtMs(Long branchDeletedAtMs) { this.branchDeletedAtMs = branchDeletedAtMs; }
}

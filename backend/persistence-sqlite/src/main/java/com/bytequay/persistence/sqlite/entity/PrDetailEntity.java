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
package com.bytequay.persistence.sqlite.entity;

import com.bytequay.persistence.sqlite.converter.InstantToTextConverter;
import com.bytequay.persistence.sqlite.converter.StringListConverter;
import com.bytequay.persistence.sqlite.converter.StringMapConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "pr_detail")
public class PrDetailEntity
{
    @Id
    private Long prId;

    private String title;

    private String htmlUrl;

    private String state;

    private String author;

    private String authorAvatarUrl;

    @Convert(converter = InstantToTextConverter.class)
    private Instant createdAt;

    @Convert(converter = InstantToTextConverter.class)
    private Instant updatedAt;

    @Convert(converter = InstantToTextConverter.class)
    private Instant mergedAt;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(nullable = false)
    @Convert(converter = StringListConverter.class)
    private List<String> labels;

    @Convert(converter = StringMapConverter.class)
    private Map<String, String> labelColors;

    @Convert(converter = StringListConverter.class)
    private List<String> assignees;

    private String milestone;

    @Convert(converter = StringListConverter.class)
    private List<String> unavailableSections;

    @Column(nullable = false)
    private boolean draft;

    private Boolean mergeable;

    private String mergeableState;

    @Column(nullable = false)
    private int additions;

    @Column(nullable = false)
    private int deletions;

    @Column(nullable = false)
    private int changedFiles;

    @Column(nullable = false)
    private int requestedReviewerCount;

    @Column(name = "requested_reviewers")
    @Convert(converter = StringListConverter.class)
    private List<String> requestedReviewers;

    private String headSha;

    @Column(name = "head_ref")
    private String headRef;

    @Column(name = "base_ref")
    private String baseRef;

    @Column(nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant syncedAt;

    public PrDetailEntity() {}

    public Long getPrId() { return prId; }
    public void setPrId(Long prId) { this.prId = prId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getHtmlUrl() { return htmlUrl; }
    public void setHtmlUrl(String htmlUrl) { this.htmlUrl = htmlUrl; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public String getAuthorAvatarUrl() { return authorAvatarUrl; }
    public void setAuthorAvatarUrl(String authorAvatarUrl) { this.authorAvatarUrl = authorAvatarUrl; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Instant getMergedAt() { return mergedAt; }
    public void setMergedAt(Instant mergedAt) { this.mergedAt = mergedAt; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public List<String> getLabels() { return labels; }
    public void setLabels(List<String> labels) { this.labels = labels; }

    public Map<String, String> getLabelColors() { return labelColors; }
    public void setLabelColors(Map<String, String> labelColors) { this.labelColors = labelColors; }

    public List<String> getAssignees() { return assignees; }
    public void setAssignees(List<String> assignees) { this.assignees = assignees; }

    public String getMilestone() { return milestone; }
    public void setMilestone(String milestone) { this.milestone = milestone; }

    public List<String> getUnavailableSections() { return unavailableSections; }
    public void setUnavailableSections(List<String> unavailableSections) { this.unavailableSections = unavailableSections; }

    public boolean isDraft() { return draft; }
    public void setDraft(boolean draft) { this.draft = draft; }

    public Boolean getMergeable() { return mergeable; }
    public void setMergeable(Boolean mergeable) { this.mergeable = mergeable; }

    public String getMergeableState() { return mergeableState; }
    public void setMergeableState(String mergeableState) { this.mergeableState = mergeableState; }

    public int getAdditions() { return additions; }
    public void setAdditions(int additions) { this.additions = additions; }

    public int getDeletions() { return deletions; }
    public void setDeletions(int deletions) { this.deletions = deletions; }

    public int getChangedFiles() { return changedFiles; }
    public void setChangedFiles(int changedFiles) { this.changedFiles = changedFiles; }

    public int getRequestedReviewerCount() { return requestedReviewerCount; }
    public void setRequestedReviewerCount(int requestedReviewerCount) { this.requestedReviewerCount = requestedReviewerCount; }

    public List<String> getRequestedReviewers() { return requestedReviewers; }
    public void setRequestedReviewers(List<String> requestedReviewers) { this.requestedReviewers = requestedReviewers; }

    public String getHeadSha() { return headSha; }
    public void setHeadSha(String headSha) { this.headSha = headSha; }

    public String getHeadRef() { return headRef; }
    public void setHeadRef(String headRef) { this.headRef = headRef; }

    public String getBaseRef() { return baseRef; }
    public void setBaseRef(String baseRef) { this.baseRef = baseRef; }

    public Instant getSyncedAt() { return syncedAt; }
    public void setSyncedAt(Instant syncedAt) { this.syncedAt = syncedAt; }
}

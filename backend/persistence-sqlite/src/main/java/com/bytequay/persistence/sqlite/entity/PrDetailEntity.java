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
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "pr_detail")
public class PrDetailEntity
{
    @Id
    private Long prId;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(nullable = false)
    @Convert(converter = StringListConverter.class)
    private List<String> labels;

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

    @Column(nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant syncedAt;

    public PrDetailEntity() {}

    public Long getPrId() { return prId; }
    public void setPrId(Long prId) { this.prId = prId; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public List<String> getLabels() { return labels; }
    public void setLabels(List<String> labels) { this.labels = labels; }

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

    public Instant getSyncedAt() { return syncedAt; }
    public void setSyncedAt(Instant syncedAt) { this.syncedAt = syncedAt; }
}

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

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "pr_detail")
class PrDetailEntity
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

    private String headSha;

    @Column(nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant syncedAt;

    protected PrDetailEntity() {}

    Long getPrId() { return prId; }
    void setPrId(Long prId) { this.prId = prId; }

    String getBody() { return body; }
    void setBody(String body) { this.body = body; }

    List<String> getLabels() { return labels; }
    void setLabels(List<String> labels) { this.labels = labels; }

    boolean isDraft() { return draft; }
    void setDraft(boolean draft) { this.draft = draft; }

    Boolean getMergeable() { return mergeable; }
    void setMergeable(Boolean mergeable) { this.mergeable = mergeable; }

    String getMergeableState() { return mergeableState; }
    void setMergeableState(String mergeableState) { this.mergeableState = mergeableState; }

    int getAdditions() { return additions; }
    void setAdditions(int additions) { this.additions = additions; }

    int getDeletions() { return deletions; }
    void setDeletions(int deletions) { this.deletions = deletions; }

    int getChangedFiles() { return changedFiles; }
    void setChangedFiles(int changedFiles) { this.changedFiles = changedFiles; }

    int getRequestedReviewerCount() { return requestedReviewerCount; }
    void setRequestedReviewerCount(int requestedReviewerCount) { this.requestedReviewerCount = requestedReviewerCount; }

    String getHeadSha() { return headSha; }
    void setHeadSha(String headSha) { this.headSha = headSha; }

    Instant getSyncedAt() { return syncedAt; }
    void setSyncedAt(Instant syncedAt) { this.syncedAt = syncedAt; }
}

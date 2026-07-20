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

/** JPA row for a {@code backlog_item}. {@code tags} is stored as a JSON
 *  text column via {@link StringListConverter}. */
@Entity
@Table(name = "backlog_item")
class BacklogItemEntity
{
    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "thread_id", nullable = false)
    private String threadId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "body", nullable = false)
    private String body;

    @Convert(converter = StringListConverter.class)
    @Column(name = "tags_json", nullable = false)
    private List<String> tags;

    @Column(name = "created_at_ms", nullable = false)
    private long createdAtMs;

    @Column(name = "started_at_ms")
    private Long startedAtMs;

    @Column(name = "linked_task_id")
    private String linkedTaskId;

    @Column(name = "workspace_id")
    private String workspaceId;

    @Column(name = "priority", nullable = false)
    private String priority;

    @Column(name = "source", nullable = false)
    private String source;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "origin", nullable = false, updatable = false)
    private String origin;

    @Column(name = "in_progress_at_ms")
    private Long inProgressAtMs;

    @Column(name = "resolved_at_ms")
    private Long resolvedAtMs;

    @Column(name = "rejected_at_ms")
    private Long rejectedAtMs;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Convert(converter = StringListConverter.class)
    @Column(name = "related_backlog_ids_json", nullable = false)
    private List<String> relatedBacklogIds;

    @Column(name = "item_key")
    private String itemKey;

    @Column(name = "summary", nullable = false)
    private String summary;

    @Column(name = "detail")
    private String detail;

    @Column(name = "impact_risk")
    private String impactRisk;

    @Column(name = "links_json", nullable = false)
    private String linksJson;

    String getId() { return id; }
    void setId(String id) { this.id = id; }

    String getThreadId() { return threadId; }
    void setThreadId(String threadId) { this.threadId = threadId; }

    String getTitle() { return title; }
    void setTitle(String title) { this.title = title; }

    String getBody() { return body; }
    void setBody(String body) { this.body = body; }

    List<String> getTags() { return tags; }
    void setTags(List<String> tags) { this.tags = tags; }

    long getCreatedAtMs() { return createdAtMs; }
    void setCreatedAtMs(long createdAtMs) { this.createdAtMs = createdAtMs; }

    Long getStartedAtMs() { return startedAtMs; }
    void setStartedAtMs(Long startedAtMs) { this.startedAtMs = startedAtMs; }

    String getLinkedTaskId() { return linkedTaskId; }
    void setLinkedTaskId(String linkedTaskId) { this.linkedTaskId = linkedTaskId; }

    String getWorkspaceId() { return workspaceId; }
    void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    String getPriority() { return priority; }
    void setPriority(String priority) { this.priority = priority; }

    String getSource() { return source; }
    void setSource(String source) { this.source = source; }

    String getStatus() { return status; }
    void setStatus(String status) { this.status = status; }

    String getCreatedBy() { return createdBy; }
    void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    String getOrigin() { return origin; }
    void setOrigin(String origin) { this.origin = origin; }

    Long getInProgressAtMs() { return inProgressAtMs; }
    void setInProgressAtMs(Long inProgressAtMs) { this.inProgressAtMs = inProgressAtMs; }

    Long getResolvedAtMs() { return resolvedAtMs; }
    void setResolvedAtMs(Long resolvedAtMs) { this.resolvedAtMs = resolvedAtMs; }

    Long getRejectedAtMs() { return rejectedAtMs; }
    void setRejectedAtMs(Long rejectedAtMs) { this.rejectedAtMs = rejectedAtMs; }

    String getRejectionReason() { return rejectionReason; }
    void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }

    List<String> getRelatedBacklogIds() { return relatedBacklogIds; }
    void setRelatedBacklogIds(List<String> relatedBacklogIds) { this.relatedBacklogIds = relatedBacklogIds; }

    String getItemKey() { return itemKey; }
    void setItemKey(String itemKey) { this.itemKey = itemKey; }

    String getSummary() { return summary; }
    void setSummary(String summary) { this.summary = summary; }

    String getDetail() { return detail; }
    void setDetail(String detail) { this.detail = detail; }

    String getImpactRisk() { return impactRisk; }
    void setImpactRisk(String impactRisk) { this.impactRisk = impactRisk; }

    String getLinksJson() { return linksJson; }
    void setLinksJson(String linksJson) { this.linksJson = linksJson; }
}

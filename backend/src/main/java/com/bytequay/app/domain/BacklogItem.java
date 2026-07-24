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
package com.bytequay.app.domain;

import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;

/**
 * One parked future-work item on a thread's backlog — the JIRA-like parking
 * lot behind the trunk's Backlog tab. Carries a status machine
 * ({@code created} -> {@code in-progress} -> {@code resolved} ->
 * {@code shipped}/{@code closed}, with {@code created} <->
 * {@code not-to-proceed}), a priority + source +
 * creator provenance, a workspace pointer for the workspace-wide view, and
 * the {@code relatedBacklogIds} sibling linkage trunk-split sets. Lifecycle
 * transitions go through the {@code with*} / {@code mark*} copy helpers so
 * the record never gets reconstructed positionally at call sites.
 */
public record BacklogItem(
        String id,
        String threadId,
        String workspaceId,
        String title,
        String body,
        List<String> tags,
        String priority,
        String source,
        String status,
        String createdBy,
        String origin,
        Instant createdAt,
        Instant inProgressAt,
        Instant startedAt,
        Instant resolvedAt,
        Instant rejectedAt,
        String rejectionReason,
        String linkedTaskId,
        List<String> relatedBacklogIds,
        String itemKey,
        String summary,
        String detail,
        String impactRisk,
        List<Link> links)
{
    public static final String PRIORITY_MEDIUM = "medium";
    public static final String SOURCE_MANUAL = "manual";
    public static final String SOURCE_AGENT = "agent";
    public static final String SOURCE_TRUNK_SPLIT = SOURCE_AGENT;
    public static final String STATUS_OPEN = "open";
    public static final String STATUS_CREATED = STATUS_OPEN;
    public static final String STATUS_IN_PROGRESS = "in-progress";
    public static final String STATUS_RESOLVED = "resolved";
    public static final String STATUS_SHIPPED = "shipped";
    public static final String STATUS_CLOSED = "closed";
    public static final String STATUS_DISCARDED = "discarded";
    public static final String STATUS_NOT_TO_PROCEED = STATUS_DISCARDED;
    public static final String CREATED_BY_USER = "user";
    public static final String CREATED_BY_TRUNK_AGENT = "trunk-agent";
    public static final String ORIGIN_USER = "user";
    public static final String ORIGIN_AGENT = "agent";
    public static final String ORIGIN_ISSUE_MONITOR = "issue-monitor";
    public static final String ORIGIN_QUALITY_SCAN = "quality-scan";

    public BacklogItem
    {
        tags = tags == null ? List.of() : ImmutableList.copyOf(tags);
        relatedBacklogIds = relatedBacklogIds == null ? List.of() : ImmutableList.copyOf(relatedBacklogIds);
        links = links == null ? List.of() : ImmutableList.copyOf(links);
        summary = summary == null || summary.isBlank() ? title : summary;
        detail = detail == null ? body : detail;
    }

    /** Compatibility shape predating workspace-local keys and structured
     *  content. */
    public BacklogItem(
            String id,
            String threadId,
            String workspaceId,
            String title,
            String body,
            List<String> tags,
            String priority,
            String source,
            String status,
            String createdBy,
            Instant createdAt,
            Instant inProgressAt,
            Instant startedAt,
            Instant resolvedAt,
            Instant rejectedAt,
            String rejectionReason,
            String linkedTaskId,
            List<String> relatedBacklogIds)
    {
        this(id, threadId, workspaceId, title, body, tags, priority, source,
                status, createdBy, originFor(source, createdBy, tags), createdAt,
                inProgressAt, startedAt,
                resolvedAt, rejectedAt, rejectionReason, linkedTaskId,
                relatedBacklogIds, null, title, body, null, List.of());
    }

    /** A freshly-created item: status {@code created}, no lifecycle stamps. */
    public static BacklogItem create(
            String id,
            String threadId,
            String workspaceId,
            String title,
            String body,
            List<String> tags,
            String priority,
            String source,
            String createdBy,
            Instant createdAt,
            List<String> relatedBacklogIds)
    {
        return new BacklogItem(
                id, threadId, workspaceId, title, body, tags,
                priority, source, STATUS_CREATED, createdBy,
                originFor(source, createdBy, tags), createdAt,
                /* inProgressAt */ null, /* startedAt */ null, /* resolvedAt */ null,
                /* rejectedAt */ null, /* rejectionReason */ null, /* linkedTaskId */ null,
                relatedBacklogIds, null, title, body, null, List.of());
    }

    /** Copy with edited user-facing fields (title / body / tags / priority). */
    public BacklogItem withDetails(String title, String body, List<String> tags, String priority)
    {
        return new BacklogItem(
                id, threadId, workspaceId, title, body, tags,
                priority, source, status, createdBy, origin, createdAt,
                inProgressAt, startedAt, resolvedAt, rejectedAt, rejectionReason,
                linkedTaskId, relatedBacklogIds, itemKey, title, body,
                impactRisk, links);
    }

    public BacklogItem withPublicFields(
            String newItemKey,
            String newSummary,
            String newDetail,
            String newImpactRisk,
            List<Link> newLinks)
    {
        String nextSummary = newSummary == null ? summary : newSummary.strip();
        if (nextSummary.isEmpty()) {
            throw new IllegalArgumentException("summary is required");
        }
        String nextDetail = newDetail == null ? detail : newDetail.strip();
        return new BacklogItem(
                id, threadId, workspaceId, title,
                nextDetail == null ? "" : nextDetail, tags, priority, source,
                status, createdBy, origin, createdAt, inProgressAt, startedAt,
                resolvedAt, rejectedAt, rejectionReason, linkedTaskId,
                relatedBacklogIds,
                newItemKey == null ? itemKey : newItemKey,
                nextSummary, nextDetail, newImpactRisk == null ? impactRisk : newImpactRisk,
                newLinks == null ? links : newLinks);
    }

    public BacklogItem withThread(String newThreadId)
    {
        return new BacklogItem(
                id, newThreadId, workspaceId, title, body, tags, priority,
                source, status, createdBy, origin, createdAt, inProgressAt, startedAt,
                resolvedAt, rejectedAt, rejectionReason, linkedTaskId,
                relatedBacklogIds, itemKey, summary, detail, impactRisk, links);
    }

    /** Move into {@code in-progress} (trunk exploration started), stamping
     *  {@code startedAt} (the click) + {@code inProgressAt} (exploration). */
    public BacklogItem markInProgress(Instant when)
    {
        return new BacklogItem(
                id, threadId, workspaceId, title, body, tags,
                priority, source, STATUS_IN_PROGRESS, createdBy, origin, createdAt,
                when, when, resolvedAt, rejectedAt, rejectionReason, linkedTaskId,
                relatedBacklogIds, itemKey, summary, detail, impactRisk, links);
    }

    /** Move into {@code resolved} once a task is cut, linking the task. */
    public BacklogItem markResolved(String taskId, Instant when)
    {
        return new BacklogItem(
                id, threadId, workspaceId, title, body, tags,
                priority, source, STATUS_RESOLVED, createdBy, origin, createdAt,
                inProgressAt == null ? when : inProgressAt, startedAt == null ? when : startedAt,
                when, rejectedAt, rejectionReason, taskId, relatedBacklogIds,
                itemKey, summary, detail, impactRisk, links);
    }

    /** Move into {@code shipped} once the cut task's PR merges (the task
     *  reaches COMPLETED). Terminal; keeps the {@code resolvedAt} cut stamp
     *  and the linked task. */
    public BacklogItem markShipped()
    {
        return withStatus(STATUS_SHIPPED);
    }

    /** Move into {@code closed} when the cut task reaches COMPLETED without
     *  its PR merging (closed unmerged, or none opened). Terminal; keeps the
     *  {@code resolvedAt} cut stamp and the linked task. */
    public BacklogItem markClosed()
    {
        return withStatus(STATUS_CLOSED);
    }

    private BacklogItem withStatus(String newStatus)
    {
        return new BacklogItem(
                id, threadId, workspaceId, title, body, tags,
                priority, source, newStatus, createdBy, origin, createdAt,
                inProgressAt, startedAt, resolvedAt, rejectedAt, rejectionReason, linkedTaskId,
                relatedBacklogIds, itemKey, summary, detail, impactRisk, links);
    }

    /** Move into {@code not-to-proceed} with an optional reason. */
    public BacklogItem markNotToProceed(String reason, Instant when)
    {
        return new BacklogItem(
                id, threadId, workspaceId, title, body, tags,
                priority, source, STATUS_NOT_TO_PROCEED, createdBy, origin, createdAt,
                inProgressAt, startedAt, resolvedAt, when, reason, linkedTaskId,
                relatedBacklogIds, itemKey, summary, detail, impactRisk, links);
    }

    /** Restore a rejected (or in-flight) item back to {@code created},
     *  clearing the exploration / rejection stamps. */
    public BacklogItem markCreated()
    {
        return new BacklogItem(
                id, threadId, workspaceId, title, body, tags,
                priority, source, STATUS_CREATED, createdBy, origin, createdAt,
                /* inProgressAt */ null, startedAt, resolvedAt,
                /* rejectedAt */ null, /* rejectionReason */ null, linkedTaskId,
                relatedBacklogIds, itemKey, summary, detail, impactRisk, links);
    }

    /** True once "Start development" has begun work (exploration or a cut). */
    public boolean isStarted()
    {
        return startedAt != null;
    }

    /** Stamp immutable provenance once, from server-controlled creator fields.
     *  Special tags only refine items actually created by the trunk agent; a
     *  manual item carrying the same editable tag remains user-originated. */
    private static String originFor(String source, String createdBy, List<String> tags)
    {
        boolean agent = SOURCE_AGENT.equals(source) || CREATED_BY_TRUNK_AGENT.equals(createdBy)
                || "agent".equals(createdBy);
        if (!agent) {
            return ORIGIN_USER;
        }
        if (tags != null && tags.contains(ORIGIN_QUALITY_SCAN)) {
            return ORIGIN_QUALITY_SCAN;
        }
        if (tags != null && (tags.contains("remote-intake")
                || tags.contains("bytequay-intake"))) {
            return ORIGIN_ISSUE_MONITOR;
        }
        return ORIGIN_AGENT;
    }

    public record Link(String type, String id) {}
}

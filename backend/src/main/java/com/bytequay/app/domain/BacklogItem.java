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
 * ({@code created} -> {@code in-progress} -> {@code resolved}, with
 * {@code created} <-> {@code not-to-proceed}), a priority + source +
 * creator provenance, a workspace pointer for the workspace-wide view, and
 * the {@code relatedBacklogIds} sibling linkage trunk-split sets. Lifecycle
 * transitions go through the {@code with*} / {@code mark*} copy helpers so
 * the 18-field record never gets reconstructed positionally at call sites.
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
        Instant createdAt,
        Instant inProgressAt,
        Instant startedAt,
        Instant resolvedAt,
        Instant rejectedAt,
        String rejectionReason,
        String linkedTaskId,
        List<String> relatedBacklogIds)
{
    public static final String PRIORITY_MEDIUM = "medium";
    public static final String SOURCE_MANUAL = "manual";
    public static final String SOURCE_TRUNK_SPLIT = "trunk-split";
    public static final String STATUS_CREATED = "created";
    public static final String STATUS_IN_PROGRESS = "in-progress";
    public static final String STATUS_RESOLVED = "resolved";
    public static final String STATUS_NOT_TO_PROCEED = "not-to-proceed";
    public static final String CREATED_BY_USER = "user";
    public static final String CREATED_BY_TRUNK_AGENT = "trunk-agent";

    public BacklogItem
    {
        tags = tags == null ? List.of() : ImmutableList.copyOf(tags);
        relatedBacklogIds = relatedBacklogIds == null ? List.of() : ImmutableList.copyOf(relatedBacklogIds);
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
                priority, source, STATUS_CREATED, createdBy, createdAt,
                /* inProgressAt */ null, /* startedAt */ null, /* resolvedAt */ null,
                /* rejectedAt */ null, /* rejectionReason */ null, /* linkedTaskId */ null,
                relatedBacklogIds);
    }

    /** Copy with edited user-facing fields (title / body / tags / priority). */
    public BacklogItem withDetails(String title, String body, List<String> tags, String priority)
    {
        return new BacklogItem(
                id, threadId, workspaceId, title, body, tags,
                priority, source, status, createdBy, createdAt,
                inProgressAt, startedAt, resolvedAt, rejectedAt, rejectionReason,
                linkedTaskId, relatedBacklogIds);
    }

    /** Move into {@code in-progress} (trunk exploration started), stamping
     *  {@code startedAt} (the click) + {@code inProgressAt} (exploration). */
    public BacklogItem markInProgress(Instant when)
    {
        return new BacklogItem(
                id, threadId, workspaceId, title, body, tags,
                priority, source, STATUS_IN_PROGRESS, createdBy, createdAt,
                when, when, resolvedAt, rejectedAt, rejectionReason, linkedTaskId, relatedBacklogIds);
    }

    /** Move into {@code resolved} once a task is cut, linking the task. */
    public BacklogItem markResolved(String taskId, Instant when)
    {
        return new BacklogItem(
                id, threadId, workspaceId, title, body, tags,
                priority, source, STATUS_RESOLVED, createdBy, createdAt,
                inProgressAt == null ? when : inProgressAt, startedAt == null ? when : startedAt,
                when, rejectedAt, rejectionReason, taskId, relatedBacklogIds);
    }

    /** Move into {@code not-to-proceed} with an optional reason. */
    public BacklogItem markNotToProceed(String reason, Instant when)
    {
        return new BacklogItem(
                id, threadId, workspaceId, title, body, tags,
                priority, source, STATUS_NOT_TO_PROCEED, createdBy, createdAt,
                inProgressAt, startedAt, resolvedAt, when, reason, linkedTaskId, relatedBacklogIds);
    }

    /** Restore a rejected (or in-flight) item back to {@code created},
     *  clearing the exploration / rejection stamps. */
    public BacklogItem markCreated()
    {
        return new BacklogItem(
                id, threadId, workspaceId, title, body, tags,
                priority, source, STATUS_CREATED, createdBy, createdAt,
                /* inProgressAt */ null, startedAt, resolvedAt,
                /* rejectedAt */ null, /* rejectionReason */ null, linkedTaskId, relatedBacklogIds);
    }

    /** True once "Start development" has begun work (exploration or a cut). */
    public boolean isStarted()
    {
        return startedAt != null;
    }
}

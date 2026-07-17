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
package com.bytequay.app.beans.backlog;

import com.bytequay.app.domain.BacklogItem;

import java.time.Instant;
import java.util.List;

/** Wire shape of a {@link BacklogItem}. {@code createdAt} is epoch-millis;
 *  the lifecycle stamps ({@code inProgressAt} / {@code startedAt} /
 *  {@code resolvedAt} / {@code rejectedAt}) are epoch-millis or null. */
public record BacklogItemDto(
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
        long createdAt,
        Long inProgressAt,
        Long startedAt,
        Long resolvedAt,
        Long rejectedAt,
        String rejectionReason,
        String linkedTaskId,
        List<String> relatedBacklogIds,
        String key,
        String summary,
        String detail,
        String impactRisk,
        List<BacklogItem.Link> links)
{
    public static BacklogItemDto from(BacklogItem item)
    {
        return new BacklogItemDto(
                item.id(),
                item.threadId(),
                item.workspaceId(),
                item.title(),
                item.body(),
                item.tags(),
                item.priority(),
                item.source(),
                item.status(),
                item.createdBy(),
                item.createdAt().toEpochMilli(),
                epochOrNull(item.inProgressAt()),
                epochOrNull(item.startedAt()),
                epochOrNull(item.resolvedAt()),
                epochOrNull(item.rejectedAt()),
                item.rejectionReason(),
                item.linkedTaskId(),
                item.relatedBacklogIds(),
                item.itemKey(),
                item.summary(),
                item.detail(),
                item.impactRisk(),
                item.links());
    }

    private static Long epochOrNull(Instant instant)
    {
        return instant == null ? null : instant.toEpochMilli();
    }
}

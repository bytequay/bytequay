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
package com.bytequay.app.beans.workspace;

import com.bytequay.app.domain.WorkspaceCardDto;

import java.util.List;

/** Ready-card payload for the public workspace landing page. */
public record WorkspaceSummaryDto(
        String id,
        String name,
        String color,
        boolean isScratch,
        List<String> repos,
        int activeThreadCount,
        int tasksInFlight,
        long spendTodayMilliUsd,
        int needsAttentionCount,
        WorkspaceCardDto.MemorySummary memory,
        Long lastActivityMs,
        RepositoryDto repository,
        List<ActivityDto> recentActivity,
        boolean ready,
        String syncState)
{
    public static WorkspaceSummaryDto from(
            WorkspaceCardDto card,
            RepositoryDto repository,
            List<ActivityDto> recentActivity,
            boolean ready,
            String syncState)
    {
        return new WorkspaceSummaryDto(
                card.id(),
                card.name(),
                card.color(),
                card.isScratch(),
                card.repos(),
                card.activeThreadCount(),
                card.tasksInFlight(),
                card.spendTodayMilliUsd(),
                card.needsAttentionCount(),
                card.memory(),
                card.lastActivityMs(),
                repository,
                List.copyOf(recentActivity),
                ready,
                syncState);
    }

    /**
     * @param forked true when the clone is fork-based — origin is the
     *        user's fork and a separate remote points at {@code fullName}.
     *        Drives the fork marker on the workspace card.
     */
    public record RepositoryDto(
            String owner,
            String repo,
            String fullName,
            String defaultBaseBranch,
            String clonePath,
            boolean verified,
            boolean forked,
            String ownerAvatarUrl)
    {
    }

    public record ActivityDto(
            String id,
            String title,
            String status,
            String itemPath,
            long occurredAt)
    {
    }
}

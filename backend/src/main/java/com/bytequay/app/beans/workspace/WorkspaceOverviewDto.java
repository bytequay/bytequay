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

import java.util.List;

/** Single round-trip payload for the workspace rail and Today page. */
public record WorkspaceOverviewDto(
        WorkspaceSummaryDto workspace,
        WorkspaceSummaryDto.RepositoryDto repository,
        SidebarCountsDto sidebarCounts,
        List<TrunkDto> pinnedTrunks,
        TodayDto today,
        WorkspaceOnboardingDto onboarding,
        String syncState)
{
    public record SidebarCountsDto(
            int todayNeedsYou,
            int trunks,
            int pullRequests,
            Integer issues,
            int backlog,
            Integer branches,
            int sessions,
            int notifications)
    {
    }

    public record TodayDto(
            List<TrunkDto> needsYou,
            List<TrunkDto> running,
            List<TrunkDto> landedToday,
            long spendTodayMilliUsd)
    {
    }
}

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
package com.bytequay.core.domain;

import java.time.Instant;
import java.util.List;

public record PullRequestDetail(
        String repo,
        int number,
        String title,
        String htmlUrl,
        String state,
        boolean draft,
        Boolean mergeable,
        String mergeableState,
        String author,
        String authorAvatarUrl,
        Instant createdAt,
        Instant updatedAt,
        Instant mergedAt,
        String baseRef,
        String headRef,
        String body,
        List<Label> labels,
        List<String> assignees,
        String milestone,
        List<Reviewer> reviewers,
        int additions,
        int deletions,
        int changedFiles,
        int approvalCount,
        CiStatus ciStatus,
        List<ActivityItem> recentActivity,
        List<CheckRun> checkRuns,
        List<String> unavailableSections,
        boolean viewerCanWrite)
{
    public enum CiStatus
    {
        PASSING,
        FAILING,
        PENDING,
        NONE
    }

    public record ActivityItem(
            String actor,
            String eventType,
            Instant timestamp,
            String body,
            Long githubId,
            PrTimelinePayload payload)
    {
        public ActivityItem(
                String actor,
                String eventType,
                Instant timestamp,
                String body,
                Long githubId)
        {
            this(actor, eventType, timestamp, body, githubId, PrTimelinePayload.empty());
        }
    }

    public record Label(String name, String color) {}

    public record Reviewer(String login, String avatarUrl, String state) {}

    public record CheckRun(
            Long githubId,
            String name,
            String status,
            String conclusion,
            String detailsUrl,
            String source,
            String category,
            Instant completedAt)
    {
    }
}

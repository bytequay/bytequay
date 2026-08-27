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

import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public record PullRequestDetail(
        long id,
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
    public PullRequestDetail
    {
        checkArgument(id > 0, "id is not a stored row id: %s", id);
        requireNonNull(repo, "repo is null");
        labels = ImmutableList.copyOf(requireNonNull(labels, "labels is null"));
        assignees = ImmutableList.copyOf(requireNonNull(assignees, "assignees is null"));
        reviewers = ImmutableList.copyOf(requireNonNull(reviewers, "reviewers is null"));
        recentActivity = ImmutableList.copyOf(requireNonNull(recentActivity, "recentActivity is null"));
        checkRuns = ImmutableList.copyOf(requireNonNull(checkRuns, "checkRuns is null"));
        unavailableSections = ImmutableList.copyOf(
                requireNonNull(unavailableSections, "unavailableSections is null"));
    }

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

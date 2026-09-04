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
import com.google.common.collect.ImmutableMap;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * The list-level shape of a PR. Most fields come straight from GitHub's
 * search/list endpoints; the trailing block (ciStatus, additions, deletions,
 * commentCount, attentionReason) is populated by the sync job from the
 * accompanying detail fetch so list and Home rows need no extra request.
 *
 * @param createdAt GitHub's {@code created_at}, or null on legacy rows.
 * @param labelColors side-table for label name to hex color.
 * @param state GitHub PR state: {@code "open"}, {@code "closed"}, or
 * {@code "merged"}.
 * @param mergeable GitHub's mergeable flag, or null while GitHub is still
 * computing it.
 * @param mergeableState GitHub's mergeable_state value.
 * @param headPushedAt timestamp of the most recent commit on the PR's head
 * branch.
 * @param headRef PR head branch name from GitHub's list response.
 */
public record PullRequest(
        long id,
        String repo,
        int number,
        String title,
        String author,
        String htmlUrl,
        Instant createdAt,
        Instant updatedAt,
        Origin origin,
        List<String> labels,
        Map<String, String> labelColors,
        boolean draft,
        Instant viewedAt,
        Instant reviewedAt,
        HandledAction handledAction,
        List<String> requestedReviewers,
        PullRequestDetail.CiStatus ciStatus,
        int additions,
        int deletions,
        int commentCount,
        AttentionReason attentionReason,
        // Detail-enrichment fields. Optional until a row has been re-synced.
        String state,
        Instant closedAt,
        Instant mergedAt,
        Boolean mergeable,
        String mergeableState,
        Instant headPushedAt,
        String headRef)
{
    public PullRequest
    {
        checkArgument(id > 0, "id is not a stored row id: %s", id);
        requireNonNull(repo, "repo is null");
        requireNonNull(author, "author is null");
        requireNonNull(title, "title is null");
        requireNonNull(htmlUrl, "htmlUrl is null");
        requireNonNull(createdAt, "createdAt is null");
        requireNonNull(updatedAt, "updatedAt is null");
        requireNonNull(origin, "origin is null");
        requireNonNull(state, "state is null");
        labels = ImmutableList.copyOf(requireNonNull(labels, "labels is null"));
        labelColors = labelColors == null ? ImmutableMap.of() : ImmutableMap.copyOf(labelColors);
        requestedReviewers = ImmutableList.copyOf(requireNonNull(requestedReviewers, "requestedReviewers is null"));
    }

    public enum Origin
    {
        AUTHORED,
        REVIEW_REQUESTED,
        OTHER
    }

    /** This pull request with the viewed and reviewed marks this application holds for it. */
    public PullRequest withViewState(PrViewState viewState)
    {
        if (viewState == null) {
            return this;
        }
        return new PullRequest(
                id, repo, number, title, author, htmlUrl, createdAt, updatedAt, origin, labels,
                labelColors, draft, viewState.viewedAt(), viewState.reviewedAt(),
                viewState.handledAction(), requestedReviewers, ciStatus, additions, deletions,
                commentCount, attentionReason, state, closedAt, mergedAt, mergeable, mergeableState,
                headPushedAt, headRef);
    }
}

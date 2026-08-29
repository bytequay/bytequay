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

import static java.util.Objects.requireNonNull;

/**
 * A pull request as GitHub reports it, before this application has stored it.
 * It carries no row id and none of the state this application owns -- viewed
 * and reviewed marks, attention, or the counts filled in by a later detail
 * refresh. Store it to obtain a {@link PullRequest}, whose id is always
 * present.
 *
 * <p>The fields a given endpoint cannot report are left null: search results
 * carry no reviewers, mergeability or head ref.
 */
public record FetchedPullRequest(
        Long githubId,
        String repo,
        int number,
        String title,
        String author,
        String htmlUrl,
        Instant createdAt,
        Instant updatedAt,
        PullRequest.Origin origin,
        List<String> labels,
        Map<String, String> labelColors,
        boolean draft,
        String state,
        Instant closedAt,
        Instant mergedAt,
        List<String> requestedReviewers,
        Boolean mergeable,
        String mergeableState,
        String headRef)
{
    /** GitHub's stand-in login for a pull request whose author deleted their account. */
    public static final String GHOST_AUTHOR = "ghost";

    public FetchedPullRequest
    {
        author = author == null ? GHOST_AUTHOR : author;
        requireNonNull(githubId, "githubId is null");
        requireNonNull(repo, "repo is null");
        requireNonNull(title, "title is null");
        requireNonNull(origin, "origin is null");
        requireNonNull(state, "state is null");
        labels = ImmutableList.copyOf(requireNonNull(labels, "labels is null"));
        labelColors = labelColors == null ? ImmutableMap.of() : ImmutableMap.copyOf(labelColors);
        requestedReviewers = ImmutableList.copyOf(
                requireNonNull(requestedReviewers, "requestedReviewers is null"));
    }

    public FetchedPullRequest withOrigin(PullRequest.Origin origin)
    {
        return new FetchedPullRequest(
                githubId, repo, number, title, author, htmlUrl, createdAt, updatedAt, origin,
                labels, labelColors, draft, state, closedAt, mergedAt, requestedReviewers,
                mergeable, mergeableState, headRef);
    }

    /** This pull request with its relationship to the current GitHub user derived from GitHub facts. */
    public FetchedPullRequest withViewerOrigin(String viewerLogin)
    {
        requireNonNull(viewerLogin, "viewerLogin is null");
        PullRequest.Origin viewerOrigin;
        if (author.equalsIgnoreCase(viewerLogin)) {
            viewerOrigin = PullRequest.Origin.AUTHORED;
        }
        else if (requestedReviewers.stream().anyMatch(viewerLogin::equalsIgnoreCase)) {
            viewerOrigin = PullRequest.Origin.REVIEW_REQUESTED;
        }
        else {
            viewerOrigin = PullRequest.Origin.OTHER;
        }
        return withOrigin(viewerOrigin);
    }
}

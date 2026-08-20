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
import java.util.Map;

/**
 * Raw data returned by a single GitHub pull-request API call, before business-logic aggregation.
 *
 * @param requestedReviewers GitHub logins still on the PR's pending-review list.
 * @param headRef branch name on the head side.
 */
public record PrRawDetail(
        String title,
        String htmlUrl,
        String state,
        String author,
        String authorAvatarUrl,
        Instant createdAt,
        Instant updatedAt,
        Instant mergedAt,
        String body,
        List<String> labels,
        Map<String, String> labelColors,
        List<String> assignees,
        String milestone,
        boolean draft,
        Boolean mergeable,
        String mergeableState,
        int additions,
        int deletions,
        int changedFiles,
        int requestedReviewerCount,
        List<String> requestedReviewers,
        String headSha,
        String headRef,
        String baseRef)
{
    public PrRawDetail(
            String body,
            List<String> labels,
            boolean draft,
            Boolean mergeable,
            String mergeableState,
            int additions,
            int deletions,
            int changedFiles,
            int requestedReviewerCount,
            List<String> requestedReviewers,
            String headSha,
            String headRef)
    {
        this(
                null, null, null, null, null, null, null, null,
                body, labels, Map.of(), List.of(), null, draft, mergeable,
                mergeableState, additions, deletions, changedFiles,
                requestedReviewerCount, requestedReviewers, headSha, headRef,
                null);
    }
}

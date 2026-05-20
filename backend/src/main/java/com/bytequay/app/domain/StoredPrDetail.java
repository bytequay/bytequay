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

import java.util.List;

/**
 * All cached detail data for a single pull request, loaded as a unit from the local store.
 *
 * @param mergeQueueState GraphQL-sourced merge-queue entry state, such as
 * {@code QUEUED}, {@code MERGEABLE}, or {@code UNMERGEABLE}. Null when the PR
 * has no queue entry.
 */
public record StoredPrDetail(
        PrRawDetail raw,
        List<PrReviewState> reviews,
        List<PullRequestDetail.ChangedFile> files,
        List<PrTimelineEvent> timeline,
        List<PrCheckRunState> checkRuns,
        List<PrReviewThreadMessage> reviewComments,
        List<PullRequestDetail.LinkedIssue> linkedIssues,
        String mergeQueueState)
{
    /** Backward-compat 7-arg constructor for callers (mostly the
     *  copy-on-mutation paths in PullRequestService + tests) that
     *  don't surface merge-queue state. Defaults {@code mergeQueueState}
     *  to null. New callers should use the canonical constructor. */
    public StoredPrDetail(
            PrRawDetail raw,
            List<PrReviewState> reviews,
            List<PullRequestDetail.ChangedFile> files,
            List<PrTimelineEvent> timeline,
            List<PrCheckRunState> checkRuns,
            List<PrReviewThreadMessage> reviewComments,
            List<PullRequestDetail.LinkedIssue> linkedIssues)
    {
        this(raw, reviews, files, timeline, checkRuns, reviewComments, linkedIssues, null);
    }
}

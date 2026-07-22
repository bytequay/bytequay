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
 * Raw data returned by a single GitHub pull-request API call, before business-logic aggregation.
 *
 * @param requestedReviewers GitHub logins still on the PR's pending-review
 * list. Null on legacy pre-V39 rows; callers that only care about presence
 * can fall back to {@link #requestedReviewerCount}.
 * @param headRef Branch name on the head side. Null on legacy rows.
 * @param headRepo Full {@code owner/repo} name of the head side. Differs
 * from {@code baseRepo} for fork PRs. Null on legacy rows.
 * @param baseRef Target branch. Null on legacy rows.
 * @param baseRepo Full {@code owner/repo} name of the target side. Same as
 * the PR's repo for in-repo PRs. Null on legacy rows.
 * @param state PR lifecycle state ("open" | "closed"). Null on cached rows
 * written before the terminal-state columns existed, and on the
 * backward-compat constructor used by callers that don't carry it.
 * @param merged True when the PR landed (merged) rather than closed unmerged.
 * @param baseSha The base-side commit SHA (base.sha from the pulls payload).
 * Null on legacy rows and on the backward-compat constructors — the
 * dashboard cache never persisted it. Needed to pin evidence bundles.
 * @param mergeCommitSha The merge commit SHA GitHub created when the PR
 * landed (merge_commit_sha). Null when the PR is unmerged or on legacy rows.
 */
public record PrRawDetail(
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
        String headRef,
        String headRepo,
        String baseRef,
        String baseRepo,
        String state,
        boolean merged,
        String baseSha,
        String mergeCommitSha)
{
    /** Backward-compat constructor for callers that carry terminal PR state
     *  but not the pinning SHAs: defaults both to null. */
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
            String headRef,
            String headRepo,
            String baseRef,
            String baseRepo,
            String state,
            boolean merged)
    {
        this(body, labels, draft, mergeable, mergeableState, additions, deletions, changedFiles,
                requestedReviewerCount, requestedReviewers, headSha, headRef, headRepo, baseRef,
                baseRepo, state, merged, null, null);
    }

    /** Backward-compat constructor for callers (mostly tests) that don't
     *  carry terminal PR state: defaults to open / not-merged. */
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
            String headRef,
            String headRepo,
            String baseRef,
            String baseRepo)
    {
        this(body, labels, draft, mergeable, mergeableState, additions, deletions, changedFiles,
                requestedReviewerCount, requestedReviewers, headSha, headRef, headRepo, baseRef,
                baseRepo, null, false, null, null);
    }
}

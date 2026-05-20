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
        String baseRepo) {}

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
        /** GitHub logins still on the PR's pending-review list (those
         *  who were requested but haven't submitted a verdict yet).
         *  Null on legacy pre-V39 rows; callers that only care about
         *  presence can fall back to {@link #requestedReviewerCount}. */
        List<String> requestedReviewers,
        String headSha,
        /** Branch name on the head side (e.g. "feat/foo"). Null on legacy
         *  rows that pre-date the PR-detail endpoint capturing branch refs. */
        String headRef,
        /** "owner/repo" of the head side. Differs from baseRepo when the PR
         *  comes from a fork. Null on legacy rows. */
        String headRepo,
        /** Target branch (almost always the repo's default branch). Null on
         *  legacy rows. */
        String baseRef,
        /** "owner/repo" of the target side. Same as the PR's repo for
         *  in-repo PRs; differs from headRepo on fork-based PRs. Null on
         *  legacy rows. */
        String baseRepo) {}

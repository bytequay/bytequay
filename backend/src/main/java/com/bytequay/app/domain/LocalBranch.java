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

import java.time.Instant;

/**
 * One row of the branches kanban on the repo detail page. Combines
 * info from {@code git for-each-ref refs/heads} with a join against
 * the watched PR table — {@link #linkedPrNumber} is set when one of
 * the user's open PRs targets this branch as its head ref.
 *
 * @param isCurrent true when this branch is what HEAD points at.
 * @param lastCommitAt timestamp of the branch tip's commit.
 * @param hasUpstream true iff the branch has an upstream tracking ref
 * configured. False means local-only branch.
 * @param ahead commits on the local branch but not on its remote, or null when
 * {@link #hasUpstream} is false.
 * @param behind commits on the remote but not on the local branch, or null when
 * {@link #hasUpstream} is false.
 * @param linkedPrNumber GitHub PR number whose head ref equals this branch, or
 * null when no open PR targets it.
 * @param cleanupReason non-null when this branch is a cleanup candidate.
 * @param commitCount commits reachable from this branch but not from the repo's
 * default base.
 * @param rebasePreview outcome of a virtual merge of this branch onto its
 * rebase target.
 * @param remoteOnly true for synthesized entries that mirror an open PR whose
 * head branch is not checked out in this clone.
 */
public record LocalBranch(
        String name,
        boolean isCurrent,
        Instant lastCommitAt,
        boolean hasUpstream,
        Integer ahead,
        Integer behind,
        Integer linkedPrNumber,
        CleanupReason cleanupReason,
        Integer commitCount,
        RebasePreview rebasePreview,
        boolean remoteOnly)
{
    public enum Column
    {
        LOCAL_WORK,
        READY_FOR_PR,
        IN_REVIEW,
        CLEAN_UP
    }

    public enum RebasePreview
    {
        /** Virtual merge succeeded with no conflicts — the rebase
         *  should apply cleanly. */
        CLEAN,
        /** Virtual merge reported file-level conflicts — the user will
         *  hit conflicts mid-rebase and need to resolve them. */
        CONFLICTS,
        /** merge-tree failed for a reason other than a conflict (e.g.
         *  base ref unresolvable). The pill renders as a quiet
         *  fallback rather than a confident verdict. */
        UNKNOWN
    }

    public enum CleanupReason
    {
        /** Upstream tracking ref is gone — git's {@code [gone]} marker.
         *  Typical post-merge state: PR was merged, the remote branch
         *  was deleted, the local branch is leftover. */
        REMOTE_GONE,
        /** Never pushed and the tip commit is older than the idle
         *  threshold (90d). Long-tail experiments the user forgot. */
        IDLE_NEVER_PUSHED
    }

    /**
     * Decides the column placement based on the branch's state.
     * CLEAN_UP wins over IN_REVIEW only on cleanup; otherwise the
     * usual order applies (linked PR → upstream → no upstream).
     * The current branch is never a cleanup candidate — deleting
     * the branch you're standing on isn't a workflow we want to
     * one-click toward.
     */
    public Column column()
    {
        if (cleanupReason != null && !isCurrent) {
            return Column.CLEAN_UP;
        }
        if (linkedPrNumber != null) {
            return Column.IN_REVIEW;
        }
        if (hasUpstream) {
            return Column.READY_FOR_PR;
        }
        return Column.LOCAL_WORK;
    }
}

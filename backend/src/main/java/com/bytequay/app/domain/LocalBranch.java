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
 */
public record LocalBranch(
        String name,
        /** True when this branch is what HEAD points at. */
        boolean isCurrent,
        /** Timestamp of the branch tip's commit. Drives the "idle 47d"
         *  hint on cleanup-candidate cards. */
        Instant lastCommitAt,
        /** True iff the branch has an upstream tracking ref configured.
         *  False = local-only branch (never pushed); these end up in
         *  the LOCAL WORK column. */
        boolean hasUpstream,
        /** Commits ahead of upstream — what's on the local branch but
         *  not on its remote. Null when {@link #hasUpstream} is false. */
        Integer ahead,
        /** Commits behind upstream — what's on the remote but not on
         *  the local branch. Null when {@link #hasUpstream} is false. */
        Integer behind,
        /** GitHub PR number whose head ref equals this branch, or null
         *  when no open PR targets it. Drives placement into the
         *  IN REVIEW column. */
        Integer linkedPrNumber,
        /** Non-null when this branch is a cleanup candidate — drives
         *  placement into CLEAN UP and authorizes the branch to be
         *  deleted via the bulk-delete flow. Null otherwise. */
        CleanupReason cleanupReason,
        /** Commits reachable from this branch but not from the repo's
         *  default base — the size of the work that lives on this
         *  branch. Null for the default branch itself, when the default
         *  can't be resolved, or when rev-list failed. */
        Integer commitCount,
        /** Outcome of a virtual merge of this branch onto its rebase
         *  target (upstream tracking ref when present, else the default
         *  branch). Null when no rebase is meaningful (no behind /
         *  no unique commits) or the preview couldn't run. */
        RebasePreview rebasePreview)
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

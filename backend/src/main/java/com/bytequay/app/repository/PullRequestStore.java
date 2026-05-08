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
package com.bytequay.app.repository;

import com.bytequay.app.domain.AttentionReason;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestDetail;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Local database store for pull request list data.
 *
 * <p>Unlike {@link PullRequestRepository}, which is the live GitHub API abstraction,
 * this interface operates on the local PostgreSQL database and requires no PAT.
 * Rows are written exclusively by the background sync job and read by the list API.
 */
public interface PullRequestStore
{
    /**
     * Returns all pull requests currently in the local store, unordered.
     */
    List<PullRequest> findAll();

    /**
     * Replaces the entire contents of the store with the given list in one transaction.
     * Existing rows matching an incoming id are updated; rows whose id is absent from
     * the new list are deleted; new rows are inserted.
     *
     * @param pullRequests the authoritative list from GitHub; may be empty
     */
    void replaceAll(List<PullRequest> pullRequests);

    /**
     * Returns the timestamp of the most recent successful sync, or empty if the
     * store has never been populated.
     */
    Optional<Instant> lastSyncedAt();

    /**
     * Returns a map of PR id → updatedAt for all rows currently in the store.
     * Used by the sync job to detect which PRs changed and need a detail refresh.
     */
    Map<Long, Instant> findUpdatedAtMap();

    /**
     * Returns the ids of PR rows whose V26 enrichment fields haven't been
     * populated yet — i.e. {@code reviewer_verdicts} is null. The sync job
     * uses this to backfill legacy rows that pre-date the V26 schema or
     * that were stored before the kanban features started reading the
     * field. Without this backfill those rows show up in the kanban as
     * "Opened" forever and never land in Needs changes / Ready to merge.
     */
    Set<Long> findIdsMissingEnrichment();

    /**
     * Returns the GitHub PR id for the given (repo, number) pair, or empty if not found.
     */
    Optional<Long> findIdByRepoAndNumber(String repo, int number);

    /** Returns the full PR record for the given id, or empty if not found. */
    Optional<PullRequest> findById(long prId);

    /**
     * Returns a map of {@code head_ref → pr_number} for every open PR
     * in {@code repo} ({@code "owner/name"}). Powers the local-repo
     * kanban's IN REVIEW column without requiring per-PR detail to
     * have been synced first. PRs whose row pre-dates V42 (head_ref
     * still null) are skipped — they'll join the next list sync.
     */
    default Map<String, Integer> openPrNumbersByHeadRef(String repo)
    {
        Map<String, Integer> out = new HashMap<>();
        for (PullRequest pr : findAll()) {
            if (!repo.equals(pr.repo()) || pr.headRef() == null) {
                continue;
            }
            // Last-write-wins on collisions (same head_ref appears on
            // more than one open PR — a recreate-then-recreate edge
            // case). The latest one is the most actionable.
            out.put(pr.headRef(), pr.number());
        }
        return out;
    }

    /**
     * Updates the detail-derived enrichment columns for a single PR row.
     * No-op if the id isn't in the store. Called by the sync job after each
     * detail fetch — the columns power the Kanban card render path so we
     * don't have to re-read the detail blob just to draw a list.
     *
     * <p>The trailing block (mergeable…reviewerVerdicts) was added by V26
     * for the kanban refactor — see docs/design/kanban-refactor.md.
     * mergeable/mergeableState come from the raw PR detail; headPushedAt
     * is the timestamp of the latest "committed" timeline event;
     * reviewerVerdicts is the rolled-up login → state map derived from
     * the cached reviews list.
     */
    void updateEnrichment(
            long prId,
            PullRequestDetail.CiStatus ciStatus,
            int additions,
            int deletions,
            int commentCount,
            AttentionReason attentionReason,
            Boolean mergeable,
            String mergeableState,
            Instant headPushedAt,
            Map<String, String> reviewerVerdicts,
            String headRef);
}

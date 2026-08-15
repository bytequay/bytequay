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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Local database store for pull request list data.
 *
 * <p>Unlike the live GitHub API repositories,
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
     * Returns ids whose detail-enrichment fields have not been populated.
     * The sync job uses this to backfill older local rows.
     */
    Set<Long> findIdsMissingEnrichment();

    /**
     * Returns the GitHub PR id for the given (repo, number) pair, or empty if not found.
     */
    Optional<Long> findIdByRepoAndNumber(String repo, int number);

    /** Returns the full PR record for the given id, or empty if not found. */
    Optional<PullRequest> findById(long prId);

    /**
     * Updates the detail-derived enrichment columns for a single PR row.
     * No-op if the id isn't in the store. Called by the sync job after each
     * detail fetch so list reads don't need to re-read the detail blob.
     *
     * <p>The trailing block (mergeable…reviewerVerdicts)
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

    /**
     * Narrow writeback for the cached aggregate ciStatus only. Used by
     * the user-driven detail / CI-snapshot paths so list and Home reads
     * pick up fresh CI state without waiting for the next full sync.
     *
     * <p>Deliberately doesn't touch attentionReason or the rest of
     * the enrichment block — those depend on per-user state
     * (currentLogin / viewedAt) that isn't always in scope on these
     * paths. The next bulk sync recomputes them in lockstep.
     */
    void updateCiStatus(long prId, PullRequestDetail.CiStatus ciStatus);
}

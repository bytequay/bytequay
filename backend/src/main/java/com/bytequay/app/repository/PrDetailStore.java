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

import com.bytequay.app.domain.StoredPrDetail;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Local database store for cached PR detail data (reviews, files, timeline, check-runs).
 * Written by the sync job; read by the PR detail API so it can return instantly without
 * hitting GitHub on every request.
 */
public interface PrDetailStore
{
    /**
     * Returns the cached detail for the given PR, or empty if it has never been synced.
     */
    Optional<StoredPrDetail> find(long prId);

    /**
     * Returns the timestamp of the last successful detail sync for this
     * PR, or empty if never synced. Used as the watermark passed back
     * to GitHub's `since=` query param on incremental fetches.
     */
    default Optional<Instant> findSyncedAt(String repo, int number)
    {
        return Optional.empty();
    }

    /**
     * Replaces all cached detail for the given PR with fresh data.
     * Safe to call concurrently for different PR ids.
     */
    void save(long prId, StoredPrDetail detail);

    /**
     * Append-only counterpart of {@link #save} for incremental syncs.
     * Existing rows are preserved; only timeline events / review-thread
     * messages whose {@code github_id} isn't already present get
     * inserted. Reviews / files / check-runs / linked-issues fall back
     * to a wholesale replace because GitHub doesn't expose a sane
     * incremental endpoint for them.
     */
    default void saveIncremental(long prId, StoredPrDetail detail)
    {
        save(prId, detail);
    }

    /**
     * Removes cached detail for all given PR ids.
     * Called when those PRs are no longer present in the sync result.
     */
    void deleteByPrIds(Set<Long> prIds);

    /**
     * Returns the PR id whose cached timeline contains a "commented"
     * event with the given GitHub comment id, or empty if no cached
     * detail references it. Used by the conversation-mutation patch path
     * (edit body, react) to find the cache row to update when the caller
     * only has a comment id.
     */
    default Optional<Long> findPrIdByIssueCommentId(long commentId)
    {
        return Optional.empty();
    }

    /**
     * Returns the PR id whose cached review-thread messages include the
     * given GitHub comment id, or empty if no cached detail references
     * it. Same role as {@link #findPrIdByIssueCommentId} for per-line
     * review comments.
     */
    default Optional<Long> findPrIdByReviewCommentId(long commentId)
    {
        return Optional.empty();
    }

    /**
     * Returns a map of {@code head_ref → pr_number} for all currently
     * open PRs against {@code repo} (form {@code "owner/repo"}) that
     * have detail synced. Used by the local-repo branches kanban to
     * route branches into the IN REVIEW column.
     *
     * <p>Branches whose PR detail hasn't been synced yet won't appear
     * here — that's by design; the IN REVIEW signal is best-effort,
     * and the PR will appear once the user opens it (which triggers
     * the detail sync). Same-name collisions across forks are
     * resolved by last-write-wins; the kanban can live with that
     * because the user typically only has one fork per watched repo.
     */
    default Map<String, Integer> openPrNumbersByHeadRef(String repo)
    {
        return Map.of();
    }

    /**
     * Returns up to {@code limit} PR ids whose cached reviews include
     * at least one row with a null {@code submitted_at} column. Used by
     * the sync job to backfill the V53 timestamp on PRs whose detail
     * blob predates that migration — without forcing a re-fetch of
     * every PR in the store. Order is unspecified; callers cap the
     * batch to keep the per-tick rate-limit cost bounded.
     */
    default List<Long> findPrIdsMissingReviewTimestamps(int limit)
    {
        return List.of();
    }
}

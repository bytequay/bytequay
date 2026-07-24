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

import com.bytequay.app.domain.BacklogItem;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence boundary for per-thread {@link BacklogItem}s. */
public interface BacklogStore
{
    /** Insert or update an item; returns the persisted row. */
    BacklogItem save(BacklogItem item);

    /** Items on a thread, oldest-first. */
    List<BacklogItem> findByThread(String threadId);

    /** Every item in a workspace, newest-first (the workspace-wide view). */
    List<BacklogItem> findByWorkspace(String workspaceId);

    /** One item by id. */
    Optional<BacklogItem> findById(String id);

    /**
     * Atomically move one unlinked in-progress item to resolved.
     * Returns false when another writer already changed the row.
     */
    boolean resolveIfInProgressAndUnlinked(String id, String taskId, Instant resolvedAt);

    default Optional<BacklogItem> findByWorkspaceAndItemKey(String workspaceId, String itemKey)
    {
        return Optional.empty();
    }

    /** Allocates the next stable BQ-N key for one workspace. */
    default String nextItemKey(String workspaceId)
    {
        return null;
    }

    /** Permanently remove an item. No-op when the id is unknown. */
    void delete(String id);
}

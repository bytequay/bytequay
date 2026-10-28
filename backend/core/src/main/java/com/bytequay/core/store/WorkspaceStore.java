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
package com.bytequay.core.store;

import com.bytequay.core.domain.Workspace;
import com.bytequay.core.hosting.HostingCacheTarget;
import com.bytequay.core.hosting.WorkspaceHostingBinding;

import java.util.List;
import java.util.Optional;

/** Persists the single authoritative watched-repository workspace model. */
public interface WorkspaceStore
{
    /** Returns watched workspaces in user display order. */
    List<Workspace> findAll();

    /** Returns watched and unwatched workspaces whose durable data is retained. */
    List<Workspace> findAllRetained();

    /** Changes watch-list membership without deleting or restoring any workspace data. */
    void setWatched(String workspaceId, boolean watched);

    /** Returns whether the workspace exists and belongs to the watch list. */
    boolean isWatched(String workspaceId);

    /** Checks every durable workflow associated with the workspace, without a recent-history limit. */
    boolean hasActiveWorkflows(String workspaceId);

    /** Finds a workspace by its ByteQuay-owned identifier. */
    Optional<Workspace> findById(String workspaceId);

    /** Finds a workspace by the selected repository's stable GitHub identifier. */
    Optional<Workspace> findBySelectedRepositoryId(long selectedRepositoryId);

    Optional<Workspace> findBySelectedHostingRepositoryId(long repositoryId);

    Optional<WorkspaceHostingBinding> findHostingBinding(String workspaceId);

    Optional<HostingCacheTarget> findHostingCacheTarget(String workspaceId);

    default Optional<Long> findHostingRepositoryId(String workspaceId)
    {
        return findHostingBinding(workspaceId).map(WorkspaceHostingBinding::monitoredRepositoryId)
                .or(() -> findHostingCacheTarget(workspaceId).map(HostingCacheTarget::repositoryId));
    }

    void saveHostingCacheTarget(String workspaceId, HostingCacheTarget target);

    /** Selects an account and retained repository identities on the workspace's hosting server. */
    WorkspaceHostingBinding bindHosting(String workspaceId, long accountId, long selectedRepositoryId, Optional<Long> upstreamRepositoryId);

    /**
     * Creates a workspace or returns the row that already owns the selected GitHub repository.
     * The database unique constraint is the final concurrency guard.
     */
    Workspace createOrGet(Workspace workspace);

    /** Creates a hosted workspace and its account binding together. */
    Workspace createHosted(Workspace workspace, long accountId, long selectedRepositoryId);

    /** Saves an existing workspace without changing its watch-list membership. */
    Workspace save(Workspace workspace);

    /** Purges workspace-owned data after tasks are removed, retaining independently referenced repository data. */
    void delete(String workspaceId);
}

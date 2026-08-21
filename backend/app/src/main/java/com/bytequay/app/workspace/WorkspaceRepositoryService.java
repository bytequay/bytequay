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
package com.bytequay.app.workspace;

import com.bytequay.core.domain.Workspace;

import java.util.List;

/** Application boundary for the durable watched-repository workspace lifecycle. */
public interface WorkspaceRepositoryService
{
    /**
     * Searches repositories visible to the connected GitHub account without changing state.
     *
     * @param query repository name or owner/name; blank returns recently pushed repositories
     * @param limit maximum number of results, from 1 through 30
     * @return repositories with stable identity, topology hints, clone routing, and permissions
     */
    List<RepositorySearchResult> searchRepositories(String query, int limit);

    /**
     * Resolves the exact direct/fork routing that the user must confirm before watching.
     *
     * @param selectedRepositoryId stable GitHub repository identifier
     * @return current routing, permission state, and confirmation fingerprint
     * @throws WorkspaceException when the repository is unavailable or has unsafe routing
     */
    WatchRepositoryPreview previewWatch(long selectedRepositoryId);

    /**
     * Creates a durable workspace after re-reading the selected repository by stable GitHub ID.
     *
     * <p>The selected repository's stable GitHub ID is idempotent: a repeated or concurrent watch
     * returns the existing workspace. A changed routing fingerprint is rejected so stale preview
     * data cannot be persisted.</p>
     *
     * @param command confirmed repository ID, preview fingerprint, and optional display name
     * @return the persisted workspace in its current code and metadata states
     * @throws WorkspaceException when the repository changed after preview
     */
    Workspace watch(WatchRepositoryCommand command);

    /**
     * Lists the authoritative workspaces used by both Home and the Workspaces page.
     *
     * @return summaries in retained user display order
     */
    List<WorkspaceSummary> listWorkspaces();

    /**
     * Loads routing, path ownership, and independent sync state for Workspace Home.
     *
     * @param workspaceId ByteQuay workspace identifier
     * @return complete workspace details
     * @throws WorkspaceException when the workspace does not exist
     */
    WorkspaceDetails getWorkspace(String workspaceId);

    /**
     * Resolves a ready backend-owned checkout for Git or cherry-pick operations.
     *
     * @param workspaceId ByteQuay workspace identifier
     * @return normalized managed path and persisted remote/base routing
     * @throws WorkspaceException when the workspace is absent, not ready, or outside the managed root
     */
    WorkspaceRepositoryContext requireReadyRepository(String workspaceId);

    /**
     * Requests code and metadata synchronization without creating another workspace.
     *
     * @param workspaceId ByteQuay workspace identifier
     * @return state immediately after the idempotent request is recorded
     */
    Workspace requestSync(String workspaceId);

    /**
     * Stops watching a workspace after verifying that no active cherry-pick references its repo.
     *
     * @param workspaceId ByteQuay workspace identifier
     * @throws WorkspaceException when the workspace is missing or busy
     */
    void unwatch(String workspaceId);
}

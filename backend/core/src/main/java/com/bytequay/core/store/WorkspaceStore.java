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

import java.util.List;
import java.util.Optional;

/** Persists the single authoritative watched-repository workspace model. */
public interface WorkspaceStore
{
    /** Returns workspaces in user display order. */
    List<Workspace> findAll();

    /** Finds a workspace by its ByteQuay-owned identifier. */
    Optional<Workspace> findById(String workspaceId);

    /** Finds a workspace by the selected repository's stable GitHub identifier. */
    Optional<Workspace> findBySelectedRepositoryId(long selectedRepositoryId);

    /**
     * Creates a workspace or returns the row that already owns the selected GitHub repository.
     * The database unique constraint is the final concurrency guard.
     */
    Workspace createOrGet(Workspace workspace);

    /** Saves lifecycle state changes for an existing workspace. */
    Workspace save(Workspace workspace);

    /** Removes the workspace row after application-level busy checks pass. */
    void delete(String workspaceId);
}

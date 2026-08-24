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

import com.bytequay.core.domain.RepositoryMetadataSnapshot;

public interface WorkspaceMetadataService
{
    /**
     * Refreshes expired reference projections for the workspace's monitored repository.
     *
     * <p>Contributors refresh weekly; labels and topics refresh monthly. A fork resolves to its
     * persisted upstream ID, and failures retain the last local snapshot.</p>
     *
     * @param workspaceId workspace whose monitored repository should be refreshed
     * @return success, projection counts, and freshness timestamps
     */
    WorkspaceMetadataSyncResult synchronize(String workspaceId);

    /**
     * Reads the latest local metadata without contacting GitHub.
     *
     * @param workspaceId workspace whose monitored repository identifies the shared cache
     * @return latest snapshot, possibly empty or stale
     */
    RepositoryMetadataSnapshot getSnapshot(String workspaceId);
}

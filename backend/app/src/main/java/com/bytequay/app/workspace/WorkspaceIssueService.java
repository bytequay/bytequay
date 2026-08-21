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

import com.bytequay.core.domain.RepositoryIssueSnapshot;

public interface WorkspaceIssueService
{
    /**
     * Reads the latest local issue projection for a workspace's monitored repository.
     *
     * @param workspaceId workspace used to resolve the stable monitored repository ID
     * @return cached open issues and their freshness timestamp
     */
    RepositoryIssueSnapshot list(String workspaceId);

    /**
     * Refreshes open issues independently from low-volatility reference metadata.
     *
     * <p>Direct workspaces target the selected repository; forks target their persisted upstream.
     * A provider failure leaves the previous local snapshot intact.</p>
     *
     * @param workspaceId workspace used to resolve the monitored repository
     * @return newly persisted issue snapshot
     */
    RepositoryIssueSnapshot synchronize(String workspaceId);
}

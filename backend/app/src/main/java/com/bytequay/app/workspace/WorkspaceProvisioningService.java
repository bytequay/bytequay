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

/** Owns managed repository clone, remote validation, fetch, and recovery behavior. */
public interface WorkspaceProvisioningService
{
    /**
     * Creates or safely resumes the backend-owned checkout for a saved workspace.
     *
     * <p>The selected repository is {@code origin}. Fork workspaces also configure their
     * persisted canonical source as {@code upstream}. Unknown directories and mismatched remotes
     * fail without deletion.</p>
     *
     * @param workspaceId saved workspace to provision
     * @return final code state and synchronization time
     */
    WorkspaceProvisioningResult provision(String workspaceId);

    /**
     * Fetches and prunes every configured remote without changing branches or local files.
     *
     * @param workspaceId ready workspace to synchronize
     * @return final code state and synchronization time
     */
    WorkspaceCodeSyncResult synchronizeCode(String workspaceId);
}

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

import java.util.List;

/**
 * User-managed extra Git remotes of a workspace checkout. {@code origin} and
 * the linked {@code upstream} are workspace routing and appear in listings
 * with their roles, but only extra remotes can be added or removed here.
 */
public interface WorkspaceRemoteSourceService
{
    List<WorkspaceRemote> list(String workspaceId);

    /**
     * Validates, persists, and configures one extra remote, then queues a
     * background fetch through the normal workspace synchronization path.
     */
    List<WorkspaceRemote> add(String workspaceId, String name, String url);

    /** Removes one extra remote from persistence and the checkout. */
    List<WorkspaceRemote> remove(String workspaceId, String name);

    record WorkspaceRemote(String name, String url, Role role)
    {
        public enum Role
        {
            ORIGIN, UPSTREAM, CUSTOM
        }
    }
}

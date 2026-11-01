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
import com.bytequay.core.domain.Workspace.Topology;
import com.bytequay.core.hosting.HostingCapabilities;
import com.bytequay.core.hosting.HostingServer;

import java.time.Instant;

import static java.util.Objects.requireNonNull;

public record WorkspaceSummary(
        String id,
        String name,
        long selectedRepositoryId,
        String selectedOwner,
        String selectedRepo,
        Long upstreamRepositoryId,
        String upstreamOwner,
        String upstreamRepo,
        long monitoredRepositoryId,
        String monitoredOwner,
        String monitoredRepo,
        Topology topology,
        String defaultBranch,
        String upstreamDefaultBranch,
        String localRepositoryPath,
        Instant lastCodeSyncAt,
        Instant lastMetadataSyncAt,
        int displayOrder,
        HostingServer hostingServer,
        HostingCapabilities capabilities)
{
    public WorkspaceSummary
    {
        requireNonNull(hostingServer, "hostingServer is null");
        requireNonNull(capabilities, "capabilities is null");
    }

    /**
     * The workspace as the app shows it.
     *
     * @param capabilities what the app supports through the implementation chosen for the workspace's server
     */
    public static WorkspaceSummary of(Workspace workspace, HostingCapabilities capabilities)
    {
        return new WorkspaceSummary(
                workspace.id(),
                workspace.name(),
                workspace.selectedRepositoryId(),
                workspace.selectedRepository().owner(),
                workspace.selectedRepository().repo(),
                workspace.upstreamRepositoryId().orElse(null),
                workspace.upstreamRepository().map(repository -> repository.owner()).orElse(null),
                workspace.upstreamRepository().map(repository -> repository.repo()).orElse(null),
                workspace.monitoredRepositoryId(),
                workspace.monitoredRepository().owner(),
                workspace.monitoredRepository().repo(),
                workspace.topology(),
                workspace.defaultBranch(),
                workspace.upstreamDefaultBranch().orElse(null),
                workspace.localRepositoryPath(),
                workspace.lastCodeSyncAt().orElse(null),
                workspace.lastMetadataSyncAt().orElse(null),
                workspace.displayOrder(),
                workspace.server(),
                capabilities);
    }
}

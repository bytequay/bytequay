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
import com.bytequay.core.domain.Workspace.CodeState;
import com.bytequay.core.domain.Workspace.MetadataState;
import com.bytequay.core.domain.Workspace.Topology;

import java.time.Instant;

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
        String localRepositoryPath,
        CodeState codeState,
        MetadataState metadataState,
        Instant lastCodeSyncAt,
        Instant lastMetadataSyncAt,
        String codeError,
        String metadataError,
        int displayOrder)
{
    public static WorkspaceSummary of(Workspace workspace)
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
                workspace.localRepositoryPath(),
                workspace.codeState(),
                workspace.metadataState(),
                workspace.lastCodeSyncAt().orElse(null),
                workspace.lastMetadataSyncAt().orElse(null),
                workspace.codeError().orElse(null),
                workspace.metadataError().orElse(null),
                workspace.displayOrder());
    }
}

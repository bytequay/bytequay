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
import com.bytequay.core.domain.Workspace.MetadataState;

import java.time.Instant;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

public record WorkspaceMetadataSyncResult(
        String workspaceId,
        MetadataState state,
        int contributorCount,
        int labelCount,
        int topicCount,
        Optional<Instant> contributorsSyncedAt,
        Optional<Instant> labelsTopicsSyncedAt)
{
    public WorkspaceMetadataSyncResult
    {
        requireNonNull(workspaceId, "workspaceId is null");
        requireNonNull(state, "state is null");
        requireNonNull(contributorsSyncedAt, "contributorsSyncedAt is null");
        requireNonNull(labelsTopicsSyncedAt, "labelsTopicsSyncedAt is null");
    }

    public static WorkspaceMetadataSyncResult of(
            String workspaceId,
            MetadataState state,
            RepositoryMetadataSnapshot snapshot)
    {
        requireNonNull(snapshot, "snapshot is null");
        return new WorkspaceMetadataSyncResult(
                workspaceId,
                state,
                snapshot.contributors().size(),
                snapshot.labels().size(),
                snapshot.topics().size(),
                snapshot.contributorsSyncedAt(),
                snapshot.labelsTopicsSyncedAt());
    }
}

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

import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace.Topology;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Immutable direct/fork routing shown to the user before a workspace is saved. */
public record WatchRepositoryPreview(
        long selectedRepositoryId,
        RepoRef selectedRepository,
        String visibility,
        Topology topology,
        Optional<Long> upstreamRepositoryId,
        Optional<RepoRef> upstreamRepository,
        long monitoredRepositoryId,
        RepoRef monitoredRepository,
        String defaultBranch,
        String developmentBase,
        String pushRemote,
        boolean pushWritable,
        RepoRef pullRequestBase,
        String fingerprint,
        Optional<String> existingWorkspaceId)
{
    public WatchRepositoryPreview
    {
        requireNonNull(selectedRepository, "selectedRepository is null");
        requireNonNull(visibility, "visibility is null");
        requireNonNull(topology, "topology is null");
        requireNonNull(upstreamRepositoryId, "upstreamRepositoryId is null");
        requireNonNull(upstreamRepository, "upstreamRepository is null");
        requireNonNull(monitoredRepository, "monitoredRepository is null");
        requireNonNull(defaultBranch, "defaultBranch is null");
        requireNonNull(developmentBase, "developmentBase is null");
        requireNonNull(pushRemote, "pushRemote is null");
        requireNonNull(pullRequestBase, "pullRequestBase is null");
        requireNonNull(fingerprint, "fingerprint is null");
        requireNonNull(existingWorkspaceId, "existingWorkspaceId is null");
    }
}

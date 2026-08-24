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
import com.google.common.collect.ImmutableSet;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/** Result of fetching a managed repository's configured remotes. */
public record WorkspaceCodeSyncResult(
        String workspaceId,
        boolean successful,
        Optional<Instant> lastCodeSyncAt,
        Set<String> failedRemoteSources)
{
    public WorkspaceCodeSyncResult
    {
        requireNonNull(workspaceId, "workspaceId is null");
        requireNonNull(lastCodeSyncAt, "lastCodeSyncAt is null");
        failedRemoteSources = ImmutableSet.copyOf(requireNonNull(
                failedRemoteSources, "failedRemoteSources is null"));
    }

    public WorkspaceCodeSyncResult(
            String workspaceId,
            boolean successful,
            Optional<Instant> lastCodeSyncAt)
    {
        this(workspaceId, successful, lastCodeSyncAt, ImmutableSet.of());
    }

    public static WorkspaceCodeSyncResult success(Workspace workspace)
    {
        return new WorkspaceCodeSyncResult(
                workspace.id(), true, workspace.lastCodeSyncAt(), ImmutableSet.of());
    }

    public static WorkspaceCodeSyncResult success(Workspace workspace, Set<String> failedRemoteSources)
    {
        return new WorkspaceCodeSyncResult(
                workspace.id(), true, workspace.lastCodeSyncAt(), failedRemoteSources);
    }

    public static WorkspaceCodeSyncResult failure(Workspace workspace)
    {
        return new WorkspaceCodeSyncResult(
                workspace.id(), false, workspace.lastCodeSyncAt(), ImmutableSet.of());
    }
}

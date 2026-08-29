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

import com.bytequay.core.domain.RemoteSource;
import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/** Backend-resolved repository path and routing used by trusted Git operations. */
public record WorkspaceRepositoryContext(
        String workspaceId,
        Path repository,
        long selectedRepositoryId,
        RepoRef selectedRepository,
        OptionalLong upstreamRepositoryId,
        RepoRef monitoredRepository,
        String sourceRemote,
        String targetRemote,
        String defaultBranch,
        List<RemoteSource> extraRemotes)
{
    public WorkspaceRepositoryContext
    {
        extraRemotes = ImmutableList.copyOf(extraRemotes);
    }

    public boolean knownRemote(String remote)
    {
        return sourceRemote.equals(remote)
                || targetRemote.equals(remote)
                || extraRemote(remote).isPresent();
    }

    public Optional<RemoteSource> extraRemote(String remote)
    {
        return extraRemotes.stream()
                .filter(source -> source.name().equals(remote))
                .findFirst();
    }

    public OptionalLong sourceRepositoryId(String remote)
    {
        if (sourceRemote.equals(remote)) {
            return upstreamRepositoryId;
        }
        return extraRemote(remote)
                .flatMap(RemoteSource::repositoryId)
                .map(OptionalLong::of)
                .orElseGet(OptionalLong::empty);
    }
}

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
package com.bytequay.core.domain;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * One user-configured extra Git remote of a workspace checkout, like
 * {@code git remote add name url}. The managed {@code origin} and linked
 * {@code upstream} remotes are workspace routing, not remote sources.
 */
public record RemoteSource(
        String workspaceId,
        String name,
        String url,
        Optional<Long> repositoryId,
        Optional<RepoRef> repository)
{
    public RemoteSource
    {
        requireNonNull(workspaceId, "workspaceId is null");
        checkArgument(!workspaceId.isBlank(), "workspaceId is blank");
        requireNonNull(name, "name is null");
        checkArgument(!name.isBlank(), "name is blank");
        requireNonNull(url, "url is null");
        checkArgument(!url.isBlank(), "url is blank");
        requireNonNull(repositoryId, "repositoryId is null");
        requireNonNull(repository, "repository is null");
        checkArgument(repositoryId.isPresent() == repository.isPresent(),
                "repository ID and coordinates must both be present or absent");
        repositoryId.ifPresent(value -> checkArgument(value > 0, "repositoryId must be positive"));
    }

    public RemoteSource(String workspaceId, String name, String url)
    {
        this(workspaceId, name, url, Optional.empty(), Optional.empty());
    }

    public RemoteSource withRepositoryIdentity(long nextRepositoryId, RepoRef nextRepository)
    {
        return new RemoteSource(
                workspaceId,
                name,
                url,
                Optional.of(nextRepositoryId),
                Optional.of(requireNonNull(nextRepository, "nextRepository is null")));
    }
}

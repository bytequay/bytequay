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
package com.bytequay.app.git;

import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.core.domain.RemoteSource;
import com.bytequay.core.domain.RepoRef;

import java.util.Optional;
import java.util.OptionalLong;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

final class CommitHistoryRouting
{
    private CommitHistoryRouting() {}

    static ResolvedCommitHistoryRef resolve(
            WorkspaceRepositoryContext context,
            CommitHistoryRef ref)
    {
        requireNonNull(context, "context is null");
        requireNonNull(ref, "ref is null");
        switch (ref.source()) {
            case WORKSPACE -> {
                return new ResolvedCommitHistoryRef(
                        OptionalLong.of(context.selectedRepositoryId()),
                        Optional.of(context.selectedRepository()),
                        context.targetRemote() + "/" + ref.branch(),
                        ref.branch());
            }
            case UPSTREAM -> {
                checkArgument(context.upstreamRepositoryId().isPresent(), "no upstream repository is linked");
                checkArgument(!context.sourceRemote().equals(context.targetRemote()),
                        "linked upstream remote is missing");
                return new ResolvedCommitHistoryRef(
                        OptionalLong.of(context.upstreamRepositoryId().orElseThrow()),
                        Optional.of(context.monitoredRepository()),
                        context.sourceRemote() + "/" + ref.branch(),
                        ref.branch());
            }
            case REMOTE -> {
                String remote = ref.remote().orElseThrow();
                RemoteSource source = context.extraRemote(remote).orElse(null);
                checkArgument(source != null,
                        "remote is not configured for this workspace");
                return new ResolvedCommitHistoryRef(
                        source.repositoryId().map(OptionalLong::of).orElseGet(OptionalLong::empty),
                        source.repository(),
                        remote + "/" + ref.branch(),
                        ref.branch());
            }
        }
        throw new IllegalArgumentException("unsupported source: " + ref.source());
    }

    record ResolvedCommitHistoryRef(
            OptionalLong repositoryId,
            Optional<RepoRef> repository,
            String gitRef,
            String providerRef)
    {
        ResolvedCommitHistoryRef
        {
            requireNonNull(repositoryId, "repositoryId is null");
            requireNonNull(repository, "repository is null");
            checkArgument(repositoryId.isPresent() == repository.isPresent(),
                    "repositoryId and repository must be present together");
            repositoryId.ifPresent(value -> checkArgument(value > 0, "repositoryId must be positive"));
            requireNonNull(gitRef, "gitRef is null");
            requireNonNull(providerRef, "providerRef is null");
        }
    }
}

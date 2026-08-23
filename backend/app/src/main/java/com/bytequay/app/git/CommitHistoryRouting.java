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
import com.bytequay.core.domain.RepoRef;

import static com.bytequay.app.git.CommitHistoryRef.Source.UPSTREAM;
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
        if (ref.source() != UPSTREAM) {
            return new ResolvedCommitHistoryRef(
                    context.selectedRepositoryId(), context.selectedRepository(), ref.branch(), ref.branch());
        }
        checkArgument(context.upstreamRepositoryId().isPresent(), "no upstream repository is linked");
        checkArgument(!context.sourceRemote().equals(context.targetRemote()), "linked upstream remote is missing");
        return new ResolvedCommitHistoryRef(
                context.upstreamRepositoryId().orElseThrow(),
                context.monitoredRepository(),
                context.sourceRemote() + "/" + ref.branch(),
                ref.branch());
    }

    record ResolvedCommitHistoryRef(
            long repositoryId,
            RepoRef repository,
            String gitRef,
            String providerRef)
    {
        ResolvedCommitHistoryRef
        {
            checkArgument(repositoryId > 0, "repositoryId must be positive");
            requireNonNull(repository, "repository is null");
            requireNonNull(gitRef, "gitRef is null");
            requireNonNull(providerRef, "providerRef is null");
        }
    }
}

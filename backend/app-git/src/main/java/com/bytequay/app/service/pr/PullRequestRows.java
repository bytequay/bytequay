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
package com.bytequay.app.service.pr;

import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Finds the stored row for a request under its retained hosting repository. */
public final class PullRequestRows
{
    private final PullRequestStore store;
    private final WorkspaceStore workspaces;

    public PullRequestRows(PullRequestStore store, WorkspaceStore workspaces)
    {
        this.store = requireNonNull(store, "store is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
    }

    public Optional<PullRequest> find(PullRequestRead route)
    {
        return store.findByHostingRepository(route.repository().id(), route.number());
    }

    public Optional<Long> findId(PullRequestRead route)
    {
        return find(route).map(PullRequest::id);
    }

    /** The row already holds the snapshot the summary came from; a summary-less row takes it. */
    public PullRequest observe(PullRequestRead route, FetchedPullRequest summary)
    {
        return findOrCreate(route.repository().id(), summary);
    }

    /** A cached summary fills its row when that row has none, and never overwrites a stored one. */
    public PullRequest findOrCreate(long repositoryId, FetchedPullRequest summary)
    {
        return store.findByHostingRepository(repositoryId, summary.number()).orElseGet(() -> store.saveHosted(repositoryId, summary));
    }

    public Optional<PullRequest> find(Workspace workspace, int number)
    {
        return workspaces.findHostingRepositoryId(workspace.id()).flatMap(id -> store.findByHostingRepository(id, number));
    }

    public Optional<Long> findId(Workspace workspace, int number)
    {
        return find(workspace, number).map(PullRequest::id);
    }

    public List<PullRequest> findByHead(Workspace workspace, String branch)
    {
        return workspaces.findHostingRepositoryId(workspace.id()).map(id -> store.findByHostingHeadRef(id, branch)).orElse(ImmutableList.of());
    }
}

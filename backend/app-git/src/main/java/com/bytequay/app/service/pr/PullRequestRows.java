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
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Optional;

import static com.bytequay.core.hosting.HostingProvider.GITHUB;
import static java.util.Objects.requireNonNull;

/**
 * Finds the stored row for a repository's request. GitHub rows predate hosted request identity and stay keyed by repository path, because
 * the dashboard, drafts, reviews and local timeline events refer to them; other providers use the row of their retained hosting repository.
 */
public final class PullRequestRows
{
    private final PullRequestStore store;
    private final WorkspaceStore workspaces;

    public PullRequestRows(PullRequestStore store, WorkspaceStore workspaces)
    {
        this.store = requireNonNull(store, "store is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
    }

    public static boolean pathKeyed(HostingProvider provider)
    {
        return provider == GITHUB;
    }

    public Optional<PullRequest> find(PullRequestRead route)
    {
        return pathKeyed(route.target().provider()) ? findByPath(route.repository().fullPath(), route.number())
                : store.findByHostingRepository(route.repository().id(), route.number());
    }

    public Optional<Long> findId(PullRequestRead route)
    {
        return pathKeyed(route.target().provider()) ? store.findIdByRepoAndNumber(route.repository().fullPath(), route.number())
                : store.findByHostingRepository(route.repository().id(), route.number()).map(PullRequest::id);
    }

    /** A path-keyed row takes the refreshed summary; a hosted row already holds the snapshot the summary came from. */
    public PullRequest observe(PullRequestRead route, FetchedPullRequest summary)
    {
        return pathKeyed(route.target().provider()) ? store.saveObserved(summary) : findOrCreateHosted(route.repository().id(), summary);
    }

    /** A cached summary creates its row when missing and never overwrites a stored one. */
    public PullRequest findOrCreate(Workspace workspace, long repositoryId, FetchedPullRequest summary)
    {
        return pathKeyed(workspace) ? findByPath(summary.repo(), summary.number()).orElseGet(() -> store.saveObserved(summary))
                : findOrCreateHosted(repositoryId, summary);
    }

    public Optional<PullRequest> find(Workspace workspace, String repo, int number)
    {
        return pathKeyed(workspace) ? findByPath(repo, number)
                : workspaces.findHostingRepositoryId(workspace.id()).flatMap(id -> store.findByHostingRepository(id, number));
    }

    public Optional<Long> findId(Workspace workspace, String repo, int number)
    {
        return pathKeyed(workspace) ? store.findIdByRepoAndNumber(repo, number)
                : workspaces.findHostingRepositoryId(workspace.id()).flatMap(id -> store.findByHostingRepository(id, number)).map(PullRequest::id);
    }

    public List<PullRequest> findByHead(Workspace workspace, String repo, String branch)
    {
        return pathKeyed(workspace) ? store.findByHeadRef(repo, branch)
                : workspaces.findHostingRepositoryId(workspace.id()).map(id -> store.findByHostingHeadRef(id, branch)).orElse(ImmutableList.of());
    }

    private static boolean pathKeyed(Workspace workspace)
    {
        return workspace.isGitHub();
    }

    private Optional<PullRequest> findByPath(String repo, int number)
    {
        return store.findIdByRepoAndNumber(repo, number).flatMap(store::findById);
    }

    private PullRequest findOrCreateHosted(long repositoryId, FetchedPullRequest summary)
    {
        return store.findByHostingRepository(repositoryId, summary.number()).orElseGet(() -> store.saveHosted(repositoryId, summary));
    }
}

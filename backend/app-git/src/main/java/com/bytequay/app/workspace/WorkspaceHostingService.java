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

import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.hosting.HostingConnectionService;
import com.bytequay.app.service.hosting.HostingPullRequestService;
import com.bytequay.app.service.hosting.HostingReadSession;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.hosting.HostingAccount;
import com.bytequay.core.hosting.HostingCacheTarget;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingPullRequestSnapshot;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.HostingServer;
import com.bytequay.core.hosting.PullRequestListPage;
import com.bytequay.core.hosting.WorkspaceHostingBinding;
import com.bytequay.core.store.HostingIdentityStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static com.bytequay.app.git.command.RepositoryUrls.isPlainHttps;
import static com.bytequay.app.service.credentials.CredentialService.GITHUB_SERVER;
import static com.bytequay.app.workspace.WorkspaceException.requireWorkspace;
import static com.bytequay.core.hosting.HostingFailure.Kind.MISSING_OR_INACCESSIBLE;
import static com.bytequay.core.hosting.HostingProvider.GITLAB;
import static com.bytequay.core.hosting.HostingPullRequestSnapshot.CONNECTED_ACCOUNT;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Binds existing workspace routing to an explicitly selected hosting account, without moving its credentials. */
@Service
public class WorkspaceHostingService
{
    private final WorkspaceStore workspaces;
    private final HostingIdentityStore identities;
    private final HostingConnectionService connections;
    private final HostingPullRequestService requests;
    private final PullRequestStore pullRequests;
    private final CredentialService desktopCredentials;
    private final WorkspacePaths paths;
    private final Clock clock;

    public WorkspaceHostingService(WorkspaceStore workspaces, HostingIdentityStore identities,
            HostingConnectionService connections, HostingPullRequestService requests, CredentialService desktopCredentials,
            WorkspacePaths paths, Clock clock, PullRequestStore pullRequests)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.identities = requireNonNull(identities, "identities is null");
        this.connections = requireNonNull(connections, "connections is null");
        this.requests = requireNonNull(requests, "requests is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.desktopCredentials = requireNonNull(desktopCredentials, "desktopCredentials is null");
        this.paths = requireNonNull(paths, "paths is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public Optional<WorkspaceHostingBinding> findBinding(String workspaceId)
    {
        requireWorkspace(workspaces, workspaceId);
        return workspaces.findHostingBinding(workspaceId);
    }

    public WorkspaceHostingBinding bind(String workspaceId, long accountId)
    {
        checkArgument(accountId > 0, "accountId must be positive");
        Workspace workspace = requireWorkspace(workspaces, workspaceId);
        HostingReadSession session = connections.readSession(accountId);
        checkArgument(session.access().server().equals(workspace.hostingServer().orElse(GITHUB_SERVER)), "Hosting account belongs to another workspace server");
        HostingRepository selected = connections.resolveRepositoryById(session, Long.toString(workspace.selectedRepositoryId()));
        Optional<Long> upstream = workspace.upstreamRepositoryId()
                .map(id -> connections.resolveRepositoryById(session, id.toString()).id());
        return workspaces.bindHosting(workspaceId, accountId, selected.id(), upstream);
    }

    public Workspace watch(long accountId, long repositoryId, Optional<String> name)
    {
        requireNonNull(name, "name is null");
        name.ifPresent(value -> checkArgument(!value.isBlank() && value.length() <= 200, "Workspace name must be 1 to 200 characters"));
        HostingReadSession session = connections.readSession(accountId);
        HostingServer server = session.access().server();
        checkArgument(server.provider() == GITLAB, "Use the GitHub watch flow for GitHub repositories");
        HostingRepository retained = identities.findRepository(repositoryId)
                .orElseThrow(() -> new HostingFailure(MISSING_OR_INACCESSIBLE, "Hosting repository is unavailable"));
        checkArgument(retained.instanceId() == session.access().instanceId().orElseThrow(), "Repository belongs to another hosting instance");
        HostingRepository selected = connections.resolveRepositoryById(session, retained.remoteId());
        checkArgument(selected.id() == retained.id(), "Hosting repository identity changed");
        checkArgument(isPlainHttps(selected.httpCloneUrl()), "Workspace clone URL must use HTTPS");
        Optional<Workspace> existing = workspaces.findBySelectedHostingRepositoryId(selected.id());
        if (existing.isPresent()) {
            return workspaces.createHosted(existing.orElseThrow(), accountId, selected.id());
        }
        String branch = selected.defaultBranch().orElseThrow(() -> WorkspaceException.invalidRepository("The repository has no default branch to clone."));
        String id = UUID.randomUUID().toString();
        Instant now = clock.instant();
        int order = workspaces.findAllRetained().stream().mapToInt(Workspace::displayOrder).max().orElse(-1) + 1;
        RepoRef coordinates = repositoryRef(selected);
        long remoteId = Long.parseLong(selected.remoteId());
        Workspace workspace = new Workspace(id, name.map(String::trim).orElse(selected.fullPath()), remoteId, coordinates, Optional.empty(), Optional.empty(),
                remoteId, coordinates, Workspace.Topology.DIRECT, branch, selected.httpCloneUrl().toString(), Optional.empty(), Optional.empty(), paths.repository(id).toString(),
                Optional.empty(), Optional.empty(), order, now, now, Optional.of(server));
        return workspaces.createHosted(workspace, accountId, selected.id());
    }

    static RepoRef repositoryRef(HostingRepository repository)
    {
        String path = repository.fullPath();
        int slash = path.lastIndexOf('/');
        checkArgument(slash > 0, "Repository path has no namespace");
        return RepoRef.of(path.substring(0, slash), path.substring(slash + 1));
    }

    public WorkspaceHostingRead captureRead(String workspaceId)
    {
        Workspace workspace = requireWorkspace(workspaces, workspaceId);
        Optional<WorkspaceHostingBinding> binding = workspaces.findHostingBinding(workspaceId);
        HostingReadSession session = binding.map(value -> connections.readSession(value.accountId()))
                .orElseGet(() -> desktopCredentials.captureWorkspaceHostingRead(workspaceId));
        checkArgument(session.access().server().equals(workspace.hostingServer().orElse(GITHUB_SERVER)), "Hosting account belongs to another workspace server");
        HostingRepository repository = connections.resolveRepositoryById(session, Long.toString(workspace.monitoredRepositoryId()));
        HostingAccount account = identities.findAccount(session.accountId())
                .orElseThrow(() -> new HostingFailure(MISSING_OR_INACCESSIBLE, "Hosting account is unavailable"));
        checkArgument(account.instanceId() == session.access().instanceId().orElseThrow(), "Hosting account belongs to another instance");
        checkArgument(repository.remoteId().equals(Long.toString(workspace.monitoredRepositoryId())), "Workspace repository identity changed");
        binding.ifPresent(value -> checkArgument(repository.id() == value.monitoredRepositoryId(), "Workspace repository identity changed"));
        if (binding.isEmpty()) {
            workspaces.saveHostingCacheTarget(workspaceId, new HostingCacheTarget(session.accountId(), repository.id(), session.credentialScope()));
        }
        return new WorkspaceHostingRead(session, repository, account.login());
    }

    public WorkspacePullRequestPage cachedPullRequests(String workspaceId, int page, int pageSize)
    {
        checkArgument(page > 0, "page must be positive");
        checkArgument(pageSize > 0 && pageSize <= 100, "pageSize must be between 1 and 100");
        Workspace workspace = requireWorkspace(workspaces, workspaceId);
        Optional<HostingCacheTarget> target = workspaces.findHostingBinding(workspaceId)
                .map(binding -> new HostingCacheTarget(binding.accountId(), binding.monitoredRepositoryId(), CONNECTED_ACCOUNT))
                .or(() -> workspaces.findHostingCacheTarget(workspaceId));
        PullRequestListPage found = target.map(value -> requests.cachedPage(value, page, pageSize))
                .orElseGet(() -> new PullRequestListPage(ImmutableList.of(), false));
        return new WorkspacePullRequestPage(found.items().stream().map(summary -> new WorkspacePullRequestRow(summary, workspace.isGitHub()
                ? pullRequests.findIdByRepoAndNumber(summary.repo(), summary.number()).flatMap(pullRequests::findById) : Optional.empty()))
                .collect(toImmutableList()), found.hasNext());
    }

    public HostingPullRequestSnapshot readPullRequest(String workspaceId, int number)
    {
        return readPullRequest(captureRead(workspaceId), number);
    }

    public HostingPullRequestSnapshot readPullRequest(WorkspaceHostingRead route, int number)
    {
        return requests.refresh(route.session(), route.repository().id(), number, false);
    }

    public Optional<HostingPullRequestSnapshot> cachedPullRequest(WorkspaceHostingRead route, int number)
    {
        return requests.cached(route.session(), route.repository().id(), number);
    }
}

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
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.hosting.HostingAccount;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingPullRequestSnapshot;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.WorkspaceHostingBinding;
import com.bytequay.core.store.HostingIdentityStore;
import com.bytequay.core.store.WorkspaceStore;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Optional;

import static com.bytequay.app.workspace.WorkspaceException.requireWorkspace;
import static com.bytequay.core.hosting.HostingFailure.Kind.MISSING_OR_INACCESSIBLE;
import static com.bytequay.core.hosting.HostingProvider.GITHUB;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Binds existing workspace routing to an explicitly selected hosting account, without moving its credentials. */
@Service
public class WorkspaceHostingService
{
    private final WorkspaceStore workspaces;
    private final HostingIdentityStore identities;
    private final HostingConnectionService connections;
    private final HostingPullRequestService requests;
    private final CredentialService desktopCredentials;

    public WorkspaceHostingService(WorkspaceStore workspaces, HostingIdentityStore identities,
            HostingConnectionService connections, HostingPullRequestService requests, CredentialService desktopCredentials)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.identities = requireNonNull(identities, "identities is null");
        this.connections = requireNonNull(connections, "connections is null");
        this.requests = requireNonNull(requests, "requests is null");
        this.desktopCredentials = requireNonNull(desktopCredentials, "desktopCredentials is null");
    }

    public Optional<WorkspaceHostingBinding> findBinding(String workspaceId)
    {
        requireWorkspace(workspaces, workspaceId);
        return workspaces.findHostingBinding(workspaceId);
    }

    public WorkspaceHostingBinding bind(String workspaceId, long accountId)
    {
        checkArgument(accountId > 0, "accountId must be positive");
        HostingAccount account = identities.findAccount(accountId)
                .orElseThrow(() -> new HostingFailure(MISSING_OR_INACCESSIBLE, "Hosting account is unavailable"));
        HostingInstance instance = identities.findInstance(account.instanceId())
                .orElseThrow(() -> new HostingFailure(MISSING_OR_INACCESSIBLE, "Hosting instance is unavailable"));
        // Existing workspace IDs belong to GitHub.com; another server's identical number is not the same repository.
        checkArgument(instance.provider() == GITHUB && instance.webBaseUrl().equals(URI.create("https://github.com"))
                && instance.apiBaseUrl().equals(URI.create("https://api.github.com")), "Existing workspaces require a GitHub.com account");
        Workspace workspace = requireWorkspace(workspaces, workspaceId);
        HostingRepository selected = connections.resolveRepositoryById(accountId, Long.toString(workspace.selectedRepositoryId()));
        Optional<Long> upstream = workspace.upstreamRepositoryId()
                .map(id -> connections.resolveRepositoryById(accountId, id.toString()).id());
        return workspaces.bindHosting(workspaceId, accountId, selected.id(), upstream);
    }

    public WorkspaceHostingRead captureRead(String workspaceId)
    {
        Workspace workspace = requireWorkspace(workspaces, workspaceId);
        Optional<WorkspaceHostingBinding> binding = workspaces.findHostingBinding(workspaceId);
        HostingReadSession session = binding.map(value -> connections.readSession(value.accountId()))
                .orElseGet(() -> desktopCredentials.captureWorkspaceHostingRead(workspaceId));
        HostingRepository repository = connections.resolveRepositoryById(session, Long.toString(workspace.monitoredRepositoryId()));
        HostingAccount account = identities.findAccount(session.accountId())
                .orElseThrow(() -> new HostingFailure(MISSING_OR_INACCESSIBLE, "Hosting account is unavailable"));
        checkArgument(account.instanceId() == session.access().instanceId().orElseThrow(), "Hosting account belongs to another instance");
        checkArgument(repository.remoteId().equals(Long.toString(workspace.monitoredRepositoryId())), "Workspace repository identity changed");
        binding.ifPresent(value -> checkArgument(repository.id() == value.monitoredRepositoryId(), "Workspace repository identity changed"));
        return new WorkspaceHostingRead(session, repository, account.login());
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

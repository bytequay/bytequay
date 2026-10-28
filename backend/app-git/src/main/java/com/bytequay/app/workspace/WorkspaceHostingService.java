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

import com.bytequay.app.service.hosting.DesktopHostingService;
import com.bytequay.app.service.hosting.HostingConnectionService;
import com.bytequay.app.service.hosting.HostingPullRequestService;
import com.bytequay.app.service.hosting.HostingReadSession;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingAccount;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingPullRequestSnapshot;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.WorkspaceHostingBinding;
import com.bytequay.core.store.HostingCredentialStore;
import com.bytequay.core.store.HostingIdentityStore;
import com.bytequay.core.store.WorkspaceStore;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Optional;
import java.util.function.Supplier;

import static com.bytequay.app.workspace.WorkspaceException.requireWorkspace;
import static com.bytequay.core.hosting.HostingFailure.Kind.MISSING_OR_INACCESSIBLE;
import static com.bytequay.core.hosting.HostingFailure.Kind.REVISION_CONFLICT;
import static com.bytequay.core.hosting.HostingProvider.GITHUB;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Binds existing workspace routing to an explicitly selected hosting account, without moving its credentials. */
@Service
public class WorkspaceHostingService
{
    private final WorkspaceStore workspaces;
    private final HostingIdentityStore identities;
    private final HostingCredentialStore credentials;
    private final HostingConnectionService connections;
    private final HostingPullRequestService requests;
    private final DesktopHostingService desktop;

    public WorkspaceHostingService(WorkspaceStore workspaces, HostingIdentityStore identities, HostingCredentialStore credentials,
            HostingConnectionService connections, HostingPullRequestService requests, DesktopHostingService desktop)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.identities = requireNonNull(identities, "identities is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.connections = requireNonNull(connections, "connections is null");
        this.requests = requireNonNull(requests, "requests is null");
        this.desktop = requireNonNull(desktop, "desktop is null");
    }

    public Optional<WorkspaceHostingBinding> findBinding(String workspaceId)
    {
        return WorkspaceOperationLocks.withLock(workspaceId, () -> {
            requireWorkspace(workspaces, workspaceId);
            return workspaces.findHostingBinding(workspaceId);
        });
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
        BindingSnapshot snapshot = WorkspaceOperationLocks.withLock(workspaceId,
                () -> new BindingSnapshot(requireWorkspace(workspaces, workspaceId), workspaces.hostingGeneration(workspaceId)));
        long generation = credentials.generation(accountId);
        HostingRepository selected = connections.resolveRepositoryById(accountId, Long.toString(snapshot.workspace().selectedRepositoryId()));
        Optional<Long> upstream = snapshot.workspace().upstreamRepositoryId()
                .map(id -> connections.resolveRepositoryById(accountId, id.toString()).id());
        return WorkspaceOperationLocks.withLock(workspaceId, () -> connections.withCurrentCredential(accountId, generation, () -> {
            requireGeneration(workspaceId, snapshot.generation());
            return workspaces.bindHosting(workspaceId, accountId, selected.id(), upstream);
        }));
    }

    public WorkspaceHostingRead captureRead(String workspaceId)
    {
        ReadSnapshot snapshot = WorkspaceOperationLocks.withLock(workspaceId, () -> new ReadSnapshot(
                requireWorkspace(workspaces, workspaceId), workspaces.hostingGeneration(workspaceId), workspaces.findHostingBinding(workspaceId)));
        HostingReadSession.Guard routing = new HostingReadSession.Guard()
        {
            @Override
            public <T> T withCurrent(Supplier<T> action)
            {
                return WorkspaceOperationLocks.withLock(workspaceId, () -> {
                    requireGeneration(workspaceId, snapshot.generation());
                    return action.get();
                });
            }
        };
        HostingReadSession selected = snapshot.binding()
                .map(binding -> routing.withCurrent(() -> connections.readSession(binding.accountId())))
                .orElseGet(() -> desktop.workspaceSession(workspaceId, routing));
        HostingReadSession.Guard guard = new HostingReadSession.Guard()
        {
            @Override
            public <T> T withCurrent(Supplier<T> action)
            {
                return routing.withCurrent(() -> selected.withCurrent(action));
            }
        };
        var server = selected.access().server();
        HostingInstance instance = new HostingInstance(selected.access().instanceId().orElseThrow(), server.provider(), server.webBaseUrl(), server.apiBaseUrl());
        HostingReadSession session = new HostingReadSession(selected.accountId(), selected.credentialScope(), selected.generation(),
                new HostingAccess(instance, () -> guard.withCurrent(selected.access()::token)), guard);
        HostingRepository repository = connections.resolveRepositoryById(session, Long.toString(snapshot.workspace().monitoredRepositoryId()));
        return session.withCurrent(() -> {
            HostingAccount account = identities.findAccount(session.accountId())
                    .orElseThrow(() -> new HostingFailure(MISSING_OR_INACCESSIBLE, "Hosting account is unavailable"));
            checkArgument(account.instanceId() == instance.id(), "Hosting account belongs to another instance");
            checkArgument(repository.remoteId().equals(Long.toString(snapshot.workspace().monitoredRepositoryId())), "Workspace repository identity changed");
            snapshot.binding().ifPresent(binding -> checkArgument(repository.id() == binding.monitoredRepositoryId(), "Workspace repository identity changed"));
            return new WorkspaceHostingRead(session, repository, account.login());
        });
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

    private void requireGeneration(String workspaceId, long expected)
    {
        requireWorkspace(workspaces, workspaceId);
        if (workspaces.hostingGeneration(workspaceId) != expected) {
            throw new HostingFailure(REVISION_CONFLICT, "Workspace hosting or repository routing changed; retry with the current selection");
        }
    }

    private record BindingSnapshot(Workspace workspace, long generation) {}

    private record ReadSnapshot(Workspace workspace, long generation, Optional<WorkspaceHostingBinding> binding) {}
}

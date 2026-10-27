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

import com.bytequay.app.service.hosting.HostingConnectionService;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.hosting.HostingAccount;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.PullRequestFetch;
import com.bytequay.core.hosting.WorkspaceHostingBinding;
import com.bytequay.core.store.HostingCredentialStore;
import com.bytequay.core.store.HostingIdentityStore;
import com.bytequay.core.store.WorkspaceStore;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Optional;

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

    public WorkspaceHostingService(WorkspaceStore workspaces, HostingIdentityStore identities, HostingCredentialStore credentials, HostingConnectionService connections)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.identities = requireNonNull(identities, "identities is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.connections = requireNonNull(connections, "connections is null");
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

    public PullRequestFetch readPullRequest(String workspaceId, int number)
    {
        checkArgument(number > 0, "request number must be positive");
        WorkspaceHostingBinding binding = findBinding(workspaceId)
                .orElseThrow(() -> new HostingFailure(REVISION_CONFLICT, "Select a hosting account for this workspace before reading requests"));
        long generation = credentials.generation(binding.accountId());
        // This live path does not reuse the legacy accountless PR cache or its validators.
        PullRequestFetch result = connections.readPullRequest(binding.accountId(), binding.monitoredRepositoryId(), number);
        return WorkspaceOperationLocks.withLock(workspaceId, () -> connections.withCurrentCredential(binding.accountId(), generation, () -> {
            requireGeneration(workspaceId, binding.generation());
            return result;
        }));
    }

    private void requireGeneration(String workspaceId, long expected)
    {
        requireWorkspace(workspaces, workspaceId);
        if (workspaces.hostingGeneration(workspaceId) != expected) {
            throw new HostingFailure(REVISION_CONFLICT, "Workspace hosting or repository routing changed; retry with the current selection");
        }
    }

    private record BindingSnapshot(Workspace workspace, long generation) {}
}

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

import com.bytequay.app.repository.github.GitHubAccountClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.hosting.HostingConnectionService;
import com.bytequay.app.service.hosting.HostingReadSession;
import com.bytequay.core.domain.RemoteSource;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.UserRepo;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.WorkspaceHostingBinding;
import com.bytequay.core.store.RemoteSourceStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;

import static com.bytequay.app.workspace.WorkspaceException.requireWorkspace;
import static java.util.Objects.requireNonNull;

/** Owns managed repository clone, remote validation, fetch, and recovery behavior. */
@Service
public class WorkspaceProvisioningService
{
    private static final Logger log = LoggerFactory.getLogger(WorkspaceProvisioningService.class);
    private final WorkspaceStore store;
    private final ManagedWorkspaceGit managedGit;
    private final GitHubAccountClient gitHub;
    private final CredentialService credentials;
    private final HostingConnectionService hosting;
    private final WorkspaceRelationships relationships;
    private final RemoteSourceStore remoteSources;
    private final Clock clock;

    public WorkspaceProvisioningService(WorkspaceStore store, ManagedWorkspaceGit managedGit, GitHubAccountClient gitHub, CredentialService credentials,
            WorkspaceRelationships relationships, RemoteSourceStore remoteSources, HostingConnectionService hosting, Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.managedGit = requireNonNull(managedGit, "managedGit is null");
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.hosting = requireNonNull(hosting, "hosting is null");
        this.relationships = requireNonNull(relationships, "relationships is null");
        this.remoteSources = requireNonNull(remoteSources, "remoteSources is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /**
     * Creates the managed checkout when needed, otherwise fetches and prunes every configured remote
     * without changing branches or local files.
     *
     * <p>The selected repository is {@code origin}. Fork workspaces also configure their
     * persisted canonical source as {@code upstream}. Unknown directories and mismatched remotes
     * fail without deletion.</p>
     *
     * @param workspaceId saved workspace to synchronize
     * @return success, the last successful synchronization time, and failed optional remotes
     */
    public WorkspaceCodeSyncResult synchronizeCode(String workspaceId)
    {
        return WorkspaceOperationLocks.withLock(workspaceId, () -> {
            Workspace workspace = requireWorkspace(store, workspaceId);
            try {
                Workspace previousRouting = workspace;
                Workspace currentRouting = refreshRouting(workspace);
                reconcileLineRemotes(workspaceId);
                Set<String> failedRemoteSources;
                if (workspace.lastCodeSyncAt().isEmpty()) {
                    managedGit.provision(previousRouting, currentRouting);
                    failedRemoteSources = ImmutableSet.of();
                }
                else {
                    failedRemoteSources = managedGit.synchronize(previousRouting, currentRouting);
                }
                Instant now = clock.instant();
                Workspace latest = mergeRouting(requireWorkspace(store, workspaceId), currentRouting, now);
                Workspace synced = store.save(latest.withCodeSyncAt(Optional.of(now), now));
                return WorkspaceCodeSyncResult.success(synced, failedRemoteSources);
            }
            catch (RuntimeException failure) {
                log.warn("Workspace {} code synchronization failed: {}", workspace.id(), failure.getMessage());
                return WorkspaceCodeSyncResult.failure(requireWorkspace(store, workspaceId));
            }
        });
    }

    /**
     * The checkout fetches exactly the remotes its repo line names. A cherry-pick reads its source remote only while
     * previewing and starting, so removing one never affects a cherry-pick that already started.
     */
    private void reconcileLineRemotes(String workspaceId)
    {
        Collection<RemoteSource> wanted = relationships.lineRemotes(workspaceId).values();
        var existing = remoteSources.findByWorkspace(workspaceId);
        for (RemoteSource remote : existing) {
            if (wanted.stream().noneMatch(value -> value.name().equals(remote.name()))) {
                remoteSources.delete(workspaceId, remote.name());
            }
        }
        for (RemoteSource remote : wanted) {
            if (!existing.contains(remote)) {
                remoteSources.save(remote);
            }
        }
    }

    private Workspace refreshRouting(Workspace workspace)
    {
        Optional<WorkspaceHostingBinding> binding = store.findHostingBinding(workspace.id());
        if (binding.isPresent()) {
            return refreshHostedRouting(workspace, binding.orElseThrow());
        }
        WorkspaceException.requireGitHub(workspace);
        String token = credentials.resolveGitHubToken();
        UserRepo selected = gitHub.fetchRepository(token, workspace.selectedRepositoryId())
                .orElseThrow(() -> new ManagedWorkspaceGit.Failure(
                        "GitHub repository is no longer accessible. Restore access and retry.",
                        "Repository ID is unavailable: " + workspace.selectedRepositoryId()));
        if (selected.id() != workspace.selectedRepositoryId()) {
            throw routingChanged("Selected repository identity changed");
        }

        RepoRef selectedRef = RepoRef.of(selected.owner(), selected.name());
        Optional<UserRepo> upstream = workspace.upstreamRepositoryId()
                .map(repositoryId -> {
                    UserRepo current = gitHub.fetchRepository(token, repositoryId)
                            .orElseThrow(() -> routingChanged("Upstream repository is no longer accessible"));
                    if (current.id() != repositoryId) {
                        throw routingChanged("Upstream repository identity changed");
                    }
                    return current;
                });
        return workspace.withRepositoryRouting(
                selectedRef,
                upstream.map(UserRepo::id),
                upstream.map(value -> RepoRef.of(value.owner(), value.name())),
                upstream.map(UserRepo::id).orElse(selected.id()),
                upstream.map(value -> RepoRef.of(value.owner(), value.name())).orElse(selectedRef),
                selected.defaultBranch(),
                selected.cloneUrl(),
                upstream.map(UserRepo::defaultBranch),
                upstream.map(UserRepo::cloneUrl),
                clock.instant());
    }

    private Workspace refreshHostedRouting(Workspace workspace, WorkspaceHostingBinding binding)
    {
        HostingReadSession session = hosting.readSession(binding.accountId());
        HostingRepository selected = hosting.resolveRepositoryById(session, Long.toString(workspace.selectedRepositoryId()));
        if (selected.id() != binding.selectedRepositoryId()) {
            throw routingChanged("Selected repository identity changed");
        }
        Optional<HostingRepository> upstream = workspace.upstreamRepositoryId().map(id -> hosting.resolveRepositoryById(session, id.toString()));
        if (!upstream.map(HostingRepository::id).equals(binding.upstreamRepositoryId())) {
            throw routingChanged("Upstream repository identity changed");
        }
        RepoRef selectedRef = WorkspaceHostingService.repositoryRef(selected);
        return workspace.withRepositoryRouting(
                selectedRef,
                workspace.upstreamRepositoryId(),
                upstream.map(WorkspaceHostingService::repositoryRef),
                workspace.monitoredRepositoryId(),
                upstream.map(WorkspaceHostingService::repositoryRef).orElse(selectedRef),
                selected.defaultBranch().orElseThrow(() -> routingChanged("Selected repository has no default branch")),
                selected.httpCloneUrl().toASCIIString(),
                upstream.map(value -> value.defaultBranch().orElseThrow(() -> routingChanged("Upstream repository has no default branch"))),
                upstream.map(value -> value.httpCloneUrl().toASCIIString()),
                clock.instant());
    }

    private static Workspace mergeRouting(Workspace latest, Workspace routing, Instant now)
    {
        return latest.withRepositoryRouting(
                routing.selectedRepository(),
                routing.upstreamRepositoryId(),
                routing.upstreamRepository(),
                routing.monitoredRepositoryId(),
                routing.monitoredRepository(),
                routing.defaultBranch(),
                routing.originCloneUrl(),
                routing.upstreamDefaultBranch(),
                routing.upstreamCloneUrl(),
                now);
    }

    private static ManagedWorkspaceGit.Failure routingChanged(String internalMessage)
    {
        return new ManagedWorkspaceGit.Failure("Workspace repository routing changed. Review the upstream repository and retry.", internalMessage);
    }
}

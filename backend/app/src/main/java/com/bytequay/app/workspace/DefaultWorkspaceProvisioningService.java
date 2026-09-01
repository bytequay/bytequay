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
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.UserRepo;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.WorkspaceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.Lock;

import static java.util.Objects.requireNonNull;

@Service
public final class DefaultWorkspaceProvisioningService
        implements WorkspaceProvisioningService
{
    private static final Logger log = LoggerFactory.getLogger(DefaultWorkspaceProvisioningService.class);
    private final WorkspaceStore store;
    private final ManagedWorkspaceGit managedGit;
    private final GitHubAccountClient gitHub;
    private final CredentialService credentials;
    private final Clock clock;

    public DefaultWorkspaceProvisioningService(
            WorkspaceStore store,
            ManagedWorkspaceGit managedGit,
            GitHubAccountClient gitHub,
            CredentialService credentials,
            Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.managedGit = requireNonNull(managedGit, "managedGit is null");
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public WorkspaceProvisioningResult provision(String workspaceId)
    {
        Lock lock = WorkspaceOperationLocks.get(workspaceId);
        lock.lock();
        try {
            Workspace workspace = requireWorkspace(workspaceId);
            try {
                boolean alreadyReady = workspace.lastCodeSyncAt().isPresent();
                Workspace previousRouting = workspace;
                Workspace currentRouting = refreshRouting(workspace);
                if (alreadyReady) {
                    managedGit.validate(previousRouting, currentRouting);
                }
                else {
                    managedGit.provision(previousRouting, currentRouting);
                }
                Instant now = clock.instant();
                Workspace latest = mergeRouting(requireWorkspace(workspaceId), currentRouting, now);
                Workspace synced = store.save(latest.withCodeSyncAt(Optional.of(now), now));
                return WorkspaceProvisioningResult.success(synced);
            }
            catch (RuntimeException failure) {
                logFailure(workspace, failure);
                return WorkspaceProvisioningResult.failure(requireWorkspace(workspaceId));
            }
        }
        finally {
            lock.unlock();
        }
    }

    @Override
    public WorkspaceCodeSyncResult synchronizeCode(String workspaceId)
    {
        Lock lock = WorkspaceOperationLocks.get(workspaceId);
        lock.lock();
        try {
            Workspace workspace = requireWorkspace(workspaceId);
            if (workspace.lastCodeSyncAt().isEmpty()) {
                WorkspaceProvisioningResult result = provision(workspaceId);
                Workspace latest = requireWorkspace(result.workspaceId());
                return result.successful()
                        ? WorkspaceCodeSyncResult.success(latest)
                        : WorkspaceCodeSyncResult.failure(latest);
            }
            try {
                Workspace previousRouting = workspace;
                Workspace currentRouting = refreshRouting(workspace);
                Set<String> failedRemoteSources = managedGit.synchronize(previousRouting, currentRouting);
                Instant now = clock.instant();
                Workspace latest = mergeRouting(requireWorkspace(workspaceId), currentRouting, now);
                Workspace synced = store.save(latest.withCodeSyncAt(Optional.of(now), now));
                return WorkspaceCodeSyncResult.success(synced, failedRemoteSources);
            }
            catch (RuntimeException failure) {
                logFailure(workspace, failure);
                return WorkspaceCodeSyncResult.failure(requireWorkspace(workspaceId));
            }
        }
        finally {
            lock.unlock();
        }
    }

    private Workspace refreshRouting(Workspace workspace)
    {
        String token = credentials.resolveGitHubToken();
        UserRepo selected = gitHub.fetchRepository(
                        token, workspace.selectedRepositoryId())
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

    private void logFailure(Workspace workspace, RuntimeException failure)
    {
        log.warn("Workspace {} code synchronization failed: {}",
                workspace.id(), failure.getMessage());
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

    private Workspace requireWorkspace(String workspaceId)
    {
        return store.findById(workspaceId)
                .orElseThrow(() -> WorkspaceException.notFound(workspaceId));
    }

    private static ManagedWorkspaceGit.Failure routingChanged(String internalMessage)
    {
        return new ManagedWorkspaceGit.Failure(
                "Workspace repository routing changed. Review the upstream repository and retry.",
                internalMessage);
    }
}

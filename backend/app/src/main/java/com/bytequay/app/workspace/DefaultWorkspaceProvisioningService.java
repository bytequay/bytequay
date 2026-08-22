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
import com.bytequay.core.domain.Workspace.CodeState;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.util.concurrent.Striped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
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
    private final Striped<Lock> locks = Striped.lock(64);

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
        Lock lock = locks.get(requireNonNull(workspaceId, "workspaceId is null"));
        lock.lock();
        try {
            Workspace workspace = requireWorkspace(workspaceId);
            try {
                boolean alreadyReady = workspace.codeState() == CodeState.READY;
                Workspace previousRouting = workspace;
                if (!alreadyReady) {
                    workspace = store.save(workspace.withCodeState(
                            CodeState.CLONING,
                            workspace.lastCodeSyncAt(),
                            Optional.empty(),
                            clock.instant()));
                }
                Workspace currentRouting = refreshRouting(workspace);
                if (alreadyReady) {
                    managedGit.validate(previousRouting, currentRouting);
                }
                else {
                    managedGit.provision(previousRouting, currentRouting);
                }
                Instant now = clock.instant();
                Workspace latest = mergeRouting(requireWorkspace(workspaceId), currentRouting, now);
                Workspace ready = store.save(latest.withCodeState(
                        CodeState.READY,
                        alreadyReady ? latest.lastCodeSyncAt() : Optional.of(now),
                        Optional.empty(),
                        now));
                return WorkspaceProvisioningResult.of(ready);
            }
            catch (RuntimeException failure) {
                return WorkspaceProvisioningResult.of(fail(workspace, failure));
            }
        }
        finally {
            lock.unlock();
        }
    }

    @Override
    public WorkspaceCodeSyncResult synchronizeCode(String workspaceId)
    {
        Lock lock = locks.get(requireNonNull(workspaceId, "workspaceId is null"));
        lock.lock();
        try {
            Workspace workspace = requireWorkspace(workspaceId);
            if (workspace.codeState() != CodeState.READY
                    && workspace.codeState() != CodeState.SYNCING) {
                return WorkspaceCodeSyncResult.of(requireWorkspace(provision(workspaceId).workspaceId()));
            }
            try {
                Workspace previousRouting = workspace;
                workspace = store.save(workspace.withCodeState(
                        CodeState.SYNCING,
                        workspace.lastCodeSyncAt(),
                        Optional.empty(),
                        clock.instant()));
                Workspace currentRouting = refreshRouting(workspace);
                managedGit.synchronize(previousRouting, currentRouting);
                Instant now = clock.instant();
                Workspace latest = mergeRouting(requireWorkspace(workspaceId), currentRouting, now);
                Workspace ready = store.save(latest.withCodeState(
                        CodeState.READY,
                        Optional.of(now),
                        Optional.empty(),
                        now));
                return WorkspaceCodeSyncResult.of(ready);
            }
            catch (RuntimeException failure) {
                return WorkspaceCodeSyncResult.of(fail(workspace, failure));
            }
        }
        finally {
            lock.unlock();
        }
    }

    private Workspace refreshRouting(Workspace workspace)
    {
        UserRepo selected = gitHub.fetchRepository(
                        credentials.resolveGitHubToken(), workspace.selectedRepositoryId())
                .orElseThrow(() -> new ManagedWorkspaceGit.Failure(
                        "GitHub repository is no longer accessible. Restore access and retry.",
                        "Repository ID is unavailable: " + workspace.selectedRepositoryId()));
        if (selected.id() != workspace.selectedRepositoryId()) {
            throw routingChanged("Selected repository identity changed");
        }

        RepoRef selectedRef = RepoRef.of(selected.owner(), selected.name());
        Optional<Workspace> upstream = workspace.upstreamWorkspaceId().map(this::requireLinkedWorkspace);
        return workspace.withRepositoryRouting(
                selectedRef,
                upstream.map(Workspace::id),
                upstream.map(Workspace::selectedRepositoryId),
                upstream.map(Workspace::selectedRepository),
                upstream.map(Workspace::selectedRepositoryId).orElse(selected.id()),
                upstream.map(Workspace::selectedRepository).orElse(selectedRef),
                selected.defaultBranch(),
                selected.cloneUrl(),
                upstream.map(Workspace::defaultBranch),
                upstream.map(Workspace::originCloneUrl),
                clock.instant());
    }

    private Workspace fail(Workspace workspace, RuntimeException failure)
    {
        String correlationId = UUID.randomUUID().toString();
        String safeMessage = failure instanceof ManagedWorkspaceGit.Failure expected
                ? expected.safeMessage()
                : "Repository synchronization failed. Check access and retry.";
        log.warn("Workspace {} code synchronization failed [{}]: {}",
                workspace.id(), correlationId, failure.getMessage());
        Workspace latest = store.findById(workspace.id()).orElse(workspace);
        return store.save(latest.withCodeState(
                CodeState.FAILED,
                latest.lastCodeSyncAt(),
                Optional.of(safeMessage + " Reference: " + correlationId),
                clock.instant()));
    }

    private static Workspace mergeRouting(Workspace latest, Workspace routing, Instant now)
    {
        return latest.withRepositoryRouting(
                routing.selectedRepository(),
                routing.upstreamWorkspaceId(),
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

    private Workspace requireLinkedWorkspace(String workspaceId)
    {
        return store.findById(workspaceId)
                .orElseThrow(() -> routingChanged("Linked upstream workspace is no longer watched"));
    }

    private static ManagedWorkspaceGit.Failure routingChanged(String internalMessage)
    {
        return new ManagedWorkspaceGit.Failure(
                "Workspace repository routing changed. Review the upstream link and retry.",
                internalMessage);
    }
}

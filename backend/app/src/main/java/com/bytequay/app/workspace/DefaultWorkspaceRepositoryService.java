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
import com.bytequay.core.cherrypick.CherryPickExecution;
import com.bytequay.core.cherrypick.CherryPickExecutionStore;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.UserRepo;
import com.bytequay.core.domain.WatchedRepo;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.domain.Workspace.CodeState;
import com.bytequay.core.domain.Workspace.MetadataState;
import com.bytequay.core.domain.Workspace.Topology;
import com.bytequay.core.store.WatchedRepoStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.Executor;

import static com.bytequay.app.config.AsyncConfig.APPLICATION_EXECUTOR;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Service
public class DefaultWorkspaceRepositoryService
        implements WorkspaceRepositoryService
{
    private static final Logger log = LoggerFactory.getLogger(DefaultWorkspaceRepositoryService.class);

    private final WorkspaceStore store;
    private final WatchedRepoStore legacyStore;
    private final CherryPickExecutionStore executions;
    private final GitHubAccountClient gitHub;
    private final CredentialService credentials;
    private final Clock clock;
    private final WorkspacePaths paths;
    private final WorkspaceProvisioningService provisioning;
    private final Executor executor;

    public DefaultWorkspaceRepositoryService(
            WorkspaceStore store,
            WatchedRepoStore legacyStore,
            CherryPickExecutionStore executions,
            GitHubAccountClient gitHub,
            CredentialService credentials,
            Clock clock,
            WorkspacePaths paths,
            WorkspaceProvisioningService provisioning,
            @Qualifier(APPLICATION_EXECUTOR) Executor executor)
    {
        this.store = requireNonNull(store, "store is null");
        this.legacyStore = requireNonNull(legacyStore, "legacyStore is null");
        this.executions = requireNonNull(executions, "executions is null");
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.paths = requireNonNull(paths, "paths is null");
        this.provisioning = requireNonNull(provisioning, "provisioning is null");
        this.executor = requireNonNull(executor, "executor is null");
    }

    @Override
    public List<RepositorySearchResult> searchRepositories(String query, int limit)
    {
        checkArgument(limit > 0 && limit <= 30, "limit must be between 1 and 30");
        String pat = credentials.resolveGitHubToken();
        String trimmed = Optional.ofNullable(query).map(String::trim).orElse("");
        List<UserRepo> repositories;
        int slash = trimmed.indexOf('/');
        if (trimmed.isEmpty()) {
            repositories = gitHub.fetchUserRepos(pat);
        }
        else if (slash > 0 && slash == trimmed.lastIndexOf('/') && slash < trimmed.length() - 1) {
            String owner = trimmed.substring(0, slash).trim();
            String name = trimmed.substring(slash + 1).trim();
            Optional<UserRepo> exact = gitHub.fetchRepository(pat, owner, name);
            repositories = exact.isPresent()
                    ? ImmutableList.of(exact.orElseThrow())
                    : gitHub.searchRepositories(pat, name + " in:name user:" + owner);
        }
        else {
            repositories = gitHub.searchRepositories(pat, trimmed + " in:name");
        }
        return repositories.stream()
                .filter(DefaultWorkspaceRepositoryService::validSearchResult)
                .limit(limit)
                .map(this::toSearchResult)
                .collect(toImmutableList());
    }

    @Override
    public WatchRepositoryPreview previewWatch(long selectedRepositoryId)
    {
        checkArgument(selectedRepositoryId > 0, "selectedRepositoryId must be positive");
        UserRepo selected = gitHub.fetchRepository(credentials.resolveGitHubToken(), selectedRepositoryId)
                .orElseThrow(() -> WorkspaceException.repositoryNotFound(selectedRepositoryId));
        validate(selected);
        return toPreview(selected);
    }

    @Override
    public Workspace watch(WatchRepositoryCommand command)
    {
        requireNonNull(command, "command is null");
        UserRepo selected = gitHub.fetchRepository(
                        credentials.resolveGitHubToken(), command.selectedRepositoryId())
                .orElseThrow(() -> WorkspaceException.repositoryNotFound(command.selectedRepositoryId()));
        validate(selected);
        Optional<Workspace> existing = store.findBySelectedRepositoryId(selected.id());
        if (existing.isPresent()) {
            Workspace workspace = existing.orElseThrow();
            scheduleIncompleteCode(workspace);
            return workspace;
        }
        if (!sameFingerprint(toPreview(selected).fingerprint(), command.previewFingerprint())) {
            throw WorkspaceException.previewChanged();
        }
        return createWorkspace(selected, command.name(), OptionalInt.empty());
    }

    @Override
    public List<WorkspaceSummary> listWorkspaces()
    {
        migrateLegacyRows();
        return store.findAll().stream().map(WorkspaceSummary::of).collect(toImmutableList());
    }

    @Override
    public WorkspaceDetails getWorkspace(String workspaceId)
    {
        return WorkspaceDetails.of(requireWorkspace(workspaceId));
    }

    @Override
    public WorkspaceRepositoryContext requireReadyRepository(String workspaceId)
    {
        Workspace workspace = requireWorkspace(workspaceId);
        if (workspace.codeState() != CodeState.READY) {
            throw WorkspaceException.repositoryNotReady();
        }
        var repository = paths.repository(workspace);
        return new WorkspaceRepositoryContext(
                workspace.id(),
                repository,
                workspace.selectedRepository(),
                workspace.monitoredRepository(),
                workspace.topology(),
                workspace.topology() == Topology.FORK ? "upstream" : "origin",
                "origin",
                workspace.defaultBranch());
    }

    @Override
    public Workspace requestSync(String workspaceId)
    {
        Workspace workspace = requireWorkspace(workspaceId);
        CodeState code = workspace.codeState();
        if (code == CodeState.CLONING || code == CodeState.SYNCING) {
            return workspace;
        }
        MetadataState metadata = workspace.metadataState();
        boolean synchronize = code == CodeState.READY;
        code = synchronize ? CodeState.SYNCING : CodeState.PENDING;
        if (metadata != MetadataState.SYNCING) {
            metadata = MetadataState.PENDING;
        }
        Workspace accepted = store.save(workspace.withStates(code, metadata, clock.instant()));
        if (synchronize) {
            submit(() -> provisioning.synchronizeCode(workspaceId), workspaceId);
        }
        else {
            submit(() -> provisioning.provision(workspaceId), workspaceId);
        }
        return accepted;
    }

    @Override
    public void unwatch(String workspaceId)
    {
        Workspace workspace = requireWorkspace(workspaceId);
        if (workspace.codeState() == CodeState.PENDING
                || workspace.codeState() == CodeState.CLONING
                || workspace.codeState() == CodeState.SYNCING) {
            throw WorkspaceException.busy();
        }
        boolean active = executions.listRecent(CherryPickExecutionStore.MAX_LIST_LIMIT).stream()
                .filter(execution -> execution.terminalAt().isEmpty())
                .map(CherryPickExecution::workspace)
                .anyMatch(executionWorkspace -> executionWorkspace.workspaceId()
                        .map(workspace.id()::equals)
                        .orElseGet(() -> executionWorkspace.repository().equals(workspace.selectedRepository())
                                || executionWorkspace.repository().equals(workspace.monitoredRepository())));
        if (active) {
            throw WorkspaceException.busy();
        }
        store.delete(workspace.id());
    }

    private Workspace createWorkspace(
            UserRepo selected,
            Optional<String> name,
            OptionalInt retainedDisplayOrder)
    {
        validate(selected);

        Optional<Workspace> existing = store.findBySelectedRepositoryId(selected.id());
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }

        int displayOrder = retainedDisplayOrder.orElseGet(() -> store.findAll().stream()
                .mapToInt(Workspace::displayOrder)
                .max()
                .orElse(-1) + 1);
        String id = UUID.randomUUID().toString();
        Instant now = clock.instant();
        RepoRef selectedRef = RepoRef.of(selected.owner(), selected.name());
        Optional<UserRepo.Upstream> upstream = selected.upstream();
        Optional<Long> upstreamId = upstream.map(UserRepo.Upstream::id);
        Optional<RepoRef> upstreamRef = upstream.map(repository ->
                RepoRef.of(repository.owner(), repository.name()));
        Topology topology = selected.fork() ? Topology.FORK : Topology.DIRECT;
        long monitoredId = upstreamId.orElse(selected.id());
        RepoRef monitoredRef = upstreamRef.orElse(selectedRef);
        String defaultBranch = upstream.map(UserRepo.Upstream::defaultBranch)
                .orElse(selected.defaultBranch());
        Workspace proposed = new Workspace(
                id,
                name.orElse(selected.fullName()),
                selected.id(),
                selectedRef,
                upstreamId,
                upstreamRef,
                monitoredId,
                monitoredRef,
                topology,
                defaultBranch,
                selected.cloneUrl(),
                upstream.map(UserRepo.Upstream::cloneUrl),
                paths.workspace(id).resolve("repository").toString(),
                CodeState.PENDING,
                MetadataState.PENDING,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                displayOrder,
                now,
                now);
        Workspace saved = store.createOrGet(proposed);
        scheduleIncompleteCode(saved);
        return saved;
    }

    private Workspace requireWorkspace(String workspaceId)
    {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw WorkspaceException.notFound(String.valueOf(workspaceId));
        }
        return store.findById(workspaceId)
                .orElseThrow(() -> WorkspaceException.notFound(workspaceId));
    }

    private void migrateLegacyRows()
    {
        for (WatchedRepo legacy : legacyStore.findAll()) {
            try {
                UserRepo selected = gitHub.fetchRepository(
                                credentials.resolveGitHubToken(), legacy.owner(), legacy.repo())
                        .orElseThrow(() -> WorkspaceException.repositoryNotFound(legacy.owner(), legacy.repo()));
                createWorkspace(
                        selected,
                        Optional.of(legacy.fullName()),
                        OptionalInt.of(legacy.displayOrder()));
                legacyStore.remove(legacy.owner(), legacy.repo());
            }
            catch (RuntimeException failure) {
                log.warn("Legacy watched repository migration deferred for {}/{}: {}",
                        legacy.owner(), legacy.repo(), failure.getMessage());
            }
        }
    }

    private RepositorySearchResult toSearchResult(UserRepo repository)
    {
        Optional<RepoRef> upstream = repository.upstream()
                .map(value -> RepoRef.of(value.owner(), value.name()));
        Optional<String> existingWorkspaceId = store.findBySelectedRepositoryId(repository.id())
                .map(Workspace::id);
        return new RepositorySearchResult(
                repository.id(),
                RepoRef.of(repository.owner(), repository.name()),
                repository.fullName(),
                repository.description(),
                repository.visibility(),
                repository.fork(),
                repository.defaultBranch(),
                repository.cloneUrl(),
                upstream,
                repository.viewerCanPush(),
                existingWorkspaceId);
    }

    private WatchRepositoryPreview toPreview(UserRepo selected)
    {
        RepoRef selectedRef = RepoRef.of(selected.owner(), selected.name());
        Optional<UserRepo.Upstream> upstream = selected.upstream();
        Optional<Long> upstreamId = upstream.map(UserRepo.Upstream::id);
        Optional<RepoRef> upstreamRef = upstream.map(value -> RepoRef.of(value.owner(), value.name()));
        Topology topology = selected.fork() ? Topology.FORK : Topology.DIRECT;
        long monitoredId = upstreamId.orElse(selected.id());
        RepoRef monitored = upstreamRef.orElse(selectedRef);
        String defaultBranch = upstream.map(UserRepo.Upstream::defaultBranch).orElse(selected.defaultBranch());
        String developmentRemote = topology == Topology.FORK ? "upstream" : "origin";
        return new WatchRepositoryPreview(
                selected.id(),
                selectedRef,
                selected.visibility(),
                topology,
                upstreamId,
                upstreamRef,
                monitoredId,
                monitored,
                defaultBranch,
                developmentRemote + "/" + defaultBranch,
                "origin",
                selected.viewerCanPush(),
                monitored,
                fingerprint(selected),
                store.findBySelectedRepositoryId(selected.id()).map(Workspace::id));
    }

    private static boolean validSearchResult(UserRepo repository)
    {
        return repository.id() > 0
                && !isBlank(repository.owner())
                && !isBlank(repository.name())
                && !isBlank(repository.fullName())
                && !isBlank(repository.defaultBranch())
                && safeCloneUrl(repository.cloneUrl());
    }

    private static String fingerprint(UserRepo repository)
    {
        String upstream = repository.upstream()
                .map(value -> String.join("\0",
                        Long.toString(value.id()), value.owner(), value.name(),
                        value.defaultBranch(), value.cloneUrl()))
                .orElse("-");
        String material = String.join("\0",
                Long.toString(repository.id()), repository.owner(), repository.name(),
                Boolean.toString(repository.fork()), repository.visibility(),
                Boolean.toString(repository.viewerCanPush()), repository.defaultBranch(),
                repository.cloneUrl(), upstream);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static boolean sameFingerprint(String current, String confirmed)
    {
        return MessageDigest.isEqual(
                current.getBytes(StandardCharsets.UTF_8),
                confirmed.getBytes(StandardCharsets.UTF_8));
    }

    private static void validate(UserRepo repository)
    {
        if (repository.id() <= 0
                || isBlank(repository.owner())
                || isBlank(repository.name())
                || isBlank(repository.fullName())
                || isBlank(repository.defaultBranch())
                || !safeCloneUrl(repository.cloneUrl())) {
            throw WorkspaceException.invalidRepository("GitHub returned incomplete repository routing.");
        }
        boolean validUpstream = repository.upstream()
                .filter(upstream -> upstream.id() > 0)
                .filter(upstream -> !isBlank(upstream.owner()))
                .filter(upstream -> !isBlank(upstream.name()))
                .filter(upstream -> !isBlank(upstream.defaultBranch()))
                .filter(upstream -> safeCloneUrl(upstream.cloneUrl()))
                .isPresent();
        if (repository.fork() && !validUpstream) {
            throw WorkspaceException.invalidRepository("Fork upstream routing is unavailable.");
        }
        if (!repository.fork() && repository.upstream().isPresent()) {
            throw WorkspaceException.invalidRepository("Direct repository unexpectedly has upstream routing.");
        }
    }

    private static boolean safeCloneUrl(String value)
    {
        if (isBlank(value)) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && !isBlank(uri.getHost())
                    && uri.getUserInfo() == null
                    && uri.getQuery() == null
                    && uri.getFragment() == null;
        }
        catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean isBlank(String value)
    {
        return value == null || value.isBlank();
    }

    private void scheduleIncompleteCode(Workspace workspace)
    {
        if (workspace.codeState() == CodeState.PENDING
                || workspace.codeState() == CodeState.CLONING) {
            submit(() -> provisioning.provision(workspace.id()), workspace.id());
        }
        else if (workspace.codeState() == CodeState.SYNCING) {
            submit(() -> provisioning.synchronizeCode(workspace.id()), workspace.id());
        }
    }

    private void submit(Runnable task, String workspaceId)
    {
        try {
            executor.execute(() -> {
                try {
                    task.run();
                }
                catch (RuntimeException failure) {
                    log.warn("Workspace {} background synchronization stopped: {}",
                            workspaceId, failure.getMessage());
                }
            });
        }
        catch (RuntimeException rejected) {
            log.warn("Workspace {} background synchronization could not be scheduled: {}",
                    workspaceId, rejected.getMessage());
        }
    }
}

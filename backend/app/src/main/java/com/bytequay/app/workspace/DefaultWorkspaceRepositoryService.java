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

import com.bytequay.app.repository.RepositoryEnrichmentService;
import com.bytequay.app.repository.github.GitHubAccountClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.pr.PullRequestRefreshService;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.UserRepo;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.domain.Workspace.Topology;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.Lock;

import static com.bytequay.app.config.AsyncConfig.APPLICATION_EXECUTOR;
import static com.bytequay.app.git.RepositoryUrls.isPlainHttps;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

@Service
public class DefaultWorkspaceRepositoryService
        implements WorkspaceRepositoryService
{
    private static final Logger log = LoggerFactory.getLogger(DefaultWorkspaceRepositoryService.class);
    private static final Duration CODE_FRESHNESS = Duration.ofMinutes(5);

    private final WorkspaceStore store;
    private final RepositoryEnrichmentService enrichment;
    private final GitHubAccountClient gitHub;
    private final CredentialService credentials;
    private final Clock clock;
    private final WorkspacePaths paths;
    private final WorkspaceProvisioningService provisioning;
    private final WorkspaceMetadataService metadata;
    private final PullRequestRefreshService pullRequestRefresh;
    private final Executor executor;
    private final Set<String> codeRefreshes = ConcurrentHashMap.newKeySet();
    private final Set<String> metadataRefreshes = ConcurrentHashMap.newKeySet();

    public DefaultWorkspaceRepositoryService(
            WorkspaceStore store,
            RepositoryEnrichmentService enrichment,
            GitHubAccountClient gitHub,
            CredentialService credentials,
            Clock clock,
            WorkspacePaths paths,
            WorkspaceProvisioningService provisioning,
            WorkspaceMetadataService metadata,
            PullRequestRefreshService pullRequestRefresh,
            @Qualifier(APPLICATION_EXECUTOR) Executor executor)
    {
        this.store = requireNonNull(store, "store is null");
        this.enrichment = requireNonNull(enrichment, "enrichment is null");
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.paths = requireNonNull(paths, "paths is null");
        this.provisioning = requireNonNull(provisioning, "provisioning is null");
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.pullRequestRefresh = requireNonNull(pullRequestRefresh, "pullRequestRefresh is null");
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
        var credential = credentials.resolveGitHubCredential();
        return toPreview(resolveWatch(selectedRepositoryId, credential.token(), credential.profile().login()));
    }

    @Override
    // ponytail: watch confirmations serialize fork creation; use per-account locks if concurrent watching becomes common.
    public synchronized Workspace watch(WatchRepositoryCommand command)
    {
        requireNonNull(command, "command is null");
        var credential = credentials.resolveGitHubCredential();
        WatchRouting routing = resolveWatch(command.selectedRepositoryId(), credential.token(), credential.profile().login());
        Optional<Workspace> existing = routing.pushRepository().flatMap(value -> store.findBySelectedRepositoryId(value.id()));
        if (existing.filter(value -> store.isWatched(value.id()) && matchesWatch(value, routing)).isPresent()) {
            Workspace workspace = existing.orElseThrow();
            scheduleIncompleteWork(workspace);
            return workspace;
        }
        if (!sameFingerprint(toPreview(routing).fingerprint(), command.previewFingerprint())) {
            throw WorkspaceException.previewChanged();
        }
        if (routing.pushRepository().isEmpty() && !command.allowForkCreation()) {
            throw WorkspaceException.forkConfirmationRequired();
        }
        UserRepo pushRepository = routing.pushRepository()
                .orElseGet(() -> gitHub.createFork(credential.token(), routing.monitoredRepository()));
        validate(pushRepository);
        if (routing.fork() && (!pushRepository.owner().equalsIgnoreCase(routing.login())
                || !pushRepository.fork()
                || pushRepository.upstream().filter(value -> value.id() == routing.monitoredRepository().id()).isEmpty())) {
            throw WorkspaceException.invalidRepository("GitHub did not return your fork of the monitored repository. Review the watch preview and retry.");
        }
        if (existing.isPresent()) {
            Workspace workspace = existing.orElseThrow();
            return WorkspaceOperationLocks.withLock(workspace.id(), () -> {
                setUpstream(workspace.id(), routing.upstream().map(UserRepo::id));
                store.setWatched(workspace.id(), true);
                return requestSyncAfterConfigurationChange(workspace.id());
            });
        }
        return createWorkspace(pushRepository, routing.upstream(), command.name());
    }

    @Override
    public List<WorkspaceSummary> listWorkspaces()
    {
        return store.findAll().stream().map(WorkspaceSummary::of).collect(toImmutableList());
    }

    @Override
    public List<WorkspaceSummary> listRetiredWorkspaces()
    {
        Set<String> watchedIds = store.findAll().stream().map(Workspace::id).collect(toImmutableSet());
        return store.findAllRetained().stream()
                .filter(workspace -> !watchedIds.contains(workspace.id()))
                .map(WorkspaceSummary::of)
                .collect(toImmutableList());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverIncompleteWork()
    {
        store.findAll().forEach(workspace -> scheduleRefreshes(workspace, false));
    }

    @Override
    public WorkspaceDetails getWorkspace(String workspaceId)
    {
        return WorkspaceDetails.of(requireWorkspace(workspaceId));
    }

    @Override
    public WorkspaceRepositoryContext requireReadyRepository(String workspaceId)
    {
        return repositoryContext(requireSynchronizedWorkspace(workspaceId));
    }

    private Workspace requireSynchronizedWorkspace(String workspaceId)
    {
        Workspace workspace = requireWorkspace(workspaceId);
        if (workspace.lastCodeSyncAt().isEmpty()) {
            provisioning.synchronizeCode(workspaceId);
            workspace = requireWorkspace(workspaceId);
            if (workspace.lastCodeSyncAt().isEmpty()) {
                throw WorkspaceException.repositoryNotReady();
            }
        }
        return workspace;
    }

    private WorkspaceRepositoryContext repositoryContext(Workspace workspace)
    {
        var repository = paths.repository(workspace);
        return new WorkspaceRepositoryContext(
                workspace.id(),
                repository,
                workspace.selectedRepositoryId(),
                workspace.selectedRepository(),
                workspace.upstreamRepositoryId().isPresent()
                        ? OptionalLong.of(workspace.upstreamRepositoryId().orElseThrow())
                        : OptionalLong.empty(),
                workspace.monitoredRepository(),
                workspace.upstreamRepositoryId().isPresent() ? "upstream" : "origin",
                "origin",
                workspace.upstreamDefaultBranch().orElse(workspace.defaultBranch()),
                enrichment.enrichRemoteSources(workspace.id()));
    }

    @Override
    public Workspace requestSync(String workspaceId)
    {
        Workspace workspace = requireWorkspace(workspaceId);
        scheduleRefreshes(workspace, false);
        submit(() -> pullRequestRefresh.refreshWorkspace(workspaceId), workspaceId);
        return workspace;
    }

    @Override
    public Workspace requestSyncAfterConfigurationChange(String workspaceId)
    {
        Workspace workspace = requireWorkspace(workspaceId);
        scheduleRefreshes(workspace, true);
        submit(() -> pullRequestRefresh.refreshWorkspace(workspaceId), workspaceId);
        return workspace;
    }

    @Override
    public Workspace setUpstream(String workspaceId, Optional<Long> upstreamRepositoryId)
    {
        requireNonNull(upstreamRepositoryId, "upstreamRepositoryId is null");
        Workspace workspace = requireWorkspace(workspaceId);
        if (workspace.upstreamRepositoryId().equals(upstreamRepositoryId)) {
            return workspace;
        }
        if (codeRefreshes.contains(workspace.id()) || store.hasActiveWorkflows(workspace.id())) {
            throw WorkspaceException.busy();
        }
        Optional<UserRepo> upstream = upstreamRepositoryId.map(id -> requireUpstream(workspace, id));
        Instant now = clock.instant();
        Workspace linked = upstream
                .map(value -> workspace.withRepositoryRouting(
                        workspace.selectedRepository(),
                        Optional.of(value.id()),
                        Optional.of(RepoRef.of(value.owner(), value.name())),
                        value.id(),
                        RepoRef.of(value.owner(), value.name()),
                        workspace.defaultBranch(),
                        workspace.originCloneUrl(),
                        Optional.of(value.defaultBranch()),
                        Optional.of(value.cloneUrl()),
                        now))
                .orElseGet(() -> workspace.withRepositoryRouting(
                        workspace.selectedRepository(),
                        Optional.empty(),
                        Optional.empty(),
                        workspace.selectedRepositoryId(),
                        workspace.selectedRepository(),
                        workspace.defaultBranch(),
                        workspace.originCloneUrl(),
                        Optional.empty(),
                        Optional.empty(),
                        now));
        Workspace saved = store.save(linked.withMetadataSyncAt(Optional.empty(), now));
        return requestSyncAfterConfigurationChange(saved.id());
    }

    @Override
    public Workspace rename(String workspaceId, String name)
    {
        String trimmed = requireNonNull(name, "name is null").trim();
        if (trimmed.isEmpty() || trimmed.length() > 200) {
            throw WorkspaceException.invalidRepository("Workspace name must be 1 to 200 characters.");
        }
        Workspace workspace = requireWorkspace(workspaceId);
        if (workspace.name().equals(trimmed)) {
            return workspace;
        }
        return store.save(workspace.withName(trimmed, clock.instant()));
    }

    @Override
    public void unwatch(String workspaceId)
    {
        requireWorkspace(workspaceId);
        store.setWatched(workspaceId, false);
    }

    private Workspace createWorkspace(
            UserRepo selected,
            Optional<UserRepo> upstream,
            Optional<String> name)
    {
        validate(selected);

        Optional<Workspace> existing = store.findBySelectedRepositoryId(selected.id());
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }

        int displayOrder = store.findAllRetained().stream().mapToInt(Workspace::displayOrder).max().orElse(-1) + 1;
        String id = UUID.randomUUID().toString();
        Instant now = clock.instant();
        RepoRef selectedRef = RepoRef.of(selected.owner(), selected.name());
        Topology topology = selected.fork() ? Topology.FORK : Topology.DIRECT;
        Workspace proposed = new Workspace(
                id,
                name.orElseGet(() -> upstream.map(UserRepo::fullName).orElse(selected.fullName())),
                selected.id(),
                selectedRef,
                upstream.map(UserRepo::id),
                upstream.map(DefaultWorkspaceRepositoryService::repositoryRef),
                upstream.map(UserRepo::id).orElse(selected.id()),
                upstream.map(DefaultWorkspaceRepositoryService::repositoryRef).orElse(selectedRef),
                topology,
                selected.defaultBranch(),
                selected.cloneUrl(),
                upstream.map(UserRepo::defaultBranch),
                upstream.map(UserRepo::cloneUrl),
                paths.workspace(id).resolve("repository").toString(),
                Optional.empty(),
                Optional.empty(),
                displayOrder,
                now,
                now);
        Workspace saved = store.createOrGet(proposed);
        scheduleIncompleteWork(saved);
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

    private UserRepo requireUpstream(Workspace workspace, long upstreamRepositoryId)
    {
        if (upstreamRepositoryId <= 0) {
            throw WorkspaceException.invalidUpstream("Choose a GitHub repository to link.");
        }
        if (workspace.selectedRepositoryId() == upstreamRepositoryId) {
            throw WorkspaceException.invalidUpstream("A workspace cannot use its selected repository as upstream.");
        }
        UserRepo upstream = gitHub.fetchRepository(credentials.resolveGitHubToken(), upstreamRepositoryId)
                .orElseThrow(() -> WorkspaceException.invalidUpstream(
                        "The upstream repository is not readable by the connected GitHub account."));
        if (upstream.id() != upstreamRepositoryId) {
            throw WorkspaceException.invalidUpstream("The upstream repository identity changed. Search again.");
        }
        validate(upstream);
        return upstream;
    }

    private RepositorySearchResult toSearchResult(UserRepo repository)
    {
        Optional<RepoRef> upstream = repository.upstream()
                .map(value -> RepoRef.of(value.owner(), value.name()));
        Optional<String> existingWorkspaceId = store.findBySelectedRepositoryId(repository.id())
                .filter(value -> store.isWatched(value.id())).map(Workspace::id);
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

    private WatchRouting resolveWatch(long selectedRepositoryId, String token, String login)
    {
        UserRepo selected = requireRepository(token, selectedRepositoryId);
        if (!selected.fork() && selected.owner().equalsIgnoreCase(login)) {
            return new WatchRouting(selected, selected, Optional.of(selected), login);
        }
        UserRepo monitored = selected.fork()
                ? requireRepository(token, selected.upstream().filter(DefaultWorkspaceRepositoryService::validUpstream)
                        .orElseThrow(() -> WorkspaceException.invalidRepository("GitHub did not return the fork's upstream repository. Retry the preview.")).id())
                : selected;
        if (monitored.fork()) {
            throw WorkspaceException.invalidRepository("GitHub did not return the original upstream repository. Retry the preview.");
        }
        if (monitored.owner().equalsIgnoreCase(login)) {
            return new WatchRouting(selected, monitored, Optional.of(monitored), login);
        }
        Optional<UserRepo> pushRepository = selected.fork() && selected.owner().equalsIgnoreCase(login)
                ? Optional.of(selected)
                : gitHub.findOwnedFork(token, monitored, login);
        pushRepository.ifPresent(DefaultWorkspaceRepositoryService::validate);
        return new WatchRouting(selected, monitored, pushRepository, login);
    }

    private UserRepo requireRepository(String token, long repositoryId)
    {
        UserRepo repository = gitHub.fetchRepository(token, repositoryId)
                .orElseThrow(() -> WorkspaceException.repositoryNotFound(repositoryId));
        validate(repository);
        if (repository.id() != repositoryId) {
            throw WorkspaceException.previewChanged();
        }
        return repository;
    }

    private WatchRepositoryPreview toPreview(WatchRouting routing)
    {
        UserRepo selected = routing.selectedRepository();
        UserRepo monitored = routing.monitoredRepository();
        RepoRef monitoredRef = repositoryRef(monitored);
        RepoRef pushRef = routing.pushRepository().map(DefaultWorkspaceRepositoryService::repositoryRef)
                .orElseGet(() -> RepoRef.of(routing.login(), monitored.name()));
        return new WatchRepositoryPreview(
                selected.id(),
                repositoryRef(selected),
                monitored.visibility(),
                routing.fork() ? Topology.FORK : Topology.DIRECT,
                routing.upstream().map(UserRepo::id),
                routing.upstream().map(DefaultWorkspaceRepositoryService::repositoryRef),
                monitored.id(),
                monitoredRef,
                monitored.defaultBranch(),
                (routing.fork() ? "upstream/" : "origin/") + monitored.defaultBranch(),
                "origin",
                routing.pushRepository().map(UserRepo::viewerCanPush).orElse(false),
                pushRef,
                routing.pushRepository().isEmpty(),
                monitoredRef,
                fingerprint(routing.login() + "\0" + fingerprint(selected) + "\0" + fingerprint(monitored) + "\0"
                        + routing.pushRepository().map(DefaultWorkspaceRepositoryService::fingerprint).orElse("create-fork")),
                routing.pushRepository().flatMap(value -> store.findBySelectedRepositoryId(value.id()))
                        .filter(value -> store.isWatched(value.id()) && matchesWatch(value, routing)).map(Workspace::id));
    }

    private static boolean matchesWatch(Workspace workspace, WatchRouting routing)
    {
        return workspace.upstreamRepositoryId().equals(routing.upstream().map(UserRepo::id));
    }

    private static RepoRef repositoryRef(UserRepo repository)
    {
        return RepoRef.of(repository.owner(), repository.name());
    }

    private record WatchRouting(UserRepo selectedRepository, UserRepo monitoredRepository, Optional<UserRepo> pushRepository, String login)
    {
        boolean fork()
        {
            return !monitoredRepository.owner().equalsIgnoreCase(login);
        }

        Optional<UserRepo> upstream()
        {
            return fork() ? Optional.of(monitoredRepository) : Optional.empty();
        }
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
        return fingerprint(material);
    }

    private static String fingerprint(String material)
    {
        return Hashing.sha256().hashString(material, StandardCharsets.UTF_8).toString();
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
    }

    private static boolean validUpstream(UserRepo.Upstream upstream)
    {
        return upstream.id() > 0
                && !isBlank(upstream.owner())
                && !isBlank(upstream.name())
                && !isBlank(upstream.defaultBranch())
                && safeCloneUrl(upstream.cloneUrl());
    }

    private static boolean safeCloneUrl(String value)
    {
        if (isBlank(value)) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            return isPlainHttps(uri);
        }
        catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean isBlank(String value)
    {
        return value == null || value.isBlank();
    }

    private void scheduleIncompleteWork(Workspace workspace)
    {
        scheduleRefreshes(workspace, false);
        submit(() -> pullRequestRefresh.refreshWorkspace(workspace.id()), workspace.id());
    }

    private void scheduleRefreshes(Workspace workspace, boolean forceCode)
    {
        if (!store.isWatched(workspace.id())) {
            return;
        }
        Instant now = clock.instant();
        if (forceCode || stale(workspace.lastCodeSyncAt(), now, CODE_FRESHNESS)) {
            submitUnique(codeRefreshes, workspace.id(), workspace.lastCodeSyncAt().isPresent()
                    ? () -> provisioning.synchronizeCode(workspace.id())
                    : () -> provisioning.provision(workspace.id()));
        }
        // The metadata service owns each projection's TTL. Always schedule its cheap local
        // freshness check so newly added projections are populated after an app upgrade.
        submitUnique(metadataRefreshes, workspace.id(), () -> metadata.synchronize(workspace.id()));
    }

    private static boolean stale(Optional<Instant> syncedAt, Instant now, Duration freshness)
    {
        return syncedAt.filter(value -> !value.isBefore(now.minus(freshness))).isEmpty();
    }

    private void submitUnique(Set<String> active, String workspaceId, Runnable task)
    {
        if (!active.add(workspaceId)) {
            return;
        }
        try {
            executor.execute(() -> {
                Lock lock = WorkspaceOperationLocks.get(workspaceId);
                lock.lock();
                try {
                    if (store.isWatched(workspaceId)) {
                        task.run();
                    }
                }
                catch (RuntimeException failure) {
                    log.warn("Workspace {} background synchronization stopped: {}",
                            workspaceId, failure.getMessage());
                }
                finally {
                    active.remove(workspaceId);
                    lock.unlock();
                }
            });
        }
        catch (RuntimeException rejected) {
            active.remove(workspaceId);
            log.warn("Workspace {} background synchronization could not be scheduled: {}",
                    workspaceId, rejected.getMessage());
        }
    }

    private void submit(Runnable task, String workspaceId)
    {
        try {
            executor.execute(() -> {
                Lock lock = WorkspaceOperationLocks.get(workspaceId);
                lock.lock();
                try {
                    if (store.isWatched(workspaceId)) {
                        task.run();
                    }
                }
                catch (RuntimeException failure) {
                    log.warn("Workspace {} background synchronization stopped: {}",
                            workspaceId, failure.getMessage());
                }
                finally {
                    lock.unlock();
                }
            });
        }
        catch (RuntimeException rejected) {
            log.warn("Workspace {} background synchronization could not be scheduled: {}",
                    workspaceId, rejected.getMessage());
        }
    }
}

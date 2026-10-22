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

import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.app.repository.github.GitHubAccountClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.pr.PullRequestRefreshService;
import com.bytequay.core.domain.RemoteSource;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.UserRepo;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.domain.Workspace.Topology;
import com.bytequay.core.store.RemoteSourceStore;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import static com.bytequay.app.config.AsyncConfig.IO_EXECUTOR;
import static com.bytequay.app.git.RepositoryUrls.isPlainHttps;
import static com.bytequay.app.workspace.WorkspaceException.requireWorkspace;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

/** Application boundary for the durable watched-repository workspace lifecycle. */
@Service
public class WorkspaceRepositoryService
{
    private static final Logger log = LoggerFactory.getLogger(WorkspaceRepositoryService.class);
    private static final Duration CODE_FRESHNESS = Duration.ofMinutes(5);

    private final WorkspaceStore store;
    private final RemoteSourceStore remoteSources;
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

    public WorkspaceRepositoryService(
            WorkspaceStore store,
            RemoteSourceStore remoteSources,
            GitHubAccountClient gitHub,
            CredentialService credentials,
            Clock clock,
            WorkspacePaths paths,
            WorkspaceProvisioningService provisioning,
            WorkspaceMetadataService metadata,
            PullRequestRefreshService pullRequestRefresh,
            @Qualifier(IO_EXECUTOR) Executor executor)
    {
        this.store = requireNonNull(store, "store is null");
        this.remoteSources = requireNonNull(remoteSources, "remoteSources is null");
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.paths = requireNonNull(paths, "paths is null");
        this.provisioning = requireNonNull(provisioning, "provisioning is null");
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.pullRequestRefresh = requireNonNull(pullRequestRefresh, "pullRequestRefresh is null");
        this.executor = requireNonNull(executor, "executor is null");
    }

    /**
     * Searches repositories visible to the connected GitHub account without changing state.
     *
     * @param query repository name or owner/name; blank returns recently pushed repositories
     * @param limit maximum number of results, from 1 through 30
     * @return repositories with stable identity, topology hints, clone routing, and permissions
     */
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
            RepositoryIdentity identity = RepositoryIdentity.valueOf(trimmed);
            String owner = identity.owner().trim();
            String name = identity.name().trim();
            Optional<UserRepo> exact = gitHub.fetchRepository(pat, owner, name);
            repositories = exact.isPresent()
                    ? ImmutableList.of(exact.orElseThrow())
                    : gitHub.searchRepositories(pat, name + " in:name user:" + owner);
        }
        else {
            repositories = gitHub.searchRepositories(pat, trimmed + " in:name");
        }
        return repositories.stream()
                .filter(WorkspaceRepositoryService::validSearchResult)
                .limit(limit)
                .map(this::toSearchResult)
                .collect(toImmutableList());
    }

    /**
     * Resolves the chosen direct-clone or personal-fork routing that the user confirms before watching.
     *
     * @param selectedRepositoryId stable GitHub repository identifier
     * @param topology chosen routing mode, or empty to use the recommendation
     * @return current routing, permission state, and confirmation fingerprint
     * @throws WorkspaceException when the repository is unavailable or has unsafe routing
     */
    public WatchRepositoryPreview previewWatch(long selectedRepositoryId, Optional<Topology> topology)
    {
        checkArgument(selectedRepositoryId > 0, "selectedRepositoryId must be positive");
        var credential = credentials.resolveGitHubCredential();
        return toPreview(resolveWatch(selectedRepositoryId, topology, credential.token(), credential.profile().login()));
    }

    /**
     * Creates a durable workspace after re-reading the selected repository by stable GitHub ID.
     *
     * <p>The resolved push repository's stable GitHub ID is idempotent. In fork mode, selecting the upstream or personal fork returns the same workspace.
     * Missing forks require explicit creation consent. A changed routing fingerprint is rejected so stale preview data cannot be persisted.</p>
     *
     * @param command confirmed repository ID, workflow, preview fingerprint, optional display name, and fork consent
     * @return the persisted workspace in its current code and metadata states
     * @throws WorkspaceException when the repository changed after preview
     */
    // ponytail: watch confirmations serialize fork creation; use per-account locks if concurrent watching becomes common.
    public synchronized Workspace watch(WatchRepositoryCommand command)
    {
        requireNonNull(command, "command is null");
        var credential = credentials.resolveGitHubCredential();
        WatchRouting routing = resolveWatch(command.selectedRepositoryId(), Optional.of(command.topology()), credential.token(), credential.profile().login());
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

    /**
     * Lists watched workspaces used by both Home and the Workspaces page.
     *
     * @return summaries in retained user display order
     */
    public List<WorkspaceSummary> listWorkspaces()
    {
        return store.findAll().stream().map(WorkspaceSummary::of).collect(toImmutableList());
    }

    /**
     * Lists retained, unwatched workspaces without scheduling synchronization or changing membership.
     *
     * @return summaries in retained user display order
     */
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

    /**
     * Loads routing, path ownership, and independent sync state for Workspace Home.
     *
     * @param workspaceId ByteQuay workspace identifier
     * @return the workspace summary
     * @throws WorkspaceException when the workspace does not exist
     */
    public WorkspaceSummary getWorkspace(String workspaceId)
    {
        return WorkspaceSummary.of(requireWorkspace(store, workspaceId));
    }

    /**
     * Resolves a backend-owned checkout for Git or cherry-pick operations, synchronizing it first when needed.
     *
     * @param workspaceId ByteQuay workspace identifier
     * @return normalized managed path and persisted remote/base routing
     * @throws WorkspaceException when the workspace is absent, not ready, or outside the managed root
     */
    public WorkspaceRepositoryContext requireReadyRepository(String workspaceId)
    {
        return repositoryContext(requireSynchronizedWorkspace(workspaceId));
    }

    /**
     * Resolves an already provisioned checkout without synchronizing, cloning or fetching.
     *
     * @param workspaceId ByteQuay workspace identifier
     * @return the existing managed path and monitored repository routing
     * @throws WorkspaceException when the workspace has never completed code synchronization
     */
    public WorkspaceRepositoryContext requireLocalRepository(String workspaceId)
    {
        Workspace workspace = requireWorkspace(store, workspaceId);
        if (workspace.lastCodeSyncAt().isEmpty()) {
            throw WorkspaceException.repositoryNotReady();
        }
        return repositoryContext(workspace);
    }

    private Workspace requireSynchronizedWorkspace(String workspaceId)
    {
        Workspace workspace = requireWorkspace(store, workspaceId);
        if (workspace.lastCodeSyncAt().isEmpty()) {
            provisioning.synchronizeCode(workspaceId);
            workspace = requireWorkspace(store, workspaceId);
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
                enrichRemoteSources(workspace.id()));
    }

    /**
     * Requests code and metadata synchronization without creating another workspace.
     *
     * @param workspaceId ByteQuay workspace identifier
     * @return the unchanged durable workspace while process-local refresh work runs
     */
    public Workspace requestSync(String workspaceId)
    {
        Workspace workspace = requireWorkspace(store, workspaceId);
        scheduleRefreshes(workspace, false);
        submit(() -> pullRequestRefresh.refreshWorkspace(workspaceId), workspaceId);
        return workspace;
    }

    /**
     * Synchronizes code after repository routing or configured remotes change, regardless of age.
     *
     * @param workspaceId workspace whose Git configuration changed
     * @return the unchanged durable workspace while process-local refresh work runs
     */
    public Workspace requestSyncAfterConfigurationChange(String workspaceId)
    {
        Workspace workspace = requireWorkspace(store, workspaceId);
        scheduleRefreshes(workspace, true);
        submit(() -> pullRequestRefresh.refreshWorkspace(workspaceId), workspaceId);
        return workspace;
    }

    /**
     * Selects a GitHub-readable repository as upstream, or unlinks it when empty.
     *
     * @param workspaceId workspace whose upstream changes
     * @param upstreamRepositoryId stable GitHub repository ID, or empty to unlink
     * @return the updated workspace after synchronization is accepted
     */
    public Workspace setUpstream(String workspaceId, Optional<Long> upstreamRepositoryId)
    {
        requireNonNull(upstreamRepositoryId, "upstreamRepositoryId is null");
        Workspace workspace = requireWorkspace(store, workspaceId);
        if (workspace.upstreamRepositoryId().equals(upstreamRepositoryId)) {
            return workspace;
        }
        if (codeRefreshes.contains(workspace.id()) || store.hasActiveWorkflows(workspace.id())) {
            throw WorkspaceException.busy();
        }
        Optional<UserRepo> upstream = upstreamRepositoryId.map(id -> requireUpstream(workspace, id));
        Instant now = clock.instant();
        Optional<RepoRef> upstreamRef = upstream.map(value -> RepoRef.of(value.owner(), value.name()));
        Workspace linked = workspace.withRepositoryRouting(
                workspace.selectedRepository(),
                upstream.map(UserRepo::id),
                upstreamRef,
                upstream.map(UserRepo::id).orElse(workspace.selectedRepositoryId()),
                upstreamRef.orElse(workspace.selectedRepository()),
                workspace.defaultBranch(),
                workspace.originCloneUrl(),
                upstream.map(UserRepo::defaultBranch),
                upstream.map(UserRepo::cloneUrl),
                now);
        Workspace saved = store.save(linked.withMetadataSyncAt(Optional.empty(), now));
        return requestSyncAfterConfigurationChange(saved.id());
    }

    /**
     * Renames the workspace's display name without changing any routing.
     *
     * @param workspaceId watched workspace ID
     * @param name new non-blank display name
     * @return renamed workspace
     */
    public Workspace rename(String workspaceId, String name)
    {
        String trimmed = requireNonNull(name, "name is null").trim();
        if (trimmed.isEmpty() || trimmed.length() > 200) {
            throw WorkspaceException.invalidRepository("Workspace name must be 1 to 200 characters.");
        }
        Workspace workspace = requireWorkspace(store, workspaceId);
        if (workspace.name().equals(trimmed)) {
            return workspace;
        }
        return store.save(workspace.withName(trimmed, clock.instant()));
    }

    /**
     * Hides a workspace from watched lists while retaining its repository, settings, tasks, and history.
     *
     * @param workspaceId ByteQuay workspace identifier
     * @throws WorkspaceException when the workspace is missing
     */
    public void unwatch(String workspaceId)
    {
        requireWorkspace(store, workspaceId);
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
        Topology topology = upstream.isPresent() ? Topology.FORK : Topology.DIRECT;
        Workspace proposed = new Workspace(
                id,
                name.orElseGet(() -> upstream.map(UserRepo::fullName).orElse(selected.fullName())),
                selected.id(),
                selectedRef,
                upstream.map(UserRepo::id),
                upstream.map(WorkspaceRepositoryService::repositoryRef),
                upstream.map(UserRepo::id).orElse(selected.id()),
                upstream.map(WorkspaceRepositoryService::repositoryRef).orElse(selectedRef),
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

    private WatchRouting resolveWatch(long selectedRepositoryId, Optional<Topology> requestedTopology, String token, String login)
    {
        UserRepo selected = requireRepository(token, selectedRepositoryId);
        boolean owned = selected.owner().equalsIgnoreCase(login);
        boolean forkModeAvailable = (selected.fork() || !owned)
                && selected.upstream().filter(value -> value.owner().equalsIgnoreCase(login)).isEmpty();
        Topology recommended = "public".equals(selected.visibility()) && !owned && forkModeAvailable ? Topology.FORK : Topology.DIRECT;
        Topology topology = requestedTopology.orElse(recommended);
        if (topology == Topology.DIRECT) {
            return new WatchRouting(selected, selected, Optional.of(selected), login, topology, recommended, forkModeAvailable);
        }
        if (!forkModeAvailable) {
            throw WorkspaceException.invalidRepository("Use direct clone for your own repository.");
        }
        UserRepo monitored = selected.fork()
                ? requireRepository(token, selected.upstream().filter(WorkspaceRepositoryService::validUpstream)
                        .orElseThrow(() -> WorkspaceException.invalidRepository("GitHub did not return the fork's upstream repository. Retry the preview.")).id())
                : selected;
        if (monitored.fork()) {
            throw WorkspaceException.invalidRepository("GitHub did not return the original upstream repository. Retry the preview.");
        }
        if (monitored.owner().equalsIgnoreCase(login)) {
            throw WorkspaceException.invalidRepository("Use direct clone for a fork of your own repository.");
        }
        Optional<UserRepo> pushRepository = selected.fork() && selected.owner().equalsIgnoreCase(login)
                ? Optional.of(selected)
                : gitHub.findOwnedFork(token, monitored, login);
        pushRepository.ifPresent(WorkspaceRepositoryService::validate);
        return new WatchRouting(selected, monitored, pushRepository, login, topology, recommended, true);
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
        RepoRef pushRef = routing.pushRepository().map(WorkspaceRepositoryService::repositoryRef)
                .orElseGet(() -> RepoRef.of(routing.login(), monitored.name()));
        return new WatchRepositoryPreview(
                selected.id(),
                repositoryRef(selected),
                monitored.visibility(),
                routing.topology(),
                routing.recommendedTopology(),
                routing.forkModeAvailable(),
                routing.upstream().map(UserRepo::id),
                routing.upstream().map(WorkspaceRepositoryService::repositoryRef),
                monitored.id(),
                monitoredRef,
                monitored.defaultBranch(),
                (routing.fork() ? "upstream/" : "origin/") + monitored.defaultBranch(),
                "origin",
                routing.pushRepository().map(UserRepo::viewerCanPush).orElse(false),
                pushRef,
                routing.pushRepository().isEmpty(),
                monitoredRef,
                fingerprint(routing.topology() + "\0" + routing.login() + "\0" + fingerprint(selected) + "\0" + fingerprint(monitored) + "\0"
                        + routing.pushRepository().map(WorkspaceRepositoryService::fingerprint).orElse("create-fork")),
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

    private record WatchRouting(
            UserRepo selectedRepository,
            UserRepo monitoredRepository,
            Optional<UserRepo> pushRepository,
            String login,
            Topology topology,
            Topology recommendedTopology,
            boolean forkModeAvailable)
    {
        boolean fork()
        {
            return topology == Topology.FORK;
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
        if (!validSearchResult(repository)) {
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
            submitUnique(codeRefreshes, workspace.id(), () -> provisioning.synchronizeCode(workspace.id()));
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
        if (active.add(workspaceId)) {
            submit(task, workspaceId, () -> active.remove(workspaceId));
        }
    }

    private void submit(Runnable task, String workspaceId)
    {
        submit(task, workspaceId, () -> {});
    }

    private void submit(Runnable task, String workspaceId, Runnable finished)
    {
        try {
            // Each task takes the workspace lock for its local part; holding it here would freeze the workspace while a PR refresh waits on GitHub.
            executor.execute(() -> {
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
                    finished.run();
                }
            });
        }
        catch (RuntimeException rejected) {
            finished.run();
            log.warn("Workspace {} background synchronization could not be scheduled: {}",
                    workspaceId, rejected.getMessage());
        }
    }

    /**
     * Attaches stable GitHub identities to custom remotes when the same repository is already watched.
     * This lookup is local-only; an unknown remote never causes a provider request while History loads.
     */
    private List<RemoteSource> enrichRemoteSources(String workspaceId)
    {
        requireNonNull(workspaceId, "workspaceId is null");
        List<KnownRepository> knownRepositories = knownRepositories();
        ImmutableList.Builder<RemoteSource> result = ImmutableList.builder();
        for (RemoteSource source : remoteSources.findByWorkspace(workspaceId)) {
            RemoteSource enriched = enrich(source, knownRepositories);
            if (!enriched.equals(source)) {
                remoteSources.save(enriched);
            }
            result.add(enriched);
        }
        return result.build();
    }

    private List<KnownRepository> knownRepositories()
    {
        List<KnownRepository> repositories = new ArrayList<>();
        for (Workspace workspace : store.findAllRetained()) {
            repositories.add(new KnownRepository(workspace.selectedRepositoryId(), workspace.selectedRepository()));
            workspace.upstreamRepositoryId().ifPresent(repositoryId -> repositories.add(new KnownRepository(repositoryId, workspace.upstreamRepository().orElseThrow())));
        }
        return repositories;
    }

    private static RemoteSource enrich(RemoteSource source, List<KnownRepository> repositories)
    {
        if (source.repositoryId().isPresent()) {
            long repositoryId = source.repositoryId().orElseThrow();
            return repositories.stream()
                    .filter(candidate -> candidate.repositoryId() == repositoryId)
                    .findFirst()
                    .map(value -> source.withRepositoryIdentity(value.repositoryId(), value.repository()))
                    .orElse(source);
        }
        Optional<KnownRepository> repository = githubRepository(source.url())
                .flatMap(coordinates -> repositories.stream()
                        .filter(candidate -> sameRepository(candidate.repository(), coordinates))
                        .findFirst());
        return repository
                .map(value -> source.withRepositoryIdentity(value.repositoryId(), value.repository()))
                .orElse(source);
    }

    private static Optional<RepoRef> githubRepository(String url)
    {
        try {
            URI remote = URI.create(url);
            if (!"https".equalsIgnoreCase(remote.getScheme())
                    || !"github.com".equalsIgnoreCase(remote.getHost())) {
                return Optional.empty();
            }
            String path = remote.getPath().replaceFirst("^/+", "").replaceFirst("/+$", "");
            if (path.toLowerCase(Locale.ROOT).endsWith(".git")) {
                path = path.substring(0, path.length() - 4);
            }
            String[] parts = path.split("/");
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                return Optional.empty();
            }
            return Optional.of(RepoRef.of(parts[0], parts[1]));
        }
        catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static boolean sameRepository(RepoRef left, RepoRef right)
    {
        return left.owner().equalsIgnoreCase(right.owner())
                && left.repo().equalsIgnoreCase(right.repo());
    }

    private record KnownRepository(long repositoryId, RepoRef repository) {}
}

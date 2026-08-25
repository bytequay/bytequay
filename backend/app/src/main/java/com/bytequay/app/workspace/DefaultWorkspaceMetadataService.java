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

import com.bytequay.app.git.AvatarCache;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.repository.github.GitHubRepoClient.RepositoryOverview;
import com.bytequay.app.repository.github.GitHubRepositoryUser;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryContributor;
import com.bytequay.core.domain.RepositoryLabel;
import com.bytequay.core.domain.RepositoryMetadataSnapshot;
import com.bytequay.core.domain.RepositoryPickerUser;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.RepositoryMetadataStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Striped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.Lock;

import static com.bytequay.app.config.AsyncConfig.IO_EXECUTOR;
import static java.util.Objects.requireNonNull;

@Service
public final class DefaultWorkspaceMetadataService
        implements WorkspaceMetadataService
{
    private static final Logger log = LoggerFactory.getLogger(DefaultWorkspaceMetadataService.class);
    private static final Duration OVERVIEW_TTL = Duration.ofHours(1);
    private static final Duration CONTRIBUTOR_TTL = Duration.ofDays(7);
    private static final Duration PICKER_USER_TTL = Duration.ofDays(7);
    private static final Duration LABEL_TOPIC_TTL = Duration.ofDays(30);

    private final WorkspaceStore workspaces;
    private final RepositoryMetadataStore metadata;
    private final GitHubRepoClient gitHub;
    private final GitHubPullRequestReadClient pullRequests;
    private final AvatarCache avatarCache;
    private final CredentialService credentials;
    private final Clock clock;
    private final Executor ioExecutor;
    private final Striped<Lock> locks = Striped.lock(64);

    public DefaultWorkspaceMetadataService(
            WorkspaceStore workspaces,
            RepositoryMetadataStore metadata,
            GitHubRepoClient gitHub,
            GitHubPullRequestReadClient pullRequests,
            AvatarCache avatarCache,
            CredentialService credentials,
            Clock clock,
            @Qualifier(IO_EXECUTOR) Executor ioExecutor)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.avatarCache = requireNonNull(avatarCache, "avatarCache is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.ioExecutor = requireNonNull(ioExecutor, "ioExecutor is null");
    }

    @Override
    public WorkspaceMetadataSyncResult synchronize(String workspaceId)
    {
        Workspace workspace = requireWorkspace(workspaceId);
        Lock lock = locks.get(workspace.monitoredRepositoryId());
        lock.lock();
        try {
            Instant now = clock.instant();
            RepositoryMetadataSnapshot current = snapshot(workspace);
            if (!expired(workspace.lastMetadataSyncAt(), now, CONTRIBUTOR_TTL)
                    && !expired(current.pickerUsersSyncedAt(), now, PICKER_USER_TTL)) {
                return WorkspaceMetadataSyncResult.of(workspaceId, true, current);
            }
            refreshExpired(workspace);
            now = clock.instant();
            Workspace latest = requireWorkspace(workspaceId);
            workspaces.save(latest.withMetadataSyncAt(Optional.of(now), now));
            return WorkspaceMetadataSyncResult.of(workspaceId, true, snapshot(workspace));
        }
        catch (RuntimeException failure) {
            log.warn("Workspace {} metadata synchronization failed: {}",
                    workspaceId, failure.getMessage());
            return WorkspaceMetadataSyncResult.of(workspaceId, false, snapshot(workspace));
        }
        finally {
            lock.unlock();
        }
    }

    @Override
    public RepositoryMetadataSnapshot getHomeSnapshot(String owner, String repo)
    {
        Workspace workspace = requireWorkspace(RepoRef.of(owner, repo));
        RepositoryMetadataSnapshot current = snapshot(workspace);
        if (!expired(current.overviewSyncedAt(), clock.instant(), OVERVIEW_TTL)) {
            return current;
        }
        if (current.overviewSyncedAt().isPresent()) {
            ioExecutor.execute(() -> refreshOverviewInBackground(workspace));
            return current;
        }
        return refreshOverview(workspace);
    }

    @Override
    public RepositoryMetadataSnapshot getSnapshot(String workspaceId)
    {
        return snapshot(requireWorkspace(workspaceId));
    }

    private void refreshExpired(Workspace workspace)
    {
        Instant now = clock.instant();
        RepositoryMetadataSnapshot current = snapshot(workspace);
        boolean overviewExpired = expired(current.overviewSyncedAt(), now, OVERVIEW_TTL);
        boolean contributorsExpired = expired(current.contributorsSyncedAt(), now, CONTRIBUTOR_TTL);
        boolean pickerUsersExpired = expired(current.pickerUsersSyncedAt(), now, PICKER_USER_TTL);
        boolean labelsExpired = expired(current.labelsTopicsSyncedAt(), now, LABEL_TOPIC_TTL);
        if (!overviewExpired && !contributorsExpired && !pickerUsersExpired && !labelsExpired) {
            return;
        }

        String pat = credentials.resolveGitHubToken();
        Optional<RepositoryOverview> overview = overviewExpired
                ? Optional.of(gitHub.fetchOverview(pat, workspace.monitoredRepository()))
                : Optional.empty();
        List<RepositoryContributor> contributors = contributorsExpired
                ? gitHub.fetchContributors(pat, workspace.monitoredRepository())
                : ImmutableList.of();
        List<GitHubRepositoryUser> pickerUsers = pickerUsersExpired
                ? pullRequests.fetchAssignableRepositoryUsers(pat, workspace.monitoredRepository())
                : ImmutableList.of();
        List<RepositoryLabel> labels = labelsExpired
                ? gitHub.fetchLabels(pat, workspace.monitoredRepository())
                : ImmutableList.of();

        overview.ifPresent(value -> metadata.upsertOverview(
                workspace.monitoredRepositoryId(),
                workspace.monitoredRepository(),
                value.description(),
                value.stargazersCount(),
                value.topics(),
                value.languages(),
                now));
        if (contributorsExpired) {
            metadata.upsertContributors(
                    workspace.monitoredRepositoryId(), workspace.monitoredRepository(), contributors, now);
        }
        if (pickerUsersExpired) {
            metadata.replacePickerUsers(
                    workspace.monitoredRepositoryId(),
                    workspace.monitoredRepository(),
                    cachePickerUsers(current.pickerUsers(), pickerUsers),
                    now);
        }
        if (labelsExpired) {
            metadata.upsertLabelsAndTopics(
                    workspace.monitoredRepositoryId(), workspace.monitoredRepository(), labels,
                    overview.map(RepositoryOverview::topics).orElse(current.topics()), now);
        }
    }

    private List<RepositoryPickerUser> cachePickerUsers(
            List<RepositoryPickerUser> current,
            List<GitHubRepositoryUser> refreshed)
    {
        Map<Long, Optional<String>> previousKeys = current.stream()
                .collect(java.util.stream.Collectors.toMap(
                        RepositoryPickerUser::id,
                        RepositoryPickerUser::avatarKey,
                        (left, right) -> right));
        return refreshed.stream()
                .map(user -> new RepositoryPickerUser(
                        user.id(),
                        user.login(),
                        cacheAvatar(user).or(() -> previousKeys.getOrDefault(user.id(), Optional.empty())),
                        user.bot()))
                .toList();
    }

    private Optional<String> cacheAvatar(GitHubRepositoryUser user)
    {
        try {
            return avatarCache.cache(user.avatarUrl());
        }
        catch (RuntimeException failure) {
            log.debug("Avatar cache write failed for {}: {}", user.login(), failure.getMessage());
            return Optional.empty();
        }
    }

    private RepositoryMetadataSnapshot refreshOverview(Workspace workspace)
    {
        Lock lock = locks.get(workspace.monitoredRepositoryId());
        lock.lock();
        try {
            RepositoryMetadataSnapshot current = snapshot(workspace);
            Instant now = clock.instant();
            if (!expired(current.overviewSyncedAt(), now, OVERVIEW_TTL)) {
                return current;
            }
            RepositoryOverview overview = gitHub.fetchOverview(
                    credentials.resolveGitHubToken(), workspace.monitoredRepository());
            metadata.upsertOverview(
                    workspace.monitoredRepositoryId(), workspace.monitoredRepository(),
                    overview.description(), overview.stargazersCount(), overview.topics(), overview.languages(), now);
            return snapshot(workspace);
        }
        finally {
            lock.unlock();
        }
    }

    private void refreshOverviewInBackground(Workspace workspace)
    {
        try {
            refreshOverview(workspace);
        }
        catch (RuntimeException failure) {
            log.warn("Repository {} overview refresh failed: {}",
                    workspace.monitoredRepository().fullName(), failure.getMessage());
        }
    }

    private RepositoryMetadataSnapshot snapshot(Workspace workspace)
    {
        return metadata.find(workspace.monitoredRepositoryId())
                .orElseGet(() -> RepositoryMetadataSnapshot.empty(
                        workspace.monitoredRepositoryId(), workspace.monitoredRepository()));
    }

    private Workspace requireWorkspace(String workspaceId)
    {
        requireNonNull(workspaceId, "workspaceId is null");
        return workspaces.findById(workspaceId)
                .orElseThrow(() -> WorkspaceException.notFound(workspaceId));
    }

    private Workspace requireWorkspace(RepoRef repository)
    {
        return workspaces.findAll().stream()
                .filter(workspace -> workspace.monitoredRepository().owner().equalsIgnoreCase(repository.owner())
                        && workspace.monitoredRepository().repo().equalsIgnoreCase(repository.repo()))
                .findFirst()
                .orElseThrow(() -> WorkspaceException.repositoryNotFound(repository.owner(), repository.repo()));
    }

    private static boolean expired(Optional<Instant> syncedAt, Instant now, Duration ttl)
    {
        return syncedAt.filter(value -> !value.isBefore(now.minus(ttl))).isEmpty();
    }
}

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
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryContributor;
import com.bytequay.core.domain.RepositoryLabel;
import com.bytequay.core.domain.RepositoryMetadataSnapshot;
import com.bytequay.core.domain.RepositoryPickerUser;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.hosting.HostingRepositoryUser;
import com.bytequay.core.hosting.RepositoryMetadataReader;
import com.bytequay.core.hosting.RepositoryOverview;
import com.bytequay.core.store.RepositoryMetadataStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Striped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.Lock;

import static com.bytequay.app.utils.Locks.withLock;
import static com.bytequay.app.workspace.WorkspaceException.requireWorkspace;
import static java.util.Objects.requireNonNull;

@Service
public class WorkspaceMetadataService
{
    private static final Logger log = LoggerFactory.getLogger(WorkspaceMetadataService.class);
    private static final Duration OVERVIEW_TTL = Duration.ofHours(1);
    private static final Duration CONTRIBUTOR_TTL = Duration.ofDays(7);
    private static final Duration PICKER_USER_TTL = Duration.ofDays(7);
    private static final Duration LABEL_TOPIC_TTL = Duration.ofDays(30);

    private final WorkspaceStore workspaces;
    private final RepositoryMetadataStore metadata;
    private final WorkspaceHostingService hosting;
    private final List<RepositoryMetadataReader> readers;
    private final AvatarCache avatarCache;
    private final Clock clock;
    private final Striped<Lock> locks = Striped.lock(64);

    public WorkspaceMetadataService(
            WorkspaceStore workspaces,
            RepositoryMetadataStore metadata,
            WorkspaceHostingService hosting,
            List<RepositoryMetadataReader> readers,
            AvatarCache avatarCache,
            Clock clock)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.hosting = requireNonNull(hosting, "hosting is null");
        this.readers = ImmutableList.copyOf(readers);
        this.avatarCache = requireNonNull(avatarCache, "avatarCache is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /**
     * Refreshes expired reference projections for the workspace's monitored repository.
     *
     * <p>Contributors and picker users refresh weekly; labels and topics refresh monthly. A fork
     * resolves to its persisted upstream ID, and failures retain the last local snapshot.</p>
     *
     * @param workspaceId workspace whose monitored repository should be refreshed
     */
    public void synchronize(String workspaceId)
    {
        WorkspaceOperationLocks.withLock(workspaceId, () -> synchronizeLocked(workspaceId));
    }

    private void synchronizeLocked(String workspaceId)
    {
        Workspace workspace = requireWorkspace(workspaces, workspaceId);
        Lock lock = locks.get(workspace.id());
        withLock(lock, () -> {
            try {
                Instant now = clock.instant();
                RepositoryMetadataSnapshot current = snapshot(workspace);
                if (!expired(workspace.lastMetadataSyncAt(), now, CONTRIBUTOR_TTL)
                        && !expired(current.overviewSyncedAt(), now, OVERVIEW_TTL)
                        && !expired(current.contributorsSyncedAt(), now, CONTRIBUTOR_TTL)
                        && !expired(current.pickerUsersSyncedAt(), now, PICKER_USER_TTL)
                        && !expired(current.labelsTopicsSyncedAt(), now, LABEL_TOPIC_TTL)) {
                    return null;
                }
                refreshExpired(workspace);
                now = clock.instant();
                Workspace latest = requireWorkspace(workspaces, workspaceId);
                workspaces.save(latest.withMetadataSyncAt(Optional.of(now), now));
            }
            catch (RuntimeException failure) {
                log.warn("Workspace {} metadata synchronization failed: {}",
                        workspaceId, failure.getMessage());
            }
            return null;
        });
    }

    /**
     * Reads the latest local metadata without contacting GitHub.
     *
     * @param workspaceId workspace whose monitored repository identifies the shared cache
     * @return latest snapshot, possibly empty or stale
     */
    public RepositoryMetadataSnapshot getSnapshot(String workspaceId)
    {
        return snapshot(requireWorkspace(workspaces, workspaceId));
    }

    /**
     * Reads local metadata when the repository belongs to a watched workspace.
     *
     * @param repository monitored repository identity
     * @return latest local snapshot, or empty when the repository is not watched
     */
    public Optional<RepositoryMetadataSnapshot> findSnapshot(RepoRef repository)
    {
        requireNonNull(repository, "repository is null");
        return findWorkspace(repository).map(this::snapshot);
    }

    public List<String> listTeams(String workspaceId)
    {
        WorkspaceHostingRead route = hosting.captureRead(workspaceId);
        return reader(route).repositoryTeams(route.session().access(), route.repository());
    }

    private RepositoryMetadataReader reader(WorkspaceHostingRead route)
    {
        return readers.stream().filter(value -> value.provider() == route.session().access().server().provider()).findFirst().orElseThrow();
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

        WorkspaceHostingRead route = hosting.captureRead(workspace.id());
        RepositoryMetadataReader reader = reader(route);
        Optional<RepositoryOverview> overview = overviewExpired
                ? Optional.of(reader.overview(route.session().access(), route.repository()))
                : Optional.empty();
        Optional<List<RepositoryContributor>> contributors = contributorsExpired
                ? reader.contributors(route.session().access(), route.repository())
                : Optional.empty();
        List<HostingRepositoryUser> pickerUsers = pickerUsersExpired
                ? reader.repositoryUsers(route.session().access(), route.repository())
                : ImmutableList.of();
        List<RepositoryLabel> labels = labelsExpired
                ? reader.repositoryLabels(route.session().access(), route.repository())
                : ImmutableList.of();

        overview.ifPresent(value -> metadata.upsertOverview(
                route.repository().id(),
                workspace.monitoredRepository(),
                value.description(),
                value.stargazersCount(),
                value.topics(),
                value.languages(),
                now));
        contributors.ifPresent(value -> metadata.upsertContributors(route.repository().id(), workspace.monitoredRepository(), value, now));
        if (pickerUsersExpired) {
            metadata.replacePickerUsers(
                    route.repository().id(),
                    workspace.monitoredRepository(),
                    cachePickerUsers(current.pickerUsers(), pickerUsers),
                    now);
        }
        if (labelsExpired) {
            metadata.upsertLabelsAndTopics(
                    route.repository().id(), workspace.monitoredRepository(), labels,
                    overview.map(RepositoryOverview::topics).orElse(current.topics()), now);
        }
    }

    private List<RepositoryPickerUser> cachePickerUsers(
            List<RepositoryPickerUser> current,
            List<HostingRepositoryUser> refreshed)
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

    private Optional<String> cacheAvatar(HostingRepositoryUser user)
    {
        try {
            return avatarCache.cache(user.avatarUrl());
        }
        catch (RuntimeException failure) {
            log.debug("Avatar cache write failed for {}: {}", user.login(), failure.getMessage());
            return Optional.empty();
        }
    }

    private RepositoryMetadataSnapshot snapshot(Workspace workspace)
    {
        return workspaces.findHostingRepositoryId(workspace.id()).flatMap(metadata::find)
                .orElseGet(() -> RepositoryMetadataSnapshot.empty(
                        workspace.monitoredRepositoryId(), workspace.monitoredRepository()));
    }

    private Optional<Workspace> findWorkspace(RepoRef repository)
    {
        requireNonNull(repository, "repository is null");
        return workspaces.findAllRetained().stream().filter(Workspace::isGitHub)
                .filter(workspace -> workspace.monitoredRepository().owner().equalsIgnoreCase(repository.owner())
                        && workspace.monitoredRepository().repo().equalsIgnoreCase(repository.repo()))
                .findFirst();
    }

    private static boolean expired(Optional<Instant> syncedAt, Instant now, Duration ttl)
    {
        return syncedAt.filter(value -> !value.isBefore(now.minus(ttl))).isEmpty();
    }
}

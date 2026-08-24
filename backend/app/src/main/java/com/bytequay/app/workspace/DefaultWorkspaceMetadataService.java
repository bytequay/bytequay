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

import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.core.domain.RepositoryContributor;
import com.bytequay.core.domain.RepositoryLabel;
import com.bytequay.core.domain.RepositoryMetadataSnapshot;
import com.bytequay.core.domain.Workspace;
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
import java.util.Optional;
import java.util.concurrent.locks.Lock;

import static java.util.Objects.requireNonNull;

@Service
public final class DefaultWorkspaceMetadataService
        implements WorkspaceMetadataService
{
    private static final Logger log = LoggerFactory.getLogger(DefaultWorkspaceMetadataService.class);
    private static final Duration CONTRIBUTOR_TTL = Duration.ofDays(7);
    private static final Duration LABEL_TOPIC_TTL = Duration.ofDays(30);

    private final WorkspaceStore workspaces;
    private final RepositoryMetadataStore metadata;
    private final GitHubRepoClient gitHub;
    private final CredentialService credentials;
    private final Clock clock;
    private final Striped<Lock> locks = Striped.lock(64);

    public DefaultWorkspaceMetadataService(
            WorkspaceStore workspaces,
            RepositoryMetadataStore metadata,
            GitHubRepoClient gitHub,
            CredentialService credentials,
            Clock clock)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public WorkspaceMetadataSyncResult synchronize(String workspaceId)
    {
        Workspace workspace = requireWorkspace(workspaceId);
        Lock lock = locks.get(workspace.monitoredRepositoryId());
        lock.lock();
        try {
            Instant now = clock.instant();
            if (!expired(workspace.lastMetadataSyncAt(), now, CONTRIBUTOR_TTL)) {
                return WorkspaceMetadataSyncResult.of(workspaceId, true, snapshot(workspace));
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
    public RepositoryMetadataSnapshot getSnapshot(String workspaceId)
    {
        return snapshot(requireWorkspace(workspaceId));
    }

    private void refreshExpired(Workspace workspace)
    {
        Instant now = clock.instant();
        RepositoryMetadataSnapshot current = snapshot(workspace);
        boolean contributorsExpired = expired(current.contributorsSyncedAt(), now, CONTRIBUTOR_TTL);
        boolean labelsExpired = expired(current.labelsTopicsSyncedAt(), now, LABEL_TOPIC_TTL);
        if (!contributorsExpired && !labelsExpired) {
            return;
        }

        String pat = credentials.resolveGitHubToken();
        List<RepositoryContributor> contributors = contributorsExpired
                ? gitHub.fetchContributors(pat, workspace.monitoredRepository())
                : ImmutableList.of();
        List<RepositoryLabel> labels = labelsExpired
                ? gitHub.fetchLabels(pat, workspace.monitoredRepository())
                : ImmutableList.of();
        List<String> topics = labelsExpired
                ? gitHub.fetchTopics(pat, workspace.monitoredRepository())
                : ImmutableList.of();

        if (contributorsExpired) {
            metadata.upsertContributors(
                    workspace.monitoredRepositoryId(), workspace.monitoredRepository(), contributors, now);
        }
        if (labelsExpired) {
            metadata.upsertLabelsAndTopics(
                    workspace.monitoredRepositoryId(), workspace.monitoredRepository(), labels, topics, now);
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

    private static boolean expired(Optional<Instant> syncedAt, Instant now, Duration ttl)
    {
        return syncedAt.filter(value -> !value.isBefore(now.minus(ttl))).isEmpty();
    }
}
